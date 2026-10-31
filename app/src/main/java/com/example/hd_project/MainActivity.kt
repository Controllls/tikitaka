package com.example.hd_project

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    private lateinit var button: RecordingButtonView
    private var resumed = false
    private var pendingStart = false
    private var pendingStandby = false
    private var updatingStandby = false
    private var invitationVisible = false
    private var invitationPulse: ValueAnimator? = null
    private var uiMessage: Int? = null
    private var lastRenderedPhase: RecordingPhase? = null
    private var pendingGleo = false
    private var savingForGleo = false
    private var gleoLaunchQueued = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launchGleo = Runnable {
        gleoLaunchQueued = false
        if (resumed && pendingGleo && !RecordingStore.state.busy) {
            pendingGleo = false
            savingForGleo = false
            showMessage(if (GleoLauncher.requestListening(this)) R.string.gleo_requested
                else R.string.gleo_request_failed)
        }
    }
    private val observer: (RecordingState) -> Unit = {
        continueGleoRequest(it)
        render(it)
    }
    private val boardingObserver: (BoardingState) -> Unit = { renderBoarding(it) }
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
    private val standbyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingStandby = granted
        if (!granted) Toast.makeText(this, R.string.standby_permission_needed, Toast.LENGTH_LONG).show()
        enableStandbyIfVisible()
        renderBoarding(BoardingCoordinator.state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BoardingCoordinator.initialize(this)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        pendingStart = savedInstanceState?.getBoolean("pending_start") ?: false
        pendingStandby = savedInstanceState?.getBoolean("pending_standby") ?: false
        uiMessage = savedInstanceState?.getInt("ui_message")?.takeIf { it != 0 }
        button = findViewById(R.id.record_button)
        button.setOnClickListener {
            if (pendingGleo) return@setOnClickListener
            when (RecordingStore.state.phase) {
                RecordingPhase.RECORDING -> startService(Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP))
                RecordingPhase.IDLE, RecordingPhase.ERROR -> startFromButton()
                else -> Unit
            }
        }
        findViewById<View>(R.id.use_gleo_button).setOnClickListener { requestGleo() }
        findViewById<View>(R.id.skip_recording).setOnClickListener {
            BoardingCoordinator.markHandled()
            showMessage(R.string.inline_skipped)
        }
        findViewById<android.view.View>(R.id.history_button).setOnClickListener { openHistory() }
        findViewById<android.view.View>(R.id.highlights_button).setOnClickListener {
            startActivity(Intent(this, HighlightsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.recent_card).setOnClickListener { openHistory() }
        findViewById<android.view.View>(R.id.home_button).setOnClickListener {
            findViewById<android.widget.ScrollView>(R.id.dashboard_scroll).smoothScrollTo(0, 0)
        }
        findViewById<SwitchMaterial>(R.id.standby_switch).setOnCheckedChangeListener { _, checked ->
            if (!updatingStandby) {
                if (checked) requestStandby()
                else {
                    pendingStandby = false
                    BoardingCoordinator.setEnabled(false)
                    stopService(Intent(this, BoardingService::class.java))
                }
            }
        }
        findViewById<View>(R.id.standby_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onStart() {
        super.onStart()
        RecordingStore.observe(observer)
        BoardingCoordinator.observe(boardingObserver)
        refreshDashboard()
        BoardingCoordinator.setScreenVisible(true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enableStandbyIfVisible()
        if (BoardingCoordinator.state.enabled) {
            if (BoardingNotifications.allowed(this)) BoardingService.start(this)
            else stopService(Intent(this, BoardingService::class.java))
        }
        renderBoarding(BoardingCoordinator.state)
        startIfVisible()
        if (BoardingCoordinator.state.awaitingResponse) pulseInvitation()
    }

    override fun onPause() {
        resumed = false
        // A request never opens the assistant later after leaving/recreating this screen.
        mainHandler.removeCallbacks(launchGleo)
        pendingGleo = false
        savingForGleo = false
        gleoLaunchQueued = false
        resetInvitationVisual()
        super.onPause()
    }

    override fun onStop() {
        BoardingCoordinator.remove(boardingObserver)
        BoardingCoordinator.setScreenVisible(false)
        resetInvitationVisual()
        RecordingStore.remove(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_start", pendingStart)
        outState.putBoolean("pending_standby", pendingStandby)
        outState.putInt("ui_message", uiMessage ?: 0)
        super.onSaveInstanceState(outState)
    }

    private fun requestStandby() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (preferences.getBoolean("standby_notification_asked", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                Toast.makeText(this, R.string.standby_permission_needed, Toast.LENGTH_LONG).show()
            } else {
                preferences.edit().putBoolean("standby_notification_asked", true).apply()
                standbyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (BoardingNotifications.allowed(this)) {
            pendingStandby = true
            enableStandbyIfVisible()
        } else Toast.makeText(this, R.string.standby_permission_needed, Toast.LENGTH_LONG).show()
        renderBoarding(BoardingCoordinator.state)
    }

    private fun enableStandbyIfVisible() {
        if (!pendingStandby || !resumed) return
        pendingStandby = false
        if (BoardingNotifications.allowed(this)) {
            BoardingCoordinator.setEnabled(true)
            BoardingService.start(this)
        }
    }

    private fun renderBoarding(state: BoardingState) {
        findViewById<TextView>(R.id.boarding_status).setText(when {
            state.connection == SeatConnection.CONNECTING -> R.string.boarding_connecting
            state.connection != SeatConnection.READY -> R.string.boarding_unavailable
            state.occupied == true -> R.string.boarding_detected
            else -> R.string.boarding_wait
        })
        updatingStandby = true
        findViewById<SwitchMaterial>(R.id.standby_switch).isChecked = state.enabled
        updatingStandby = false
        val allowed = BoardingNotifications.allowed(this)
        findViewById<TextView>(R.id.standby_description).setText(when {
            !allowed -> R.string.standby_permission_needed
            !state.enabled -> R.string.standby_off_description
            state.startFailed -> R.string.standby_start_failed
            !state.serviceRunning -> R.string.standby_starting
            state.recording -> R.string.standby_recording
            state.connection == SeatConnection.UNAVAILABLE -> R.string.standby_connection_failed
            state.connection == SeatConnection.CONNECTING -> R.string.standby_connecting
            else -> R.string.standby_on_description
        })
        findViewById<View>(R.id.standby_settings).visibility = if (allowed) View.GONE else View.VISIBLE
        if (state.occupied == false && uiMessage == R.string.inline_skipped) uiMessage = null
        val fresh = state.awaitingResponse && !invitationVisible
        invitationVisible = state.awaitingResponse
        if (fresh) uiMessage = null
        render(RecordingStore.state)
        if (fresh && resumed) pulseInvitation()
    }

    private fun startFromButton() {
        if (!resumed || pendingGleo || RecordingStore.state.busy) return
        // Consent information is visible on the card. Only an explicit button press
        // reaches permission checks and the microphone service; sensor events never do.
        BoardingCoordinator.markHandled()
        uiMessage = null
        render(RecordingStore.state)
        checkMicrophonePermission()
    }

    private fun pulseInvitation() {
        resetInvitationVisual()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        invitationPulse = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 900
            repeatCount = 1
            addUpdateListener { animation ->
                updateInvitationEmphasis(animation.animatedValue as Float)
            }
            start()
        }
    }

    private fun resetInvitationVisual() {
        invitationPulse?.cancel()
        invitationPulse?.removeAllUpdateListeners()
        invitationPulse = null
        updateInvitationEmphasis(0f)
    }

    private fun updateInvitationEmphasis(amount: Float) {
        val invited = BoardingCoordinator.state.awaitingResponse && !RecordingStore.state.busy
        val base = ContextCompat.getColor(this, R.color.accent_outline)
        val accent = ContextCompat.getColor(this, R.color.sample_primary)
        findViewById<MaterialCardView>(R.id.recording_card).apply {
            strokeColor = if (invited) ColorUtils.blendARGB(base, accent, 0.3f + amount * 0.7f) else base
            strokeWidth = ((if (invited) 2 + amount else 1f) * resources.displayMetrics.density).toInt()
        }
        button.showInvitation(invited, amount)
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
        uiMessage = message
        render(RecordingStore.state)
    }

    private fun requestGleo() {
        val state = RecordingStore.state
        if (!resumed || pendingStart || pendingGleo ||
            state.phase == RecordingPhase.STARTING || state.phase == RecordingPhase.STOPPING) return
        if (!GleoLauncher.isAvailable(this)) {
            showMessage(R.string.gleo_unavailable)
            return
        }
        pendingGleo = true
        savingForGleo = state.phase == RecordingPhase.RECORDING
        uiMessage = null
        if (savingForGleo) {
            try {
                startService(Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP))
            } catch (_: Exception) {
                pendingGleo = false
                savingForGleo = false
                showMessage(R.string.gleo_request_failed)
            }
        } else continueGleoRequest(state)
        render(RecordingStore.state)
    }

    private fun continueGleoRequest(state: RecordingState) {
        if (!pendingGleo || state.busy || !resumed) return
        if (savingForGleo && state.phase == RecordingPhase.ERROR) {
            pendingGleo = false
            savingForGleo = false
            // Keep the actual save/interruption error visible; do not hide it behind Gleo.
            return
        }
        if (!gleoLaunchQueued) {
            gleoLaunchQueued = true
            // RecordingService publishes its final state only after stop/release completes.
            // Give the system audio route notification time to reach Gleo as well.
            mainHandler.postDelayed(launchGleo, 300)
        }
    }

    private fun openHistory() {
        if (RecordingStore.state.busy)
            Toast.makeText(this, R.string.archive_during_recording, Toast.LENGTH_SHORT).show()
        else startActivity(Intent(this, RecordingsActivity::class.java))
    }

    private fun refreshDashboard() {
        val recordings = runCatching { RecordingFiles.list(this) }.getOrElse {
            findViewById<TextView>(R.id.latest_title).setText(R.string.archive_load_failed)
            return
        }
        findViewById<TextView>(R.id.recording_count).text = getString(R.string.recording_count, recordings.size)
        findViewById<TextView>(R.id.total_duration).text = formatDuration(recordings.sumOf { it.durationMs })
        val latest = recordings.firstOrNull()
        findViewById<TextView>(R.id.latest_title).text = latest?.title ?: getString(R.string.no_recent_title)
        findViewById<TextView>(R.id.latest_details).text = latest?.details ?: getString(R.string.no_recent_description)
    }

    private fun render(state: RecordingState) {
        val invited = BoardingCoordinator.state.awaitingResponse && !state.busy
        if (!invited) resetInvitationVisual()
        else if (invitationPulse?.isRunning != true) updateInvitationEmphasis(0f)
        if (lastRenderedPhase != state.phase) {
            lastRenderedPhase = state.phase
            if (!state.busy) refreshDashboard()
        }
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
        val titleView = findViewById<TextView>(R.id.recording_title)
        val titleText = getString(if (invited) R.string.inline_boarding_title else title)
        if (titleView.text.toString() != titleText) titleView.text = titleText
        findViewById<TextView>(R.id.recording_status).apply {
            setText(if (invited) R.string.inline_waiting_status else status)
            setTextColor(ContextCompat.getColor(this@MainActivity,
                if (state.phase == RecordingPhase.RECORDING || state.phase == RecordingPhase.ERROR)
                    R.color.recording_accent else R.color.sample_primary))
        }
        findViewById<TextView>(R.id.recording_time).text = formatDuration(state.durationMs())
        findViewById<View>(R.id.recording_time).visibility = if (invited) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.skip_recording).visibility = if (invited) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recording_notice).visibility = if (state.busy) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.recording_action).setText(when (state.phase) {
            RecordingPhase.RECORDING -> R.string.stop_recording
            RecordingPhase.STARTING -> R.string.starting_status
            RecordingPhase.STOPPING -> R.string.saving_status
            else -> R.string.inline_start
        })
        findViewById<TextView>(R.id.recording_hint).setText(
            when {
                state.phase == RecordingPhase.RECORDING -> R.string.recording_hint
                state.phase == RecordingPhase.STARTING -> R.string.starting_status
                state.phase == RecordingPhase.STOPPING -> R.string.saving_status
                invited -> R.string.inline_boarding_hint
                else -> uiMessage ?: state.message ?: R.string.idle_hint
            }
        )
        button.show(state.phase == RecordingPhase.RECORDING, state.level,
            !pendingGleo && state.phase != RecordingPhase.STARTING && state.phase != RecordingPhase.STOPPING)
        findViewById<TextView>(R.id.use_gleo_button).apply {
            isEnabled = !pendingStart && !pendingGleo &&
                state.phase != RecordingPhase.STARTING && state.phase != RecordingPhase.STOPPING
            setText(when {
                pendingGleo && state.busy -> R.string.gleo_saving
                pendingGleo -> R.string.gleo_opening
                else -> R.string.use_gleo
            })
        }
    }
}
