package it.vibepocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.*
import java.io.File

/** Keeps working when Activity is closed; checkpoints every 30 seconds of audio. */
class TranscriptionService : Service() {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: JobStore
    private lateinit var manager: NotificationManager
    private var running = false
    private var heartbeat: Job? = null

    override fun onCreate() {
        super.onCreate()
        store = JobStore(this)
        manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Trascrizione in corso", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = store.load()
        if (initial == null || initial.phase == "completed") {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Avvio trascrizione", initial.percent))
        if (running) return START_NOT_STICKY
        running = true
        worker.launch {
            heartbeat = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(10000L)
                    try {
                        store.modify { job ->
                            if (job.phase in listOf("converting", "loading", "transcribing"))
                                job.copy(lastHeartbeatAtMs = System.currentTimeMillis())
                            else job
                        }
                    } catch (_: Exception) { }
                }
            }
            try {
                process()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = store.load()
                if (current != null) {
                    store.save(current.copy(phase = "failed", error = e.message ?: "Errore durante la trascrizione"))
                }
                updateNotification("Errore. Apri Vibe Pocket per riprendere.", current?.percent ?: 0)
            } finally {
                heartbeat?.cancel()
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun process() {
        var job = store.load() ?: error("Nessun lavoro da riprendere")
        val source = File(job.source)
        val normalized = File(job.normalized)
        require(source.exists()) { "File audio originale mancante" }
        val selectedModel = ModelCatalog.all.firstOrNull { it.name == job.modelName }
            ?: error("Modello non riconosciuto")
        val modelFile = ModelFiles(this).file(selectedModel)
        require(modelFile.isFile && modelFile.length() >= selectedModel.minBytes) {
            "Modello eliminato o incompleto. Scaricalo nuovamente."
        }

        if (!AudioPreparer.canonical(normalized)) {
            val conversionStarted = System.currentTimeMillis()
            job = job.copy(phase = "converting", error = "", conversionPercent = 0,
                phaseStartedAtMs = conversionStarted, lastHeartbeatAtMs = conversionStarted)
            store.save(job)
            updateNotification("Preparazione M4A/audio: 0%", job.percent)
            var lastSaved = -1
            AudioPreparer.prepare(source, normalized) { pct ->
                if (pct >= lastSaved + 2 || pct == 100) {
                    lastSaved = pct
                    val updated = store.load() ?: job
                    store.save(updated.copy(phase = "converting", conversionPercent = pct, lastHeartbeatAtMs = System.currentTimeMillis()))
                    updateNotification("Preparazione audio: " + pct + "%", 0)
                }
            }
            job = (store.load() ?: job).copy(preparationMs = System.currentTimeMillis() - conversionStarted)
            store.save(job)
        }

        val total = AudioPreparer.totalChunks(normalized)
        val loadingStart = System.currentTimeMillis()
        job = (store.load() ?: job).copy(phase = "loading", error = "", totalChunks = total,
            phaseStartedAtMs = loadingStart, modelLoadStartedAtMs = loadingStart, lastHeartbeatAtMs = loadingStart)
        require(job.completedChunks <= total) { "Checkpoint incompatibile con la registrazione" }
        store.save(job)
        updateNotification("Caricamento modello Whisper…", job.percent)

        val whisperModel = Whisper.loadModel(this, modelFile.absolutePath)
        val loadedAt = System.currentTimeMillis()
        job = (store.load() ?: job).copy(phase = "transcribing", error = "",
            modelLoadMs = loadedAt - loadingStart, phaseStartedAtMs = loadedAt,
            lastHeartbeatAtMs = loadedAt)
        store.save(job)
        updateNotification("Trascrizione: " + job.percent + "%", job.percent)
        try {
            val scratch = File(cacheDir, "whisper_chunk_" + job.id + ".wav")
            try {
                while (job.completedChunks < total) {
                    ensureActiveWorker()
                    val index = job.completedChunks
                    val chunkStart = System.currentTimeMillis()
                    job = job.copy(chunkStartedAtMs = chunkStart, lastHeartbeatAtMs = chunkStart)
                    store.save(job)
                    AudioPreparer.chunk(normalized, index, scratch)
                    val output = Whisper.transcribe(
                        whisperModel, scratch.absolutePath,
                        WhisperConfig(language = job.language, threads = job.threads.coerceIn(1,8))
                    )
                    val shift = index.toLong() * AudioPreparer.CHUNK_SECONDS * 1000L
                    val newSegments = output.segments.map { segment ->
                        SavedSegment(
                            startMs = segment.startMs + shift,
                            endMs = segment.endMs + shift,
                            text = segment.text.trim()
                        )
                    }
                    val chunkElapsed = System.currentTimeMillis() - chunkStart
                    // The text and completed-chunk index are committed together.
                    // A process kill retries only the unfinished chunk.
                    job = job.copy(
                        completedChunks = index + 1,
                        segments = job.segments + newSegments,
                        phase = "transcribing",
                        chunkStartedAtMs = 0L,
                        lastChunkMs = chunkElapsed,
                        totalChunkMs = job.totalChunkMs + chunkElapsed,
                        lastHeartbeatAtMs = System.currentTimeMillis()
                    )
                    store.save(job)
                    updateNotification(
                        "Trascrizione: " + job.percent + "% (" + job.completedChunks + "/" + total + " blocchi)",
                        job.percent
                    )
                }
            } finally {
                scratch.delete()
            }
        } finally {
            Whisper.releaseModel(whisperModel)
        }
        store.save(job.copy(phase = "completed", completedChunks = total,
            completedAtMs = System.currentTimeMillis(), chunkStartedAtMs = 0L))
        updateNotification("Trascrizione completata: 100%", 100)
    }

    private fun ensureActiveWorker() {
        if (!worker.isActive) throw CancellationException("Trascrizione interrotta")
    }
    private fun notification(message: String, pct: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Vibe Pocket")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(pct < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .build()
    }
    private fun updateNotification(message: String, pct: Int) {
        manager.notify(NOTIFICATION_ID, notification(message, pct))
    }
    override fun onDestroy() {
        worker.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "vibe_transcription"
        private const val NOTIFICATION_ID = 2026
    }
}
