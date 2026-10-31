package com.example.hd_project

/**
 * Compatibility for the pinned Vehicle 2.3.2 + Pleos Connect v2.1.0.
 *
 * SeatImpl.getCurrentSeatOccupancy and PropertyCallback compare raw occupancy to 1.
 * This emulator uses 1=VACANT, 2=OCCUPIED, so the SDK boolean describes vacancy.
 * Confirmed against Virtual Console driver occupied=false/true in both directions.
 * Revalidate this adapter when either SDK or vehicle firmware changes.
 *
 * The SDK also collapses some unknown raw values to false. They cannot be recovered
 * through this Boolean API: this is a prompt heuristic, NEVER recording consent.
 */
object PleosSeatOccupancyCompat {
    fun occupied(sdkVacant: Boolean?): Boolean? = sdkVacant?.not()
}
