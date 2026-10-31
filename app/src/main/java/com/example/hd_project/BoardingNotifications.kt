package com.example.hd_project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object BoardingNotifications {
    const val STANDBY_CHANNEL = "boarding_standby"
    const val INVITATION_CHANNEL = "boarding_invitation"
    const val STANDBY_ID = 2101
    const val INVITATION_ID = 2102

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STANDBY_CHANNEL,
            context.getString(R.string.standby_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(INVITATION_CHANNEL,
            context.getString(R.string.boarding_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    fun allowed(context: Context): Boolean {
        createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            listOf(STANDBY_CHANNEL, INVITATION_CHANNEL).all {
                manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
            }
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 2100,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(context: Context, name: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, BoardingActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun standby(context: Context, state: BoardingState): Notification {
        val text = when {
            state.recording -> R.string.standby_recording
            state.connection == SeatConnection.UNAVAILABLE -> R.string.standby_connection_failed
            state.connection == SeatConnection.CONNECTING -> R.string.standby_connecting
            else -> R.string.standby_notification_text
        }
        return NotificationCompat.Builder(context, STANDBY_CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(context.getString(if (state.recording) R.string.standby_recording_title
                else R.string.standby_notification_title))
            .setContentText(context.getString(text))
            .setContentIntent(open(context))
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, context.getString(R.string.standby_disable),
                action(context, BoardingActionReceiver.DISABLE, 2103))
            .build()
    }

    fun invitation(context: Context): Notification = NotificationCompat.Builder(context, INVITATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_car)
        .setContentTitle(context.getString(R.string.boarding_notification_title))
        .setContentText(context.getString(R.string.boarding_notification_text))
        .setContentIntent(open(context))
        .setDeleteIntent(action(context, BoardingActionReceiver.SKIP, 2104))
        .setAutoCancel(true).setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .addAction(0, context.getString(R.string.consent_skip),
            action(context, BoardingActionReceiver.SKIP, 2104))
        .build()
}
