package com.example.hd_project

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LaughterAnalyzer(private val context: Context) {
    fun analyze(file: File, cancelled: () -> Boolean, progress: (Int) -> Unit,
        onWindow: ((LaughWindow) -> Unit)? = null): List<HighlightRange> {
        val bytes = context.assets.open("yamnet.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        model.rewind()
        val labels = context.assets.open("yamnet_labels.txt").bufferedReader().use { it.readLines() }
        val laughNames = setOf("Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle")
        val laughIndices = labels.indices.filter { labels[it] in laughNames }
        val musicIndex = labels.indexOf("Music")
        check(laughIndices.size == 6 && musicIndex >= 0)
        Interpreter(model, Interpreter.Options().setNumThreads(2)).use { interpreter ->
            check(interpreter.getInputTensor(0).numElements() == 15600)
            check(interpreter.getOutputTensor(0).numElements() == labels.size)
            val input = ByteBuffer.allocateDirect(15600 * 4).order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(labels.size * 4).order(ByteOrder.nativeOrder())
            val frame = FloatArray(15600)
            var count = 0
            var offset = 0L
            val windows = mutableListOf<LaughWindow>()
            var durationMs = 0L
            decode(file, cancelled, { durationMs = it }, progress) { sample ->
                frame[count++] = sample
                if (count == frame.size) {
                    check(!cancelled()) { "cancelled" }
                    input.clear()
                    frame.forEach(input::putFloat)
                    input.rewind()
                    output.clear()
                    interpreter.run(input, output)
                    val laughter = laughIndices.maxOf { output.getFloat(it * 4) }
                    // Store only positive windows: long recordings have bounded working memory.
                    val window = LaughWindow(offset / 16, (offset + frame.size) / 16,
                        laughter, output.getFloat(musicIndex * 4))
                    onWindow?.invoke(window)
                    if (laughter >= 0.25f) windows.add(window)
                    frame.copyInto(frame, 0, 7800)
                    count = 7800
                    offset += 7800
                }
            }
            return HighlightSelector.select(windows, durationMs)
        }
    }

    private fun decode(file: File, cancelled: () -> Boolean, duration: (Long) -> Unit,
        progress: (Int) -> Unit, sample: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            require(durationUs > 0) { "Empty audio" }
            duration(durationUs / 1000)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = MonoResampler(rate, sample)
            var inputDone = false
            var lastProgress = -1
            var lastOutputTime = android.os.SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()
            while (!cancelled()) {
                check(android.os.SystemClock.elapsedRealtime() - lastOutputTime < 30_000) { "Decoder stalled" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    val outputRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rate != outputRate) { rate = outputRate; resampler = MonoResampler(rate, sample) }
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(channels in 1..8)
                    encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                } else if (index >= 0) {
                    try {
                        val buffer = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        while (buffer.remaining() >= sampleBytes * channels) {
                            var mono = 0f
                            repeat(channels) {
                                mono += if (sampleBytes == 4) buffer.float else buffer.short / 32768f
                            }
                            resampler.accept((mono / channels).coerceIn(-1f, 1f))
                        }
                        lastOutputTime = android.os.SystemClock.elapsedRealtime()
                        val percent = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99)
                        if (percent > lastProgress) { lastProgress = percent; progress(percent) }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            check(!cancelled()) { "cancelled" }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
