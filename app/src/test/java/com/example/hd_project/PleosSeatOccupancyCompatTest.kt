package com.example.hd_project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PleosSeatOccupancyCompatTest {
    @Test fun pinnedSdkBooleanIsVacancyInTestedEmulator() {
        assertEquals(false, PleosSeatOccupancyCompat.occupied(true))
        assertEquals(true, PleosSeatOccupancyCompat.occupied(false))
        assertNull(PleosSeatOccupancyCompat.occupied(null))
    }
}
