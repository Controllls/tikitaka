@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.hd_project

import android.content.Intent
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.test.core.app.ActivityScenario
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
    @Test fun recordingButtonRunsRealAnalysisAndFixedVideo() {
        val source=File(RecordingFiles.directory(context),"momo_validation_synthetic_20261.m4a")
        if(!source.exists())asset("momo-synthetic-korean.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
        val metadata=File(source.parentFile,source.nameWithoutExtension+".json")
        metadata.writeText(JSONObject().put("title","MOMO 영상 데모 · 합성 한국어").toString())
        val originalHash=sha256(source)
        // This test-owned project is reset so every run exercises upload + real inference.
        runBlocking { ProjectRepository(context).deleteLocal(MomoVideoSession.projectId(source.name)) }
        val consent=context.getSharedPreferences("momo-consent",0)
        val previousConsent=consent.getBoolean("server-voice-v1",false)
        consent.edit().putBoolean("server-voice-v1",true).commit()
        val output=AnimationFiles.output(context,source)
        val previousStamp=output.lastModified()
        try {
            ActivityScenario.launch<AnimationActivity>(Intent(context,AnimationActivity::class.java).putExtra("sourceId",source.name)).use { scenario ->
                val deadline=SystemClock.elapsedRealtime()+300000
                var enabled=false
                while(!enabled && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { enabled=it.findViewById<View>(R.id.animation_generate).isEnabled }
                    SystemClock.sleep(100)
                }
                assertTrue("Video screen did not become ready",enabled)
                scenario.onActivity { activity ->
                    assertEquals(0,activity.resources.getIdentifier("animation_subtitles","id",context.packageName))
                    activity.findViewById<View>(R.id.animation_generate).performClick()
                }
                while((!output.exists() || output.lastModified()==previousStamp) && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(500)
                assertTrue("The video screen did not produce a new MP4",output.exists() && output.lastModified()!=previousStamp)
                VideoComposer.validateMp4(output)
                assertEquals(originalHash,sha256(source))
                runBlocking {
                    val repository=ProjectRepository(context)
                    val bundle=repository.loadAnalysis(MomoVideoSession.projectId(source.name))!!
                    assertEquals("inference",bundle.result.dataOrigin)
                    assertTrue(bundle.result.captions.isNotEmpty())
                    assertNotNull(bundle.result.voiceModelId)
                    assertTrue("Synthetic solo speech must yield an enrollment sample",bundle.result.speakerSignatures.any { it.speechUs>=6_000_000 })
                }
                output.copyTo(File(context.filesDir,"momo-validation-server.mp4"),overwrite=true)
                val beforeExport=latestExportId()
                scenario.onActivity { it.findViewById<View>(R.id.animation_export).performClick() }
                var exportedId=beforeExport
                val saveDeadline=SystemClock.elapsedRealtime()+15000
                while(exportedId<=beforeExport && SystemClock.elapsedRealtime()<saveDeadline) {
                    SystemClock.sleep(100);exportedId=latestExportId()
                }
                assertTrue("MP4 was not published to Movies/이동기록",exportedId>beforeExport)
                val exported=File(context.cacheDir,"momo-export-validation.mp4")
                try {
                    val uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,exportedId)
                    context.contentResolver.openInputStream(uri)!!.use { input -> exported.outputStream().use { input.copyTo(it) } }
                    assertEquals("Published MP4 must match the completed movie",sha256(output),sha256(exported))
                } finally { exported.delete() }
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(output.path)
                    assertEquals("540",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertEquals("960",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                }
            }
        } finally { consent.edit().putBoolean("server-voice-v1",previousConsent).commit() }
    }
    private fun latestExportId(): Long = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
        arrayOf("Movies/이동기록/","MOMO_%"),"${MediaStore.Video.Media._ID} DESC"
    )?.use { if(it.moveToFirst())it.getLong(0) else 0L } ?: 0L
}
