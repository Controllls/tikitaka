package com.example.hd_project

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardingPromptGateTest {
    @Test fun initialOccupiedAsksOnlyOnce() {
        val gate = BoardingPromptGate()
        assertTrue(gate.onOccupancy(true, false))
        repeat(10) { assertFalse(gate.onOccupancy(true, false)) }
    }

    @Test fun unknownDoesNotCreateAnotherTrip() {
        val gate = BoardingPromptGate()
        assertFalse(gate.onOccupancy(null, false))
        assertTrue(gate.onOccupancy(true, false))
        assertFalse(gate.onOccupancy(null, false))
        assertFalse(gate.onOccupancy(true, false))
    }

    @Test fun leavingThenBoardingCanAskAgain() {
        val gate = BoardingPromptGate()
        assertTrue(gate.onOccupancy(true, false))
        assertFalse(gate.onOccupancy(false, false))
        assertTrue(gate.onOccupancy(true, false))
    }

    @Test fun activeRecordingConsumesTripWithoutPromptingAfterStop() {
        val gate = BoardingPromptGate()
        assertFalse(gate.onOccupancy(true, true))
        assertFalse(gate.onOccupancy(true, false))
        assertFalse(gate.onOccupancy(false, false))
        assertTrue(gate.onOccupancy(true, false))
    }

    @Test fun manualConsentOrSkipHandlesCurrentTrip() {
        val gate = BoardingPromptGate()
        gate.markHandled()
        assertFalse(gate.onOccupancy(true, false))
        gate.onOccupancy(false, false)
        assertTrue(gate.onOccupancy(true, false))
    }
}
