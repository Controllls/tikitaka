package com.example.hd_project.tiki

import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.StoryDirection
import org.junit.Assert.*
import org.junit.Test

class DialogueStoryTest {
    private val lines=listOf(StoryLine(0,2_000_000,"편의점에 또 왔네"),StoryLine(2_000_000,4_000_000,"길을 좀 외워봐"))
    @Test fun offlinePlanUsesTranscriptAndNeverInventsPlaceOrPeople() {
        val story=DialogueStoryRules.plan(lines,4_000_000)
        assertTrue(lines.any { it.text==story.title })
        assertEquals("none",story.model);assertEquals("extracted",story.status)
        assertEquals("",story.mentionedPlace)
        assertEquals(listOf(StoryBeat(0,2_000_000,"wide"),StoryBeat(2_000_000,4_000_000,"speaker")),story.beats)
        assertEquals(story,DialogueStoryRules.plan(lines,4_000_000))
    }
    @Test fun missingSpeechUsesBasicTitleWithoutFailingProduction() {
        for(rows in listOf(emptyList(),listOf(StoryLine(0,100,"(웃음)")),listOf(StoryLine(100,0,"잘못된 대사")))) {
            val story=DialogueStoryRules.plan(rows,4_000_000,"웃음이 담긴 순간")
            assertEquals("웃음이 담긴 순간",story.title)
            assertEquals("basic",story.status);assertEquals("no_transcript",story.reason)
            assertTrue(story.beats.isEmpty())
        }
        assertEquals("함께 나눈 이야기",DialogueStoryRules.plan(emptyList(),100," \n ").title)
    }
    @Test fun topicSentenceWinsOverGreetingAndAcknowledgements() {
        val dialogue=listOf("안녕하세요", "네", "주차장 입구를 또 지나쳤네", "주차장 입구가 어디야?")
            .mapIndexed { i,text -> StoryLine(i*1000L,(i+1)*1000L,text) }
        val story=DialogueStoryRules.plan(dialogue,4000)
        assertTrue(story.title.startsWith("주차장 입구"))
        assertTrue(story.beats.all { it.startUs>=0 && it.endUs<=4000 })
        assertTrue(story.beats.any { it.shot=="exchange" })
    }
    @Test fun longTitlesRespectCodePointsAndWhitespace() {
        val text="오늘은 아주 긴 여행 이야기를 나누고 마지막에는 웃으며 집으로 돌아왔다 😀😀😀😀"
        val title=DialogueStoryRules.title(listOf(StoryLine(0,100,text)))
        assertTrue(title.codePointCount(0,title.length)<=24)
        assertTrue(text.startsWith(title.removeSuffix("…")))
        val emojiTitle=DialogueStoryRules.title(listOf(StoryLine(0,100,"여행"+"😀".repeat(40))))
        assertEquals(24,emojiTitle.codePointCount(0,emojiTitle.length))
        assertEquals(emojiTitle,emojiTitle.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals("내일 여행 가자",DialogueStoryRules.title(listOf(StoryLine(0,100,"내일\t 여행   가자"))))
    }
    @Test fun overlappingAndOutOfRangeCaptionsProduceBoundedContiguousShots() {
        val story=DialogueStoryRules.plan(listOf(StoryLine(-100,1000,"오늘 어디로 갈까?"),
            StoryLine(0,800,"공원에 가자"),StoryLine(300,3000,"그럼 공원으로 출발하자"),StoryLine(3000,4000,"구간 밖")),2000)
        assertEquals(0L,story.beats.first().startUs);assertEquals(2000L,story.beats.last().endUs)
        assertTrue(story.beats.all { it.endUs>it.startUs && it.startUs>=0 && it.endUs<=2000 })
        assertTrue(story.beats.zipWithNext().all { (a,b) -> a.endUs==b.startUs })
        assertFalse(story.title.contains("구간 밖"))
    }
    @Test fun longTranscriptIsBoundedButKeepsBeginningMiddleAndEnd() {
        val scenes=DialogueStoryRules.scenes((0 until 60).map { StoryLine(it*1_000_000L,(it+1)*1_000_000L,"대사 $it") })
        assertEquals(6,scenes.size);assertTrue(scenes.first().text.startsWith("대사 0"));assertTrue(scenes.last().text.endsWith("대사 59"))
        assertTrue(scenes.all { it.text.length<=200 })
        assertTrue(DialogueStoryRules.scenes(listOf(StoryLine(0,100,"(웃음)"))).isEmpty())
    }
    @Test fun oneLongSttSegmentKeepsItsPunchline() {
        val dialogue="어디로 갈지 이야기하고 ".repeat(45)+"결국 목적지는 정했지만 저녁은 못 정했다"
        val scenes=DialogueStoryRules.scenes(listOf(StoryLine(0,60_000_000,dialogue)))
        assertTrue(scenes.size in 2..6)
        assertTrue(scenes.last().text.endsWith("저녁은 못 정했다"))
        assertTrue(scenes.sumOf { it.text.length }<=960)
        assertEquals(0L,scenes.first().startUs)
        assertEquals(60_000_000L,scenes.last().endUs)
    }
    @Test fun directingDoesNotCreateSpeakerIdentityOrChangeSound() {
        val scene=SceneProject("x","제목","x","a".repeat(64),4_000_000,
            speakerMapping=mapOf("s1" to "P1","s2" to "P2"),storyBeats=listOf(StoryBeat(0,4_000_000,"reaction")))
        val result=AnalysisResult(jobId="x",durationUs=4_000_000,dataOrigin="inference",audio=AudioInfo("a".repeat(64),"audio/mp4"),
            speakers=listOf(Speaker("s1",0),Speaker("s2",0)),events=emptyList(),captions=listOf(Caption("c",0,4_000_000,"대사",listOf("s1"))),tracks=TrackRef("local",frameCount=200))
        val bundle=AnalysisBundle(result,MotionTracks(jobId="x",durationUs=4_000_000,frameCount=200,mixedVoiceEnergy=List(200){0.0},speakerTracks=emptyList()))
        assertEquals("P2",StoryDirection.frame(scene,bundle,1_000_000).focusPersonId)
        assertEquals("P1",StoryDirection.frame(scene.copy(storyBeats=listOf(StoryBeat(0,4_000_000,"speaker"))),bundle,1_000_000).focusPersonId)
        val unknown=bundle.copy(result=result.copy(captions=listOf(result.captions.first().copy(speakerIds=emptyList()))))
        assertNull(StoryDirection.frame(scene,unknown,1_000_000).focusPersonId)
        assertEquals(result,bundle.result)
    }
}
