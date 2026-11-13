package com.example.hd_project

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.hd_project.tiki.RecordingJournal
import com.example.hd_project.tiki.EpisodeWork
import com.example.hd_project.pleos.RecordingPowerSignals
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** A microphone foreground service, started only after explicit in-app consent. */
class RecordingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var recorder: MediaRecorder? = null
    private var pendingFile: File? = null
    private var samples: ScheduledFuture<*>? = null
    private var startedAt = 0L
    private var recorderStarted = false
    private var acceptedStart = false
    @Volatile private var finished = false
    @Volatile private var stopping = false
    private var ticks = 0
    private var audioCallback: AudioManager.AudioRecordingCallback? = null
    private var recordOnly = false
    @Volatile private var paused = false
    @Volatile private var accumulatedMs = 0L
    @Volatile private var segmentStart = 0L
    private var maxPeak = 0
    private var sessionFile: File? = null
    private val segments = mutableListOf<File>()
    private var powerSignals: AutoCloseable? = null
    private var stopTrigger = "manual"

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel),
                NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE || intent?.action == ACTION_RESUME) {
            if (acceptedStart && !stopping) worker.execute {
                if (intent.action == ACTION_PAUSE) pauseRecording()
                else if (paused) { paused = false; beginRecording() }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            if (acceptedStart) requestStop() else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (acceptedStart) return START_NOT_STICKY
        acceptedStart = true
        recordOnly = intent.getBooleanExtra(EXTRA_RECORD_ONLY, false)
        RecordingStore.publish(RecordingState(RecordingPhase.STARTING))
        try {
            val notification = notification(starting = true)
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, notification)
            val sessionStartedAtMs = System.currentTimeMillis()
            worker.execute { beginRecording() }
            powerSignals = RecordingPowerSignals.watch(sessionStartedAtMs) {
                if (acceptedStart && !stopping && !finished) {
                    stopTrigger = "ignition_off"
                    Log.i(TAG, "Ignition Off: finalize and validate recording")
                    requestStop()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Foreground microphone start failed", error)
            finished = true
            RecordingStore.publish(RecordingState(RecordingPhase.ERROR,
                message = R.string.recording_start_failed))
            stopSelf()
        }
        // Killing/rebooting never starts an unconsented recording.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun beginRecording() {
        if (finished || stopping) return
        try {
            val directory = RecordingFiles.directory(this)
            if (directory.usableSpace < MIN_FREE_BYTES) {
                finishRecording(R.string.recording_storage_full)
                return
            }
            val output = RecordingFiles.pending(this)
            pendingFile = output
            if (sessionFile == null) sessionFile = output
            RecordingJournal.active(this, output, segments, recordOnly, accumulatedMs, "recording")
            val audio = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = audio
            audio.setAudioSource(MediaRecorder.AudioSource.MIC)
            audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            audio.setAudioChannels(1)
            audio.setAudioSamplingRate(44100)
            audio.setAudioEncodingBitRate(96000)
            audio.setOutputFile(output.absolutePath)
            audio.setOnErrorListener { _, _, _ ->
                main.post { requestStop(R.string.recording_failed) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (configs.any { it.isClientSilenced }) {
                            // Recheck after transient routing changes.
                            main.postDelayed({
                                if (!stopping) worker.execute {
                                    if (!finished && recorder?.activeRecordingConfiguration?.isClientSilenced == true)
                                        finishRecording(R.string.recording_interrupted)
                                }
                            }, 800)
                        }
                    }
                }
                audioCallback = callback
                audio.registerAudioRecordingCallback(worker, callback)
            }
            audio.prepare()
            audio.start()
            recorderStarted = true
            segmentStart = SystemClock.elapsedRealtime()
            startedAt = segmentStart - accumulatedMs
            if (!stopping) {
                RecordingStore.publish(RecordingState(RecordingPhase.RECORDING, startedAt))
                main.post {
                    if (!stopping) getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(starting = false))
                }
            }
            samples?.cancel(false)
            samples = worker.scheduleWithFixedDelay({ sample() }, 0, 100, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "Recorder initialization failed", error)
            finishRecording(R.string.recording_failed)
        }
    }

    private fun sample() {
        if (stopping || finished || paused || !recorderStarted) return
        try {
            val peak = recorder?.maxAmplitude ?: 0
            maxPeak = maxOf(maxPeak, peak)
            // Perceptual response without generating a fake sound level.
            val level = sqrt(peak.coerceAtLeast(0) / 32767f).coerceIn(0f, 1f)
            RecordingStore.publish(RecordingState(RecordingPhase.RECORDING, startedAt,
                SystemClock.elapsedRealtime() - startedAt, level))
            if (++ticks % 100 == 0 && pendingFile?.parentFile?.usableSpace
                ?.let { it < MIN_FREE_BYTES } == true) finishRecording(R.string.recording_storage_full)
        } catch (error: Exception) {
            Log.w(TAG, "Microphone polling failed", error)
            finishRecording(R.string.recording_failed)
        }
    }

    private fun releaseSegment() {
        if (!recorderStarted) return
        val length = SystemClock.elapsedRealtime() - segmentStart
        try {
            recorder?.stop()
            val file = checkNotNull(pendingFile)
            if (RecordingJournal.duration(file) >= 100) segments.add(file)
            accumulatedMs += length
        } finally {
            if (Build.VERSION.SDK_INT >= 29) audioCallback?.let { callback ->
                runCatching { recorder?.unregisterAudioRecordingCallback(callback) }
            }
            runCatching { recorder?.release() }
            recorder = null; recorderStarted = false; audioCallback = null
        }
    }

    private fun pauseRecording() {
        if (paused || finished || !recorderStarted) return
        try {
            samples?.cancel(false)
            releaseSegment()
            paused = true
            RecordingJournal.active(this, checkNotNull(pendingFile), segments, recordOnly, accumulatedMs, "paused")
            RecordingStore.publish(RecordingState(RecordingPhase.PAUSED, elapsedMs = accumulatedMs))
            main.post { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                notification(starting = false)) }
        } catch (error: Exception) {
            Log.w(TAG, "Could not close paused segment", error)
            finishRecording(R.string.recording_save_failed)
        }
    }

    private fun requestStop(reason: Int? = null) {
        if (stopping) return
        stopping = true
        RecordingStore.publish(RecordingStore.state.copy(phase = RecordingPhase.STOPPING,
            elapsedMs = RecordingStore.state.durationMs(), level = 0f))
        worker.execute { finishRecording(reason) }
    }

    /** All encoder/file operations are serialized on one worker. */
    private fun finishRecording(reason: Int? = null, stopService: Boolean = true) {
        if (finished) return
        finished = true
        stopping = true
        powerSignals?.close()
        samples?.cancel(false)
        var message = reason
        var saved = false
        var savedFile: File? = null
        var duration = accumulatedMs
        try {
            releaseSegment()
            duration = accumulatedMs
            if (duration >= 800 && segments.isNotEmpty()) {
                RecordingJournal.active(this, checkNotNull(pendingFile), segments, recordOnly, duration, "saving")
                val joined = File(checkNotNull(sessionFile).path + ".joined")
                duration = RecordingJournal.join(segments, joined)
                RecordingJournal.save(this,checkNotNull(RecordingJournal.read(this))
                    .put("finalCandidate",joined.nameWithoutExtension+"_"+duration+".m4a")
                    .put("normalExpected",reason==null && maxPeak>80)
                    .put("stopTrigger",stopTrigger))
                savedFile = RecordingFiles.finish(joined, duration)
                saved = true
            } else if (message == null) message = R.string.recording_too_short
        } catch (error: Exception) {
            Log.w(TAG, "Could not finalize audio", error)
            if (message == null) message = if (duration < 800) R.string.recording_too_short
                else R.string.recording_save_failed
        } finally {
            if (Build.VERSION.SDK_INT >= 29) audioCallback?.let { callback ->
                runCatching { recorder?.unregisterAudioRecordingCallback(callback) }
            }
            runCatching { recorder?.release() }
            recorder = null
            recorderStarted = false
            // Keep encoder segments on any failure so explicit recovery can retry later.
            if (saved) segments.forEach { it.delete() }
        }
        val normal = saved && reason == null && maxPeak > 80
        RecordingJournal.finished(this, savedFile, pendingFile, recordOnly,
            if (!saved) "failed" else if(reason==null && maxPeak<=80)"silent" else if (!normal) "recovered" else "saved",
            if(reason==R.string.recording_interrupted) "interrupted" else if(reason!=null) "error" else stopTrigger)
        savedFile?.let { file ->
            if (normal && !recordOnly) runCatching {
                kotlinx.coroutines.runBlocking { EpisodeWork.enqueue(applicationContext, file) }
            }.onFailure { Log.w(TAG, "Automatic episode scheduling failed", it) }
        }
        val result = RecordingState(
            phase = if (message == null) RecordingPhase.IDLE else RecordingPhase.ERROR,
            elapsedMs = if (saved) duration else 0,
            message = message ?: R.string.recording_saved
        )
        main.post {
            RecordingStore.publish(result)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopService) stopSelf()
        }
    }

    private fun notification(starting: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (starting) R.string.notification_starting
                else R.string.notification_recording))
            .setContentText(if (paused) "녹음을 잠시 멈췄어요. 앱에서 다시 시작할 수 있어요." else getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_mic, getString(R.string.stop_recording), stop)
            .apply {
                if (!starting && !paused) {
                    setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt))
                    setUsesChronometer(true)
                }
            }.build()
    }

    override fun onDestroy() {
        powerSignals?.close()
        main.removeCallbacksAndMessages(null)
        samples?.cancel(false)
        if (acceptedStart) worker.execute { finishRecording(R.string.recording_interrupted, stopService = false) }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.hd_project.START_RECORDING"
        const val ACTION_STOP = "com.example.hd_project.STOP_RECORDING"
        const val ACTION_PAUSE = "com.example.hd_project.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.hd_project.RESUME_RECORDING"
        const val EXTRA_RECORD_ONLY = "record_only"
        private const val CHANNEL = "conversation_recording"
        private const val NOTIFICATION_ID = 10
        private const val MIN_FREE_BYTES = 5L * 1024 * 1024
        private const val TAG = "RecordingService"
    }
}
