package com.example.hd_project

import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.CharacterAction
import com.example.hd_project.momo.render.MotionEngine
import org.junit.Assert.*
import org.junit.Test

class HeadTurnPolicyTest {
    private fun question(id: String,start: Long,end: Long,speaker: String) = Caption(id,start,end,"어디로 갈까요?",listOf(speaker))
    private fun engine(captions: List<Caption>,events: List<SoundEvent> = emptyList()): MotionEngine {
        val duration=12_000_000L;val frames=600
        val speakers=(1..4).map { "S$it" }
        val scene=SceneProject("turn-test","test","unused","a".repeat(64),duration,
            people=SeatIds.mapIndexed { i,seat -> Person("P${i+1}","person",seat) },
            speakerMapping=speakers.mapIndexed { i,id -> id to "P${i+1}" }.toMap(),rendererVersion="tikitaka-local-story-v12")
        val result=AnalysisResult(jobId="turn-test",durationUs=duration,dataOrigin="inference",
            audio=AudioInfo("a".repeat(64),"audio/mp4"),speakers=speakers.map { Speaker(it,0) },
            events=events,captions=captions,tracks=TrackRef("unused",frameCount=frames))
        val tracks=MotionTracks(jobId="turn-test",durationUs=duration,frameCount=frames,
            mixedVoiceEnergy=List(frames){.01},speakerTracks=speakers.map { SpeakerTrack(it,List(frames){1.0},List(frames){1.0}) })
        return MotionEngine(scene,AnalysisBundle(result,tracks))
    }

    @Test fun speakingAndTurningAreExclusiveEvenWhenTheMouthCloses() {
        val engine=engine(listOf(question("q",0,3_000_000,"S1"),
            Caption("reply",500_000,2_500_000,"대답",listOf("S2"))))
        for(time in 0L until 3_000_000 step 20_000) {
            val speaking=engine.motionAt("P1",time)
            assertEquals(CharacterAction.SPEAKING,speaking.action)
            assertNull(speaking.gaze);assertEquals("neutral",speaking.sprite("left"))
            val listener=engine.motionAt("P2",time)
            // Reject the whole reaction rather than turning, talking, and turning again.
            assertNull(listener.gaze)
            if(time in 500_000L until 2_500_000L)assertEquals(CharacterAction.SPEAKING,listener.action)
        }
    }

    @Test fun aFullSecondIsRequiredAfterThePreviousTurnEnds() {
        for(gap in listOf(999_999L,1_000_000L)) {
            val start=1_500_000+gap
            val engine=engine(listOf(question("first",0,1_500_000,"S1"),question("next",start,start+1_500_000,"S2")))
            assertEquals("right",engine.motionAt("P4",500_000).gaze)
            assertNull(engine.motionAt("P4",1_500_000).gaze)
            assertNull(engine.motionAt("P4",2_499_999).gaze)
            val next=engine.motionAt("P4",start+100_000)
            if(gap<1_000_000)assertNull(next.gaze) else assertEquals("left",next.gaze)
        }
    }

    @Test fun cooldownIsPerCharacterAndDoesNotBlockSpeech() {
        val engine=engine(listOf(question("first",0,1_500_000,"S1"),question("next",2_200_000,3_700_000,"S2")))
        assertEquals("right",engine.gazeAt("P4",500_000))
        assertNull(engine.gazeAt("P4",2_300_000))
        assertEquals("left",engine.gazeAt("P1",2_300_000))
        assertEquals(CharacterAction.SPEAKING,engine.motionAt("P2",2_300_000).action)
        assertNull(engine.motionAt("P2",2_300_000).gaze)
    }

    @Test fun sameDirectionCannotRestartDuringCooldownAndSeekCannotBypassIt() {
        val engine=engine(listOf(question("first",0,1_500_000,"S1"),
            question("blocked",2_300_000,3_800_000,"S1"),question("allowed",5_000_000,6_500_000,"S1")))
        val times=(0L until 8_000_000 step 20_000).toList()
        val sequential=times.associateWith { engine.motionAt("P4",it) }
        assertEquals("right",sequential.getValue(500_000).gaze)
        assertNull(sequential.getValue(2_400_000).gaze)
        assertEquals("neutral",sequential.getValue(2_400_000).sprite("right"))
        assertEquals("right",sequential.getValue(5_500_000).gaze)
        times.reversed().forEach { assertEquals(sequential[it],engine.motionAt("P4",it)) }
    }

    @Test fun laughterAlsoWinsOverHeadTurning() {
        val engine=engine(listOf(question("question",0,3_000_000,"S1")),
            listOf(SoundEvent("laugh",1_000_000,1_500_000,"laughter",listOf("S2"),laughingSpeakerIds=listOf("S2"))))
        val motion=engine.motionAt("P2",1_200_000)
        assertEquals(CharacterAction.SPEAKING,motion.action)
        assertTrue(motion.laughing);assertEquals("happy",motion.sprite("right"));assertNull(motion.gaze)
        assertNull(engine.gazeAt("P2",200_000));assertNull(engine.gazeAt("P2",2_000_000))
    }
}
