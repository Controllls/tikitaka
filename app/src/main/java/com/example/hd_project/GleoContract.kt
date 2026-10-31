package com.example.hd_project

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class GleoRequest(val action: String, val sourceId: String?, val number: Int?)

object GleoContract {
    const val ANALYZE = "FIND_RECORDING_HIGHLIGHTS"
    const val SHOW = "SHOW_RECORDING_HIGHLIGHTS"
    const val PLAY = "PLAY_RECORDING_HIGHLIGHT"
    const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    val actions = setOf(ANALYZE, SHOW, PLAY)

    // Gleo 4.2 prefixes actions with the app package; the public examples use bare names.
    fun actionName(action: String?, packageName: String): String? =
        action?.removePrefix("$packageName.")?.takeIf { it in actions }

    @Suppress("DEPRECATION") // Inspect the actual incoming type before accepting a parameter.
    fun parse(intent: Intent, packageName: String): GleoRequest {
        val action = requireNotNull(actionName(intent.action, packageName))
        val extras = intent.extras
        val packed = extras?.get("ACTION_PARAM")
        require(packed == null || packed is String)
        val json = if (packed is String) {
            require(packed.length <= 4096)
            // Official prose describes individual extras; its code sample uses ACTION_PARAM.
            val value = JSONTokener(packed).nextValue()
            if (value is String) JSONObject(value) else value as? JSONObject
                ?: throw IllegalArgumentException("Expected an object")
        } else JSONObject()
        fun value(key: String): Any? {
            val individual = extras?.get(key)
            val nested = if (json.has(key)) json.get(key) else null
            require(individual == null || nested == null || individual.toString() == nested.toString())
            return individual ?: nested
        }
        val source = value("recordingId")
        require(source == null || source is String && source.length in 1..160)
        val numberValue = value("highlightNumber")
        val number = if (numberValue == null) null else {
            require(numberValue is Number)
            val numeric = numberValue.toDouble()
            require(numeric.isFinite() && numeric >= 1 && numeric <= 3 && numeric % 1.0 == 0.0)
            numeric.toInt()
        }
        return GleoRequest(action, source as? String, number)
    }

    fun respond(context: Context, function: String, status: String, message: String,
        data: JSONArray? = null, silent: Boolean = false) {
        val response = JSONObject().put("functionName", function).put("status", status)
            .put("message", message).put("mode", if (silent) "silent" else "voice")
        data?.let { response.put("data", it.toString()) }
        context.sendBroadcast(Intent(RESPONSE).putExtra("packageName", context.packageName)
            .putExtra("response", response.toString()))
        // Do not log transcripts, paths or recordings.
        Log.i("GleoBridge", "$function -> $status")
    }
}
