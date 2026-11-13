package com.example.hd_project

import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.pleos.DrivingRestrictions
import com.example.hd_project.pleos.RestrictedVideoView
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PleosRuntimeTest {
    @Test fun actualVideoAndMediaControllerCannotBypassRestriction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "pleos-playback-test.mp4")
        instrumentation.context.assets.open("pleos-playback.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val prepared = CountDownLatch(1)
        lateinit var video: RestrictedVideoView
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    video=RestrictedVideoView(activity).apply { canStart={true};setOnPreparedListener { prepared.countDown() } }
                    activity.addContentView(video, FrameLayout.LayoutParams(320, 240))
                    video.setVideoPath(file.path)
                }
                assertTrue(prepared.await(8, TimeUnit.SECONDS))
                scenario.onActivity { (video as MediaController.MediaPlayerControl).start();assertFalse(video.isPlaying) }
                scenario.onActivity { video.playbackAllowed=true;video.start() }
                SystemClock.sleep(400)
                var pausedAt=0
                scenario.onActivity {
                    assertTrue(video.isPlaying);assertTrue(video.currentPosition>0)
                    video.playbackAllowed=false
                    assertFalse(video.isPlaying)
                    pausedAt=video.currentPosition
                    (video as MediaController.MediaPlayerControl).start()
                    assertFalse(video.isPlaying)
                }
                SystemClock.sleep(200)
                scenario.onActivity {
                    assertTrue(kotlin.math.abs(video.currentPosition-pausedAt)<100)
                    video.playbackAllowed=true
                    assertFalse("Restriction release must not auto-resume", video.isPlaying)
                    video.stopPlayback()
                }
            }
        } finally { file.delete() }
    }
    @Test fun realPlatformRestrictionSubscriptionAndDisconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        lateinit var monitor: DrivingRestrictions
        var value = DrivingRestrictions.State()
        instrumentation.runOnMainSync {
            monitor = DrivingRestrictions(context) { value=it; if(it.available) ready.countDown() }
            monitor.start()
        }
        try {
            assertTrue("CarUxRestrictions unavailable on this image", ready.await(8, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                File(context.filesDir, "pleos-platform-restrictions.txt").writeText(value.toString())
                assertTrue(value.available)
                monitor.stop()
                assertFalse(value.videoAllowed)
                assertFalse(value.animationAllowed)
            }
        } finally { instrumentation.runOnMainSync { monitor.stop() } }
    }

    @Test fun homeCanRecreateAndPauseWithoutLosingMainActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences=instrumentation.targetContext.getSharedPreferences("tikitaka-ui",0)
        val setup=preferences.getBoolean("setup",false)
        preferences.edit().putBoolean("setup",true).commit()
        val start = SystemClock.elapsedRealtime()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                File(activity.filesDir, "pleos-home-launch-ms.txt").writeText((SystemClock.elapsedRealtime()-start).toString())
                for(tag in listOf("record_start","nav_home","nav_library","nav_character","nav_settings"))
                    assertNotNull(activity.window.decorView.findViewWithTag<View>(tag))
            }
            scenario.recreate()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNotNull(it.window.decorView.findViewWithTag<View>("nav_library")) }
        } } finally { preferences.edit().putBoolean("setup",setup).commit() }
    }
}
