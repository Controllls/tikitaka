package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test

class LocalLaughterTest {
    @Test fun weakMultiLabelLaughterNeedsBothViewsAndConsecutiveEvidence() {
        val raw=List(2) { AcousticScore(.035,.003,.015,.08,2) }
        val band=List(2) { AcousticScore(.05,.003,.015,.1,2) }
        assertTrue(LaughDecision.accepted(raw,band,.1).all { it==0.0 })
        assertTrue(LaughDecision.enhanced(raw,band,raw,band,List(2){.01},.1).values.all { it>0 })
        assertTrue(LaughDecision.enhanced(raw.take(1),band.take(1),raw.take(1),band.take(1),listOf(.01),.1).values.all { it==0.0 })
        val cough=raw.map { it.copy(cough=.2,noise=.2) }
        assertTrue(LaughDecision.enhanced(cough,band,cough,band,List(2){.01},.1).values.all { it==0.0 })
    }
    @Test fun gainIsOnlySupportingEvidenceAndSilenceCannotBePromoted() {
        val quiet=List(3) { AcousticScore(.01,0.0,0.0) };val strong=List(3) { AcousticScore(.3,0.0,0.0) }
        assertTrue(LaughDecision.enhanced(quiet,quiet,strong,strong,List(3){.01},.1).values.all { it>0 })
        assertTrue(LaughDecision.enhanced(strong,strong,strong,strong,List(3){.00001},.1).values.all { it==0.0 })
        val none=List(3) { AcousticScore(0.0,0.0,0.0) }
        assertTrue(LaughDecision.enhanced(none,none,strong,strong,List(3){.01},.1).values.all { it==0.0 })
    }
    @Test fun overlapContextOwnsOnlyItsCoreAndNeverDuplicatesEvents() {
        val value=LocalLaughAnalysis(listOf(LocalLaugh(500,1500,.2)),listOf(LaughFrame(500,1500,.2,.2,0.0,0.0,.2,"accepted")))
        val block=LocalLaughBlock(FloatArray(32000),29000,30000,60000)
        val clipped=block.trim(value)
        assertEquals(30000L,clipped.events.single().startMs);assertEquals(30500L,clipped.events.single().endMs)
        assertEquals(30000L,clipped.frames.single().startMs)
    }
    @Test fun rejectsNoiseAndIsolatedWeakScores() {
        val raw=listOf(AcousticScore(.20,.3,.1),AcousticScore(.08,0.0,0.0),AcousticScore(.3,.1,.5))
        assertTrue(LaughDecision.accepted(raw,raw,.1).all { it==0.0 })
    }
    @Test fun acceptsSustainedAndQuietFilteredLaughter() {
        val raw=List(3) { AcousticScore(.1,0.0,0.0) }
        assertTrue(LaughDecision.accepted(raw,raw,.1).all { it==.1 })
        val quiet=List(3) { AcousticScore(.03,0.0,0.0) }
        val band=List(3) { AcousticScore(.09,0.0,0.0) }
        assertTrue(LaughDecision.accepted(quiet,band,.1).all { it==.09 })
        assertTrue(LaughDecision.accepted(quiet,band,.5).all { it==0.0 })
    }
}
