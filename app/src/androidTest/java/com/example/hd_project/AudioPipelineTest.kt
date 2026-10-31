package com.example.hd_project

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {
    @Test fun knownLaughProducesARealHighlight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "test_${UUID.randomUUID()}.m4a")
        try {
            val report = mutableListOf<String>()
            for (name in listOf("laughing.m4a", "1-73123-A-26.m4a")) {
                instrumentation.context.assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
                val windows = mutableListOf<LaughWindow>()
                val ranges = LaughterAnalyzer(context).analyze(source, { false }, {}, windows::add)
                report.add("$name: count=${ranges.size}, scores=${windows.map { it.laughter }}")
                if (name == "1-73123-A-26.m4a") {
                    assertTrue("$name: $windows", ranges.isNotEmpty())
                }
            }
            android.util.Log.i("LaughEvaluation", report.joinToString("\n"))
        } finally { source.delete() }
    }
    @Test fun actualModelRejectsSilenceAndMuxedClipIsPlayableWithoutChangingSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "test_${UUID.randomUUID()}.m4a")
        val destination = File(context.cacheDir, "test_clip_${UUID.randomUUID()}.m4a")
        try {
            instrumentation.context.assets.open("silence_8000.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
            val original = source.readBytes()
            assertTrue(LaughterAnalyzer(context).analyze(source, { false }, {}).isEmpty())
            val range = AudioClipper.export(source, destination, HighlightRange(1500, 4500, .9f)) { false }
            assertTrue(range.startMs in 1470..1500)
            assertTrue(range.endMs in 4500..4530)
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(destination.absolutePath)
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("duration=$duration", duration in 2970..3060)
            }
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete(); destination.delete() }
    }
}
