package com.example.hd_project

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardingPromptGateTest {
    @Test fun handledSessionSurvivesProcessRecreation() {
        val first = BoardingPromptGate()
        first.onOccupancy(true, false)
        first.markHandled()
        val restored = BoardingPromptGate(first.handled, first.awaitingResponse)
        assertFalse(restored.onOccupancy(true, false))
        assertFalse(restored.awaitingResponse)
        restored.onOccupancy(false, false)
        assertTrue(restored.onOccupancy(true, false))
    }

    @Test fun pendingSessionSurvivesProcessRecreationWithoutCreatingAnotherEvent() {
        val first = BoardingPromptGate()
        first.onOccupancy(true, false)
        val restored = BoardingPromptGate(first.handled, first.awaitingResponse)
        assertTrue(restored.awaitingResponse)
        assertFalse(restored.onOccupancy(true, false))
        restored.onOccupancy(null, false)
        assertTrue(restored.awaitingResponse)
        restored.onOccupancy(false, false)
        assertFalse(restored.awaitingResponse)
    }

    @Test fun inconsistentStoredStateCannotCreateAnInvitation() {
        assertFalse(BoardingPromptGate(false, true).awaitingResponse)
    }

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

    @Test fun unansweredInvitationSurvivesRepeatedAndUnknownReadings() {
        val gate = BoardingPromptGate()
        gate.onOccupancy(true, false)
        assertTrue(gate.awaitingResponse)
        assertFalse(gate.onOccupancy(true, false))
        gate.onOccupancy(null, false)
        assertTrue(gate.awaitingResponse)
    }

    @Test fun skipDismissesInvitationUntilAnotherTrip() {
        val gate = BoardingPromptGate()
        gate.onOccupancy(true, false)
        gate.markHandled()
        assertFalse(gate.awaitingResponse)
        assertFalse(gate.onOccupancy(true, false))
        gate.onOccupancy(false, false)
        assertTrue(gate.onOccupancy(true, false))
        assertTrue(gate.awaitingResponse)
    }

    @Test fun vacantSeatWithdrawsUnansweredInvitation() {
        val gate = BoardingPromptGate()
        gate.onOccupancy(true, false)
        gate.onOccupancy(false, false)
        assertFalse(gate.awaitingResponse)
    }

    @Test fun recordingClearsAnOutstandingInvitation() {
        val gate = BoardingPromptGate()
        gate.onOccupancy(true, false)
        assertFalse(gate.onOccupancy(true, true))
        assertFalse(gate.awaitingResponse)
    }
}
