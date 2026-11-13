package com.example.hd_project

import com.example.hd_project.momo.PresetDocument
import com.example.hd_project.momo.PresetPerson
import org.junit.Assert.*
import org.junit.Test

class MomoPresetTest {
    private fun preset()=PresetDocument("fixed","bg.mp4","car.png",people=listOf(
        PresetPerson("P1","one","driver","one/manifest.json"),PresetPerson("P2","two","front_passenger","two/manifest.json")),speakerMapping=mapOf("S1" to "P1","S2" to "P2"))
    @Test fun suppliedPresetRequiresDistinctSeatsAndValidMapping() {
        preset().validate()
        assertThrows(IllegalArgumentException::class.java) { preset().copy(people=preset().people.map { it.copy(seat="driver") }).validate() }
        assertThrows(IllegalArgumentException::class.java) { preset().copy(speakerMapping=mapOf("S1" to "P9")).validate() }
        assertThrows(IllegalArgumentException::class.java) { preset().copy(people=preset().people.map { it.copy(pose="generated") }).validate() }
    }
}
