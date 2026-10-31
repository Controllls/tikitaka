package com.example.hd_project

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

enum class SeatConnection { STOPPED, CONNECTING, READY, UNAVAILABLE }

data class BoardingState(
    val enabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val screenVisible: Boolean = false,
    val connection: SeatConnection = SeatConnection.STOPPED,
    val occupied: Boolean? = null,
    val awaitingResponse: Boolean = false,
    val recording: Boolean = false,
    val startFailed: Boolean = false
) {
    val shouldNotify: Boolean get() = enabled && serviceRunning && !screenVisible && !recording &&
        connection == SeatConnection.READY && occupied == true && awaitingResponse
}

/** Main-thread owner of the ONE SDK connection shared by the screen and standby service. */
@SuppressLint("StaticFieldLeak") // Only applicationContext is retained, never an Activity or Service.
object BoardingCoordinator {
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(BoardingState) -> Unit>()
    private var monitor: VehicleSeatMonitor? = null
    private var monitoring = false
    private var retry: Runnable? = null
    private var retries = 0
    private var busy = false
    private var boot = -1
    private var gate = BoardingPromptGate()
    private val prefs get() = context.getSharedPreferences("boarding", Context.MODE_PRIVATE)
    var state = BoardingState()
        private set

    fun initialize(source: Context) {
        if (::context.isInitialized) return
        context = source.applicationContext
        boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        // Preserve skip/pending across process recreation, but start a new session after a boot.
        if (boot >= 0 && prefs.getInt("session_boot", -2) == boot) {
            gate = BoardingPromptGate(prefs.getBoolean("handled", false),
                prefs.getBoolean("awaiting", false))
        }
        state = BoardingState(enabled = prefs.getBoolean("enabled", false),
            awaitingResponse = gate.awaitingResponse)
        RecordingStore.observe { recording ->
            if (busy != recording.busy) {
                busy = recording.busy
                update(state.copy(recording = busy))
                if (busy) markHandled()
            }
        }
    }

    fun observe(listener: (BoardingState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }
    fun remove(listener: (BoardingState) -> Unit) { listeners.remove(listener) }

    fun setEnabled(enabled: Boolean) {
        retry?.let(main::removeCallbacks)
        retry = null
        retries = 0
        prefs.edit().putBoolean("enabled", enabled).apply()
        update(state.copy(enabled = enabled, startFailed = false))
        reconcile()
    }

    fun setScreenVisible(visible: Boolean) {
        update(state.copy(screenVisible = visible))
        if (visible && !monitoring) retries = 0
        reconcile()
    }

    fun setServiceRunning(running: Boolean) {
        update(state.copy(serviceRunning = running,
            startFailed = if (running) false else state.startFailed))
        if (running) retries = 0
        reconcile()
    }

    fun startFailed() { update(state.copy(startFailed = true)) }

    fun markHandled() {
        gate.markHandled()
        saveSession()
        update(state.copy(awaitingResponse = false))
    }

    private fun wanted() = state.screenVisible || (state.enabled && state.serviceRunning)

    private fun reconcile() {
        if (!wanted()) {
            retry?.let(main::removeCallbacks)
            retry = null
            monitor?.stop()
            monitor = null
            monitoring = false
            retries = 0
            update(state.copy(connection = SeatConnection.STOPPED, occupied = null))
        } else if (!monitoring && retry == null) connect()
    }

    private fun connect() {
        monitoring = true
        update(state.copy(connection = SeatConnection.CONNECTING, occupied = null))
        monitor = VehicleSeatMonitor(context,
            onOccupancy = { occupied ->
                if (occupied == null) disconnected()
                else {
                    retries = 0
                    gate.onOccupancy(occupied, busy)
                    saveSession()
                    update(state.copy(connection = SeatConnection.READY,
                        occupied = occupied, awaitingResponse = gate.awaitingResponse))
                }
            },
            onUnavailable = { disconnected() }
        )
        monitor?.start()
    }

    private fun disconnected() {
        monitor?.stop()
        monitor = null
        monitoring = false
        update(state.copy(connection = SeatConnection.UNAVAILABLE, occupied = null))
        // Bounded reconnection, not a permanent seat-polling timer.
        val delays = longArrayOf(5_000, 15_000, 60_000)
        if (wanted() && retries < delays.size && retry == null) {
            retry = Runnable { retry = null; if (wanted()) connect() }
                .also { main.postDelayed(it, delays[retries++]) }
        }
    }

    private fun saveSession() {
        prefs.edit().putInt("session_boot", boot).putBoolean("handled", gate.handled)
            .putBoolean("awaiting", gate.awaitingResponse).apply()
    }

    private fun update(next: BoardingState) {
        if (next == state) return
        state = next
        listeners.toList().forEach { it(next) }
    }
}
