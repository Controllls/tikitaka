package com.example.hd_project

import com.example.hd_project.pleos.IgnitionOffGate
import org.junit.Assert.*
import org.junit.Test

class IgnitionOffGateTest {
    @Test fun onlyFreshAvailableOffEndsSessionOnce() {
        val gate = IgnitionOffGate(100)
        assertFalse(gate.accept(true, 2, 90)) // Cached OFF at subscription.
        assertFalse(gate.accept(false, 2, 150))
        assertFalse(gate.accept(true, 0, 160)) // Unknown isn't shutdown.
        assertFalse(gate.accept(true, 4, 170))
        assertFalse(gate.accept(true, 2, 165)) // Out of order.
        assertFalse(gate.accept(true, 1, 180)) // LOCK isn't OFF.
        assertFalse(gate.accept(true, 3, 190)) // Accessory isn't OFF.
        assertTrue(gate.accept(true, 2, 200))
        assertFalse(gate.accept(true, 2, 210))
    }
}
