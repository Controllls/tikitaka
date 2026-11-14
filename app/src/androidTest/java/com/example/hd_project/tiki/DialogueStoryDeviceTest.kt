package com.example.hd_project.tiki

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.model.SceneProject
import com.example.hd_project.momo.model.StoryBeat
import com.example.hd_project.momo.render.SceneRenderer
import com.example.hd_project.momo.render.VideoComposer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DialogueStoryDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun offlineRulesSetTitleAndRenderShotsWithoutAModel(): Unit=runBlocking {
        check(context.packageName.endsWith(".verification")) { "Run in the isolated verification app" }
        val lines=listOf(StoryLine(0,1_000_000,"다이어트 한다더니 치킨은 왜 시켰어?"),
            StoryLine(1_000_000,2_000_000,"닭가슴살도 닭이잖아. 같은 다이어트야."),
            StoryLine(2_000_000,3_000_000,"그럼 치즈볼은 뭐야? 운동할 때 굴리는 공이야?"))
        val story=DialogueStoryRules.plan(lines,3_000_000)
        assertEquals("extracted",story.status);assertEquals("none",story.model)
        assertTrue("Title must relate to this dialogue: ${story.title}",listOf("다이어트","치킨","닭","치즈","살","식단").any { it in story.title })
        assertEquals(3,story.beats.size)
        assertTrue(story.beats.any { it.shot!="wide" })
        val folder=File(context.filesDir,"story-validation").apply { mkdirs() }
        File(folder,"text-title-result.json").writeText(DialogueStoryRules.json.encodeToString(story))
        val audio=File(folder,"fixture.m4a")
        InstrumentationRegistry.getInstrumentation().context.assets.open("tiki-synthetic-3.m4a").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val rows=JSONArray();lines.forEach { rows.put(JSONObject().put("start_ms",it.startUs/1000).put("end_ms",it.endUs/1000).put("text",it.text)) }
        val clip=JSONObject().put("captions",rows).put("laughter",JSONArray())
        val hash=com.example.hd_project.momo.storage.sha256(audio)
        val bundle=MetadataEpisodes.bundle(clip,"story-test",0,3_000_000,hash,2,List(150){.01},mapOf(0L to 0,1000L to 1,2000L to 0))
        val scene=TikiScene.buildPerformance(context,SceneProject("story-test",story.title,audio.path,hash,3_000_000,
            storyBeats=story.beats,mentionedPlace=story.mentionedPlace))
        val movie=File(folder,"story-test.mp4")
        VideoComposer.exportFile(context,VideoComposer.prepare(context,scene,bundle),movie) {}
        VideoComposer.validateMp4(movie,1280,720)
        assertEquals("Rendering must preserve the source audio",hash,com.example.hd_project.momo.storage.sha256(audio))
        // The current renderer keeps the cabin fixed even when saved plans request speaker focus.
        val wide=SceneRenderer(scene.copy(storyBeats=emptyList(),mentionedPlace=""),bundle)
        val focus=SceneRenderer(scene.copy(storyBeats=listOf(StoryBeat(0,3_000_000,"speaker")),mentionedPlace=""),bundle)
        val a=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);val b=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        try {
            wide.draw(Canvas(a),500_000);focus.draw(Canvas(b),500_000)
            assertTrue("Speaker focus must preserve the fixed cabin framing",a.sameAs(b))
            File(folder,"wide.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"speaker.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { wide.close();focus.close();a.recycle();b.recycle() }
    }

    @Test fun missingTranscriptStillProducesABasicPlanWithoutDownloads() {
        check(context.packageName.endsWith(".verification"))
        val story=DialogueStoryRules.plan(emptyList(),1_000_000,"웃음이 담긴 순간")
        assertEquals("basic",story.status);assertEquals("웃음이 담긴 순간",story.title)
        assertEquals("none",story.model);assertTrue(story.beats.isEmpty())
    }
}
