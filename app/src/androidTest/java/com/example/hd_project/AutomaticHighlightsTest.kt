package com.example.hd_project

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.hd_project.momo.network.ApiClient
import com.example.hd_project.momo.storage.MomoDatabase
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomaticHighlightsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun stoppedServerKeepsOneDurableRequestAndOriginal() = runBlocking {
        val previousSource = AutomaticHighlights.latestSource(context)
        val name = "auto-offline-${UUID.randomUUID()}_3000.m4a"
        val file = File(RecordingFiles.directory(context),name)
        val dao = MomoDatabase.get(context).dao()
        val endpoint = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/"
        try {
            instrumentation.context.assets.open("momo-synthetic-korean.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
            val before = sha256(file)
            AutomaticHighlights.recordingSaved(context,file,endpoint)
            val first = dao.highlight(name)!!
            AutomaticHighlights.recordingSaved(context,file,endpoint)
            assertEquals(first.idempotencyKey,dao.highlight(name)!!.idempotencyKey)
            val error = runCatching { AutomaticHighlights.syncOnce(context,name) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(AutomaticHighlights.failed(context,name,error as Exception,0))
            assertEquals("waiting",dao.highlight(name)!!.state)
            assertEquals(first.idempotencyKey,dao.highlight(name)!!.idempotencyKey)
            assertEquals(before,sha256(file))
            assertNull(dao.highlight(name)!!.jobId)
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork(AutomaticHighlights.workName(name)).result.get()
            dao.deleteHighlight(name); file.delete()
            context.getSharedPreferences("automatic-highlights",0).edit().putString("latest",previousSource)
                .remove("state:$name").remove("message:$name").commit()
        }
    }

    @Test fun manualSearchControlsAreRemoved() {
        instrumentation.runOnMainSync {
            val themed = android.view.ContextThemeWrapper(context, R.style.Theme_HD_project)
            val screen = android.view.LayoutInflater.from(themed).inflate(R.layout.activity_highlights, null)
            assertEquals(0,context.resources.getIdentifier("analyze_highlights","id",context.packageName))
            assertEquals(0,context.resources.getIdentifier("cancel_analysis","id",context.packageName))
            assertNotNull(screen.findViewById<android.view.View>(R.id.highlight_rows))
        }
    }

    @Test fun savedRecordingAutomaticallyRunsRealServerAndDownloadsPlayableClips() = runBlocking {
        val name = "auto-highlight-validation-${UUID.randomUUID()}_25267.m4a"
        val file = File(RecordingFiles.directory(context),name)
        instrumentation.context.assets.open("auto-highlight-synthetic.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        val before = sha256(file)
        AutomaticHighlights.recordingSaved(context,file)
        val dao = MomoDatabase.get(context).dao()
        val firstKey = dao.highlight(name)!!.idempotencyKey
        AutomaticHighlights.recordingSaved(context,file)
        val deadline = SystemClock.elapsedRealtime()+900_000
        var reported = 0L
        while (SystemClock.elapsedRealtime()<deadline) {
            val request = dao.highlight(name)!!
            if(request.state in listOf("completed","failed"))break
            if(SystemClock.elapsedRealtime()-reported>30_000) {
                reported=SystemClock.elapsedRealtime()
                instrumentation.sendStatus(2,Bundle().apply { putString("stream","Automatic highlights: ${request.state}\n") })
            }
            SystemClock.sleep(1000)
        }
        val request = dao.highlight(name)!!
        assertEquals(request.message,"completed",request.state)
        assertEquals(firstKey,request.idempotencyKey)
        assertEquals(before,sha256(file))
        val batch = HighlightStore.batches(context).single { it.sourceId == name }
        assertTrue("Synthetic speech and laughter should yield a topic clip",batch.clips.isNotEmpty())
        val api = ApiClient(context,BuildConfig.MOMO_API_URL).api
        val result = api.highlightResult(request.jobId!!)
        assertEquals("inference",result.dataOrigin)
        assertEquals(.1,result.threshold,0.0)
        assertEquals(before,result.audioSha256)
        batch.clips.forEachIndexed { index, clip ->
            assertEquals(result.clips[index].sha256,sha256(clip.file))
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(clip.file.path)
                assertEquals("yes",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            }
        }
        AutomaticHighlights.recordingSaved(context,file)
        assertEquals(request.jobId,dao.highlight(name)!!.jobId)
        assertEquals(1,HighlightStore.batches(context).count { it.sourceId == name })
    }

    @Test fun completedServerJobRepairsInterruptedDownloadWithoutReupload() = runBlocking {
        val dao = MomoDatabase.get(context).dao()
        val source = RecordingFiles.list(context).firstOrNull { it.file.name.startsWith("auto-highlight-validation-") }?.file
        org.junit.Assume.assumeNotNull(source)
        context.getSharedPreferences("automatic-highlights",0).edit().putString("latest",source!!.name).commit()
        val initial = dao.highlight(source.name)!!
        val result = ApiClient(context,initial.serverUrl).api.highlightResult(initial.jobId!!)
        assertEquals("inference",result.dataOrigin)
        assertEquals(sha256(source),result.audioSha256)
        WorkManager.getInstance(context).cancelUniqueWork(AutomaticHighlights.workName(source.name)).result.get()
        dao.putHighlight(initial.copy(state="running"))
        AutomaticHighlights.recordingSaved(context,source)
        val firstDeadline = SystemClock.elapsedRealtime()+60_000
        while (SystemClock.elapsedRealtime()<firstDeadline && dao.highlight(source.name)!!.state != "completed") {
            SystemClock.sleep(250)
        }
        assertEquals("completed",dao.highlight(source.name)!!.state)
        val completed = HighlightStore.batches(context).firstOrNull {
            it.sourceId == source.name && it.clips.isNotEmpty()
        }
        assertNotNull(completed)
        val batch = completed!!
        val request = dao.highlight(batch.sourceId)!!
        val file = batch.clips.first().file
        val original = file.readBytes()
        WorkManager.getInstance(context).cancelUniqueWork(AutomaticHighlights.workName(batch.sourceId)).result.get()
        try {
            // Only this suite's synthetic clip is changed. A committed index must not hide corruption.
            file.writeBytes(byteArrayOf(0))
            dao.putHighlight(request.copy(state="running"))
            AutomaticHighlights.recordingSaved(context,File(RecordingFiles.directory(context),batch.sourceId))
            val deadline = SystemClock.elapsedRealtime()+60_000
            while (SystemClock.elapsedRealtime()<deadline && dao.highlight(batch.sourceId)!!.state != "completed") {
                SystemClock.sleep(250)
            }
            val restored = dao.highlight(batch.sourceId)!!
            assertEquals("completed",restored.state)
            assertEquals(request.jobId,restored.jobId)
            assertEquals(request.idempotencyKey,restored.idempotencyKey)
            assertArrayEquals(original,file.readBytes())
            assertEquals(result.clips.first().sha256,sha256(file))
            android.media.MediaMetadataRetriever().use { media ->
                media.setDataSource(file.path)
                assertEquals("yes",media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            }
            assertEquals(1,HighlightStore.batches(context).count { it.sourceId == batch.sourceId })
        } finally {
            if (!file.isFile || !file.readBytes().contentEquals(original)) file.writeBytes(original)
        }
    }

    @Test fun savedSilenceCompletesAutomaticallyWithNoClips() = runBlocking {
        val previousSource = AutomaticHighlights.latestSource(context)
        val file = File(RecordingFiles.directory(context),"auto-silence-${UUID.randomUUID()}_8000.m4a")
        val dao = MomoDatabase.get(context).dao()
        instrumentation.context.assets.open("silence_8000.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            AutomaticHighlights.recordingSaved(context,file)
            val key = dao.highlight(file.name)!!.idempotencyKey
            val deadline = SystemClock.elapsedRealtime()+120_000
            while (SystemClock.elapsedRealtime()<deadline && dao.highlight(file.name)!!.state !in listOf("completed","failed")) {
                SystemClock.sleep(500)
            }
            val request = dao.highlight(file.name)!!
            assertEquals(request.message,"completed",request.state)
            assertEquals(key,request.idempotencyKey)
            val result = ApiClient(context,request.serverUrl).api.highlightResult(request.jobId!!)
            assertEquals("inference",result.dataOrigin)
            assertEquals(sha256(file),result.audioSha256)
            assertTrue(result.clips.isEmpty())
            assertTrue(HighlightStore.batches(context).single { it.sourceId == file.name }.clips.isEmpty())
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork(AutomaticHighlights.workName(file.name)).result.get()
            dao.highlight(file.name)?.jobId?.let { job ->
                val folder = File(context.noBackupFilesDir,"highlights/server-$job")
                File(folder,"index.json").delete()
                folder.delete()
            }
            dao.deleteHighlight(file.name)
            file.delete()
            context.getSharedPreferences("automatic-highlights",0).edit().putString("latest",previousSource)
                .remove("state:${file.name}").remove("message:${file.name}").commit()
        }
    }
}
