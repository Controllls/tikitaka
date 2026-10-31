package com.example.hd_project

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var button: android.widget.Button
    private lateinit var monitor: VehicleSeatMonitor
    private var resumed = false
    private var pendingBoarding = false
    private var pendingStart = false
    private var consent: AlertDialog? = null
    private val observer: (RecordingState) -> Unit = { render(it) }
    private val preferences by lazy { getSharedPreferences("recording_ui", MODE_PRIVATE) }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationThenStart()
        else {
            pendingStart = false
            showMessage(R.string.permission_denied)
        }
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.notification_limited, Toast.LENGTH_LONG).show()
        pendingStart = true
        startIfVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pendingStart = savedInstanceState?.getBoolean("pending_start") ?: false
        button = findViewById(R.id.record_button)
        button.setOnClickListener {
            when (RecordingStore.state.phase) {
                RecordingPhase.RECORDING -> startService(Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP))
                RecordingPhase.IDLE, RecordingPhase.ERROR -> showConsent()
                else -> Unit
            }
        }
        findViewById<android.view.View>(R.id.history_button).setOnClickListener { openHistory() }
        monitor = VehicleSeatMonitor(this,
            onOccupancy = { occupied ->
                findViewById<TextView>(R.id.boarding_status).setText(when (occupied) {
                    true -> R.string.boarding_detected
                    false -> R.string.boarding_wait
                    null -> R.string.boarding_unavailable
                })
                when (occupied) {
                    false -> {
                        pendingBoarding = false
                        RecordingStore.boardingGate.onOccupancy(false, RecordingStore.state.busy)
                    }
                    true -> {
                        pendingBoarding = true
                        askForBoardingIfVisible()
                    }
                    null -> pendingBoarding = false
                }
            },
            onUnavailable = {
                pendingBoarding = false
                findViewById<TextView>(R.id.boarding_status).setText(R.string.boarding_unavailable)
            }
        )
    }

    override fun onStart() {
        super.onStart()
        RecordingStore.observe(observer)
        findViewById<TextView>(R.id.boarding_status).setText(R.string.boarding_connecting)
        monitor.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startIfVisible()
        askForBoardingIfVisible()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        monitor.stop()
        pendingBoarding = false
        consent?.dismiss()
        consent = null
        RecordingStore.remove(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_start", pendingStart)
        super.onSaveInstanceState(outState)
    }

    private fun askForBoardingIfVisible() {
        if (!resumed || !pendingBoarding) return
        pendingBoarding = false
        if (RecordingStore.boardingGate.onOccupancy(true, RecordingStore.state.busy)) showConsent()
    }

    private fun showConsent() {
        if (!resumed || consent != null || RecordingStore.state.busy) return
        consent = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setPositiveButton(R.string.consent_start) { _, _ ->
                RecordingStore.boardingGate.markHandled()
                checkMicrophonePermission()
            }
            .setNegativeButton(R.string.consent_skip) { _, _ ->
                RecordingStore.boardingGate.markHandled()
            }
            .setOnCancelListener { RecordingStore.boardingGate.markHandled() }
            .create().also { dialog ->
                dialog.setOnDismissListener { consent = null }
                dialog.setCanceledOnTouchOutside(false)
                dialog.show()
                listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach {
                    dialog.getButton(it).minHeight = (56 * resources.displayMetrics.density).toInt()
                }
            }
    }

    private fun checkMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            requestNotificationThenStart()
            return
        }
        if (preferences.getBoolean("microphone_asked", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.permission_settings)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName)))
                }
                .setNegativeButton(R.string.cancel, null).show()
        } else {
            preferences.edit().putBoolean("microphone_asked", true).apply()
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingStart = true
            startIfVisible()
        }
    }

    private fun startIfVisible() {
        // Microphone foreground services must start while this screen is visible.
        if (!resumed || !pendingStart || RecordingStore.state.busy) return
        pendingStart = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            showMessage(R.string.permission_denied)
            return
        }
        RecordingStore.publish(RecordingState(RecordingPhase.STARTING))
        try {
            ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START))
        } catch (_: Exception) {
            RecordingStore.publish(RecordingState(RecordingPhase.ERROR,
                message = R.string.recording_start_failed))
        }
    }

    private fun showMessage(message: Int) {
        findViewById<TextView>(R.id.recording_hint).setText(message)
    }

    private fun openHistory() {
        if (RecordingStore.state.busy)
            Toast.makeText(this, R.string.archive_during_recording, Toast.LENGTH_SHORT).show()
        else startActivity(Intent(this, RecordingsActivity::class.java))
    }

    private fun render(state: RecordingState) {
        val title = when (state.phase) {
            RecordingPhase.IDLE -> R.string.idle_title
            RecordingPhase.STARTING -> R.string.starting_title
            RecordingPhase.RECORDING -> R.string.recording_title
            RecordingPhase.STOPPING -> R.string.saving_title
            RecordingPhase.ERROR -> R.string.error_title
        }
        val status = when (state.phase) {
            RecordingPhase.IDLE -> R.string.ready_status
            RecordingPhase.STARTING -> R.string.starting_status
            RecordingPhase.RECORDING -> R.string.recording_status
            RecordingPhase.STOPPING -> R.string.saving_status
            RecordingPhase.ERROR -> R.string.error_status
        }
        findViewById<TextView>(R.id.recording_title).setText(title)
        findViewById<TextView>(R.id.recording_status).apply {
            setText(status)
            setTextColor(ContextCompat.getColor(this@MainActivity,
                if (state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.ERROR)
                    R.color.recording_accent else R.color.sample_primary))
        }
        findViewById<TextView>(R.id.recording_time).text = formatDuration(state.durationMs())
        button.setText(when (state.phase) {
            RecordingPhase.RECORDING -> R.string.stop_recording
            RecordingPhase.STARTING -> R.string.starting_status
            RecordingPhase.STOPPING -> R.string.saving_status
            else -> R.string.start_recording
        })
        findViewById<TextView>(R.id.recording_hint).setText(
            state.message ?: if (state.phase == RecordingPhase.RECORDING) R.string.recording_hint
            else R.string.idle_hint
        )
        button.isEnabled = state.phase != RecordingPhase.STARTING &&
            state.phase != RecordingPhase.STOPPING
    }
}
