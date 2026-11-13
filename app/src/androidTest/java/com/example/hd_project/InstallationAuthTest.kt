package com.example.hd_project

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.network.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.storage.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class InstallationAuthTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tokens(access: String) = """{"access_token":"$access","refresh_token":"refresh-$access","token_type":"Bearer","expires_in":900}"""
    private val page = """{"items":[],"next_cursor":null}"""

    private data class Request(val path: String, val authorization: String?, val body: ByteArray)
    private class Server(val reply: (Request) -> Pair<Int, String>) : Closeable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/auth-test-${UUID.randomUUID()}/"
        val requests = CopyOnWriteArrayList<Request>()
        @Volatile var failure: Throwable? = null
        private val serving = thread {
            try {
                while (!socket.isClosed) socket.accept().use { connection ->
                    connection.soTimeout = 15000
                    val input = connection.getInputStream().buffered()
                    fun line(): String = buildString {
                        while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) append(byte.toChar()) }
                    }
                    val path = line().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) { val header = line(); if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var offset = 0
                    while (offset < body.size) { val count = input.read(body, offset, body.size-offset); check(count > 0); offset += count }
                    val request = Request(path, headers["authorization"], body)
                    requests.add(request)
                    val (code, json) = reply(request)
                    val bytes = json.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (e: Throwable) { if (e !is SocketException || !socket.isClosed) failure = e }
        }
        override fun close() { socket.close(); serving.join(16000); failure?.let { throw AssertionError("Test HTTP server failed", it) } }
    }

    @Test fun firstUseAndConcurrentClientsShareOneEncryptedIdentity() = runBlocking {
        Server { if (it.path.endsWith("/installation")) 200 to tokens("first") else 200 to page }.use { server ->
            coroutineScope { (1..6).map { async(Dispatchers.IO) { ApiClient(context, server.url).api.list() } }.awaitAll() }
            assertEquals(1, server.requests.count { it.path.endsWith("/installation") })
            val store = InstallationStore(context, server.url)
            val original = store.read()
            assertEquals(original, InstallationStore(context, server.url).read())
            assertEquals(original.credentials.installationId, ApiClient(context, server.url).installationId)
            assertTrue(original.credentials.installationSecret.matches(Regex("[a-f0-9]{64}")))
            val payload = JSONObject(server.requests.first().body.decodeToString())
            assertEquals(original.credentials.installationId, payload.getString("installation_id"))
            assertTrue(server.requests.filter { it.path.endsWith("/analyses") }.all { it.authorization == "Bearer first" })
            assertNotEquals(original.credentials, InstallationStore(context, server.url + "another/").read().credentials)
            val encryptedFiles = context.noBackupFilesDir.listFiles()!!.filter { it.name.startsWith("momo-installation-") }
            assertTrue(encryptedFiles.isNotEmpty())
            assertTrue(encryptedFiles.none { it.readBytes().decodeToString().contains(original.credentials.installationSecret) })
        }
    }

    @Test fun concurrentExpiredRequestsRotateRefreshTokenOnlyOnce() = runBlocking {
        Server { request -> when {
            request.path.endsWith("/refresh") -> 200 to tokens("rotated")
            request.authorization == "Bearer rotated" -> 200 to page
            else -> 401 to "{}"
        } }.use { server ->
            val store = InstallationStore(context, server.url)
            store.saveTokens(Tokens("expired", "original-refresh", "Bearer", 900))
            coroutineScope { (1..6).map { async(Dispatchers.IO) { ApiClient(context, server.url).api.list() } }.awaitAll() }
            assertEquals(1, server.requests.count { it.path.endsWith("/refresh") })
            assertEquals(0, server.requests.count { it.path.endsWith("/installation") })
            assertEquals("rotated", store.read().tokens!!.accessToken)
        }
    }

    @Test fun expiredRefreshAutomaticallyAuthenticatesSameInstallation() = runBlocking {
        Server { request -> when {
            request.path.endsWith("/installation") -> 200 to tokens("recovered")
            request.authorization == "Bearer recovered" -> 200 to page
            else -> 401 to "{}"
        } }.use { server ->
            val store = InstallationStore(context, server.url)
            val original = store.read().credentials
            store.saveTokens(Tokens("expired", "expired-refresh", "Bearer", 900))
            ApiClient(context, server.url).api.list()
            assertEquals(original, store.read().credentials)
            assertEquals("recovered", store.read().tokens!!.accessToken)
            val request = server.requests.single { it.path.endsWith("/installation") }
            assertEquals(original.installationId, JSONObject(request.body.decodeToString()).getString("installation_id"))
        }
    }

    @Test fun failedRegistrationRetainsIdentityForRetry() = runBlocking {
        val attempts = AtomicInteger()
        Server { if (attempts.incrementAndGet() == 1) 503 to "{}" else 200 to tokens("retried") }.use { server ->
            val first = ApiClient(context, server.url)
            val failure = runCatching { first.ensureAuthenticated() }.exceptionOrNull()
            assertTrue(failure is InstallationAuthenticationException)
            assertNull(InstallationStore(context, server.url).read().tokens)
            ApiClient(context, server.url).ensureAuthenticated()
            assertArrayEquals(server.requests[0].body, server.requests[1].body)
        }
    }

    @Test fun revokedSessionDoesNotCreateAnotherInstallation() = runBlocking {
        Server { if (it.path.endsWith("/refresh")) 403 to "{}" else 401 to "{}" }.use { server ->
            val store = InstallationStore(context, server.url)
            val original = store.read().credentials
            store.saveTokens(Tokens("revoked", "revoked-refresh", "Bearer", 900))
            val failure = runCatching { ApiClient(context, server.url).api.list() }.exceptionOrNull()
            assertTrue(failure is InstallationAuthenticationException)
            assertEquals(403, (failure as InstallationAuthenticationException).statusCode)
            assertEquals(original, store.read().credentials)
            assertEquals(0, server.requests.count { it.path.endsWith("/installation") })
        }
    }

    @Test fun legacyPendingJobReuploadsOriginalUnderNewInstallation() = runBlocking {
        val id = "auth-upgrade-${UUID.randomUUID()}"
        val repository = ProjectRepository(context)
        val audio = File(context.cacheDir, "$id.wav").apply { writeBytes(ByteArray(100)) }
        val job = """{"job_id":"new-job","status":"queued","stage":"queued","progress":0,"created_at":"2026-11-06T00:00:00Z","updated_at":"2026-11-06T00:00:00Z","error":null}"""
        Server { if (it.path.endsWith("/installation")) 200 to tokens("new-install") else 200 to job }.use { server ->
            try {
                repository.save(SceneProject(id, "Upgrade test", audio.path, sha256(audio), 1000000))
                repository.dao.putOperation(PendingOperation(id, "old-operation", "old-key", server.url,
                    MomoJson.encodeToString(AnalysisOptions()), jobId="old-shared-job"))
                assertFalse(repository.syncOnce(id))
                val pending = repository.dao.operation(id)!!
                assertEquals("new-job", pending.jobId)
                assertNotEquals("old-key", pending.idempotencyKey)
                assertEquals(ApiClient(context, server.url).installationId, pending.installationId)
                assertTrue(server.requests.none { it.path.contains("old-shared-job") })
                assertTrue(audio.exists())
            } finally { repository.deleteLocal(id); audio.delete() }
        }
    }
}
