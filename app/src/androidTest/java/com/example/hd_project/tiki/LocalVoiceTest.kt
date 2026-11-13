package com.example.hd_project.tiki

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.LaughterAnalyzer
import com.example.hd_project.RecordingFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalVoiceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun nativeEmbeddingIsNormalizedAndStableWithoutUploading() {
        val file=File(context.cacheDir,"voice-model-${UUID.randomUUID()}.m4a")
        try {
            instrumentation.context.assets.open("momo-synthetic-korean.m4a").use { input->file.outputStream().use { input.copyTo(it) } }
            val buffer=FloatArray(16000*35);var count=0
            LaughterAnalyzer(context).decode(file,{false},{},{}) { if(count<buffer.size)buffer[count++]=it }
            val pcm=buffer.copyOf(minOf(count,160000))
            val started=android.os.SystemClock.elapsedRealtime()
            val a=LocalVoice.embedding(context,pcm);val b=LocalVoice.embedding(context,pcm)
            assertEquals(512,a.size);assertEquals(1.0,a.sumOf { it*it },.0001);assertTrue(LocalVoice.cosine(a,b)>.999)
            assertFalse(LocalVoice.isMine(context,pcm,null))
            val reference=LocalVoice.enroll(context,pcm)
            assertTrue(LocalVoice.isMine(context,pcm,reference))
            assertFalse(LocalVoice.isMine(context,pcm,reference.copy(modelId="legacy-remote-model")))
            val modelMs=android.os.SystemClock.elapsedRealtime()-started
            val name="voice-validation-${UUID.randomUUID()}"
            val repo=com.example.hd_project.momo.people.PeopleRepository(context,name)
            val profile=com.example.hd_project.momo.people.PersonProfile(UUID.randomUUID().toString(),"검증", "04-teal",reference)
            try {
                repo.save(profile);assertEquals(reference,repo.list().single().voice)
                repo.save(profile.copy(name="이름 변경",characterId="01-bear"));assertEquals(reference,repo.list().single().voice)
                try { repo.save(profile.copy(voice=reference.copy(embedding=listOf(0.0))));fail("Invalid replacement accepted") } catch(_: IllegalArgumentException) { }
                assertEquals(reference,repo.list().single().voice)
            } finally {
                File(context.noBackupFilesDir,name).deleteRecursively()
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("momo.people.$name.v1") }
            }
            File(context.cacheDir,"local-voice-validation.json").writeText(JSONObject().put("dimensions",a.size).put("sameInputCosine",LocalVoice.cosine(a,b)).put("enrollment",true).put("encryptedReplacement",true).put("modelProbeMs",modelMs).toString())
        } finally { file.delete() }
    }
    @Test fun silenceNeverEnrolled() {
        try { LocalVoice.enroll(context,FloatArray(160000));fail("Silence must not replace a voice profile") } catch(_: IllegalArgumentException) { }
    }
    @Test fun sessionsRemainRecoverableAfterStartingAnotherRecording() {
        val root=File(context.cacheDir,"journal-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated=object: ContextWrapper(context) { override fun getNoBackupFilesDir()=root }
        try {
            val first=File(RecordingFiles.directory(isolated),"first.pending")
            instrumentation.context.assets.open("tiki-synthetic-3.m4a").use { input->first.outputStream().use { input.copyTo(it) } }
            RecordingJournal.active(isolated,first,emptyList(),false,0,"recording")
            RecordingJournal.finished(isolated,null,first,false,"failed")
            val id=RecordingJournal.read(isolated)!!.getString("sessionId")
            val second=File(first.parentFile,"second.pending");first.copyTo(second)
            RecordingJournal.active(isolated,second,emptyList(),true,0,"recording")
            assertEquals(2,RecordingJournal.drafts(isolated).size)
            val restored=RecordingJournal.recover(isolated,id)
            assertTrue(RecordingJournal.duration(restored)>1000)
            assertFalse(RecordingJournal.read(isolated)!!.getBoolean("autoProductionEligible"))
            assertEquals(1,RecordingJournal.drafts(isolated).size)
            assertTrue(first.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun verifiedAndUnknownDialogueNeverAnimateBothCharacters() {
        val clip=JSONObject().put("captions",JSONArray().put(JSONObject().put("start_ms",0).put("end_ms",1000).put("text","내 대사"))
            .put(JSONObject().put("start_ms",1000).put("end_ms",2000).put("text","다른 대사"))).put("laughter",JSONArray())
        val bundle=MetadataEpisodes.bundle(clip,"voice-test",0,2_000_000,"a".repeat(64),2,List(100){.1},mapOf(0L to 0,1000L to 1))
        assertEquals(listOf("performance1"),bundle.result.captions.first().speakerIds)
        assertEquals(listOf("performance2"),bundle.result.captions.last().speakerIds)
        assertTrue(bundle.result.events.all { it.activeSpeakerIds.size<=1 })
        repeat(100) { frame->assertTrue(bundle.tracks.speakerTracks.count { it.activity[frame]>0 }<=1) }
    }
}
