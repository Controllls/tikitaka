package com.example.hd_project

data class LaughWindow(val startMs: Long, val endMs: Long, val laughter: Float, val music: Float)
data class HighlightRange(val startMs: Long, val endMs: Long, val score: Float)

/** Experimental sound-event selection, not a judgment of conversational humor. */
object HighlightSelector {
    fun select(windows: List<LaughWindow>, durationMs: Long): List<HighlightRange> {
        if (durationMs <= 0) return emptyList()
        val events = windows.filter {
            it.laughter.isFinite() && it.music.isFinite() && it.laughter >= 0.25f &&
                it.laughter > it.music && it.music < 0.5f &&
                it.startMs >= 0 && it.endMs > it.startMs && it.startMs < durationMs
        }.sortedBy { it.startMs }
        val clusters = mutableListOf<MutableList<LaughWindow>>()
        for (event in events) {
            val last = clusters.lastOrNull()
            if (last == null || event.startMs - last.last().endMs > 2500 ||
                event.endMs - last.first().startMs > 25_000) clusters.add(mutableListOf(event))
            else last.add(event)
        }
        // Require more than one positive window; isolated transients are poor candidates.
        val candidates = clusters.filter { it.size >= 2 }.map { group ->
            HighlightRange((group.first().startMs - 12_000).coerceAtLeast(0),
                (group.last().endMs + 6000).coerceAtMost(durationMs),
                group.map { it.laughter }.average().toFloat())
        }.sortedByDescending { it.score }
        val selected = mutableListOf<HighlightRange>()
        for (candidate in candidates) {
            if (selected.none { candidate.startMs < it.endMs && it.startMs < candidate.endMs }) {
                selected.add(candidate)
                if (selected.size == 3) break
            }
        }
        return selected.sortedBy { it.startMs }
    }
}

/** Continuous linear interpolation; retains phase across decoder buffer boundaries. */
internal class MonoResampler(private val inputRate: Int, private val output: (Float) -> Unit) {
    private var inputIndex = 0L
    private var nextOutput = 0.0
    private var previous = 0f
    init { require(inputRate > 0) }
    fun accept(value: Float) {
        if (inputIndex == 0L) {
            output(value)
            nextOutput += inputRate / 16000.0
        } else while (nextOutput <= inputIndex) {
            val fraction = (nextOutput - (inputIndex - 1)).toFloat()
            output(previous + (value - previous) * fraction)
            nextOutput += inputRate / 16000.0
        }
        previous = value
        inputIndex++
    }
}
