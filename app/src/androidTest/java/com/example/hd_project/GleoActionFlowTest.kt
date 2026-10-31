package com.example.hd_project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Executes the public intents, without pretending to test Gleo's natural-language service. */
@RunWith(AndroidJUnit4::class)
class GleoActionFlowTest {
    @Test fun publicActionsAnalyzeListAndPlayAndValidateArguments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val responses = LinkedBlockingQueue<JSONObject>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("packageName") == context.packageName) {
                    responses.add(JSONObject(intent.getStringExtra("response")!!))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(GleoContract.RESPONSE), ContextCompat.RECEIVER_EXPORTED)
        val source = File(RecordingFiles.directory(context), "test_${UUID.randomUUID()}_5000.m4a")
        fun call(action: String, extras: Intent.() -> Unit = {}): JSONObject {
            val start = SystemClock.elapsedRealtime()
            context.startActivity(Intent(action).setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras))
            val response = responses.poll(6500, TimeUnit.MILLISECONDS)
            assertNotNull("No callback for $action", response)
            assertEquals(action, response!!.getString("functionName"))
            assertTrue(SystemClock.elapsedRealtime() - start < 7000)
            return response
        }
        try {
            assertEquals("asking", call(GleoContract.PLAY).getString("status"))
            assertEquals("asking", call(GleoContract.PLAY) { putExtra("ACTION_PARAM", "{") }.getString("status"))
            assertEquals("fail", call(GleoContract.ANALYZE) { putExtra("recordingId", "../../outside.m4a") }.getString("status"))
            instrumentation.context.assets.open("1-73123-A-26.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
            val original = source.readBytes()
            val accepted = call(GleoContract.ANALYZE) { putExtra("recordingId", source.name) }
            assertEquals("success", accepted.getString("status"))
            assertTrue(accepted.getString("message").contains("accepted"))
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (HighlightService.runningSource != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertNull(HighlightService.runningSource)
            val batch = HighlightStore.batches(context).first { it.sourceId == source.name }
            assertFalse(batch.clips.isEmpty())
            assertArrayEquals(original, source.readBytes())
            val listed = call(GleoContract.SHOW)
            assertEquals("success", listed.getString("status"))
            assertTrue(org.json.JSONArray(listed.getString("data")).length() > 0)
            val replay = call(GleoContract.PLAY) { putExtra("ACTION_PARAM", "{\"highlightNumber\":1}") }
            assertEquals("success", replay.getString("status"))
            assertEquals("silent", replay.getString("mode"))
            assertEquals("success", call(GleoContract.ANALYZE) { putExtra("recordingId", source.name) }.getString("status"))
            assertEquals(1, HighlightStore.batches(context).count { it.sourceId == source.name })
        } finally {
            context.unregisterReceiver(receiver)
            source.delete()
            HighlightStore.batches(context).filter { it.sourceId == source.name }.flatMap { it.clips }
                .firstOrNull()?.file?.parentFile?.let { folder -> folder.listFiles()?.forEach { it.delete() }; folder.delete() }
        }
    }
}
