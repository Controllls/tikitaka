package com.example.hd_project

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.people.*
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.sha256
import com.example.hd_project.tiki.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerEpisodesTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext

    @Test fun castMatchesSelfLocallyAndSendsNoVoiceVector() {
        val vector=List(192) { if(it==0)1.0 else 0.0 }
        val voice=VoiceReference("same-model",vector,8_000_000,"fixture",0)
        val profile=PersonProfile("00000000-0000-0000-0000-000000000001","나","06-fox",voice)
        val source=JSONObject().put("voice_model_id","same-model")
            .put("speakers",JSONArray().put(JSONObject().put("speaker_id","S1").put("first_seen_us",0))
                .put(JSONObject().put("speaker_id","S2").put("first_seen_us",5_000_000)))
            .put("speaker_signatures",JSONArray().put(JSONObject().put("speaker_id","S2").put("embedding",JSONArray(vector))
                .put("speech_us",8_000_000).put("segments",JSONArray().put(JSONObject().put("start_us",5_000_000).put("end_us",13_000_000)))))
        val cast=EpisodeCast.build(source,listOf(profile))
        assertEquals(setOf("people"),cast.keys().asSequence().toSet())
        val self=cast.getJSONArray("people").getJSONObject(0)
        assertEquals(setOf("speaker","name","character"),self.keys().asSequence().toSet())
        assertEquals("S2",self.getString("speaker"));assertEquals("나",self.getString("name"));assertEquals("06-fox",self.getString("character"))
        source.put("voice_model_id","different-model")
        val unknown=EpisodeCast.build(source,listOf(profile)).getJSONArray("people")
        assertTrue((0 until unknown.length()).all { unknown.getJSONObject(it).getString("name").startsWith("함께한 사람") })
    }

    @Test fun realServerProductionResumesOneJobAndPublishesVerifiedMovie() = runBlocking {
        val sourceName=InstrumentationRegistry.getArguments().getString("episodeSource")
        assumeTrue("Opt-in real inference with an explicitly imported test recording",sourceName!=null)
        check(!RecordingStore.state.busy)
        val source=File(RecordingFiles.directory(context),sourceName!!)
        require(source.canonicalFile.parentFile==RecordingFiles.directory(context).canonicalFile && source.isFile)
        val originalHash=sha256(source)
        EpisodeWork.enqueue(context,source)
        val end=SystemClock.elapsedRealtime()+15*60_000
        while(EpisodeWork.status(context,source.name)?.has("analysisJobId")!=true && SystemClock.elapsedRealtime()<end) {
            assertNotEquals("failed",EpisodeWork.status(context,source.name)?.optString("state"));delay(500)
        }
        val firstId=EpisodeWork.status(context,source.name)!!.getString("analysisJobId")
        WorkManager.getInstance(context).cancelUniqueWork("tikitaka-"+EpisodeWork.id(source.name)).result.get()
        delay(1500)
        EpisodeWork.enqueue(context,source)
        while(EpisodeWork.status(context,source.name)?.optString("state") !in listOf("completed","empty","failed") && SystemClock.elapsedRealtime()<end)delay(1000)
        val state=EpisodeWork.status(context,source.name)!!
        assertEquals(state.optString("message"),"completed",state.getString("state"))
        assertEquals(firstId,state.getString("analysisJobId"))
        assertEquals(originalHash,sha256(source))
        val movies=state.getJSONArray("movies")
        assertTrue(movies.length()>0)
        for(i in 0 until movies.length()) {
            val movie=File(AnimationFiles.directory(context),movies.getString(i))
            VideoComposer.validateMp4(movie)
            assertEquals(sha256(movie),JSONObject(File(movie.path+".json").readText()).getString("video"))
            assertTrue(File(movie.path+".captions.json").length()>2)
            assertTrue(File(movie.path+".poster.png").length()>0)
        }
        val renderId=state.getString("renderJobId")
        EpisodeWork.enqueue(context,source,true)
        assertEquals(renderId,EpisodeWork.status(context,source.name)!!.getString("renderJobId"))
        val report=File(context.filesDir,"tikitaka-validation/server-episodes.json")
        report.parentFile!!.mkdirs();report.writeText(state.put("originalSha256",originalHash).put("resumedSameJob",true).toString(2))
    }
}
