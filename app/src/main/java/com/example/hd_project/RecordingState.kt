package com.example.hd_project

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

enum class RecordingPhase { IDLE, STARTING, RECORDING, PAUSED, STOPPING, ERROR }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val startedAt: Long = 0,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val message: Int? = null
) {
    val busy: Boolean get() = phase == RecordingPhase.STARTING ||
        phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED || phase == RecordingPhase.STOPPING
    fun durationMs(): Long = if (phase == RecordingPhase.RECORDING)
        SystemClock.elapsedRealtime() - startedAt else elapsedMs
}

/** In-process UI state. It never restarts a recording after the process dies. */
object RecordingStore {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(RecordingState) -> Unit>()
    var state = RecordingState()
        private set

    fun publish(next: RecordingState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publish(next) }
            return
        }
        state = next
        listeners.toList().forEach { it(next) }
    }

    fun observe(listener: (RecordingState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun remove(listener: (RecordingState) -> Unit) { listeners.remove(listener) }
}

fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%02d:%02d:%02d",
        seconds / 3600, (seconds / 60) % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
