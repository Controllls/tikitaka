package com.example.hd_project

/**
 * One question per occupied session. An unknown sensor value is NOT an empty seat.
 * A true reading while already recording consumes this session as well.
 */
class BoardingPromptGate {
    private var handled = false

    fun onOccupancy(occupied: Boolean?, recording: Boolean): Boolean {
        if (occupied == false) handled = false
        if (occupied != true || handled) return false
        handled = true
        return !recording
    }

    fun markHandled() { handled = true }
}
