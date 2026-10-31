package com.example.hd_project

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Private receiver reached only by our immutable notification PendingIntents. */
class BoardingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BoardingCoordinator.initialize(context)
        when (intent.action) {
            SKIP -> {
                BoardingCoordinator.markHandled()
                context.getSystemService(android.app.NotificationManager::class.java)
                    .cancel(BoardingNotifications.INVITATION_ID)
            }
            DISABLE -> {
                BoardingCoordinator.setEnabled(false)
                context.stopService(Intent(context, BoardingService::class.java))
                context.getSystemService(android.app.NotificationManager::class.java)
                    .cancel(BoardingNotifications.INVITATION_ID)
            }
        }
    }

    companion object {
        const val SKIP = "com.example.hd_project.BOARDING_SKIP"
        const val DISABLE = "com.example.hd_project.BOARDING_DISABLE"
    }
}
