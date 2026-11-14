package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test

class JourneySummaryTest {
    @Test fun measuresOnlyFreshPlausibleMovement() {
        val trip=JourneyAccumulator()
        trip.observe(37.0,127.0,1000,true);trip.observe(37.0001,127.0,6000,true)
        assertEquals(5000,trip.snapshot().movingMs)
        assertEquals(11.12,trip.snapshot().distanceMeters,.1)
        trip.observe(38.0,127.0,7000,true) // Teleport must not become a 111 km trip.
        assertEquals(5000,trip.snapshot().observedMs)
    }
    @Test fun ignoresPausesAndStaleGaps() {
        val trip=JourneyAccumulator()
        trip.observe(37.0,127.0,1000,true);trip.resetFix()
        trip.observe(37.001,127.0,11000,true)
        assertFalse(trip.snapshot().available)
        trip.observe(37.002,127.0,40000,true)
        assertFalse(trip.snapshot().available)
        trip.observe(37.002,127.0,45000,true)
        assertTrue(trip.snapshot().available);assertEquals(0,trip.snapshot().movingMs)
    }
    @Test fun absentPermissionAndInvalidCoordinatesNeverInventDistance() {
        assertFalse(JourneySummary().available)
        val trip=JourneyAccumulator()
        trip.observe(0.0,0.0,1000,true);trip.observe(37.0,127.0,2000,false);trip.observe(Double.NaN,127.0,3000,true)
        assertFalse(trip.snapshot().available)
    }
    @Test fun connectionFailureDoesNotIncludeNormalLocalFailures() {
        assertTrue(ProductionConnection.isTransportFailure("NETWORK_ERROR"))
        assertTrue(ProductionConnection.isTransportFailure("DeadObjectException"))
        for(reason in listOf(null,"TIMEOUT","ON_DEVICE_ENGINE_ERROR","PERMISSION_DENIED","UNAVAILABLE_LANGUAGE","disk full"))
            assertFalse(ProductionConnection.isTransportFailure(reason))
    }
}
