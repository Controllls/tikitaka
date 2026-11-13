package com.example.hd_project.tiki

/** Project tuning values, not a calibrated laughter probability or a platform requirement. */
object LaughterSensitivity {
    const val MIN = .001
    const val DEFAULT = MIN
    val values = listOf(.001, .005, .01, .05, .1, .2)
    val labels = listOf("최대 · 0.001", "매우 높음 · 0.005", "높음 · 0.01", "보통 · 0.05", "낮음 · 0.10", "매우 낮음 · 0.20")

    // Preferences store Float; restore the exact preset before using it in checkpoint paths.
    fun fromStored(value: Double): Double = if (value.isFinite()) values.minBy { kotlin.math.abs(it - value) } else DEFAULT
}
