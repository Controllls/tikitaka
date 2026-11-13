package com.example.hd_project

import android.car.drivingstate.CarUxRestrictions
import com.example.hd_project.pleos.DrivingRestrictions.State
import org.junit.Assert.*
import org.junit.Test

class DrivingRestrictionsTest {
    @Test fun unknownOrDisconnectedNeverAllowsVideoOrDecoration() {
        assertFalse(State().videoAllowed)
        assertFalse(State().animationAllowed)
    }
    @Test fun parkedAllowsVideoAndDecoration() {
        val parked = State(true, false, 0)
        assertTrue(parked.videoAllowed)
        assertTrue(parked.animationAllowed)
    }
    @Test fun noVideoFlagBlocksPlaybackButUnrelatedFlagsDoNotInventRestrictions() {
        assertFalse(State(true, true, CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO).videoAllowed)
        assertTrue(State(true, true, CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD).videoAllowed)
        assertFalse(State(true, true, 0).animationAllowed)
    }
    @Test fun transitionsRequireFreshPlatformState() {
        val transitions = listOf(State(true, false, 0), State(true, true, 16), State(), State(true, false, 0))
        assertEquals(listOf(true, false, false, true), transitions.map { it.videoAllowed })
    }
}
