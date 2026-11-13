package com.example.hd_project.tiki

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalHighlightPlannerTest {
    private val json = Json { encodeDefaults = true }
    private fun metadata() = LocalHighlightPlanner.Metadata("local-metadata-v1", "transcript-events-v1",
        "a".repeat(64), 180_000, "complete", "yamnet-local-v2", .01,
        listOf(LocalHighlightPlanner.Caption(0, 1000, "오늘의 대화")), emptyList())

    @Test fun matchesExistingServerSelectionFixtures() {
        val fixtures = javaClass.getResourceAsStream("/local-highlight-plans.json")!!.bufferedReader().use { it.readText() }
        for (entry in Json.parseToJsonElement(fixtures).jsonArray) {
            val row = entry.jsonObject
            val actual = Json.parseToJsonElement(LocalHighlightPlanner.plan(row.getValue("input").toString())).jsonObject
            assertEquals(row.getValue("name").jsonPrimitive.content, row.getValue("clips"), actual.getValue("clips"))
        }
    }

    @Test fun rankedClipsAreChronologicalNonOverlappingAndBounded() {
        val body = metadata().copy(laughter = listOf(
            LocalHighlightPlanner.Laugh(20_000, 21_000, .5),
            LocalHighlightPlanner.Laugh(45_000, 46_000, .9),
            LocalHighlightPlanner.Laugh(100_000, 101_000, .7)))
        val clips = LocalHighlightPlanner.plan(body).clips
        assertEquals(2, clips.size)
        assertEquals(18_334L, clips[0].startMs)
        assertEquals(.9, clips[0].score, 0.0)
        assertTrue(clips.zipWithNext().all { (a,b) -> a.endMs <= b.startMs })
        assertTrue(clips.all { it.endMs - it.startMs in 10_000..40_000 })
    }

    @Test fun durationAndMaximumCountAreApplied() {
        val body = metadata().copy(clipSeconds = 120, maxClips = 1,
            laughter = listOf(LocalHighlightPlanner.Laugh(179_000, 180_000, .9)))
        assertEquals(60_000L, LocalHighlightPlanner.plan(body).clips.single().startMs)
        assertEquals(180_000L, LocalHighlightPlanner.plan(body).clips.single().endMs)
        val short = body.copy(durationMs = 3500, captions = emptyList(), laughter = listOf(LocalHighlightPlanner.Laugh(1000, 2000, .4)))
        assertEquals(3500L, LocalHighlightPlanner.plan(short).clips.single().endMs)
    }

    @Test fun newSensitivityValuesReachHighlightPlanning() {
        for(threshold in listOf(.001,.005)) {
            val body=metadata().copy(detector="yamnet-local-v3",laughterThreshold=threshold,
                laughter=listOf(LocalHighlightPlanner.Laugh(20_000,21_000,.003)))
            assertEquals("laughter",LocalHighlightPlanner.plan(body).clips.single().reason)
        }
        assertThrows(IllegalArgumentException::class.java) { LocalHighlightPlanner.plan(metadata().copy(laughterThreshold=.0001)) }
    }

    @Test fun dialogueFallbackSupportsUnicodeAndEmptyResults() {
        val text = "😀".repeat(40)
        val body = metadata().copy(captions = listOf(LocalHighlightPlanner.Caption(0, 1000, text)))
        val clip = LocalHighlightPlanner.plan(body).clips.single()
        assertEquals("dialogue", clip.reason)
        assertEquals("😀".repeat(36), clip.title)
        assertEquals(.2, clip.score, 0.0)
        val empty = LocalHighlightPlanner.plan(body.copy(captions = emptyList(), sttStatus = "unavailable"))
        assertTrue(empty.clips.isEmpty())
        assertTrue(empty.warnings.isNotEmpty())
    }

    @Test fun planIdIsStableAcrossJsonFormattingAndChangesWithSettings() {
        val body = metadata()
        val encoded = json.encodeToJsonElement(body).jsonObject
        val reordered = JsonObject(encoded.entries.reversed().associate { it.toPair() })
        val first = Json.parseToJsonElement(LocalHighlightPlanner.plan(encoded.toString())).jsonObject
        val second = Json.parseToJsonElement(LocalHighlightPlanner.plan("\n $reordered \n")).jsonObject
        assertEquals(first, second)
        assertNotEquals(LocalHighlightPlanner.plan(body).planId, LocalHighlightPlanner.plan(body.copy(clipSeconds = 120)).planId)
        assertTrue(LocalHighlightPlanner.plan(body).planId.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun rejectsInvalidOrOverlappingMetadataBeforeSelecting() {
        val body = metadata()
        for (invalid in listOf(body.copy(durationMs = 0), body.copy(durationMs = 43_200_001),
            body.copy(sourceSha256 = "invalid"), body.copy(clipSeconds = 121), body.copy(maxClips = 0),
            body.copy(laughterThreshold = Double.NaN), body.copy(sttStatus = "unavailable"),
            body.copy(captions = listOf(LocalHighlightPlanner.Caption(-1, 1, "a"))),
            body.copy(captions = listOf(LocalHighlightPlanner.Caption(0, 1, "  "))),
            body.copy(captions = listOf(LocalHighlightPlanner.Caption(0, 1, "a".repeat(2001)))),
            body.copy(captions = listOf(LocalHighlightPlanner.Caption(0, 10, "a"), LocalHighlightPlanner.Caption(9, 20, "b"))),
            body.copy(laughter = listOf(LocalHighlightPlanner.Laugh(0, 1, Double.POSITIVE_INFINITY))),
            body.copy(laughter = listOf(LocalHighlightPlanner.Laugh(1, 1, .5))),
            body.copy(laughter = listOf(LocalHighlightPlanner.Laugh(180_000, 180_001, .5))))) {
            assertThrows(IllegalArgumentException::class.java) { LocalHighlightPlanner.plan(invalid) }
        }
    }

    @Test fun rejectsOversizedListsAndUnknownFields() {
        val body = metadata().copy(laughter = List(30_001) { LocalHighlightPlanner.Laugh(it * 2L, it * 2L + 1, .1) })
        assertThrows(IllegalArgumentException::class.java) { LocalHighlightPlanner.plan(body) }
        val encoded = json.encodeToJsonElement(metadata()).jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            LocalHighlightPlanner.plan(JsonObject(encoded + ("audio" to JsonPrimitive("unexpected"))).toString())
        }
    }
}
