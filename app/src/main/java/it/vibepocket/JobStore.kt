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
    val conversionPercent: Int = 0
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
                conversionPercent = obj.optInt("conversionPercent", 0)
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

    fun newJob(audio: File, displayName: String, model: Model, language: String): TranscriptionJob {
        require(audio.exists() && audio.length() > 0L) { "Audio non disponibile" }
        val previous = load()
        val normalized = File(root, "normalized_" + UUID.randomUUID().toString() + ".wav")
        val job = TranscriptionJob(
            id = UUID.randomUUID().toString(),
            source = audio.absolutePath,
            originalName = displayName,
            normalized = normalized.absolutePath,
            modelName = model.name,
            language = language
        )
        save(job)
        if (previous != null) {
            if (previous.normalized != job.normalized) File(previous.normalized).delete()
            if (previous.source != job.source && File(previous.source).parentFile == root) File(previous.source).delete()
        }
        return job
    }
}
