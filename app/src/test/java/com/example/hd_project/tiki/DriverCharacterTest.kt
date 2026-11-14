package com.example.hd_project.tiki

import com.example.hd_project.momo.model.Person
import com.example.hd_project.momo.model.SeatIds
import com.example.hd_project.momo.model.SceneProject
import org.junit.Assert.*
import org.junit.Test

class DriverCharacterTest {
    @Test fun unknownVoiceNeverGetsAssignedToSelectedDriver() {
        for(speakers in 0..4) {
            val count=TikiScene.castCount(speakers,maxOf(1,speakers),null)
            val people=(0 until count).map { Person("P$it","Speaker $it",SeatIds[it],manifestRef="character-$it") }
            val result=TikiScene.assignSpeakers(SceneProject("test","test","unused","a".repeat(64),1000000,people=people),speakers,null,"Me")
            val driver=result.people.single { it.seat=="driver" }
            assertEquals("character-0",driver.manifestRef)
            assertFalse(driver.personId in result.speakerMapping.values)
            assertEquals(minOf(speakers,3),result.speakerMapping.size)
            assertEquals(count,result.people.size)
        }
    }
    @Test fun laterVerifiedSpeakerKeepsItsIdAndAnimatesDriver() {
        val people=(0 until 3).map { Person("P$it","Speaker $it",SeatIds[it],manifestRef="character-$it") }
        val result=TikiScene.assignSpeakers(SceneProject("test","test","unused","a".repeat(64),1000000,people=people),3,2,"Me")
        assertEquals("P2",result.speakerMapping["performance3"])
        assertEquals("P2",result.people.single { it.seat=="driver" }.personId)
        assertEquals("character-0",result.people.single { it.seat=="driver" }.manifestRef)
    }
    @Test fun selectedCharacterStaysInDriverSeatForEverySpeakerOrder() {
        for(count in 1..4) {
            val people=(0 until count).map { Person("P$it","Speaker $it",SeatIds[it],manifestRef="character-$it") }
            for(self in 0 until count) {
                val result=TikiScene.placeSelfAtDriver(people,self)
                assertEquals("driver",result[self].seat)
                assertEquals("character-0",result[self].manifestRef)
                assertEquals(people.map { it.personId },result.map { it.personId })
                assertEquals(people.map { it.seat }.toSet(),result.map { it.seat }.toSet())
                assertEquals(people.map { it.manifestRef }.toSet(),result.map { it.manifestRef }.toSet())
            }
            assertEquals(people,TikiScene.placeSelfAtDriver(people,null))
            assertEquals(people,TikiScene.placeSelfAtDriver(people,count))
        }
    }
}
