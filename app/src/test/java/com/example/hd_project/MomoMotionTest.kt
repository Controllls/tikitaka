package com.example.hd_project

import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.MotionEngine
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

@Serializable private data class Reference(val timeUs: Long,val personId: String,val mouth: Double,
    val laughing: Boolean,val dx: Double,val dy: Double,val tilt: Double)
class MomoMotionTest {
    @Test fun speakingLockIncludesSilentSyllablesAndOverlappingCaptions() {
        val source = bundle()
        val analysis = source.copy(result = source.result.copy(events = emptyList(), captions = listOf(
            Caption("question", 0, 3_000_000, "왜?", listOf("S1")),
            Caption("reply", 500_000, 2_500_000, "대답", listOf("S2")))))
        val engine = MotionEngine(project().copy(rendererVersion = "tikitaka-local-story-v11"), analysis)
        (500_000L until 2_500_000L step 20_000L).forEach { time ->
            assertTrue(engine.isSpeakingAt("P2", time))
            assertEquals(0.0, engine.motionAt("P2", time).mouth, 0.0)
            assertNull(engine.gazeAt("P2", time))
        }
        assertFalse(engine.isSpeakingAt("P2", 2_500_000))
    }
    @Test fun speakingHeadNeverAlternatesWithMouthPulses() {
        val source = bundle()
        val analysis = source.copy(result = source.result.copy(
            captions = listOf(Caption("question", 0, 3_000_000, "왜?", listOf("S1"))),
            events = source.result.events.filter { it.activeSpeakerIds == listOf("S1") }))
        val engine = MotionEngine(project().copy(rendererVersion = "tikitaka-local-story-v11"), analysis)
        (0L until 3_000_000L step 20_000L).forEach { time ->
            assertNull(engine.gazeAt("P1", time))
            assertEquals("right", engine.gazeAt("P2", time))
        }
        // Random access must render the same pose as sequential playback.
        assertEquals("right", engine.gazeAt("P2", 200_000))
    }
    @Test fun briefQuestionsAndOverlappingListenersDoNotTurnHeads() {
        val source = bundle()
        fun engine(captions: List<Caption>, events: List<SoundEvent>) = MotionEngine(
            project().copy(rendererVersion = "tikitaka-local-story-v11"),
            source.copy(result = source.result.copy(captions = captions, events = events)))
        val brief = engine(listOf(Caption("short", 0, 500_000, "왜?", listOf("S1"))), emptyList())
        assertNull(brief.gazeAt("P2", 200_000))
        val overlap = engine(listOf(Caption("long", 0, 3_000_000, "왜?", listOf("S1"))),
            listOf(SoundEvent("reply", 1_000_000, 1_200_000, "speech", listOf("S2"))))
        assertNull(overlap.gazeAt("P2", 200_000))
        assertNull(overlap.gazeAt("P2", 2_000_000))
    }
    private fun text(name: String)=javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun bundle()=AnalysisBundle(MomoJson.decodeFromString<AnalysisResult>(text("result-3.json")),MomoJson.decodeFromString<MotionTracks>(text("tracks-3.json")))
    private fun project()=SceneProject("test","test","sample.wav","0".repeat(64),3_000_000,speakerMapping=mapOf("S1" to "P1","S2" to "P2"))
    @Test fun originalJsNumericalRegression() {
        val engine=MotionEngine(project(),bundle())
        val reference=MomoJson.decodeFromString<List<Reference>>(text("motion-reference.json"))
        reference.forEach { row ->
            val actual=engine.motionAt(row.personId,row.timeUs)
            assertEquals(row.mouth,actual.mouth,1e-4); assertEquals(row.laughing,actual.laughing)
            assertEquals(row.dx,actual.dx,1e-4); assertEquals(row.dy,actual.dy,1e-4); assertEquals(row.tilt,actual.tilt,1e-4)
        }
    }
    @Test fun onlyVerifiedLaugherLaughsDuringOverlap() {
        val engine=MotionEngine(project(),bundle())
        assertTrue(engine.motionAt("P1",1_650_000).laughing)
        assertFalse(engine.motionAt("P2",1_650_000).laughing)
        assertEquals(0.0,engine.motionAt("P2",1_650_000).dy,0.0)
        assertEquals(0.0,engine.motionAt("P1",3_000_000).mouth,0.0)
        assertEquals(0.0,engine.motionAt("P2",700_000).mouth,0.0)
    }
    @Test fun emptyOverrideStopsAndMultipleIsExplicit() {
        val analysis=bundle(); val event=analysis.result.events.first { it.kind=="speech" }
        val stopped=project().copy(eventOverrides=listOf(EventOverride(event.eventId,emptyList())))
        assertEquals(0.0,MotionEngine(stopped,analysis).motionAt("P1",700_000).mouth,0.0)
        val shared=project().copy(eventOverrides=listOf(EventOverride(event.eventId,listOf("P1","P2"))))
        assertTrue(MotionEngine(shared,analysis).motionAt("P2",850_000).mouth>0)
    }
}
