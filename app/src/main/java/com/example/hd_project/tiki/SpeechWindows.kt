package com.example.hd_project.tiki

import kotlin.math.sqrt

/** Source-sample boundaries, never callback arrival times or text-length estimates.
 * Energy gating is deliberately conservative; STT still decides whether a window contains words.
 */
internal object SpeechWindows {
    data class Window(val start: Int, val end: Int)
    private const val FRAME = 320 // 20 ms at 16 kHz
    fun find(pcm: FloatArray): List<Window> {
        if (pcm.isEmpty()) return emptyList()
        val energy = (pcm.indices step FRAME).map { a ->
            val b = minOf(a + FRAME, pcm.size)
            sqrt((a until b).sumOf { pcm[it].toDouble() * pcm[it] } / (b - a))
        }
        val sorted = energy.sorted()
        val threshold = maxOf(.001, minOf(.008, sorted[sorted.size / 5] * 3))
        val regions = mutableListOf<Window>()
        var first = -1
        var last = -1
        fun finish() {
            if (first >= 0 && last - first >= 4) {
                regions.add(Window(maxOf(0, (first - 4) * FRAME), minOf(pcm.size, (last + 5) * FRAME)))
            }
            first = -1; last = -1
        }
        energy.forEachIndexed { i, level ->
            if (level >= threshold) { if (first < 0) first = i; last = i }
            else if (first >= 0 && i - last >= 12) finish() // 240 ms pause
        }
        finish()
        return buildList {
            regions.forEach { region ->
                var start = region.start
                while (region.end - start > 6 * 16000) {
                    // Bound long uninterrupted speech at the quietest point in seconds 4–6.
                    val from = (start + 4 * 16000) / FRAME
                    val to = minOf((start + 6 * 16000) / FRAME, energy.lastIndex)
                    val cut = (from..to).minBy { energy[it] } * FRAME
                    add(Window(start, cut)); start = cut
                }
                if (region.end > start) add(Window(start, region.end))
            }
        }
    }
}
