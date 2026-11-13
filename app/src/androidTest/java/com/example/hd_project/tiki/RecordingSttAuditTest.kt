package com.example.hd_project.tiki

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.example.hd_project.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** Opt-in device audit. Host injects a known speech fixture into the emulator microphone.
 * No production changes, permission grants, remote uploads or deletion of existing media.
 */
class RecordingSttAuditTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val id = (args.getString("auditId") ?: "disabled").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    private val mode = args.getString("auditMode", "sequential")
    private val root = File(context.cacheDir, "recording-stt-audit/$id").apply { mkdirs() }
    private val report = JSONObject().put("id", id).put("mode", mode)
    @Before fun requireExplicitAudit() {
        assumeTrue("Run with an explicit auditId and the host audio harness", args.containsKey("auditId"))
    }
    private fun phase(value: String) {
        MetadataEpisodes.write(File(root, "phase.json"), JSONObject().put("phase", value).put("timeMs", System.currentTimeMillis()))
    }
    private fun waitFor(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        check(condition()) { "Timed out: $label; recorder=${RecordingStore.state}" }
    }
    private fun field(activity: MainActivity, name: String): Any? =
        TikiActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun command(scenario: ActivityScenario<MainActivity>, action: String) {
        scenario.onActivity {
            val intent = Intent(it, RecordingService::class.java).setAction(action).putExtra(RecordingService.EXTRA_RECORD_ONLY, true)
            if (action == RecordingService.ACTION_START) it.startForegroundService(intent) else it.startService(intent)
        }
    }
    private fun recordingSnapshot() = JSONObject().put("phase", RecordingStore.state.phase.name)
        .put("message", RecordingStore.state.message?.let { context.getString(it) })
        .put("configs", JSONArray(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.map {
            JSONObject().put("source", it.clientAudioSource).put("silenced", it.isClientSilenced)
                .put("sampleRate", it.clientFormat.sampleRate).put("session", it.clientAudioSessionId)
        }))

    private inner class MicStt {
        val ready = CountDownLatch(1)
        val started = CountDownLatch(1)
        val inputReady = CountDownLatch(1)
        val events = JSONArray()
        var error: String? = null
        lateinit var sdk: SpeechToText
        private fun event(kind: String, text: String = "", done: Boolean = false) = synchronized(events) {
            events.put(JSONObject().put("event", kind).put("text", text).put("completed", done).put("timeMs", System.currentTimeMillis()))
        }
        val listener = object : ResultListener {
            override fun onReady() { event("ready"); inputReady.countDown() }
            override fun onStartedRecognition() { event("started"); started.countDown() }
            override fun onEndedRecognition() { event("ended") }
            override fun onUpdated(stt: String, completed: Boolean) { event("text", stt, completed) }
            override fun onUpdatedEpdData(on: Long, off: Long) { event("epd", "$on:$off") }
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onError(errorType: ErrorType) { error = errorType.name; event("error", errorType.name); ready.countDown() }
        }
        fun start(requestMicrophone: Boolean = true) {
            ins.runOnMainSync {
                sdk = SpeechToText(context, Mode.ON_DEVICE)
                sdk.addListener(listener)
                sdk.initialize(object : OnInitializeListener {
                    override fun onSuccess() { event("initialized"); ready.countDown() }
                    override fun onFailure(errorType: ErrorType) { error = errorType.name; event("initError", errorType.name); ready.countDown() }
                })
            }
            check(ready.await(15, TimeUnit.SECONDS)) { "STT initialization timeout" }
            check(error == null) { "STT initialization: $error" }
            ins.runOnMainSync { sdk.setLocale(Locale.KOREA); if (requestMicrophone) sdk.request() }
            if (requestMicrophone) check(inputReady.await(12, TimeUnit.SECONDS)) { "STT microphone was not ready: $error" }
        }
        fun close() {
            if (!::sdk.isInitialized) return
            ins.runOnMainSync { runCatching { sdk.stop() } }
            SystemClock.sleep(1000)
            ins.runOnMainSync { runCatching { sdk.removeListener(listener) }; runCatching { sdk.release() } }
            synchronized(events) { report.put("micSttEvents", JSONArray(events.toString())).put("micSttError", error) }
        }
    }

    @Test fun fileInputEventOrder() {
        check(!RecordingStore.state.busy)
        val source = File(context.cacheDir, requireNotNull(args.getString("auditSource"))).canonicalFile
        require(source.toPath().startsWith(File(context.cacheDir,"recording-stt-audit").canonicalFile.toPath()))
        val mic = MicStt()
        try {
            val samples = ArrayList<Float>()
            LaughterAnalyzer(context).decode(source, {false}, {}, {}, samples::add)
            report.put("source", source.path).put("samples", samples.size)
            mic.start(requestMicrophone = false)
            phase("file-stt")
            report.put("inputStartMs", System.currentTimeMillis())
            for (offset in samples.indices step 1600) {
                val count = minOf(1600, samples.size-offset)
                val bytes = ByteBuffer.allocate(count*2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { bytes.putShort((samples[offset+it]*32767).toInt().coerceIn(-32768,32767).toShort()) }
                ins.runOnMainSync { mic.sdk.sendAudio(bytes.array()) }
                SystemClock.sleep(100)
            }
            report.put("completeAudioSendAtMs", System.currentTimeMillis())
            ins.runOnMainSync { mic.sdk.completeAudioSend() }
            SystemClock.sleep(8500)
            report.put("beforeStopMs", System.currentTimeMillis())
        } finally {
            mic.close()
            MetadataEpisodes.write(File(root,"report.json"),report)
            phase("done")
        }
    }

    @Test fun recordPlaybackAndRecognize() = runBlocking<Unit> {
        check(mode in listOf("sequential", "direct", "concurrent", "stt-first"))
        check(!RecordingStore.state.busy) { "User recording active" }
        val permissions = listOf("android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE", "pleos.car.permission.STT_SERVICE")
        report.put("permissions", JSONObject().also { result -> permissions.forEach { result.put(it, context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) } })
        val journal = File(RecordingFiles.directory(context), "session-journal.json")
        val previousJournal = journal.takeIf { it.isFile }?.readBytes()
        val previousFiles = RecordingFiles.list(context).map { it.file.name }.toSet()
        val mic = if (mode != "sequential") MicStt() else null
        var ownRecording = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                if (mode == "direct" || mode == "stt-first") { mic!!.start(); phase("direct") }
                if (mode != "direct") {
                    command(scenario, RecordingService.ACTION_START); ownRecording = true
                    waitFor("recording started") { RecordingStore.state.phase == RecordingPhase.RECORDING }
                    phase(if (mode == "sequential") "capture" else "concurrent")
                    if (mode == "concurrent") { SystemClock.sleep(1200); mic!!.start() }
                }
                val snapshots = JSONArray()
                repeat(28) { snapshots.put(recordingSnapshot()); SystemClock.sleep(1000) }
                report.put("recordingSnapshots", snapshots)
                if (ownRecording && RecordingStore.state.busy) {
                    command(scenario, RecordingService.ACTION_STOP)
                    waitFor("recording finalized") { !RecordingStore.state.busy }
                }
                mic?.close()
                phase("inspect")
                val added = RecordingFiles.list(context).filter { it.file.name !in previousFiles }
                report.put("newRecordings", JSONArray(added.map { it.file.name }))
                if (mode != "direct") {
                    check(added.size == 1) { "Expected one recording, got ${added.size}" }
                    val source = added.single().file
                    report.put("source", source.absolutePath).put("bytes", source.length()).put("durationMs", RecordingJournal.duration(source))
                    report.put("journal", RecordingJournal.read(context))
                    source.copyTo(File(root, "recorded.m4a"))
                    // Label this newly-created diagnostic recording without changing any pre-existing item.
                    File(source.parentFile, source.nameWithoutExtension + ".json").writeText(JSONObject()
                        .put("title", "마이크·STT 검증 ($mode)").put("auditId", id).toString())
                    val samples = ArrayList<Float>()
                    LaughterAnalyzer(context).decode(source, { false }, {}, {}, samples::add)
                    val pcm = samples.toFloatArray()
                    report.put("decodedSamples", pcm.size).put("rms", sqrt(pcm.sumOf { it.toDouble()*it }/pcm.size.coerceAtLeast(1)))
                        .put("peak", pcm.maxOfOrNull { abs(it) } ?: 0f)
                    if (mode == "sequential") {
                        assertTrue("Microphone recording is silent", report.getDouble("rms") > .001)
                        scenario.onActivity { activity ->
                            TikiActivity::class.java.getDeclaredField("source").apply { isAccessible = true }.set(activity, source.name)
                            TikiActivity::class.java.getDeclaredMethod("go", String::class.java).apply { isAccessible = true }.invoke(activity, "audio")
                        }
                        waitFor("audio UI prepared") {
                            var ready = false
                            scenario.onActivity { ready = field(it,"playbackPrepared") == true && field(it,"playbackSeekPending") == false }
                            ready
                        }
                        phase("playback"); SystemClock.sleep(1000)
                        scenario.onActivity { (field(it, "play") as TextView).performClick() }
                        var lastPosition = 0
                        val positions = JSONArray()
                        repeat(12) {
                            SystemClock.sleep(1000)
                            scenario.onActivity { activity ->
                                val player = field(activity, "player") as MediaPlayer
                                positions.put(JSONObject().put("positionMs", player.currentPosition).put("playing", player.isPlaying))
                                lastPosition = player.currentPosition
                            }
                        }
                        report.put("playbackPositions", positions)
                        assertTrue("Playback did not advance", lastPosition > 8000)
                        scenario.onActivity { (field(it, "play") as TextView).performClick() }
                        phase("file-stt")
                        val started = SystemClock.elapsedRealtime()
                        val transcript = PleosLocalStt.recognize(context, pcm)
                        report.put("fileStt", JSONObject().put("complete", transcript.complete).put("error", transcript.error)
                            .put("elapsedMs", SystemClock.elapsedRealtime()-started).put("captions", JSONArray(transcript.captions.map { it.text })))
                        assertTrue("STT did not complete: ${transcript.error}", transcript.complete)
                        assertTrue("Known speech not recognized", transcript.captions.any { it.text.contains("안전벨트") || it.text.contains("드라이브") })
                    }
                    pcm.fill(0f)
                }
            }
            report.put("testCompleted", true)
        } catch (error: Throwable) {
            report.put("failure", error.toString()); throw error
        } finally {
            if (ownRecording && RecordingStore.state.busy) {
                context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                runCatching { waitFor("cleanup") { !RecordingStore.state.busy } }
            }
            runCatching { mic?.close() }
            if (previousJournal != null) journal.writeBytes(previousJournal) else journal.delete()
            MetadataEpisodes.write(File(root, "report.json"), report)
            phase("done")
        }
    }
}
