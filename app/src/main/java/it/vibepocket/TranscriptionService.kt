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
            job = job.copy(phase = "converting", error = "", conversionPercent = 0)
            store.save(job)
            updateNotification("Preparazione M4A/audio: 0%", job.percent)
            var lastSaved = -1
            AudioPreparer.prepare(source, normalized) { pct ->
                if (pct >= lastSaved + 2 || pct == 100) {
                    lastSaved = pct
                    val updated = store.load() ?: job
                    store.save(updated.copy(phase = "converting", conversionPercent = pct))
                    updateNotification("Preparazione audio: " + pct + "%", 0)
                }
            }
        }

        val total = AudioPreparer.totalChunks(normalized)
        job = (store.load() ?: job).copy(phase = "transcribing", error = "", totalChunks = total)
        require(job.completedChunks <= total) { "Checkpoint incompatibile con la registrazione" }
        store.save(job)
        updateNotification("Trascrizione: " + job.percent + "%", job.percent)

        val whisperModel = Whisper.loadModel(this, modelFile.absolutePath)
        try {
            val scratch = File(cacheDir, "whisper_chunk_" + job.id + ".wav")
            try {
                while (job.completedChunks < total) {
                    ensureActiveWorker()
                    val index = job.completedChunks
                    AudioPreparer.chunk(normalized, index, scratch)
                    val output = Whisper.transcribe(
                        whisperModel, scratch.absolutePath,
                        WhisperConfig(language = job.language, threads = 4)
                    )
                    val shift = index.toLong() * AudioPreparer.CHUNK_SECONDS * 1000L
                    val newSegments = output.segments.map { segment ->
                        SavedSegment(
                            startMs = segment.startMs + shift,
                            endMs = segment.endMs + shift,
                            text = segment.text.trim()
                        )
                    }
                    // The text and completed-chunk index are committed together.
                    // A process kill retries only the unfinished chunk.
                    job = job.copy(
                        completedChunks = index + 1,
                        segments = job.segments + newSegments,
                        phase = "transcribing"
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
        store.save(job.copy(phase = "completed", completedChunks = total))
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
