package com.example.hd_project

import com.example.hd_project.pleos.RecordingPowerOffGate
import org.junit.Assert.*
import org.junit.Test

class RecordingPowerOffGateTest {
    @Test fun oldOffDoesNotStopANewSessionButANewOffDoes() {
        val gate = RecordingPowerOffGate(1000)
        assertFalse(gate.accept(true, "Off", 999))
        assertFalse(gate.accept(true, "Off", 999))
        assertTrue(gate.accept(true, "Off", 1100))
        assertFalse(gate.accept(true, "Off", 1200))
    }
    @Test fun activeAndStandbyStatesKeepRecording() {
        val gate = RecordingPowerOffGate(1000)
        listOf("On", "Ready", "Driving", "StandBy").forEachIndexed { i, state ->
            assertFalse(gate.accept(true, state, 1001L + i))
        }
        assertTrue(gate.accept(true, "Off", 1010))
    }
    @Test fun disconnectMissingAndInvalidValuesAreNotOff() {
        val gate = RecordingPowerOffGate(1000)
        assertFalse(gate.accept(false, "Off", 1100))
        assertFalse(gate.accept(true, null, 1100))
        assertFalse(gate.accept(true, "Unknown", 1100))
        assertFalse(gate.accept(true, "Off", 0))
        assertTrue(gate.accept(true, "Off", 1100))
    }
    @Test fun outOfOrderAndRepeatedEventsCannotStopTwice() {
        val gate = RecordingPowerOffGate(1000)
        assertFalse(gate.accept(true, "On", 1200))
        assertFalse(gate.accept(true, "Off", 1150))
        assertFalse(gate.accept(true, "Off", 1200))
        assertTrue(gate.accept(true, "Off", 1300))
        assertFalse(gate.accept(true, "On", 1400))
        assertFalse(gate.accept(true, "Off", 1500))
    }
    @Test fun firstObservedOffAfterSessionStartIsHandledEvenIfOnWasMissed() {
        assertTrue(RecordingPowerOffGate(1000).accept(true, "Off", 1000))
    }
}
