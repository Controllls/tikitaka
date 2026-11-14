package com.example.hd_project.tiki

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class JourneySummary(
    val enabled: Boolean = false,
    val observedMs: Long = 0,
    val movingMs: Long = 0,
    val distanceMeters: Double = 0.0,
) {
    val available: Boolean get() = enabled && observedMs > 0
}

/** Coordinates are transient. Only aggregate distance/time is retained; pause and stale gaps are excluded. */
class JourneyAccumulator {
    private data class Fix(val latitude: Double, val longitude: Double, val at: Long)
    private var previous: Fix? = null
    private var summary = JourneySummary(enabled = true)
    @Synchronized fun resetFix() { previous = null }
    @Synchronized fun observe(latitude: Double, longitude: Double, at: Long, recording: Boolean) {
        if (!recording || !latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0 || latitude == 0.0 && longitude == 0.0) { previous = null; return }
        val fix = Fix(latitude, longitude, at)
        val last = previous
        previous = fix
        if (last == null) return
        val dt = at - last.at
        if (dt !in 1..NavigationBackgroundPolicy.FRESH_MS) return
        val lat = Math.toRadians(latitude - last.latitude)
        val lon = Math.toRadians(longitude - last.longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(last.latitude)) * cos(Math.toRadians(latitude)) * sin(lon / 2).pow(2)
        val distance = 6_371_000 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
        // Ignore jumps (> 200 km/h) and stationary GPS jitter. This is measured coverage, not a complete trip claim.
        if (distance / dt * 1000 > 55.6) return
        val moving = distance >= 3 && distance / dt * 1000 >= 1.0
        summary = summary.copy(observedMs = summary.observedMs + dt,
            movingMs = summary.movingMs + if (moving) dt else 0,
            distanceMeters = summary.distanceMeters + if (moving) distance else 0.0)
    }
    @Synchronized fun snapshot() = summary
}
