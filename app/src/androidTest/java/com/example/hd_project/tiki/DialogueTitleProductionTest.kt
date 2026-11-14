package com.example.hd_project.tiki

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.AnimationFiles
import com.example.hd_project.RecordingFiles
import com.example.hd_project.RecordingStore
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in integration check: runs the worker with text rules, retaining the new video and all old ones. */
class DialogueTitleProductionTest {
    @Test fun regenerateWithAnAnalyzedTitle(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val name=InstrumentationRegistry.getArguments().getString("titleSource")
        assumeTrue("Explicit titleSource is required; never touch user media in the default suite",!name.isNullOrBlank())
        val context=instrumentation.targetContext
        check(!RecordingStore.state.busy) { "Do not interrupt a user recording" }
        val source=File(RecordingFiles.directory(context),requireNotNull(name)).canonicalFile
        check(source.parentFile==RecordingFiles.directory(context).canonicalFile && source.isFile)
        val audioHash=sha256(source)
        val existing=AnimationFiles.directory(context).listFiles().orEmpty().filter { it.extension=="mp4" }.associateWith(::sha256)
        EpisodeWork.enqueue(context,source,retry=true,recreate=true,reanalyze=false)
        withTimeout(20*60_000L) {
            var last=""
            while(true) {
                val row=requireNotNull(EpisodeWork.status(context,source.name))
                val message=row.optString("message")
                if(message!=last) {
                    instrumentation.sendStatus(0,Bundle().apply { putString("stream","\n$message\n") });last=message
                }
                check(row.optString("state")!="failed") { message }
                if(row.optString("state")=="completed")break
                delay(1000)
            }
        }
        val movie=requireNotNull(EpisodeWork.latestMovie(context,source.name))
        assertFalse("Must preserve old movies and add a new version",movie in existing)
        val sidecar=JSONObject(File(movie.path+".json").readText())
        val story=sidecar.getJSONObject("story")
        assertEquals("extracted",story.getString("status"))
        assertEquals(DialogueStoryRules.VERSION,story.getString("version"))
        assertEquals(DialogueStoryRules.MODEL,story.getString("model"))
        assertFalse(File(context.noBackupFilesDir,"story-models").listFiles().orEmpty().any { it.extension=="gguf" || it.extension=="download" })
        assertEquals(story.getString("title"),sidecar.getString("title"))
        VideoComposer.validateMp4(movie,sidecar.getInt("width"),sidecar.getInt("height"))
        assertEquals(audioHash,sha256(source))
        existing.forEach { (file,hash) -> assertEquals("Existing video changed: ${file.name}",hash,sha256(file)) }
        val report=File(context.filesDir,"tikitaka-validation/title-production.json")
        report.parentFile!!.mkdirs()
        report.writeText(JSONObject().put("movie",movie.name).put("story",story)
            .put("preservedVideos",existing.size).put("sourcePreserved",true).toString())
    }
}
