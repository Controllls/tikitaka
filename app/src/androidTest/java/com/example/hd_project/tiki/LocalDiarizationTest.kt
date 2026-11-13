package com.example.hd_project.tiki

import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.LaughterAnalyzer
import com.example.hd_project.momo.model.SceneProject
import com.example.hd_project.momo.render.MotionEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalDiarizationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun analyze(file: File): DiarizationResult {
        val samples=ArrayList<Float>()
        LaughterAnalyzer(context).decode(file,{false},{},{}) { samples.add(it) }
        val pcm=samples.toFloatArray()
        return try { LocalDiarization.analyze(context,pcm) } finally { pcm.fill(0f) }
    }
    @Test fun nativeSingleVoiceAndSilenceDoNotForceTwoSpeakers() {
        val file=File(context.cacheDir,"diarization-single-test.m4a")
        instrumentation.context.assets.open("momo-synthetic-korean.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val result=analyze(file)
            assertEquals(1,result.speakerCount)
            assertTrue(result.turns.any { it.speaker==0 })
            assertEquals(0,LocalDiarization.analyze(context,FloatArray(16000*4)).speakerCount)
            MetadataEpisodes.write(File(context.cacheDir,"diarization-single-report.json"),result.json())
        } finally { file.delete() }
    }
    @Test fun nativeKnownTwoAndFourVoices() {
        // Public upstream sample stays in cache; no third-party recording is bundled in the app/test APK.
        val input=InstrumentationRegistry.getArguments().getString("diarizationFixture")
        org.junit.Assume.assumeTrue("Explicit external reference fixture required",!input.isNullOrBlank())
        val file=File(context.cacheDir,input!!).canonicalFile
        require(file.parentFile==context.cacheDir.canonicalFile && file.isFile)
        val samples=ArrayList<Float>();LaughterAnalyzer(context).decode(file,{false},{},{}) { samples.add(it) }
        val pcm=samples.toFloatArray()
        try {
            val two=LocalDiarization.analyze(context,pcm.copyOf(13600*16))
            assertEquals(2,two.speakerCount)
            assertEquals(0,two.active(5000).single().speaker)
            assertEquals(1,two.active(9000).single().speaker)
            val four=LocalDiarization.analyze(context,pcm)
            assertEquals(4,four.speakerCount)
            MetadataEpisodes.write(File(context.cacheDir,"diarization-native-report.json"),JSONObject().put("two",two.json()).put("four",four.json()))
        } finally { pcm.fill(0f) }
    }
    @Test fun speakerTurnsDriveMouthsAndTextIsNeverDuplicated() {
        val text="첫 번째 사람이 말을 합니다 두 번째 사람이 답을 합니다 함께 말해도 문장은 한번만 나옵니다"
        val result=DiarizationResult(listOf(SpeakerTurn(0,2000,0,.9),SpeakerTurn(2000,4000,1,.8),
            SpeakerTurn(4000,5000,0,.7),SpeakerTurn(4000,5000,1,.8),SpeakerTurn(5000,6000,null,.1)),2,2,10)
        val clip=JSONObject().put("captions",JSONArray().put(JSONObject().put("start_ms",1000).put("end_ms",7000).put("text",text))).put("laughter",JSONArray())
        val bundle=MetadataEpisodes.bundle(clip,"diarization-test",1000,6000000,"a".repeat(64),2,List(300){.01},diarization=result)
        assertEquals(text,bundle.result.captions.joinToString(" ") { it.text })
        assertTrue(bundle.result.captions.zipWithNext().all { (a,b)->a.endUs<=b.startUs })
        assertTrue(bundle.result.captions.all { it.speakerIds.size<=1 })
        val scene=TikiScene.buildPerformance(context,SceneProject("diarization-test","검증","unused","a".repeat(64),6000000),count=2)
        val motion=MotionEngine(scene,bundle)
        assertEquals(listOf("performance1"),motion.eventAt(1000000)!!.activeSpeakerIds)
        assertEquals(listOf("performance2"),motion.eventAt(3000000)!!.activeSpeakerIds)
        assertEquals(setOf("performance1","performance2"),motion.eventAt(4500000)!!.activeSpeakerIds.toSet())
        assertTrue(motion.eventAt(5500000)!!.activeSpeakerIds.isEmpty())
        assertTrue(motion.motionAt("P1",1000000).mouth>0)
        assertEquals(0.0,motion.motionAt("P2",1000000).mouth,0.0)
        assertEquals(0.0,motion.motionAt("P1",3000000).mouth,0.0)
        assertTrue(motion.motionAt("P2",3000000).mouth>0)
        val timeline=PlaybackDiagnostics.Timeline(PlaybackDiagnostics.build(scene,bundle,1000,.01,"partial",JSONArray(),JSONArray(),result))
        assertTrue(timeline.summary(1000,1).contains("화자 1: ● 감지됨 · 점수 0.900"))
        assertTrue(timeline.summary(1000,1).contains("화자 2: 대기"))
        assertTrue(timeline.summary(3000,1).contains("화자 2: ● 감지됨 · 점수 0.800"))
        assertTrue(timeline.summary(5500,1).contains("화자 불분명: ● 감지됨"))
        assertFalse(timeline.summary(6000,1).contains("● 감지됨"))
        assertTrue(timeline.summary(1000,1).contains("감도 0.010"))
    }
}
