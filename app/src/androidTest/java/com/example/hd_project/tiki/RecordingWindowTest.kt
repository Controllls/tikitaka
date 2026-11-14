package com.example.hd_project.tiki

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
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
    @Test fun recordingAndHomeStayInOneTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Synthetic UI state must never replace a user's real recording state.
        assumeTrue(context.packageName.endsWith(".verification"))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra("tikiDestination", "home")).use { scenario ->
            try {
                repeat(2) {
                    var taskId = -1
                    scenario.onActivity { activity ->
                        taskId = activity.taskId
                        RecordingStore.publish(RecordingState(RecordingPhase.RECORDING,
                            startedAt = SystemClock.elapsedRealtime()))
                        TikiActivity::class.java.getDeclaredMethod("go", String::class.java)
                            .apply { isAccessible = true }.invoke(activity, "record")
                    }
                    var returned = false
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (!returned && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync {
                            val recording = ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<RecordingActivity>()
                                .firstOrNull()
                            val home = recording?.findViewById<View>(android.R.id.content)
                                ?.findViewWithTag<View>("nav_home")
                            if (recording != null && home != null) {
                                assertEquals("Recording opened a separate split-screen task", taskId, recording.taskId)
                                home.performClick()
                                returned = true
                            }
                        }
                        if (!returned) SystemClock.sleep(100)
                    }
                    assertTrue("Recording screen did not become ready", returned)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertEquals(taskId, activity.taskId)
                        assertNotNull(activity.findViewById<View>(android.R.id.content)
                            .findViewWithTag<View>("tiki_home"))
                    }
                }
            } finally {
                instrumentation.runOnMainSync { RecordingStore.publish(RecordingState()) }
            }
        }
    }
}
