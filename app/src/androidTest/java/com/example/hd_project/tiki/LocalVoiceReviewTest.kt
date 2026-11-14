package com.example.hd_project.tiki

import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.LaughterAnalyzer
import com.example.hd_project.momo.people.PeopleRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local review. Reads saved audio/profile; writes only score reports in cache. */
class LocalVoiceReviewTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext

    @Test fun reviewExplicitSavedMoviesWithoutChangingRecordingsOrEnrollment() {
        val names=InstrumentationRegistry.getArguments().getString("voiceReviewMovies")
        assumeTrue("Explicit saved movie names required",!names.isNullOrBlank())
        val root=File(context.noBackupFilesDir,"momo-videos").canonicalFile
        val profile=TikiProfile.get(context)
        val report=JSONArray()
        names!!.split(',').forEach { name ->
            val movie=File(root,name).canonicalFile
            require(movie.parentFile==root && movie.isFile && movie.extension=="mp4")
            val saved=JSONObject(PlaybackDiagnostics.file(movie).readText())
            val original=saved.getJSONObject("diarization")
            val input=File(context.noBackupFilesDir,"metadata-episodes").walkTopDown()
                .firstOrNull { it.isFile && it.name==movie.nameWithoutExtension+".m4a" }
            require(input!=null) { "Original prepared clip required for an exact comparison" }
            val buffer=FloatArray(121*16000);var count=0
            LaughterAnalyzer(context).decode(input,{false},{},{}) { if(count<buffer.size)buffer[count++]=it }
            val pcm=buffer.copyOf(count);buffer.fill(0f)
            try {
                val source=JSONObject(File(movie.path+".json").readText()).getString("source")
                val snapshot=PeopleRepository(context,"tikitaka-job-"+EpisodeWork.id(source)).list().firstOrNull()
                val result=LocalDiarization.analyze(context,pcm,profile.voice)
                val checks=(0 until result.voice.length()).map { result.voice.getJSONObject(it) }.distinctBy { it.getInt("speaker") }
                assertTrue(checks.none { it.getString("reason")=="inference_error" })
                report.put(JSONObject().put("movie",name).put("enrolled",profile.voice!=null)
                    .put("audio","original-prepared-clip").put("enrollmentSeconds",(profile.voice?.speechUs ?: 0)/1_000_000.0)
                    .put("sameVoiceAsJobSnapshot",profile.voice==snapshot?.voice)
                    .put("previousSelf",original.opt("selfSpeaker")).put("selfSpeaker",result.selfSpeaker ?: JSONObject.NULL)
                    .put("checks",JSONArray(checks)).put("diarization",result.json()))
            } finally { pcm.fill(0f) }
        }
        assertEquals(profile,TikiProfile.get(context))
        File(context.cacheDir,"local-voice-review.json").writeText(report.toString(2))
    }

    @Test fun knownSpeakerMatchesHeldOutSpeechAndRejectsOtherSpeaker() {
        val fixture=InstrumentationRegistry.getArguments().getString("diarizationFixture")
        assumeTrue("Explicit upstream four-speaker fixture required",!fixture.isNullOrBlank())
        val file=File(context.cacheDir,fixture!!).canonicalFile
        require(file.parentFile==context.cacheDir.canonicalFile && file.isFile)
        val buffer=FloatArray(60*16000);var count=0
        LaughterAnalyzer(context).decode(file,{false},{},{}) { if(count<buffer.size)buffer[count++]=it }
        val pcm=buffer.copyOf(count);buffer.fill(0f)
        fun speech(vararg ranges: LongRange): FloatArray = LocalDiarization.isolatedPcm(pcm,
            ranges.map { SpeakerTurn(it.first,it.last+1,0,.9) },0)
        val enrollment=speech(638L until 2022,2325L until 3979,4418L until 6848)
        val heldOut=speech(22154L until 24837,52545L until 54587)
        val other=speech(7017L until 8097,8535L until 10695,11472L until 13547)
        try {
            val reference=LocalVoice.enroll(context,enrollment)
            val positive=LocalVoice.verify(context,heldOut,reference)
            val negative=LocalVoice.verify(context,other,reference)
            val checks=mapOf(0 to negative,1 to positive)
            File(context.cacheDir,"local-voice-held-out.json").writeText(JSONObject()
                .put("sameSpeaker",positive.score ?: JSONObject.NULL).put("otherSpeaker",negative.score ?: JSONObject.NULL)
                .put("selectedSpeaker",LocalDiarization.selectSelf(checks) ?: JSONObject.NULL).toString())
            assertTrue("Held-out same voice: $positive",positive.mine)
            assertFalse("Different voice: $negative",negative.mine)
            assertEquals(1,LocalDiarization.selectSelf(checks))
        } finally { pcm.fill(0f);enrollment.fill(0f);heldOut.fill(0f);other.fill(0f) }
    }
}
