package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test

class LocalLaughterTest {
    @Test fun shortMixedBurstSurvivesChangingDominantLabels() {
        // Captured model scores at 14.647–15.127s; supporting labels vary across windows.
        val raw=listOf(
            AcousticScore(.01953125,.08203125,.05859375,.03515625,1,.015625),
            AcousticScore(.03125,.19921875,.109375,.0703125,3,.109375),
            AcousticScore(.03125,.1484375,.04296875,.07421875,4,.04296875))
        val band=listOf(raw[0].copy(laugh=.03125,family=.0546875,support=2,cough=.04296875,noise=.08203125),
            raw[1].copy(cough=.1484375,noise=.1484375),raw[2])
        val gain=List(3){AcousticScore(.015625,.2,.1)}
        val verdict=LaughDecision.enhanced(raw,band,gain,gain,List(3){.01},.001)
        assertTrue(verdict.values.all { it>0 })
        assertTrue(verdict.reasons.all { it=="mixed_laughter" })
        assertTrue(LaughDecision.enhanced(raw,band,gain,gain,List(3){.01},.2).values.all { it==0.0 })
    }

    @Test fun sustainedMixedLaughterDoesNotHaveToOutrankOtherVocalSounds() {
        val raw=List(4) { AcousticScore(.03125,.199,.109) }
        val band=List(4) { AcousticScore(.03125,.15,.08) }
        val normalized=List(4) { AcousticScore(.015625,.1,.07) }
        assertTrue(LaughDecision.accepted(raw,band,.001).all { it==0.0 })
        val result=LaughDecision.enhanced(raw,band,normalized,normalized,List(4){.01},.001)
        assertTrue(result.values.all { it>0.0 })
        assertTrue(result.reasons.all { it=="mixed_laughter" })
        val strong=List(4) { AcousticScore(.3,.5,.4) }
        assertTrue(LaughDecision.enhanced(strong,strong,strong,strong,List(4){.01},.1).values.all { it>0.0 })
        val snorting=List(4) { AcousticScore(.03125,.199,.109,.0703,3,.109) }
        val snortingBand=snorting.map { it.copy(cough=.148,noise=.148) }
        assertTrue(LaughDecision.enhanced(snorting,snortingBand,normalized,normalized,List(4){.01},.001).values.all { it>0.0 })
    }

    @Test fun mixedRescueRejectsCoughNoiseSilenceAndUncorroboratedSpikes() {
        val raw=List(4) { AcousticScore(.03125,.199,.109) }
        fun verdict(a: List<AcousticScore>, b: List<AcousticScore> = a, level: Double = .01) =
            LaughDecision.enhanced(a,b,a,b,List(a.size){level},.001).values
        assertTrue(verdict(raw.map { it.copy(cough=.1) }).all { it==0.0 })
        assertTrue(verdict(raw.map { it.copy(noise=.8) }).all { it==0.0 })
        assertTrue(verdict(raw,level=.00001).all { it==0.0 })
        assertTrue(verdict(raw.take(2)).all { it==0.0 })
        assertTrue(verdict(raw,raw.map { it.copy(laugh=.001) }).all { it==0.0 })
        assertTrue(verdict(raw.map { it.copy(laugh=.003) }).all { it==0.0 })
        assertTrue(verdict(raw.map { it.copy(family=.07,support=3,cough=.7,noise=.7) }).all { it==0.0 })
    }

    @Test fun lowerThresholdAcceptsWeakLaughterButStillRejectsSilence() {
        val weak=List(3) { AcousticScore(.003,0.0,0.0) }
        assertTrue(LaughDecision.enhanced(weak,weak,weak,weak,List(3){.01},.01).values.all { it==0.0 })
        assertTrue(LaughDecision.enhanced(weak,weak,weak,weak,List(3){.01},.001).values.all { it>0.0 })
        assertTrue(LaughDecision.enhanced(weak,weak,weak,weak,List(3){.00001},.001).values.all { it==0.0 })
        assertThrows(IllegalArgumentException::class.java) { LaughDecision.accepted(weak,weak,.0001) }
    }

    @Test fun sensitivityPresetsSurviveFloatPreferenceStorage() {
        for(value in LaughterSensitivity.values)assertEquals(value,LaughterSensitivity.fromStored(value.toFloat().toDouble()),0.0)
        assertEquals(.001,LaughterSensitivity.DEFAULT,0.0)
    }

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
