package com.example.hd_project.tiki

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.model.SceneProject
import com.example.hd_project.momo.render.MotionEngine
import com.example.hd_project.momo.render.SceneRenderer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HeadTurnRenderTest {
    @Test fun actualSpritesTurnOnceAndStayFrontDuringCooldown() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clip=JSONObject().put("captions",JSONArray()
            .put(JSONObject().put("start_ms",0).put("end_ms",1500).put("text","왜 그래요?"))
            .put(JSONObject().put("start_ms",2300).put("end_ms",3800).put("text","어디로 가나요?")))
            .put("laughter",JSONArray())
        val bundle=MetadataEpisodes.bundle(clip,"head-turn",0,4_000_000,"a".repeat(64),2,List(200){0.0},mapOf(0L to 0,2300L to 0))
        val scene=TikiScene.buildPerformance(context,SceneProject("head-turn","검증","unused","a".repeat(64),4_000_000))
        val engine=MotionEngine(scene,bundle)
        val actual=SceneRenderer(scene,bundle)
        val front=SceneRenderer(scene.copy(rendererVersion="momo-android-motion-v1"),bundle)
        val a=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        val b=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        val folder=File(context.cacheDir,"head-turn-verification").apply { mkdirs() }
        try {
            actual.draw(Canvas(a),500_000);front.draw(Canvas(b),500_000)
            assertEquals("right",engine.motionAt("P2",500_000).gaze)
            assertFalse("A permitted head turn must use different sprite pixels",a.sameAs(b))
            File(folder,"allowed-turn.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG,100,it) }
            actual.draw(Canvas(a),2_400_000);front.draw(Canvas(b),2_400_000)
            assertNull(engine.motionAt("P2",2_400_000).gaze)
            assertTrue("The second cue inside cooldown must render front-facing pixels",a.sameAs(b))
            File(folder,"blocked-turn.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG,100,it) }
            actual.draw(Canvas(a),500_000);front.draw(Canvas(b),500_000)
            assertFalse("Seeking backwards must not change the accepted turn",a.sameAs(b))
        } finally { actual.close();front.close();a.recycle();b.recycle() }
    }
}
