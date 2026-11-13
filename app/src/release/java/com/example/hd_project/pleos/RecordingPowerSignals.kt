package com.example.hd_project.pleos

/** Release listens directly to the public Automotive ignition property. */
object RecordingPowerSignals {
    @Suppress("UNUSED_PARAMETER")
    fun watch(context: android.content.Context, sessionStartedAtMs: Long, onOff: () -> Unit): AutoCloseable =
        VehicleIgnitionSignals.watch(context, onOff)
}
