package com.example.hd_project.tiki

import com.example.hd_project.momo.model.Person
import com.example.hd_project.momo.model.SeatIds
import org.junit.Assert.*
import org.junit.Test

class DriverCharacterTest {
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
