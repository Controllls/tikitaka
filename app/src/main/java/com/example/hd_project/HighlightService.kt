package com.example.hd_project

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HighlightService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { cancelled.set(true); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        val sourceId = intent?.getStringExtra("sourceId")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.highlight_channel),
            NotificationManager.IMPORTANCE_LOW))
        try { startForeground(NOTIFICATION, notification(0)) }
        catch (e: Exception) {
            Log.w("Highlights", "Foreground analysis unavailable", e)
            finishStatus("error", R.string.highlight_start_failed)
            stopSelf(); return START_NOT_STICKY
        }
        executor.execute {
            try {
                check(!RecordingStore.state.busy) { "Recording in progress" }
                val source = RecordingFiles.list(this).firstOrNull { it.file.name == sourceId }
                    ?: error("Recording not found")
                HighlightStore.cleanIncomplete(this)
                val ranges = LaughterAnalyzer(this).analyze(source.file, cancelled::get, { percent ->
                    preferences(this).edit { putInt("progress", percent) }
                    if (percent % 5 == 0) manager.notify(NOTIFICATION, notification(percent))
                })
                HighlightStore.create(this, source.file, ranges, cancelled::get)
                finishStatus("done", if (ranges.isEmpty()) R.string.highlight_none_found else R.string.highlight_done)
            } catch (e: Exception) {
                Log.w("Highlights", "Analysis did not finish", e)
                finishStatus("error", if (cancelled.get()) R.string.highlight_cancelled else R.string.highlight_failed)
            } catch (e: LinkageError) {
                Log.w("Highlights", "Audio model runtime unavailable", e)
                finishStatus("error", R.string.highlight_failed)
            } finally {
                main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private fun finishStatus(state: String, message: Int) {
        preferences(this).edit { putString("state", state); putString("message", getString(message)) }
    }

    private fun notification(percent: Int): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, HighlightsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HighlightService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_audio)
            .setContentTitle(getString(R.string.highlight_analyzing)).setContentText("$percent%")
            .setContentIntent(open).setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), stop).build()
    }

    override fun onDestroy() {
        cancelled.set(true)
        runningSource = null
        executor.shutdown()
        super.onDestroy()
    }

    // Android 15+ dataSync foreground-service time budget.
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "highlight_analysis"
        private const val NOTIFICATION = 4102
        private const val CANCEL = "com.example.hd_project.CANCEL_HIGHLIGHTS"
        @Volatile var runningSource: String? = null
            private set
        fun preferences(context: Context) = context.getSharedPreferences("highlights", MODE_PRIVATE)

        /** Called only from a resumed activity after an explicit button/Gleo action. */
        fun enqueue(context: Context, source: SavedRecording): Boolean {
            if (runningSource != null || RecordingStore.state.busy) return false
            runningSource = source.file.name
            preferences(context).edit {
                putString("state", "running"); putString("sourceId", source.file.name)
                putInt("progress", 0); putString("message", "")
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, HighlightService::class.java)
                    .putExtra("sourceId", source.file.name))
                true
            } catch (_: Exception) {
                runningSource = null
                preferences(context).edit {
                    putString("state", "error"); putString("message", context.getString(R.string.highlight_start_failed))
                }
                false
            }
        }
        fun cancel(context: Context) {
            if (runningSource != null) context.startService(Intent(context, HighlightService::class.java).setAction(CANCEL))
        }
    }
}
