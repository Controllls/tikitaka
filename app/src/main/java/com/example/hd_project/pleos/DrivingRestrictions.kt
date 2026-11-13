package com.example.hd_project.pleos

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder

/** Subscribe only while the screen is visible. No speed/gear heuristic or new permission. */
class DrivingRestrictions(context: Context, private val changed: (State) -> Unit) {
    data class State(val available: Boolean = false, val requiresOptimization: Boolean = true,
                     val activeRestrictions: Int = 0) {
        // App policy: viewing is available only after a fresh, unrestricted platform state.
        // This is deliberately stricter than checking the platform's NO_VIDEO flag alone.
        val videoAllowed: Boolean get() = available && !requiresOptimization &&
            activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO == 0
        val animationAllowed: Boolean get() = available && !requiresOptimization
    }

    private val context = context.applicationContext
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var started = false
    var state = State()
        private set

    private fun publish(value: CarUxRestrictions?) {
        state = if (value == null) State() else State(true, value.isRequiresDistractionOptimization, value.activeRestrictions)
        changed(state)
    }

    @Suppress("DEPRECATION") // Async ServiceConnection API also works on minSdk 28.
    fun start() {
        if (started) return
        started = true
        publish(null)
        try {
            car = Car.createCar(context, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (!started) return
                    try {
                        val current = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        manager = current
                        current?.registerListener { if (started) publish(it) }
                        publish(current?.currentCarUxRestrictions)
                    } catch (_: Exception) { publish(null) }
                }
                override fun onServiceDisconnected(name: ComponentName?) { manager = null; if (started) publish(null) }
            })
            car?.connect()
        } catch (_: Exception) { publish(null) }
        catch (_: LinkageError) { publish(null) } // Older images may not expose the optional car API.
    }

    fun stop() {
        started = false
        runCatching { manager?.unregisterListener() }
        manager = null
        runCatching { car?.disconnect() }
        car = null
        publish(null)
    }
}
