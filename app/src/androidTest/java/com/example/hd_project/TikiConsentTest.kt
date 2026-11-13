package com.example.hd_project

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.tiki.EpisodeWork
import com.example.hd_project.tiki.RecordingJournal
import com.example.hd_project.tiki.TikiActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TikiConsentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folder get() = File(context.filesDir, "tikitaka-consent-validation").apply { mkdirs() }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out", condition())
    }
    private fun click(scenario: ActivityScenario<MainActivity>, tag: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            val view = it.window.decorView.findViewWithTag<View>(tag)
            assertNotNull("Missing $tag", view)
            assertTrue(view.performClick())
        }
        instrumentation.waitForIdleSync()
    }
    private fun screen(scenario: ActivityScenario<MainActivity>, block: (MainActivity, FrameLayout) -> Unit) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { block(it, it.window.decorView.findViewWithTag("tiki_root")) }
    }
    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        assertFalse("Preserve active user recordings", RecordingStore.state.busy)
        val prefs = context.getSharedPreferences("tikitaka-ui", 0)
        val setup = prefs.getBoolean("setup", false)
        prefs.edit().putBoolean("setup", true).commit()
        try { ActivityScenario.launch(MainActivity::class.java).use(block) }
        finally { prefs.edit().putBoolean("setup", setup).commit() }
    }
    private fun capture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun cancelAndRecreationKeepModeWithoutRecording() = launch { scenario ->
        val journal = RecordingJournal.read(context)?.toString()
        click(scenario, "record_start")
        screen(scenario) { _, root -> root.findViewWithTag<CheckBox>("record_only").isChecked = true }
        click(scenario, "record_confirm")
        screen(scenario) { _, root ->
            assertNotNull(root.findViewWithTag<View>("tiki_consent"))
            assertTrue(root.findViewWithTag<TextView>("consent_message").text.contains("기기에만"))
        }
        scenario.recreate()
        screen(scenario) { _, root ->
            assertNotNull(root.findViewWithTag<View>("tiki_consent"))
            assertFalse(RecordingStore.state.busy)
        }
        click(scenario, "consent_cancel")
        screen(scenario) { _, root -> assertTrue(root.findViewWithTag<CheckBox>("record_only").isChecked) }
        click(scenario, "record_confirm")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        screen(scenario) { _, root -> assertTrue(root.findViewWithTag<CheckBox>("record_only").isChecked) }
        click(scenario, "record_confirm")
        click(scenario, "consent_back")
        screen(scenario) { _, root -> root.findViewWithTag<CheckBox>("record_only").isChecked = false }
        click(scenario, "record_confirm")
        screen(scenario) { _, root ->
            assertTrue(root.findViewWithTag<TextView>("consent_message").text.contains("전사문·웃음 구간만 서버"))
            capture(root, "live-auto")
        }
        click(scenario, "nav_settings")
        scenario.recreate()
        assertFalse(RecordingStore.state.busy)
        assertEquals(journal, RecordingJournal.read(context)?.toString())
    }

    @Test fun confirmationStartsOneLocalRecordingAfterExplicitConsent() {
        assertFalse(RecordingStore.state.busy)
        val journal = File(RecordingFiles.directory(context), "session-journal.json")
        val backup = journal.takeIf { it.isFile }?.readBytes()
        val existing = RecordingFiles.list(context).map { it.file.name }.toSet()
        val latest = AutomaticHighlights.latestSource(context)
        val prefs = context.getSharedPreferences("tikitaka-ui", 0)
        val seen = prefs.getLong("seenFinish", 0)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        try { launch { scenario ->
            click(scenario, "record_start")
            screen(scenario) { _, root -> root.findViewWithTag<CheckBox>("record_only").isChecked = true }
            click(scenario, "record_confirm")
            assertFalse("Opening consent must not start recording", RecordingStore.state.busy)
            screen(scenario) { _, root ->
                val accept = root.findViewWithTag<View>("consent_confirm")
                accept.performClick()
                accept.performClick() // A queued second tap must not create another session.
            }
            waitFor { RecordingStore.state.phase == RecordingPhase.RECORDING }
            screen(scenario) { _, root -> assertNotNull(root.findViewWithTag<View>("tiki_record")) }
            assertTrue(RecordingJournal.read(context)!!.getBoolean("recordOnly"))
            SystemClock.sleep(2200)
            scenario.onActivity { it.startService(Intent(it, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) }
            waitFor { !RecordingStore.state.busy }
            val created = RecordingFiles.list(context).filter { it.file.name !in existing }
            assertEquals(1, created.size)
            assertTrue(RecordingJournal.duration(created.single().file) > 1000)
            assertNull(EpisodeWork.status(context, created.single().file.name))
            assertEquals(latest, AutomaticHighlights.latestSource(context))
        } } finally {
            if (RecordingStore.state.busy) {
                context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                waitFor { !RecordingStore.state.busy }
            }
            RecordingFiles.list(context).filter { it.file.name !in existing }.forEach { RecordingFiles.delete(it) }
            if (backup == null) journal.delete() else journal.writeBytes(backup)
            prefs.edit().putLong("seenFinish", seen).commit()
        }
    }

    @Test fun consentReflowsAcrossTwelvePresetSizesInBothModes() = launch { scenario ->
        val presets = listOf(1700 to 1184, 840 to 1184, 1360 to 948, 672 to 948, 1274 to 855, 628 to 855,
            1640 to 810, 800 to 810, 1640 to 1400, 800 to 1400, 1256 to 800, 648 to 800)
        val report = JSONArray()
        val route = TikiActivity::class.java.getDeclaredField("route").apply { isAccessible = true }
        val mode = TikiActivity::class.java.getDeclaredField("recordOnly").apply { isAccessible = true }
        val render = TikiActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
        try {
            for (only in listOf(false, true)) for ((widthDp, heightDp) in presets) screen(scenario) { activity, root ->
                route.set(activity, "consent")
                mode.setBoolean(activity, only)
                val density = activity.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (heightDp * density).toInt()
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                layout(); render.invoke(activity); layout()
                val consent = root.findViewWithTag<View>("recording_consent")
                val errors = JSONArray()
                fun check(view: View) {
                    if (view.parent is ViewGroup) {
                        val parent = view.parent as ViewGroup
                        if (view.left < -1 || view.right > parent.width + 1) errors.put("horizontal: ${view.tag}")
                    }
                    if (view is TextView && view.text.isNotEmpty()) {
                        val text = view.layout
                        if (text == null || text.height > view.height - view.paddingTop - view.paddingBottom + 1)
                            errors.put("height: ${view.text}")
                        if (text != null) for (i in 0 until text.lineCount) {
                            if (text.getLineWidth(i) > view.width - view.paddingLeft - view.paddingRight + 1)
                                errors.put("width: ${view.text}")
                        }
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
                }
                check(consent)
                val mic = root.findViewWithTag<ViewGroup>("consent_mic")
                assertEquals(112f, mic.width / density, 1f)
                assertEquals(48f, mic.getChildAt(0).width / density, 1f)
                val confirm = root.findViewWithTag<View>("consent_confirm")
                val cancel = root.findViewWithTag<View>("consent_cancel")
                assertTrue(confirm.top >= cancel.bottom || confirm.left >= cancel.right)
                val gap = if(confirm.top >= cancel.bottom)confirm.top - cancel.bottom else confirm.left - cancel.right
                assertEquals("Figma action gap", 24f, gap / density, 1f)
                report.put(JSONObject().put("widthDp", widthDp).put("heightDp", heightDp).put("recordOnly", only)
                    .put("density", density).put("errors", errors))
                capture(root, "$widthDp-$heightDp-${if (only) "local" else "auto"}")
            }
        } finally { File(folder, "layouts.json").writeText(report.toString(2)) }
        assertEquals(report.toString(), 0, (0 until report.length()).sumOf { report.getJSONObject(it).getJSONArray("errors").length() })
    }
}
