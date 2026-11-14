package com.example.hd_project.tiki

import android.content.Context

/** NaviHelper has a normal platform permission. This is the user's optional, revocable in-app location choice. */
object JourneyPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("tikitaka-location", Context.MODE_PRIVATE)
    fun decided(context: Context) = prefs(context).contains("enabled")
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun choose(context: Context, enabled: Boolean) { check(prefs(context).edit().putBoolean("enabled", enabled).commit()) }
}
