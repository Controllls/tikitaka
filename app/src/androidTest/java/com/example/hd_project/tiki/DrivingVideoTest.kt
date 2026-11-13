package com.example.hd_project.tiki

import android.content.Intent
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.*
import com.example.hd_project.pleos.DrivingRestrictions
import com.example.hd_project.pleos.RestrictedVideoView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in real Virtual Console -> CarUxRestrictions -> playback/UI regression check. */
class DrivingVideoTest {
    private fun request(path: String, method: String="GET"): JSONObject {
        val c=URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        return try {
            c.requestMethod=method;c.connectTimeout=2000;c.readTimeout=2000
            check(c.responseCode==200) { "Console HTTP ${c.responseCode}" }
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private fun field(a: TikiActivity,name: String): Any? = TikiActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun guard(a: TikiActivity) = TikiActivity::class.java.getDeclaredMethod("getRestrictions").apply { isAccessible=true }.invoke(a) as DrivingRestrictions
    private fun video(a: TikiActivity)=requireNotNull(field(a,"video")) as RestrictedVideoView
    private fun waitFor(s: ActivityScenario<MainActivity>, condition: (TikiActivity)->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<deadline) {
            var done=false;s.onActivity { done=condition(it) };if(done)return
            SystemClock.sleep(100)
        }
        s.onActivity { assertTrue("Timed out: ${guard(it).state}",condition(it)) }
    }
    @Test fun realDrivingTransitionHidesAndLocksVideoUntilManualParkedPlayback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val name=InstrumentationRegistry.getArguments().getString("drivingMovie")
        assumeTrue("An explicit local movie is required; this test changes simulated driving state",!name.isNullOrBlank())
        val context=instrumentation.targetContext
        check(!RecordingStore.state.busy) { "Do not interrupt a user recording" }
        val movie=File(AnimationFiles.directory(context),requireNotNull(name)).canonicalFile
        check(movie.parentFile==AnimationFiles.directory(context).canonicalFile && movie.isFile)
        val originalHash=com.example.hd_project.momo.storage.sha256(movie)
        val original=request("/vehicle/state")
        val originalGear=original.getJSONObject("gear").getJSONObject("actual").getString("value")
        val originalControls=original.getJSONObject("controls")
        val prefs=context.getSharedPreferences("tikitaka-ui",0)
        val savedPosition=prefs.all["position:video:$name"] as? Int
        val heartbeat=Executors.newSingleThreadScheduledExecutor()
        val lease="/browser/session?client=tikitaka-driving-verification"
        val report=JSONObject()
        try {
            request(lease,"POST")
            heartbeat.scheduleAtFixedRate({ runCatching { request(lease,"POST") } },500,500,TimeUnit.MILLISECONDS)
            request("/vehicle/controls?speed=0&accelerator=0&brake=0","PUT")
            request("/vehicle/gear?gear=Park","PUT")
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("episodeMovie",name)).use { s ->
                waitFor(s) { guard(it).state.videoAllowed && field(it,"playbackPrepared")==true }
                s.onActivity {
                    assertEquals(View.GONE,it.window.decorView.findViewWithTag<View>("video_driving_blocker").visibility)
                    video(it).start();(field(it,"player") as MediaPlayer).apply { isLooping=true;setVolume(0f,0f) }
                }
                SystemClock.sleep(400)
                s.onActivity { assertTrue(video(it).isPlaying) }
                val started=SystemClock.elapsedRealtime()
                request("/vehicle/gear?gear=Drive","PUT")
                request("/vehicle/controls?speed=30&accelerator=20&brake=0","PUT")
                waitFor(s) { guard(it).state.available && !guard(it).state.videoAllowed }
                report.put("driveToBlockedMs",SystemClock.elapsedRealtime()-started)
                File(context.filesDir,"tikitaka-validation").mkdirs()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.filesDir,"tikitaka-validation/driving-video-blocked.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    };bitmap.recycle()
                }
                var paused=0
                s.onActivity {
                    report.put("drivingState",guard(it).state.toString())
                    assertFalse(video(it).isPlaying);paused=video(it).currentPosition
                    assertEquals(View.VISIBLE,it.window.decorView.findViewWithTag<View>("video_driving_blocker").visibility)
                    assertFalse((field(it,"play") as TextView).isEnabled)
                    assertFalse((field(it,"seek") as SeekBar).isEnabled)
                    assertFalse(it.window.decorView.findViewWithTag<View>("diagnostics_play").isEnabled)
                    video(it).start();video(it).seekTo(paused+1200);video(it).resume()
                    assertFalse(video(it).isPlaying)
                }
                SystemClock.sleep(250)
                s.onActivity { assertTrue(kotlin.math.abs(video(it).currentPosition-paused)<150) }
                s.moveToState(Lifecycle.State.CREATED);s.moveToState(Lifecycle.State.RESUMED)
                waitFor(s) { guard(it).state.available && !guard(it).state.videoAllowed }
                s.recreate()
                waitFor(s) { guard(it).state.available && !guard(it).state.videoAllowed && field(it,"playbackPrepared")==true }
                s.onActivity {
                    assertFalse(video(it).isPlaying)
                    assertEquals(View.VISIBLE,it.window.decorView.findViewWithTag<View>("video_driving_blocker").visibility)
                }
                request("/vehicle/controls?speed=0&accelerator=0&brake=0","PUT")
                // Stopping at a light in Drive does not unlock the app's video.
                SystemClock.sleep(350)
                s.onActivity { assertFalse(guard(it).state.videoAllowed) }
                request("/vehicle/gear?gear=Park","PUT")
                waitFor(s) { guard(it).state.videoAllowed && field(it,"playbackSeekPending")==false }
                s.onActivity {
                    assertFalse("Parking must not automatically resume playback",video(it).isPlaying)
                    assertEquals(View.GONE,it.window.decorView.findViewWithTag<View>("video_driving_blocker").visibility)
                    assertTrue((field(it,"seek") as SeekBar).isEnabled)
                    assertTrue("Resume position must be retained",kotlin.math.abs(video(it).currentPosition-paused)<300)
                    (field(it,"play") as TextView).performClick()
                    (field(it,"player") as MediaPlayer).setVolume(0f,0f)
                    assertTrue(video(it).isPlaying)
                    guard(it).stop()
                    assertFalse(video(it).isPlaying)
                    assertTrue(it.window.decorView.findViewWithTag<TextView>("video_driving_blocker").text.contains("확인"))
                    assertFalse((field(it,"seek") as SeekBar).isEnabled)
                    guard(it).start()
                }
                waitFor(s) { guard(it).state.videoAllowed }
                s.onActivity { assertFalse(video(it).isPlaying) }
                report.put("blockedSeekAndStart",true).put("blockedAfterRecreation",true)
                    .put("parkRequiresManualPlay",true).put("disconnectedBlocked",true).put("savedPositionMs",paused)
            }
            assertEquals(originalHash,com.example.hd_project.momo.storage.sha256(movie))
            File(context.filesDir,"tikitaka-validation").mkdirs()
            File(context.filesDir,"tikitaka-validation/driving-video.json").writeText(report.toString())
        } catch(error: Throwable) {
            report.put("error",error.toString());throw error
        } finally {
            File(context.filesDir,"tikitaka-validation").mkdirs()
            File(context.filesDir,"tikitaka-validation/driving-video.json").writeText(report.toString())
            try {
                request("/vehicle/controls?speed=0&accelerator=0&brake=0","PUT")
                request("/vehicle/gear?gear=$originalGear","PUT")
                request("/vehicle/controls?speed=${originalControls.getInt("speed_kph")}&accelerator=${originalControls.getInt("accelerator_pct")}&brake=${originalControls.getInt("brake_pct")}","PUT")
            } finally {
                heartbeat.shutdownNow();runCatching { request("$lease&closing=1","POST") }
                prefs.edit().apply { if(savedPosition==null)remove("position:video:$name") else putInt("position:video:$name",savedPosition) }.commit()
            }
        }
    }
}
