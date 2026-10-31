package com.example.hd_project

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Copies complete AAC packets into a new M4A; never edits the original recording. */
object AudioClipper {
    fun export(source: File, destination: File, range: HighlightRange, cancelled: () -> Boolean): HighlightRange {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val writer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val outputTrack = writer.addTrack(format)
            writer.start()
            extractor.seekTo(range.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(65536) else 65536
            val buffer = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            var lastUs = -1L
            while (extractor.sampleTime >= 0 && extractor.sampleTime < range.endMs * 1000) {
                check(!cancelled()) { "cancelled" }
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                if (bytes < 0) break
                if (firstUs < 0) firstUs = extractor.sampleTime
                lastUs = extractor.sampleTime
                info.set(0, bytes, lastUs - firstUs, 0)
                writer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            check(firstUs >= 0 && lastUs >= firstUs) { "Empty clip" }
            writer.stop()
            val packetUs = 1024L * 1_000_000 / format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            return range.copy(startMs = firstUs / 1000,
                endMs = ((lastUs + packetUs) / 1000).coerceAtMost(format.getLong(MediaFormat.KEY_DURATION) / 1000))
        } finally {
            muxer?.release()
            extractor.release()
        }
    }
}
