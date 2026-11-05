package it.vibepocket

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import java.util.Locale

data class CpuTrial(val threads: Int, val elapsedMs: Long) {
    val seconds: Double get() = elapsedMs / 1000.0
}
data class CpuBenchmark(val modelLoadMs: Long, val trials: List<CpuTrial>) {
    val best: CpuTrial get() = trials.minByOrNull { it.elapsedMs } ?: error("Nessuna prova")
    fun summary(): String {
        val rows = trials.joinToString("\n") { trial ->
            trial.threads.toString() + " thread: " + String.format(Locale.US, "%.1f", trial.seconds) + " s"
        }
        return "Campione di 10 secondi di audio\nCaricamento modello: " +
            String.format(Locale.US, "%.1f", modelLoadMs / 1000.0) + " s\n" +
            rows + "\nPiù veloce in questo test: " + best.threads.toString() +
            " thread\nIl risultato dipende dal modello e dalla temperatura del telefono."
    }
}

/** Compare 4, 6 and 8 threads using identical 10-second audio and one loaded model. */
object CpuBenchmarker {
    suspend fun run(
        context: Context, source: File, model: Model, language: String,
        callback: suspend (String)->Unit
    ): CpuBenchmark {
        require(AudioPreparer.canonical(source)) { "Completa prima una trascrizione M4A/audio" }
        val modelFile = ModelFiles(context).file(model)
        require(modelFile.exists()) { "Il modello deve essere scaricato" }
        val sample = File(context.cacheDir, "benchmark_sample_10s.wav")
        AudioPreparer.chunk(source, 0, sample, 10)
        var handle: dev.ffmpegkit.whisper.WhisperModel? = null
        try {
            callback("Benchmark: caricamento modello")
            val start = System.nanoTime()
            handle = Whisper.loadModel(context, modelFile.absolutePath)
            val loadMs = (System.nanoTime() - start) / 1_000_000L
            val trials = mutableListOf<CpuTrial>()
            for (threads in listOf(4, 6, 8)) {
                callback("Benchmark: test " + threads + " thread (" + (trials.size + 1) + "/3)")
                val started = System.nanoTime()
                Whisper.transcribe(handle, sample.absolutePath, WhisperConfig(language=language, threads=threads))
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                trials.add(CpuTrial(threads, elapsed))
            }
            callback("Benchmark completato")
            return CpuBenchmark(loadMs, trials)
        } finally {
            if (handle != null) Whisper.releaseModel(handle)
            sample.delete()
        }
    }
}
