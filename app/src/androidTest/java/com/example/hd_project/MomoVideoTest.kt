@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.hd_project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class MomoVideoTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun asset(name: String)=instrumentation.context.assets.open(name)
    private suspend fun prepared(seconds: Int): Pair<SceneProject,AnalysisBundle> {
        val source=File(context.filesDir,"momo-pulse-$seconds.wav")
        asset("sample-$seconds.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        val result=MomoJson.decodeFromString<AnalysisResult>(asset("result-$seconds.json").bufferedReader().use { it.readText() })
        val tracks=MomoJson.decodeFromString<MotionTracks>(asset("tracks-$seconds.json").bufferedReader().use { it.readText() })
        val preset=FixedPreset.install(context)
        val scene=preset.apply(SceneProject("validation-$seconds","MOMO 리소스 검증 · 합성음",source.path,sha256(source),result.durationUs))
        return scene to AnalysisBundle(result,tracks)
    }
    @Test fun suppliedResourcesEncodeThreeAndThirtySeconds() = runBlocking {
        for(seconds in listOf(3,30)) {
            val (scene,bundle)=prepared(seconds)
            assertFalse(scene.sampleVisuals)
            assertTrue(scene.people.all { it.manifestRef!=null })
            val before=sha256(File(scene.audioLocalRef))
            val ready=VideoComposer.prepare(context,scene,bundle)
            val bitmap=Bitmap.createBitmap(540,960,Bitmap.Config.ARGB_8888)
            ready.renderer.draw(Canvas(bitmap),850000)
            File(context.filesDir,"momo-frame-$seconds.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
            val output=File(context.filesDir,"momo-validation-$seconds.mp4").apply { delete() }
            val started=SystemClock.elapsedRealtime()
            VideoComposer.exportFile(context,ready,output) {}
            File(context.filesDir,"momo-validation-$seconds.json").writeText(JSONObject().put("export_ms",SystemClock.elapsedRealtime()-started).toString())
            VideoComposer.validateMp4(output)
            assertEquals(before,sha256(File(scene.audioLocalRef)))
        }
    }
    @Test fun cancellationRemovesIncompleteVideo() = runBlocking {
        val (scene,bundle)=prepared(30)
        val output=File(context.filesDir,"momo-cancel.pending.mp4").apply { delete() }
        val ready=VideoComposer.prepare(context,scene,bundle)
        val job=launch { VideoComposer.exportFile(context,ready,output) {} }
        delay(1000);job.cancelAndJoin();delay(250)
        assertFalse(output.exists())
    }
}
