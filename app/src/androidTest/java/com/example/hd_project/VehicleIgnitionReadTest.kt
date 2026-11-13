package com.example.hd_project

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only: never toggles global vehicle power while another app may be recording. */
class VehicleIgnitionReadTest {
    @Suppress("DEPRECATION")
    @Test fun publicIgnitionPropertyAndPermissionAreAvailableOnPleosImage() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission("android.car.permission.CAR_POWERTRAIN"))
        val ready=CountDownLatch(1);var car: Car?=null;var failure: Throwable?=null
        val report=JSONObject().put("permissionGranted",true)
        instrumentation.runOnMainSync {
            car=Car.createCar(context,object: ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val manager=car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        val config=manager.getCarPropertyConfig(VehiclePropertyIds.IGNITION_STATE)
                        report.put("supported",config!=null)
                        if(config!=null) {
                            val value=manager.getProperty<Int>(VehiclePropertyIds.IGNITION_STATE,0)
                            report.put("value",value.value).put("timestampNs",value.timestamp).put("status",value.status)
                        }
                    } catch(e: Throwable) { failure=e;report.put("error",e.javaClass.name) }
                    finally { ready.countDown() }
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            })
            car!!.connect()
        }
        try {
            assertTrue(ready.await(10,TimeUnit.SECONDS))
            File(context.filesDir,"story-validation").mkdirs()
            File(context.filesDir,"story-validation/ignition-read.json").writeText(report.toString())
            failure?.let { throw AssertionError("Public ignition property could not be read",it) }
            org.junit.Assume.assumeTrue("This image does not expose IGNITION_STATE",report.getBoolean("supported"))
            assertTrue(report.getInt("value") in 0..5)
        } finally { instrumentation.runOnMainSync { car?.disconnect() } }
    }
}
