package com.example.hd_project.pleos

/** A connection failure or an old Off value must never terminate a new recording. */
class RecordingPowerOffGate(private val sessionStartedAtMs: Long) {
    private var newestTimestamp = 0L
    private var handled = false

    fun accept(connected: Boolean, actualState: String?, timestampMs: Long): Boolean {
        if (handled || !connected || actualState !in STATES || timestampMs <= newestTimestamp) return false
        newestTimestamp = timestampMs
        if (actualState != "Off" || timestampMs < sessionStartedAtMs) return false
        handled = true
        return true
    }

    private companion object { val STATES = setOf("Off", "StandBy", "Ready", "On", "Driving") }
}
