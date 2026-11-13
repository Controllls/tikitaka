package com.example.hd_project.pleos

/** Vehicle timestamps and the session boundary both use elapsedRealtimeNanos, never wall time. */
class IgnitionOffGate(private val startedAtNanos: Long) {
    private var latest = 0L
    private var delivered = false

    fun accept(available: Boolean, ignition: Int, timestampNanos: Long): Boolean {
        if (delivered || !available || ignition !in 1..5 || timestampNanos <= latest) return false
        latest = timestampNanos
        // LOCK and ACC are distinct states. Only an explicit, fresh OFF (2) finalizes a session.
        if (ignition != 2 || timestampNanos < startedAtNanos) return false
        delivered = true
        return true
    }
}
