@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.hd_project.tiki

import android.Manifest
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.MainActivity
import com.example.hd_project.RecordingPhase
import com.example.hd_project.RecordingService
import com.example.hd_project.RecordingStore
import com.example.hd_project.momo.model.SceneProject
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.sha256
import com.example.hd_project.pleos.RecordingNavigation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NavigationBackgroundTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun isolated() { check(context.packageName.endsWith(".verification")) { "Run with -PisolatedVerification=true; never record into the user's app" } }
    private fun waitFor(condition: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+20_000
        while(!condition() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        assertTrue("Timed out",condition())
    }
    @Test fun publicNaviHelperConnectsAndReleasesOnPleos() {
        isolated()
        lateinit var capture: RecordingNavigation
        instrumentation.runOnMainSync { capture=RecordingNavigation(context);capture.start() }
        try {
            waitFor { capture.status!="connecting" }
            assertEquals("connected",capture.status)
            SystemClock.sleep(1500)
            val report=JSONObject().put("sdk","3.0.5").put("status",capture.status)
                .put("environment",capture.observation().environment.name).put("evidence",capture.observation().evidence)
            File(context.filesDir,"navigation-sdk-verification.json").writeText(report.toString())
        } finally { instrumentation.runOnMainSync { capture.close();capture.close() } }
    }
    @Test fun recordingCheckpointsNavigationAndExcludesPauseTime() {
        org.junit.Assume.assumeTrue("Only run after checking the user's recorder is idle",
            InstrumentationRegistry.getArguments().getString("allowRecordingTest")=="true")
        isolated();assertFalse(RecordingStore.state.busy)
        JourneyPreferences.choose(context,true)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                activity.onActivity { ContextCompat.startForegroundService(it,Intent(it,RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_RECORD_ONLY,true)) }
                waitFor { RecordingStore.state.phase==RecordingPhase.RECORDING }
                SystemClock.sleep(5500)
                assertNotNull(RecordingJournal.read(context)?.optJSONObject("navigationBackground"))
                context.startService(Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE))
                waitFor { RecordingStore.state.phase==RecordingPhase.PAUSED }
                val paused=RecordingStore.state.durationMs()
                val before=RecordingJournal.read(context)!!.getJSONObject("navigationBackground").toString()
                SystemClock.sleep(1200)
                assertEquals(paused,RecordingStore.state.durationMs())
                assertEquals(before,RecordingJournal.read(context)!!.getJSONObject("navigationBackground").toString())
                context.startService(Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_RESUME))
                waitFor { RecordingStore.state.phase==RecordingPhase.RECORDING }
                SystemClock.sleep(1200)
                context.startService(Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                waitFor { !RecordingStore.state.busy }
                val journal=RecordingJournal.read(context)!!
                val timeline=NavigationBackgroundPolicy.json.decodeFromString<RoadTimeline>(journal.getJSONObject("navigationBackground").toString())
                assertTrue(timeline.intervals.isNotEmpty())
                assertTrue(timeline.intervals.last().endMs>=paused+1000)
                assertTrue(timeline.intervals.last().endMs<paused+2500)
                assertFalse(journal.getJSONObject("navigationBackground").toString().contains("latitude"))
                assertFalse(journal.getJSONObject("navigationBackground").toString().contains("address"))
                File(context.filesDir,"navigation-recording-verification.json").writeText(journal.toString())
            } finally {
                if(RecordingStore.state.busy) {
                    context.startService(Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                    waitFor { !RecordingStore.state.busy }
                }
            }
        }
    }
    @Test fun allThreeSelectedAssetsProducePlayableVideoWithOriginalAudio() = runBlocking {
        isolated()
        val source=File(context.cacheDir,"navigation-background-audio.wav")
        instrumentation.context.assets.open("sample-3.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        val audioHash=sha256(source)
        val reports=JSONArray()
        for(environment in RoadEnvironment.entries) {
            val trail=RoadTimeline(intervals=listOf(RoadInterval(0,3000,RoadObservation(environment,"test-current-road"))))
            val selection=NavigationBackgroundPolicy.select(trail,0,3000)
            val clip=JSONObject().put("captions",JSONArray()).put("laughter",JSONArray())
            val key="navigation-${environment.name}"
            val bundle=MetadataEpisodes.bundle(clip,key,0,3_000_000,audioHash,2,List(150){0.0})
            val scene=TikiScene.buildPerformance(context,SceneProject(key,"내비 배경 검증",source.path,audioHash,3_000_000),background=selection)
            assertEquals(selection.asset,File(requireNotNull(scene.backgroundRef)).name)
            val output=File(context.filesDir,"$key.mp4")
            val started=SystemClock.elapsedRealtime()
            VideoComposer.exportFile(context,VideoComposer.prepare(context,scene,bundle),output) {}
            VideoComposer.validateMp4(output,1280,720)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                assertEquals(1,(0 until extractor.trackCount).count { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true })
            } finally { extractor.release() }
            assertEquals(audioHash,sha256(source))
            reports.put(JSONObject(selection.json()).put("output",output.name).put("exportMs",SystemClock.elapsedRealtime()-started))
        }
        File(context.filesDir,"navigation-render-verification.json").writeText(reports.toString())
    }
}
