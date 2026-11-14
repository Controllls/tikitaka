package com.example.hd_project.tiki

import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.*
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit developer action only: never edits a user recording in the normal test suite. */
class RebuildLocalEpisodeTest {
    @Test fun explicitlyRequestedCachedRecording()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("rebuildSource")
        assumeTrue("Requires an explicit recording name",!name.isNullOrBlank())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(!RecordingStore.state.busy)
        val source=File(RecordingFiles.directory(context),name!!).canonicalFile
        require(source.parentFile==RecordingFiles.directory(context).canonicalFile && source.isFile)
        val job=EpisodeWork.status(context,name)!!
        require(job.optString("state") in listOf("completed","empty","failed"))
        val metadata=File(job.getString("localMetadata"));require(metadata.isFile && File(metadata.parentFile,"plan.json").isFile)
        val before=sha256(source)
        val existing=AnimationFiles.directory(context).listFiles().orEmpty().filter { it.extension=="mp4" }.associateWith { sha256(it) }
        val requestedThreshold=InstrumentationRegistry.getArguments().getString("rebuildThreshold")?.toDouble()
        requestedThreshold?.let { MetadataEpisodes.threshold(context,it) }
        EpisodeWork.patch(context,name) { it.put("castCount",2).put("clipSeconds",40)
            if(requestedThreshold!=null)it.put("laughterThreshold",requestedThreshold).put("localAttempt",it.optInt("localAttempt")+1)
            it.put("renderGeneration",it.optInt("renderGeneration")+1).put("state","queued") }
        val started=System.currentTimeMillis()
        MetadataEpisodes.run(context,source)
        assertEquals(before,sha256(source))
        existing.forEach { (file,hash)->assertEquals("Existing video changed: ${file.name}",hash,sha256(file)) }
        val result=EpisodeWork.status(context,name)!!
        assertEquals("completed",result.getString("state"))
        val added=AnimationFiles.directory(context).listFiles().orEmpty().filter { it.extension=="mp4" && it !in existing }
        assertTrue(added.isNotEmpty())
        added.forEach { movie ->
            val info=JSONObject(File(movie.path+".json").readText())
            assertTrue(info.getInt("castCount") in 2..4)
            assertEquals(ProductionSettings.RENDERER,info.getString("renderer"))
            assertTrue(info.has("diarization"))
            com.example.hd_project.momo.render.VideoComposer.validateMp4(movie,1280,720)
            val frames=JSONObject(PlaybackDiagnostics.file(movie).readText()).getJSONArray("frames")
            val lastDirection=mutableMapOf<Int,String>()
            val returnedFront=mutableMapOf<Int,Long>()
            repeat(frames.length()) { frameIndex ->
                val frame=frames.getJSONObject(frameIndex);val time=frame.getLong("timeMs")
                val actors=frame.getJSONArray("actors")
                repeat(actors.length()) { person ->
                    val actor=actors.getJSONObject(person);val direction=actor.getString("gaze")
                    if(direction!="front") {
                        assertEquals("turning",actor.getString("action"))
                        assertFalse(actor.getBoolean("speech"));assertFalse(actor.getBoolean("smile"))
                        assertEquals(0.0,actor.getDouble("mouth"),0.0)
                        if(lastDirection[person] in listOf(null,"front"))returnedFront[person]?.let { end ->
                            assertTrue("Head turn cooldown violated at $time ms",time-end>=1000)
                        } else assertEquals("A head cannot change direction without cooldown",lastDirection[person],direction)
                    } else if(lastDirection[person] !in listOf(null,"front"))returnedFront[person]=time
                    lastDirection[person]=direction
                }
            }
            if(requestedThreshold!=null)assertEquals(requestedThreshold,JSONObject(PlaybackDiagnostics.file(movie).readText()).getDouble("threshold"),.00001)
        }
        MetadataEpisodes.write(File(context.cacheDir,"cast-rebuild-report.json"),JSONObject().put("state",result)
            .put("sourceHashPreserved",true).put("existingVideosPreserved",existing.size).put("elapsedMs",System.currentTimeMillis()-started)
            .put("newVideos",JSONArray(added.map { it.name })))
    }
}
