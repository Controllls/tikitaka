package com.example.hd_project

/**
 * One question per occupied session. An unknown sensor value is NOT an empty seat.
 * A true reading while already recording consumes this session as well.
 */
class BoardingPromptGate(
    handled: Boolean = false,
    awaitingResponse: Boolean = false
) {
    var handled = handled
        private set
    // Keep an unanswered in-card invitation across activity recreation/navigation.
    var awaitingResponse = handled && awaitingResponse
        private set

    fun onOccupancy(occupied: Boolean?, recording: Boolean): Boolean {
        if (occupied == false) {
            handled = false
            awaitingResponse = false
        }
        if (occupied == true && recording) {
            markHandled()
            return false
        }
        if (occupied != true || handled) return false
        handled = true
        awaitingResponse = true
        return true
    }

    fun markHandled() {
        handled = true
        awaitingResponse = false
    }
}
