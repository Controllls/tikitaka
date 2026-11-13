package com.example.hd_project

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.awaitAnalysis
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.network.AnalysisServerUnavailable
import com.example.hd_project.momo.network.ApiClient
import com.example.hd_project.momo.storage.ProjectRepository
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BackendConnectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun response(code: Int, body: String, test: (ApiClient) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            var requestedPath: String? = null
            val serving = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream().bufferedReader()
                    requestedPath = input.readLine()
                    while (!input.readLine().isNullOrEmpty()) { /* headers */ }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            try {
                test(ApiClient(context, "http://127.0.0.1:${server.localPort}/"))
            } finally {
                serving.join(11000)
            }
            assertEquals("GET /health/ready HTTP/1.1", requestedPath)
        }
    }

    @Test fun readyServerAcceptsNewAnalysis() = response(200, """{"status":"ready","data_origin":"inference"}""") {
        runBlocking { it.requireAnalysisReady() }
    }

    @Test fun loadingServerShowsPreparationMessage() = response(503, """{"status":"not_ready"}""") {
        val error = runCatching { runBlocking { it.requireAnalysisReady() } }.exceptionOrNull()
        assertTrue(error is AnalysisServerUnavailable)
        assertTrue(error!!.message!!.contains("아직 준비되지"))
    }

    @Test fun stoppedServerShowsConnectionMessage() {
        val port = ServerSocket(0).use { it.localPort }
        val error = runCatching {
            runBlocking { ApiClient(context, "http://127.0.0.1:$port/").requireAnalysisReady() }
        }.exceptionOrNull()
        assertTrue(error is AnalysisServerUnavailable)
        assertTrue(error!!.message!!.contains("연결할 수 없어요"))
    }

    @Test fun cachedAnalysisDoesNotCreateServerWork() = runBlocking {
        val id = "offline-cache-${UUID.randomUUID()}"
        val repository = ProjectRepository(context)
        val audio = File(context.cacheDir, "$id.wav")
        try {
            instrumentation.context.assets.open("sample-3.wav").use { input ->
                audio.outputStream().use { input.copyTo(it) }
            }
            val result = instrumentation.context.assets.open("result-3.json").bufferedReader().use {
                MomoJson.decodeFromString<AnalysisResult>(it.readText())
            }.copy(voiceModelId = "test-cached-voice-v1")
            val tracks = instrumentation.context.assets.open("tracks-3.json").bufferedReader().use {
                MomoJson.decodeFromString<MotionTracks>(it.readText())
            }
            repository.save(SceneProject(id, "Offline cache test", audio.path, sha256(audio), result.durationUs))
            repository.cache(id, "http://127.0.0.1:1/", AnalysisBundle(result, tracks))
            val loaded = repository.awaitAnalysis(id, requireVoice = true) { fail("Cached analysis must not wait for server work") }
            assertEquals(result.jobId, loaded.result.jobId)
            assertNull(repository.dao.operation(id))
        } finally {
            repository.deleteLocal(id)
            audio.delete()
        }
    }
}
