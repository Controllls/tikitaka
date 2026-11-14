package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceMatchingTest {
    @Test fun briefOverlapDoesNotDiscardTwentySixSecondsOfCleanSpeech() {
        val turns=listOf(SpeakerTurn(30,14965,0,.71),SpeakerTurn(10122,10493,null,.78),
            SpeakerTurn(16349,28043,0,.60),SpeakerTurn(25799,26170,null,null),
            SpeakerTurn(28043,28296,null,.49),SpeakerTurn(32059,40007,1,.85))
        val clean=LocalDiarization.isolatedRanges(turns,0,40007)
        assertEquals(listOf(30L until 10122,10493L until 14965,16349L until 25799,26170L until 28043),clean)
        assertEquals(25887L,clean.sumOf { it.last+1-it.first })
    }
    @Test fun nestedAndDuplicateTurnsNeverDuplicatePcmOrLeakOverlappingVoices() {
        val turns=listOf(SpeakerTurn(-100,5000,0,.9),SpeakerTurn(500,1500,0,.9),
            SpeakerTurn(1000,2000,1,.9),SpeakerTurn(1800,2400,null,null),SpeakerTurn(6000,8000,0,.9))
        val pcm=FloatArray(7000*16) { it.toFloat() }
        val clean=LocalDiarization.isolatedPcm(pcm,turns,0)
        val expected=pcm.copyOfRange(0,16000)+pcm.copyOfRange(2400*16,5000*16)+pcm.copyOfRange(6000*16,7000*16)
        assertArrayEquals(expected,clean,0f)
        assertTrue(LocalDiarization.isolatedRanges(turns,2,7000).isEmpty())
    }
    @Test fun noisyOutlierDoesNotVetoMajorityButOneGoodWindowCannotEstablishIdentity() {
        assertTrue(LocalVoice.summarize(listOf(.71,.34,.75)).mine)
        assertFalse(LocalVoice.summarize(listOf(.34,.72,.44)).mine)
        assertFalse(LocalVoice.summarize(listOf(.34,.72)).mine)
        assertFalse(LocalVoice.summarize(listOf(.9,Double.NaN,.9)).mine)
        assertFalse(LocalVoice.summarize(emptyList()).mine)
    }
    @Test fun belowThresholdRivalStillMakesAMatchAmbiguous() {
        val checks=mapOf(0 to LocalVoice.summarize(listOf(.66)),1 to LocalVoice.summarize(listOf(.64)))
        assertNull(LocalDiarization.selectSelf(checks))
        assertTrue(LocalDiarization.resolveChecks(checks).values.none { it.mine })
        assertEquals("ambiguous_speakers",LocalDiarization.resolveChecks(checks).getValue(0).reason)
    }
    @Test fun onlyClearWinnerIsMarkedAsMine() {
        val checks=mapOf(0 to LocalVoice.summarize(listOf(.69)),1 to LocalVoice.summarize(listOf(.84)))
        assertEquals(1,LocalDiarization.selectSelf(checks))
        assertEquals(listOf(1),LocalDiarization.resolveChecks(checks).filterValues { it.mine }.keys.toList())
        assertNull(LocalDiarization.selectSelf(mapOf(0 to VoiceCheck(null,false,"too_short"))))
    }
}
