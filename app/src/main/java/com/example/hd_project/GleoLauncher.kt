package com.example.hd_project

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Compatibility with the Gleo receiver installed on Pleos Connect v2.1.0.
 * This requests listening; sending a broadcast is not an acknowledgement from Gleo.
 */
object GleoLauncher {
    private const val PACKAGE = "ai.umos.ambientai"
    private const val VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

    fun isAvailable(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun requestListening(context: Context): Boolean = try {
        context.sendBroadcast(Intent(VOICE_ASSIST)
            .setPackage(PACKAGE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        Log.i("GleoLauncher", "Requested Gleo listening after microphone release")
        true
    } catch (error: SecurityException) {
        Log.w("GleoLauncher", "Gleo listening request was denied", error)
        false
    }
}
