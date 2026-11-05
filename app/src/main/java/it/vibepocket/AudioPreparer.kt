package it.vibepocket

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Converts M4A/AAC, MP3, FLAC and PCM WAV to streaming 16 kHz / mono / PCM16 WAV.
 * Android's system MediaExtractor/MediaCodec handles the compressed audio:
 * the Whisper native JNI then only sees standard WAV.
 *
 * Conversion produces a .part file and atomically renames it on completion,
 * so a process crash never exposes half-decoded audio as finished.
 */
object AudioPreparer {
    const val HZ = 16000
    const val BYTES_PER_SEC = HZ * 2
    const val CHUNK_SECONDS = 30
    const val CHUNK_BYTES = BYTES_PER_SEC * CHUNK_SECONDS

    fun canonical(file: File): Boolean {
        if (!file.isFile || file.length() < 44L) return false
        return try {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(44)
                f.readFully(head)
                String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                    String(head, 12, 4, Charsets.US_ASCII) == "fmt " &&
                    little16(head, 20) == 1 &&
                    little16(head, 22) == 1 &&
                    little32(head, 24) == HZ.toLong() &&
                    little16(head, 34) == 16 &&
                    String(head, 36, 4, Charsets.US_ASCII) == "data" &&
                    little32(head, 40) == file.length() - 44L
            }
        } catch (_: Exception) { false }
    }

    fun prepare(source: File, finished: File, progress: (Int) -> Unit) {
        if (canonical(finished)) {
            progress(100)
            return
        }
        require(source.isFile && source.length() > 0L) { "Audio non disponibile" }
        require(finished.parentFile?.usableSpace ?: 0L > 80L * 1024L * 1024L) {
            "Spazio insufficiente per decodificare l'audio"
        }
        val partial = File(finished.parentFile, finished.name + ".part")
        partial.delete()
        val output = PcmWavWriter(partial)
        try {
            if (looksLikeWave(source)) convertPcmWave(source, output, progress)
            else convertWithAndroid(source, output, progress)
            output.finish()
            require(canonical(partial)) { "Audio convertito non valido" }
            if (finished.exists()) finished.delete()
            check(partial.renameTo(finished)) { "Impossibile salvare la conversione" }
            progress(100)
        } catch (ex: Exception) {
            output.closeQuietly()
            partial.delete()
            throw ex
        }
    }

    fun totalChunks(wav: File): Int {
        require(canonical(wav)) { "WAV non valido o incompleto" }
        val size = wav.length() - 44L
        return ((size + CHUNK_BYTES - 1L) / CHUNK_BYTES).toInt().coerceAtLeast(1)
    }

    fun chunk(wav: File, index: Int, destination: File, seconds: Int = CHUNK_SECONDS) {
        require(seconds in 1..CHUNK_SECONDS) { "Durata segmento non valida" }
        require(canonical(wav)) { "Audio normalizzato mancante" }
        val offset = index.toLong() * CHUNK_BYTES
        val length = min((seconds.toLong() * BYTES_PER_SEC), (wav.length() - 44L - offset).coerceAtLeast(0L))
        require(length > 0) { "Indice segmento non valido" }
        RandomAccessFile(wav, "r").use { input ->
            RandomAccessFile(destination, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(44))
                input.seek(44L + offset)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    check(n > 0) { "Lettura audio interrotta" }
                    out.write(buffer, 0, n)
                    remaining -= n
                }
                writeHeader(out, length)
            }
        }
    }

    private fun looksLikeWave(input: File): Boolean {
        if (input.length() < 12L) return false
        return try {
            RandomAccessFile(input, "r").use { f ->
                val b = ByteArray(12); f.readFully(b)
                String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(b, 8, 4, Charsets.US_ASCII) == "WAVE"
            }
        } catch (_: Exception) { false }
    }

    private fun convertPcmWave(source: File, writer: PcmWavWriter, progress: (Int) -> Unit) {
        RandomAccessFile(source, "r").use { wav ->
            wav.seek(12L)
            var format = -1
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var dataStart = -1L
            var dataSize = 0L
            while (wav.filePointer + 8 <= wav.length()) {
                val key = ByteArray(4)
                wav.readFully(key)
                val length = readU32(wav)
                val pos = wav.filePointer
                if (String(key, Charsets.US_ASCII) == "fmt ") {
                    require(length >= 16) { "Intestazione WAV non valida" }
                    format = readU16(wav)
                    channels = readU16(wav)
                    sampleRate = readU32(wav).toInt()
                    readU32(wav); readU16(wav)
                    bits = readU16(wav)
                } else if (String(key, Charsets.US_ASCII) == "data") {
                    dataStart = pos
                    dataSize = min(length, wav.length() - pos)
                    break
                }
                val next = pos + length + (length and 1L)
                check(next > pos && next <= wav.length()) { "WAV danneggiato" }
                wav.seek(next)
            }
            require(dataStart >= 0 && channels in 1..8 && sampleRate in 8000..192000) {
                "Formato WAV non riconosciuto"
            }
            require((format == 1 && bits == 16) || (format == 3 && bits == 32)) {
                "WAV non supportato: occorre PCM16 oppure Float32"
            }
            val bytesPerSample = bits / 8
            val frameBytes = channels * bytesPerSample
            val resampler = StreamResampler(sampleRate, writer)
            val buffer = ByteArray(frameBytes * 1024)
            var done = 0L
            wav.seek(dataStart)
            while (done + frameBytes <= dataSize) {
                val length = min(buffer.size.toLong(), dataSize - done).toInt() / frameBytes * frameBytes
                if (length <= 0) break
                wav.readFully(buffer, 0, length)
                val b = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                repeat(length / frameBytes) {
                    var sum = 0f
                    repeat(channels) {
                        sum += if (format == 3) b.float else b.short / 32768f
                    }
                    resampler.accept(sum / channels)
                }
                done += length
                progress((done * 100 / dataSize.coerceAtLeast(1L)).toInt().coerceIn(0, 99))
            }
            check(done > 0) { "WAV vuoto" }
        }
    }

    private fun convertWithAndroid(source: File, writer: PcmWavWriter, progress: (Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Nessuna traccia audio nel file")
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: error("Formato audio sconosciuto")
            val duration = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else -1L
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnd = false
            var outputEnd = false
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamResampler(rate, writer)
            var hadOutput = false
            while (!outputEnd) {
                if (!inputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(10000L)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("Decoder senza buffer di ingresso")
                        buffer.clear()
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd = true
                        } else {
                            val timeUs = extractor.sampleTime.coerceAtLeast(0)
                            codec.queueInputBuffer(inputIndex, 0, n, timeUs, 0)
                            extractor.advance()
                            if (duration > 0) progress((timeUs * 100L / duration).toInt().coerceIn(0, 99))
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val newRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newEncoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        check(!hadOutput || (newRate == rate && newChannels == channels && newEncoding == encoding)) {
                            "Formato audio variato durante la decodifica"
                        }
                        rate = newRate
                        channels = newChannels
                        encoding = newEncoding
                        if (!hadOutput) resampler = StreamResampler(rate, writer)
                    }
                    in 0..Int.MAX_VALUE -> {
                        if (info.size > 0) {
                            val data = codec.getOutputBuffer(outputIndex)?.duplicate()
                                ?: error("Decoder senza buffer di uscita")
                            data.order(ByteOrder.LITTLE_ENDIAN)
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            check(channels in 1..8) { "Numero canali non supportato" }
                            val sampleBytes = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> error("Formato PCM non supportato (" + encoding + ")")
                            }
                            while (data.remaining() >= sampleBytes * channels) {
                                var sample = 0f
                                repeat(channels) {
                                    sample += if (sampleBytes == 2) data.short / 32768f else data.float
                                }
                                resampler.accept(sample / channels)
                                hadOutput = true
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnd = true
                    }
                }
            }
            check(hadOutput) { "Nessun dato audio decodificato" }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private class StreamResampler(private val inputRate: Int, private val output: PcmWavWriter) {
        private var frame = -1L
        private var previous = 0f
        private val step = inputRate.toDouble() / HZ
        private var next = 0.0
        init { require(inputRate in 8000..192000) { "Frequenza audio non supportata" } }
        fun accept(sample: Float) {
            frame++
            if (frame == 0L) {
                output.writeSample(sample)
                next = step
            } else {
                while (next <= frame.toDouble()) {
                    val fraction = (next - (frame - 1L)).toFloat().coerceIn(0f, 1f)
                    output.writeSample(previous + (sample - previous) * fraction)
                    next += step
                }
            }
            previous = sample
        }
    }

    private class PcmWavWriter(private val file: File) {
        private val out = FileOutputStream(file)
        private val bytes = ByteArray(64 * 1024)
        private var pos = 0
        private var written = 0L
        private var closed = false
        init { out.write(ByteArray(44)) }
        fun writeSample(sample: Float) {
            val clipped = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            val s = (clipped * 32767f).toInt()
            if (pos >= bytes.size - 1) flush()
            bytes[pos++] = s.toByte()
            bytes[pos++] = (s shr 8).toByte()
            written += 2
        }
        private fun flush() { if (pos > 0) { out.write(bytes, 0, pos); pos = 0 } }
        fun finish() {
            check(written > 0L) { "Il file non contiene audio" }
            check(written < 4_000_000_000L) { "Registrazione troppo lunga per WAV standard" }
            flush()
            out.fd.sync()
            out.close()
            closed = true
            RandomAccessFile(file, "rw").use { writeHeader(it, written) }
        }
        fun closeQuietly() { if (!closed) { try { out.close() } catch (_: Exception) {} ; closed = true } }
    }

    private fun writeHeader(out: RandomAccessFile, size: Long) {
        out.seek(0)
        out.writeBytes("RIFF"); write32(out, size + 36)
        out.writeBytes("WAVEfmt "); write32(out, 16)
        write16(out, 1); write16(out, 1)
        write32(out, HZ.toLong()); write32(out, BYTES_PER_SEC.toLong())
        write16(out, 2); write16(out, 16)
        out.writeBytes("data"); write32(out, size)
    }
    private fun write16(out: RandomAccessFile, n: Int) {
        out.write(n and 255); out.write((n shr 8) and 255)
    }
    private fun write32(out: RandomAccessFile, n: Long) {
        repeat(4) { out.write(((n shr (it * 8)) and 255).toInt()) }
    }
    private fun little16(b: ByteArray, start: Int): Int =
        (b[start].toInt() and 255) or ((b[start + 1].toInt() and 255) shl 8)
    private fun little32(b: ByteArray, start: Int): Long =
        (0..3).fold(0L) { acc, k -> acc or ((b[start + k].toLong() and 255L) shl (k * 8)) }
    private fun readU16(input: RandomAccessFile): Int =
        input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun readU32(input: RandomAccessFile): Long {
        var result = 0L
        repeat(4) { result = result or (input.readUnsignedByte().toLong() shl (8 * it)) }
        return result
    }
}
