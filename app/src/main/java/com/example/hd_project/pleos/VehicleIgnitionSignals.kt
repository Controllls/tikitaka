package com.example.hd_project.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Public AAOS powertrain property. No console, socket, or private Pleos API in this path. */
class VehicleIgnitionSignals private constructor(context: Context, private val onOff: () -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val gate = IgnitionOffGate(SystemClock.elapsedRealtimeNanos())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private val retry = Runnable { connect() }
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.IGNITION_STATE || value.areaId != 0) return
            main.post {
                if (!closed.get() && gate.accept(value.status == CarPropertyValue.STATUS_AVAILABLE,
                        value.value as? Int ?: 0, value.timestamp)) {
                    Log.i(TAG, "Fresh vehicle IGNITION_STATE=OFF; finalizing recording")
                    onOff()
                }
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            if (propId == VehiclePropertyIds.IGNITION_STATE) Log.w(TAG, "Ignition unavailable; recording continues")
        }
    }

    @Suppress("DEPRECATION") // Public async API supported by API 28 and the API 34 Pleos image.
    private fun connect() {
        if (closed.get()) return
        releaseConnection()
        try {
            car = Car.createCar(context, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (closed.get()) return
                    try {
                        val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                        properties = manager
                        if (manager?.getCarPropertyConfig(VehiclePropertyIds.IGNITION_STATE) == null) {
                            Log.w(TAG, "IGNITION_STATE unsupported; manual stop remains available")
                            return
                        }
                        check(manager.registerCallback(callback, VehiclePropertyIds.IGNITION_STATE,
                            CarPropertyManager.SENSOR_RATE_ONCHANGE)) { "Ignition subscription rejected" }
                        Log.i(TAG, "Public AAOS ignition subscription active")
                    } catch (e: Exception) { unavailable(e) }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    properties = null
                    if (!closed.get()) {
                        Log.w(TAG, "Car service disconnected; recording continues")
                        main.removeCallbacks(retry); main.postDelayed(retry, 5000)
                    }
                }
            })
            car?.connect()
        } catch (e: Exception) { unavailable(e) }
        catch (e: LinkageError) { Log.w(TAG, "Public car API unavailable", e) }
    }

    private fun unavailable(e: Exception) {
        Log.w(TAG, "Ignition unavailable; recording continues", e)
        if (!closed.get() && e !is SecurityException) {
            main.removeCallbacks(retry); main.postDelayed(retry, 5000)
        }
    }
    private fun releaseConnection() {
        runCatching { properties?.unregisterCallback(callback) }; properties = null
        runCatching { car?.disconnect() }; car = null
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) main.post {
            main.removeCallbacks(retry); releaseConnection()
        }
    }
    companion object {
        private const val TAG = "VehicleIgnitionSignals"
        fun watch(context: Context, onOff: () -> Unit): AutoCloseable =
            VehicleIgnitionSignals(context, onOff).also { watcher -> watcher.main.post { watcher.connect() } }
    }
}
