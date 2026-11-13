package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceWindowsTest {
    @Test fun shortTailNeverMakesAnOtherwiseUsableSegmentUnmatchable() {
        for(samples in listOf(48000,64000,80000,128000,160000,336000,1920000)) {
            val windows=LocalVoice.windows(samples)
            assertTrue(windows.isNotEmpty());assertTrue(windows.size<=8)
            assertEquals(0,windows.first().first);assertEquals(samples-1,windows.last().last)
            assertTrue(windows.all { it.count() in 48000..80000 })
        }
    }
    @Test fun shortUtterancesAreUnidentifiedRatherThanForcedToMatch() {
        assertTrue(LocalVoice.windows(47999).isEmpty())
        assertTrue(LocalVoice.windows(0).isEmpty())
    }
}
