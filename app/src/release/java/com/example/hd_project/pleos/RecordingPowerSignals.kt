package com.example.hd_project.pleos

/** Await the target vehicle's supported, approved power API; no localhost simulator in release builds. */
object RecordingPowerSignals {
    @Suppress("UNUSED_PARAMETER")
    fun watch(sessionStartedAtMs: Long, onOff: () -> Unit): AutoCloseable = AutoCloseable { }
}
