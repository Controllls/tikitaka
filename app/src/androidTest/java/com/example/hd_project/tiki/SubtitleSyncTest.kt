package com.example.hd_project.tiki

import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.LaughterAnalyzer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SubtitleSyncTest {
    @Test fun koreanSpeechKeepsSourceWindows() = runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation()
        val context=ins.targetContext
        val source=File(context.cacheDir,"subtitle-sync-fixture.m4a")
        ins.context.assets.open("momo-synthetic-korean.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
        val samples=mutableListOf<Float>()
        LaughterAnalyzer(context).decode(source,{false},{},{},samples::add)
        val pcm=samples.toFloatArray()
        try {
            val expected=SpeechWindows.find(pcm)
            val result=PleosLocalStt.recognize(context,pcm)
            val rows=JSONArray(result.captions.map { JSONObject().put("start_ms",it.startMs).put("end_ms",it.endMs).put("text",it.text) })
            MetadataEpisodes.write(File(context.cacheDir,"subtitle-sync-result.json"),JSONObject()
                .put("complete",result.complete).put("error",result.error).put("captions",rows)
                .put("windows",JSONArray(expected.map { JSONObject().put("startMs",it.start/16).put("endMs",it.end/16) })))
            assertTrue("No meaningful transcript: ${result.error}",result.captions.size>=3)
            assertTrue(result.captions.any { it.text.contains("안전벨트") })
            assertTrue(result.captions.any { it.text.contains("드라이브") })
            assertTrue(result.captions.all { c -> expected.any { c.startMs==it.start/16L && c.endMs==it.end/16L } })
            assertTrue(result.captions.zipWithNext().all { (a,b)->a.endMs<=b.startMs })
            val clip=JSONObject().put("captions",rows).put("laughter",JSONArray())
            val bundle=MetadataEpisodes.bundle(clip,"sync",0,pcm.size*1000L/16,"a".repeat(64),sourceTimedCaptions=true)
            assertEquals(result.captions.size,bundle.result.captions.size)
            result.captions.zip(bundle.result.captions).forEach { (before,after) ->
                assertEquals(before.startMs*1000,after.startUs);assertEquals(before.endMs*1000,after.endUs)
                assertEquals(before.text,after.text)
            }
        } finally { pcm.fill(0f);source.delete() }
    }
}
