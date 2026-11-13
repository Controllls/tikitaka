package com.example.hd_project

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.tiki.EpisodeWork
import com.example.hd_project.tiki.RecordingJournal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Run only after the host verifies there is no user recording: instrumentation restarts the app. */
class RecordingPowerSignalsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun request(path: String, method: String = "GET"): JSONObject {
        val connection = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method; connection.connectTimeout = 2000; connection.readTimeout = 2000
            assertEquals(200, connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun publish(state: String) {
        request("/vehicle/power-system/simulator?area_id=0&state=$state", "PUT")
        waitFor { request("/vehicle/power-system").optJSONObject("actual")?.optJSONObject("0")?.optString("state") == state }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for power/recording state", condition())
    }
    @Test fun powerOffFinalizesAnActiveRecording() = checkStop(paused = false)
    @Test fun powerOffFinalizesAPausedRecording() = checkStop(paused = true)

    private fun checkStop(paused: Boolean) {
        assertFalse("Do not interrupt a user recording", RecordingStore.state.busy)
        val initial = request("/vehicle/power-system")
        assertTrue(initial.getBoolean("bridge_ready"))
        val originalPower = initial.getJSONObject("actual").getJSONObject("0").getString("state")
        val journal = File(RecordingFiles.directory(context), "session-journal.json")
        val backup = journal.takeIf { it.isFile }?.readBytes()
        val existing = RecordingFiles.list(context).map { it.file.name }.toSet()
        val prefs = context.getSharedPreferences("tikitaka-ui", 0)
        val wasSetup = prefs.getBoolean("setup", false)
        val highlightBefore = AutomaticHighlights.latestSource(context)
        var ownSession = false
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        prefs.edit().putBoolean("setup", true).commit()
        try {
            publish("On")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.startForegroundService(Intent(it, RecordingService::class.java).setAction(RecordingService.ACTION_START)
                        .putExtra(RecordingService.EXTRA_RECORD_ONLY, true))
                    ownSession = true
                }
                waitFor { RecordingStore.state.phase == RecordingPhase.RECORDING }
                SystemClock.sleep(2200)
                if (paused) {
                    scenario.onActivity { it.startService(Intent(it, RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE)) }
                    waitFor { RecordingStore.state.phase == RecordingPhase.PAUSED }
                }
                // Power monitoring belongs to the recording service, including while the UI is stopped.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                val offAt = SystemClock.elapsedRealtime()
                publish("Off")
                waitFor { !RecordingStore.state.busy }
                val elapsed = SystemClock.elapsedRealtime() - offAt
                val saved = requireNotNull(RecordingJournal.read(context))
                assertEquals("ignition_off", saved.getString("stopTrigger"))
                assertTrue(saved.getString("state") in listOf("saved", "silent"))
                val added = RecordingFiles.list(context).filter { it.file.name !in existing }
                assertEquals(1, added.size)
                assertTrue(RecordingJournal.duration(added.single().file) >= 1800)
                assertNull(EpisodeWork.status(context, added.single().file.name))
                assertEquals(highlightBefore, AutomaticHighlights.latestSource(context))
                publish("Off"); publish("On"); SystemClock.sleep(1200)
                assertFalse("Power On must not start recording", RecordingStore.state.busy)
                assertEquals(1, RecordingFiles.list(context).count { it.file.name !in existing })
                File(context.filesDir, "tikitaka-validation").mkdirs()
                File(context.filesDir, "tikitaka-validation/power-off-${if(paused) "paused" else "active"}.json")
                    .writeText(JSONObject().put("stopTrigger", "ignition_off").put("saved", true)
                        .put("paused", paused).put("uiStopped", true).put("offToSavedMs", elapsed)
                        .put("durationMs", RecordingJournal.duration(added.single().file)).toString())
            }
        } finally {
            if (ownSession && RecordingStore.state.busy) {
                context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                waitFor { !RecordingStore.state.busy }
            }
            publish(originalPower)
            RecordingFiles.list(context).filter { it.file.name !in existing }.forEach { RecordingFiles.delete(it) }
            if (backup == null) journal.delete() else journal.writeBytes(backup)
            prefs.edit().putBoolean("setup", wasSetup).commit()
        }
    }
}
