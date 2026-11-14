package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class SpeechWindowsTest {
    @Test fun silenceCreatesNoInventedCaptionWindow() {
        assertTrue(SpeechWindows.find(FloatArray(16000*8)).isEmpty())
    }
    @Test fun preservesUnequalUtteranceDurationsAndPause() {
        val pcm=FloatArray(16000*8)
        fun voice(a: Int,b: Int) { for(i in a*16000 until b*16000)pcm[i]=(.1*sin(i*.1)).toFloat() }
        voice(1,2);voice(4,7)
        val windows=SpeechWindows.find(pcm)
        assertEquals(2,windows.size)
        assertEquals(920,windows[0].start/16)
        assertEquals(2080,windows[0].end/16)
        assertEquals(3920,windows[1].start/16)
        assertEquals(7080,windows[1].end/16)
    }
    @Test fun longSpeechIsBoundedWithoutDroppingOrOverlappingSamples() {
        val pcm=FloatArray(16000*15) { (.1*sin(it*.1)).toFloat() }
        val windows=SpeechWindows.find(pcm)
        assertTrue(windows.all { it.end-it.start<=6*16000 })
        assertEquals(0,windows.first().start)
        assertEquals(pcm.size,windows.last().end)
        assertTrue(windows.zipWithNext().all { (a,b)->a.end==b.start })
    }
}
