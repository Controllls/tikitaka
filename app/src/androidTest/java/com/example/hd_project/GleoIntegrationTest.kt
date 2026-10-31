package com.example.hd_project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GleoIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagedSchemaResolvesEveryActionInInstalledVariant() {
        val schema = JSONObject(context.assets.open("schema.json").bufferedReader().use { it.readText() })
        assertEquals(context.packageName, schema.getString("packageName"))
        val calls = schema.getJSONArray("actionCalls")
        assertEquals(3, calls.length())
        for (i in 0 until calls.length()) {
            val function = calls.getJSONObject(i).getJSONObject("function")
            val action = function.getString("name")
            assertTrue(action in GleoContract.actions)
            assertEquals("activity", function.getString("component"))
            for (intentAction in listOf(action, "${context.packageName}.$action")) {
                val resolved = context.packageManager.resolveActivity(Intent(intentAction).setPackage(context.packageName),
                    PackageManager.MATCH_DEFAULT_ONLY)
                assertNotNull(intentAction, resolved)
                assertTrue(resolved!!.activityInfo.exported)
                assertEquals(action, GleoContract.parse(Intent(intentAction), context.packageName).action)
            }
        }
    }
    @Test fun acceptsBothDocumentedParameterFormatsAndMissingOptionalSource() {
        assertNull(GleoContract.parse(Intent(GleoContract.ANALYZE), context.packageName).sourceId)
        assertEquals(2, GleoContract.parse(Intent(GleoContract.PLAY).putExtra("highlightNumber", 2), context.packageName).number)
        assertEquals(3, GleoContract.parse(Intent(GleoContract.PLAY)
            .putExtra("ACTION_PARAM", "{\"highlightNumber\":3}"), context.packageName).number)
        assertEquals(1, GleoContract.parse(Intent(GleoContract.PLAY)
            .putExtra("ACTION_PARAM", JSONObject.quote("{\"highlightNumber\":1}")), context.packageName).number)
    }
    @Test fun acceptsObservedGleoActionsAndRejectsForeignPackagePrefixes() {
        val request = GleoContract.parse(Intent("${context.packageName}.${GleoContract.ANALYZE}")
            .putExtra("ACTION_PARAM", "{}"), context.packageName)
        assertEquals(GleoContract.ANALYZE, request.action)
        assertNull(request.sourceId)
        assertEquals(2, GleoContract.parse(Intent("${context.packageName}.${GleoContract.PLAY}")
            .putExtra("ACTION_PARAM", "{\"highlightNumber\":2}"), context.packageName).number)
        assertNull(GleoContract.actionName(null, context.packageName))
        for (action in listOf("other.package.${GleoContract.ANALYZE}",
            "${context.packageName}.${context.packageName}.${GleoContract.ANALYZE}",
            "${context.packageName}.UNKNOWN_ACTION")) {
            assertTrue(action, runCatching { GleoContract.parse(Intent(action), context.packageName) }.isFailure)
        }
    }
    @Test fun rejectsMalformedFractionalConflictingOrOversizedParameters() {
        val inputs = listOf(Intent(GleoContract.PLAY).putExtra("highlightNumber", 1.5),
            Intent(GleoContract.PLAY).putExtra("highlightNumber", "one"),
            Intent(GleoContract.PLAY).putExtra("highlightNumber", 0),
            Intent(GleoContract.PLAY).putExtra("ACTION_PARAM", "[]"),
            Intent(GleoContract.PLAY).putExtra("ACTION_PARAM", "{").putExtra("highlightNumber", 1),
            Intent(GleoContract.ANALYZE).putExtra("recordingId", "x".repeat(161)),
            Intent(GleoContract.PLAY).putExtra("highlightNumber", 2).putExtra("ACTION_PARAM", "{\"highlightNumber\":1}"))
        inputs.forEach { intent -> assertTrue(runCatching { GleoContract.parse(intent, context.packageName) }.isFailure) }
    }
    @Test fun callbackUsesRequiredBroadcastEnvelopeAndJsonArrayString() {
        val latch = CountDownLatch(1)
        var received: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received = intent; latch.countDown() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(GleoContract.RESPONSE), ContextCompat.RECEIVER_EXPORTED)
        try {
            GleoContract.respond(context, GleoContract.SHOW, "success", "The highlight list is displayed.",
                JSONArray().put(JSONObject().put("highlightNumber", 1)))
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(context.packageName, received!!.getStringExtra("packageName"))
            val response = JSONObject(received!!.getStringExtra("response")!!)
            assertEquals("success", response.getString("status"))
            assertEquals(GleoContract.SHOW, response.getString("functionName"))
            assertEquals(1, JSONArray(response.getString("data")).getJSONObject(0).getInt("highlightNumber"))
        } finally { context.unregisterReceiver(receiver) }
    }
}
