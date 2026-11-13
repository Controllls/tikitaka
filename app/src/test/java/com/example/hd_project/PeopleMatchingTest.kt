package com.example.hd_project

import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.people.*
import org.junit.Assert.*
import org.junit.Test

class PeopleMatchingTest {
    private fun vector(index: Int)=List(192) { if(it==index)1.0 else 0.0 }
    private fun profile(index: Int,name: String)=PersonProfile("00000000-0000-0000-0000-${index.toString().padStart(12,'0')}",name,
        CharacterCatalog.ids[index%2],VoiceReference("test-v1",vector(index),8_000_000,"test",0))
    private fun result(vararg vectors: List<Double>): AnalysisResult=AnalysisResult(jobId="test",durationUs=30_000_000,
        dataOrigin="fixture",audio=AudioInfo("a".repeat(64),"audio/wav"),
        speakers=vectors.indices.map { Speaker("S${it+1}",it*8_000_000L) },events=emptyList(),captions=emptyList(),
        tracks=TrackRef("test",frameCount=1500),voiceModelId="test-v1",
        speakerSignatures=vectors.mapIndexed { i,v -> SpeakerSignature("S${i+1}",v,8_000_000,listOf(VoiceSegment(i*8_000_000L,(i+1)*8_000_000L))) })
    @Test fun abcThenBadKeepsIdentityAcrossOrderAndRejectsNewVoice() {
        val profiles=listOf(profile(0,"A"),profile(1,"B"),profile(2,"C"))
        assertEquals(listOf("A","B","C"),VoiceMatcher.match(result(vector(0),vector(1),vector(2)),profiles).map { it.profile?.name })
        assertEquals(listOf("B","A",null),VoiceMatcher.match(result(vector(1),vector(0),vector(3)),profiles).map { it.profile?.name })
    }
    @Test fun ambiguousAndDuplicateAssignmentsStayUnknown() {
        val a=profile(0,"A");val b=profile(1,"B").copy(voice=a.voice)
        assertNull(VoiceMatcher.match(result(vector(0)),listOf(a,b)).single().profile)
        assertTrue(VoiceMatcher.match(result(vector(0),vector(0)),listOf(a)).all { it.profile==null })
    }
    @Test fun versionMismatchMissingAndShortSpeechStayUnknown() {
        val result=result(vector(0));val profiles=listOf(profile(0,"A"))
        assertNull(VoiceMatcher.match(result.copy(voiceModelId="other"),profiles).single().profile)
        assertNull(VoiceMatcher.match(result.copy(speakerSignatures=emptyList()),profiles).single().profile)
        assertNull(VoiceMatcher.match(result.copy(speakerSignatures=result.speakerSignatures.map { it.copy(speechUs=1_000_000) }),profiles).single().profile)
    }
    @Test fun lowScoreAndInsufficientMarginDoNotChooseNearestProfile() {
        val v=vector(0).toMutableList().apply { this[0]=.64;this[4]=kotlin.math.sqrt(1-.64*.64) }
        assertNull(VoiceMatcher.match(result(v),listOf(profile(0,"A"))).single().profile)
        val similar=profile(1,"B").copy(voice=profile(0,"A").voice!!.copy(embedding=vector(0).toMutableList().apply { this[0]=.96;this[1]=.28 }))
        assertNull(VoiceMatcher.match(result(vector(0)),listOf(profile(0,"A"),similar)).single().profile)
    }
    @Test fun deletionAndCharacterChangesInvalidateGeneratedVideo() {
        val a=profile(0,"A");val before=PeopleScene.fingerprint(listOf(a))
        assertNotEquals(before,PeopleScene.fingerprint(emptyList()))
        assertNotEquals(before,PeopleScene.fingerprint(listOf(a.copy(characterId=CharacterCatalog.ids[1]))))
        assertNull(VoiceMatcher.match(result(vector(0)),emptyList()).single().profile)
    }
    @Test fun enrollmentRejectsShortNonfiniteAndInvalidCharacters() {
        val a=profile(0,"A");a.validate()
        for(p in listOf(a.copy(voice=a.voice!!.copy(speechUs=5_000_000)),a.copy(characterId="../invalid"),
            a.copy(voice=a.voice.copy(embedding=List(192) { Double.NaN })))) {
            assertThrows(IllegalArgumentException::class.java) { p.validate() }
        }
    }
}
