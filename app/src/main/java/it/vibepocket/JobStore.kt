package it.vibepocket

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedSegment(val startMs: Long, val endMs: Long, val text: String)

data class TranscriptionJob(
    val id: String,
    val source: String,
    val originalName: String,
    val normalized: String,
    val modelName: String,
    val language: String,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val phase: String = "ready",
    val error: String = "",
    val segments: List<SavedSegment> = emptyList(),
    val conversionPercent: Int = 0,
    val threads: Int = 4,
    val startedAtMs: Long = 0L,
    val phaseStartedAtMs: Long = 0L,
    val modelLoadMs: Long = 0L,
    val modelLoadStartedAtMs: Long = 0L,
    val chunkStartedAtMs: Long = 0L,
    val lastChunkMs: Long = 0L,
    val totalChunkMs: Long = 0L,
    val preparationMs: Long = 0L,
    val completedAtMs: Long = 0L,
    val lastHeartbeatAtMs: Long = 0L
) {
    val percent: Int get() = when {
        phase == "completed" -> 100
        totalChunks > 0 -> (completedChunks * 100L / totalChunks).toInt().coerceIn(0, 100)
        else -> 0
    }
    val transcript: String get() = segments.joinToString(" ") { it.text }.trim()
}

class JobStore(context: Context) {
    val root = File(context.filesDir, "transcription").apply { mkdirs() }
    private val state = AtomicFile(File(root, "job.json"))

    @Synchronized fun load(): TranscriptionJob? {
        return try {
            val obj = JSONObject(state.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val entries = obj.optJSONArray("segments") ?: JSONArray()
            val segments = buildList {
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    add(SavedSegment(e.getLong("start"), e.getLong("end"), e.getString("text")))
                }
            }
            TranscriptionJob(
                id = obj.getString("id"),
                source = obj.getString("source"),
                originalName = obj.getString("originalName"),
                normalized = obj.getString("normalized"),
                modelName = obj.getString("modelName"),
                language = obj.getString("language"),
                completedChunks = obj.optInt("completedChunks", 0),
                totalChunks = obj.optInt("totalChunks", 0),
                phase = obj.optString("phase", "ready"),
                error = obj.optString("error", ""),
                segments = segments,
                conversionPercent = obj.optInt("conversionPercent", 0),
                threads = obj.optInt("threads", 4),
                startedAtMs = obj.optLong("startedAtMs", 0L),
                phaseStartedAtMs = obj.optLong("phaseStartedAtMs", 0L),
                modelLoadMs = obj.optLong("modelLoadMs", 0L),
                modelLoadStartedAtMs = obj.optLong("modelLoadStartedAtMs", 0L),
                chunkStartedAtMs = obj.optLong("chunkStartedAtMs", 0L),
                lastChunkMs = obj.optLong("lastChunkMs", 0L),
                totalChunkMs = obj.optLong("totalChunkMs", 0L),
                preparationMs = obj.optLong("preparationMs", 0L),
                completedAtMs = obj.optLong("completedAtMs", 0L),
                lastHeartbeatAtMs = obj.optLong("lastHeartbeatAtMs", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized fun save(job: TranscriptionJob) {
        val json = JSONObject().apply {
            put("id", job.id)
            put("source", job.source)
            put("originalName", job.originalName)
            put("normalized", job.normalized)
            put("modelName", job.modelName)
            put("language", job.language)
            put("completedChunks", job.completedChunks)
            put("totalChunks", job.totalChunks)
            put("phase", job.phase)
            put("error", job.error)
            put("conversionPercent", job.conversionPercent)
            put("threads", job.threads)
            put("startedAtMs", job.startedAtMs)
            put("phaseStartedAtMs", job.phaseStartedAtMs)
            put("modelLoadMs", job.modelLoadMs)
            put("modelLoadStartedAtMs", job.modelLoadStartedAtMs)
            put("chunkStartedAtMs", job.chunkStartedAtMs)
            put("lastChunkMs", job.lastChunkMs)
            put("totalChunkMs", job.totalChunkMs)
            put("preparationMs", job.preparationMs)
            put("completedAtMs", job.completedAtMs)
            put("lastHeartbeatAtMs", job.lastHeartbeatAtMs)
            put("segments", JSONArray().apply {
                job.segments.forEach { s ->
                    put(JSONObject().apply {
                        put("start", s.startMs)
                        put("end", s.endMs)
                        put("text", s.text)
                    })
                }
            })
        }
        val output = state.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            state.finishWrite(output)
        } catch (t: Throwable) {
            state.failWrite(output)
            throw t
        }
    }

    @Synchronized fun modify(transform: (TranscriptionJob) -> TranscriptionJob): TranscriptionJob? {
        val old = load() ?: return null
        val updated = transform(old)
        save(updated)
        return updated
    }

    fun importSource(from: java.io.InputStream, name: String): File {
        val dst = File(root, "audio_" + UUID.randomUUID().toString() + ".source")
        try {
            dst.outputStream().use { from.copyTo(it, 128 * 1024) }
            check(dst.length() > 0L) { "Il file audio è vuoto" }
            return dst
        } catch (t: Throwable) {
            dst.delete()
            throw t
        }
    }

    fun newJob(audio: File, displayName: String, model: Model, language: String, threads: Int): TranscriptionJob {
        require(audio.exists() && audio.length() > 0L) { "Audio non disponibile" }
        val previous = load()
        val normalized = File(root, "normalized_" + UUID.randomUUID().toString() + ".wav")
        val job = TranscriptionJob(
            id = UUID.randomUUID().toString(),
            source = audio.absolutePath,
            originalName = displayName,
            normalized = normalized.absolutePath,
            modelName = model.name,
            language = language,
            threads = threads.coerceIn(1, 8),
            startedAtMs = System.currentTimeMillis(),
            phaseStartedAtMs = System.currentTimeMillis()
        )
        save(job)
        if (previous != null) {
            if (previous.normalized != job.normalized) File(previous.normalized).delete()
            if (previous.source != job.source && File(previous.source).parentFile == root) File(previous.source).delete()
        }
        return job
    }
}
