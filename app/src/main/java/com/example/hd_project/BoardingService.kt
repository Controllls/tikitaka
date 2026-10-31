package com.example.hd_project

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/** Opt-in passive seat listener. This service never opens or starts the microphone. */
class BoardingService : Service() {
    private var observing = false
    private var invitationShown = false
    private var status: Pair<SeatConnection, Boolean>? = null
    private val observer: (BoardingState) -> Unit = { refresh(it) }

    override fun onCreate() {
        super.onCreate()
        BoardingCoordinator.initialize(this)
        BoardingNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BoardingCoordinator.state.enabled || !BoardingNotifications.allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = BoardingNotifications.standby(this, BoardingCoordinator.state)
            if (Build.VERSION.SDK_INT >= 34) startForeground(BoardingNotifications.STANDBY_ID,
                notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(BoardingNotifications.STANDBY_ID, notification)
            BoardingCoordinator.setServiceRunning(true)
            if (!observing) {
                observing = true
                BoardingCoordinator.observe(observer)
            }
        } catch (error: Exception) {
            Log.w("BoardingService", "Standby foreground service rejected", error)
            BoardingCoordinator.startFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        // Only passive standby can be restarted by Android; recording remains NOT_STICKY.
        return START_STICKY
    }

    private fun refresh(state: BoardingState) {
        if (!state.enabled || !BoardingNotifications.allowed(this)) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val nextStatus = state.connection to state.recording
        if (status != nextStatus) {
            status = nextStatus
            manager.notify(BoardingNotifications.STANDBY_ID,
                BoardingNotifications.standby(this, state))
        }
        if (state.shouldNotify && !invitationShown) {
            manager.notify(BoardingNotifications.INVITATION_ID, BoardingNotifications.invitation(this))
            invitationShown = true
        } else if (!state.shouldNotify) {
            manager.cancel(BoardingNotifications.INVITATION_ID)
            invitationShown = false
        }
    }

    override fun onDestroy() {
        if (observing) BoardingCoordinator.remove(observer)
        getSystemService(NotificationManager::class.java).cancel(BoardingNotifications.INVITATION_ID)
        BoardingCoordinator.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            BoardingCoordinator.initialize(context)
            if (!BoardingCoordinator.state.enabled || !BoardingNotifications.allowed(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, BoardingService::class.java))
            } catch (error: Exception) {
                Log.w("BoardingService", "Standby start unavailable", error)
                BoardingCoordinator.startFailed()
            }
        }
    }
}
