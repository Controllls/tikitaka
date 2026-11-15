package com.example.hd_project.tiki

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.hd_project.MainActivity
import com.example.hd_project.RecordingPhase
import com.example.hd_project.RecordingState
import com.example.hd_project.RecordingStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RecordingWindowTest {
    /**
     * The Pleos home screen covers a task while driving unless the activity that created it is
     * distraction optimized, and split view shows every task of an app. Recording therefore runs
     * as the root of its own task and closes the parked-only task while it is shown.
     */
    @Test fun recordingReplacesTheParkedTaskAndReturnsHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Synthetic UI state must never replace a user's real recording state.
        assumeTrue(context.packageName.endsWith(".verification"))
        fun <T : Activity> resumed(type: Class<T>, ready: (T) -> Boolean): T {
            var found: T? = null
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (found == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance(type).firstOrNull(ready)
                }
                if (found == null) SystemClock.sleep(100)
            }
            return requireNotNull(found) { "${type.simpleName} did not become ready" }
        }
        fun View.tagged(tag: String): View? = findViewWithTag(tag)
        fun Activity.content(): View = findViewById(android.R.id.content)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("tikiDestination", "home"))
        try {
            repeat(2) {
                val main = resumed(MainActivity::class.java) { it.content().tagged("tiki_home") != null }
                val parkedTask = main.taskId
                instrumentation.runOnMainSync {
                    RecordingStore.publish(RecordingState(RecordingPhase.RECORDING,
                        startedAt = SystemClock.elapsedRealtime()))
                    TikiActivity::class.java.getDeclaredMethod("go", String::class.java)
                        .apply { isAccessible = true }.invoke(main, "record")
                }
                // The parked-only destinations are enabled once the parked vehicle state has arrived.
                val recording = resumed(RecordingActivity::class.java) {
                    it.content().tagged("nav_library")?.isEnabled == true
                }
                assertNotEquals("Recording stayed in the parked-only task", parkedTask, recording.taskId)
                val closed = SystemClock.elapsedRealtime() + 10_000
                while (!main.isDestroyed && SystemClock.elapsedRealtime() < closed) SystemClock.sleep(100)
                assertTrue("The parked-only task stayed next to the recording task", main.isDestroyed)
                instrumentation.runOnMainSync { recording.content().tagged("nav_home")!!.performClick() }
                val home = resumed(MainActivity::class.java) { it.content().tagged("tiki_home") != null }
                assertNotSame(main, home)
                assertNotEquals(recording.taskId, home.taskId)
            }
        } finally {
            instrumentation.runOnMainSync {
                RecordingStore.publish(RecordingState())
                listOf(Stage.RESUMED, Stage.STARTED, Stage.STOPPED, Stage.CREATED).flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.distinct().filter { it is MainActivity || it is RecordingActivity }.forEach { it.finish() }
            }
        }
    }
}
