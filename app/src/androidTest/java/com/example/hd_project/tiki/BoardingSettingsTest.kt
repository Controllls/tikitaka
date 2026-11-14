package com.example.hd_project.tiki

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BoardingSettingsTest {
    @Test fun optInStartsStandbyAndNotificationOpensPreparationWithoutRecording() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".verification"))
        assumeTrue(Build.VERSION.SDK_INT>=29)
        if(Build.VERSION.SDK_INT>=33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        fun waitFor(condition: ()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+10_000
            while(SystemClock.elapsedRealtime()<deadline) {
                var ready=false
                instrumentation.runOnMainSync { ready=condition() }
                if(ready)return
                SystemClock.sleep(100)
            }
            instrumentation.runOnMainSync { assertTrue("Timed out waiting for boarding state",condition()) }
        }
        fun clickDialogButton(label: String) {
            // Pleos embeds multiple resumed tasks; Espresso's focused-root heuristic
            // can select the other app. Dispatch to this process's actual dialog button.
            instrumentation.runOnMainSync {
                val button=WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap { root -> sequenceOf(android.R.id.button1,android.R.id.button2,android.R.id.button3)
                        .mapNotNull { root.findViewById<Button>(it) } }
                    .firstOrNull { it.text.toString()==label }
                assertNotNull("Missing dialog button: $label",button)
                button!!.performClick()
            }
            instrumentation.waitForIdleSync()
        }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
            .putExtra("tikiDestination","settings")).use { scenario ->
            try {
                // Let the parked-state callback arrive before opening settings.
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1000)
                scenario.onActivity { activity ->
                    TikiActivity::class.java.getDeclaredMethod("boardingOptions").apply { isAccessible=true }.invoke(activity)
                }
                clickDialogButton("켜기")
                waitFor { BoardingCoordinator.state.enabled && BoardingCoordinator.state.serviceRunning }
                assertTrue(context.getSharedPreferences("boarding",0).getBoolean("enabled",false))
                BoardingNotifications.invitation(context).contentIntent.send()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("tiki_record"))
                    assertFalse("An invitation must never start the microphone",RecordingStore.state.busy)
                    assertFalse("Opening an invitation must consume it",BoardingCoordinator.state.awaitingResponse)
                    TikiActivity::class.java.getDeclaredMethod("boardingOptions").apply { isAccessible=true }.invoke(activity)
                }
                clickDialogButton("끄기")
                waitFor { !BoardingCoordinator.state.enabled && !BoardingCoordinator.state.serviceRunning }
                assertFalse(context.getSharedPreferences("boarding",0).getBoolean("enabled",true))
            } finally {
                instrumentation.runOnMainSync {
                    BoardingCoordinator.setEnabled(false)
                    context.stopService(Intent(context,BoardingService::class.java))
                }
            }
        }
    }
}
