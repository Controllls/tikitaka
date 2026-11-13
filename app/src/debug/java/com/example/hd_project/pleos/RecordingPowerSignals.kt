package com.example.hd_project.pleos

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Debug emulator adapter. This reads the Virtual Console; it is not a production Vehicle SDK API. */
object RecordingPowerSignals {
    private val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun watch(context: android.content.Context, sessionStartedAtMs: Long, onOff: () -> Unit): AutoCloseable {
        val delivered = AtomicBoolean(false)
        val dispatch = { if (delivered.compareAndSet(false, true)) onOff() }
        val vehicle = VehicleIgnitionSignals.watch(context, dispatch)
        if (Build.HARDWARE !in setOf("ranchu", "goldfish")) return vehicle
        val closed = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val main = Handler(Looper.getMainLooper())
        val gate = RecordingPowerOffGate(sessionStartedAtMs)
        scope.launch {
            var connected: Boolean? = null
            while (isActive && !closed.get()) {
                val state = runCatching {
                    val request = Request.Builder().url("http://127.0.0.1:8088/vehicle/power-system")
                        .header("Cache-Control", "no-cache").build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful)
                        JSONObject(requireNotNull(response.body).string())
                    }
                }.getOrNull()
                val available = state?.opt("bridge_ready") == true && state.opt("emulator_active") == true
                if (available != connected) {
                    connected = available
                    Log.i("RecordingPowerSignals", if (available) "Virtual Console power connected" else
                        "Virtual Console power unavailable; recording continues")
                }
                val actual = state?.optJSONObject("actual")?.optJSONObject("0")
                if (gate.accept(available, actual?.optString("state"), actual?.optLong("timestamp_ms") ?: 0L)) {
                    main.post { if (!closed.get()) dispatch() }
                    break
                }
                delay(1000)
            }
        }
        return AutoCloseable { if (closed.compareAndSet(false, true)) { scope.cancel(); vehicle.close() } }
    }
}
