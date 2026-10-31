package com.example.hd_project

import org.junit.Assert.*
import org.junit.Test

class HighlightSelectorTest {
    private fun event(start: Long, score: Float = .8f, music: Float = .01f) = LaughWindow(start, start + 975, score, music)

    @Test fun silenceAndIsolatedSoundsProduceNoClips() {
        assertTrue(HighlightSelector.select(emptyList(), 60_000).isEmpty())
        assertTrue(HighlightSelector.select(listOf(event(10_000)), 60_000).isEmpty())
        assertTrue(HighlightSelector.select(listOf(event(10_000, .1f), event(10_500, .1f)), 60_000).isEmpty())
    }
    @Test fun musicAndInvalidScoresDoNotBecomeHighlights() {
        assertTrue(HighlightSelector.select(listOf(event(10_000, .4f, .7f), event(10_500, Float.NaN)), 60_000).isEmpty())
    }
    @Test fun contextIsIncludedAndBoundedByTheSource() {
        val ranges = HighlightSelector.select(listOf(event(1000), event(1500)), 5000)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges.single().startMs)
        assertEquals(5000, ranges.single().endMs)
    }
    @Test fun strongestOverlappingCandidateWinsWithoutDuplicatedAudio() {
        val ranges = HighlightSelector.select(listOf(event(20_000, .4f), event(20_500, .4f),
            event(26_000, .9f), event(26_500, .9f)), 60_000)
        assertEquals(1, ranges.size)
        assertEquals(14_000, ranges.single().startMs)
    }
    @Test fun selectsAtMostThreeStrongestThenOrdersChronologically() {
        val windows = listOf(150_000L to .6f, 20_000L to .7f, 100_000L to .9f, 60_000L to .8f)
            .flatMap { (start, score) -> listOf(event(start, score), event(start + 500, score)) }
        val selected = HighlightSelector.select(windows, 200_000)
        assertEquals(listOf(8000L, 48000L, 88000L), selected.map { it.startMs })
    }
    @Test fun extendedLaughterDoesNotCreateUnboundedClips() {
        val ranges = HighlightSelector.select((0L..200_000L step 500).map { event(it) }, 201_000)
        assertTrue(ranges.all { it.endMs - it.startMs <= 43_000 })
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }
    @Test fun resamplerKeepsTimingAndDcAcrossIrregularChunks() {
        for (rate in listOf(8000, 16000, 44100, 48000)) {
            val output = mutableListOf<Float>()
            val resampler = MonoResampler(rate) { output.add(it) }
            repeat(rate * 3) { resampler.accept(.25f) }
            assertTrue("rate=$rate count=${output.size}", kotlin.math.abs(output.size - 48000) <= 2)
            assertTrue(output.all { it == .25f })
        }
    }
}
