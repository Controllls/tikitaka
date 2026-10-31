package com.example.hd_project

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener

/** Owned by BoardingCoordinator for visible UI or opted-in standby. No cold-wake API. */
class VehicleSeatMonitor(
    private val context: Context,
    private val onOccupancy: (Boolean?) -> Unit,
    private val onUnavailable: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private var vehicle: Vehicle? = null
    private var seat: Seat? = null
    private var listener: SeatOccupancyListener? = null
    private var generation = 0
    private var updates = 0
    private var timeout: Runnable? = null
    private var settle: Runnable? = null
    private var candidate: Boolean? = null
    private var hasCandidate = false

    fun start() {
        stop()
        val current = generation
        timeout = Runnable { if (current == generation) fail(null) }
            .also { main.postDelayed(it, 10_000) }
        try {
            val api = Vehicle(context.applicationContext)
            vehicle = api
            api.initialize()
            val seats = api.getSeat()
            seat = seats
            // P1171: capability and supported area before subscribing.
            seats.checkSeatOccupancyCapabilities(
                onComplete = { config, areas -> dispatch(current) {
                    if ((config.accessType != AccessType.READ &&
                                config.accessType != AccessType.READ_WRITE) ||
                        areas.none { it.values.contains(SeatValue.ROW_1_LEFT) }) {
                        fail(null)
                    } else subscribe(seats, current)
                } },
                onFailed = { error -> dispatch(current) { fail(error) } }
            )
        } catch (error: Exception) { fail(error) }
    }

    private fun subscribe(seats: Seat, current: Int) {
        val registered = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = dispatch(current) {
                updates++
                accept(PleosSeatOccupancyCompat.occupied(occupancy))
            }
            override fun onFailed(e: Exception) = dispatch(current) { fail(e) }
        }
        listener = registered
        try {
            // P1216 / P1188: a newer subscription event wins over an older getter response.
            seats.registerSeatOccupancy(driver, registered)
            val beforeRead = updates
            seats.getCurrentSeatOccupancy(driver,
                onComplete = { occupied -> dispatch(current) {
                    if (updates == beforeRead) accept(PleosSeatOccupancyCompat.occupied(occupied))
                } },
                onFailed = { error -> dispatch(current) {
                    if (updates == beforeRead) fail(error)
                } }
            )
        } catch (error: Exception) { fail(error) }
    }

    private fun accept(occupied: Boolean?) {
        Log.d("VehicleSeatMonitor", "Driver seat occupied: " + occupied)
        timeout?.let(main::removeCallbacks)
        timeout = null
        // Continuous identical events must not keep postponing the stability timer.
        if (hasCandidate && candidate == occupied) return
        candidate = occupied
        hasCandidate = true
        settle?.let(main::removeCallbacks)
        if (occupied == null) {
            onOccupancy(null)
            return
        }
        settle = Runnable { onOccupancy(occupied) }.also { main.postDelayed(it, 1200) }
    }

    private fun dispatch(current: Int, action: () -> Unit) {
        main.post { if (current == generation && vehicle != null) action() }
    }

    private fun fail(error: Exception?) {
        if (error != null) Log.w("VehicleSeatMonitor", "Seat connection failed", error)
        stop()
        onUnavailable()
    }

    fun stop() {
        generation++
        timeout?.let(main::removeCallbacks)
        settle?.let(main::removeCallbacks)
        timeout = null
        settle = null
        listener?.let { registered ->
            runCatching { seat?.unregisterSeatOccupancy(driver, registered) }
        }
        runCatching { vehicle?.release() }
        listener = null
        seat = null
        vehicle = null
        updates = 0
        candidate = null
        hasCandidate = false
    }
}
