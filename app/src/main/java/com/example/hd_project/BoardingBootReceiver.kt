package com.example.hd_project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore an existing opt-in after user-unlocked boot or package update, never record. */
class BoardingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BoardingService.start(context)
    }
}
