package com.example.hd_project.tiki

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable enum class RoadEnvironment { UNKNOWN, CITY, HIGHWAY }
@Serializable data class RoadObservation(val environment: RoadEnvironment = RoadEnvironment.UNKNOWN, val evidence: String = "unavailable")
@Serializable data class RoadInterval(val startMs: Long, val endMs: Long, val observation: RoadObservation)
@Serializable data class RoadTimeline(val version: Int = 1, val intervals: List<RoadInterval> = emptyList())
@Serializable data class NavigationBackgroundSelection(
    val policy: String = NavigationBackgroundPolicy.VERSION,
    val environment: RoadEnvironment,
    val asset: String,
    val reason: String,
    val knownMs: Long,
    val highwayMs: Long,
    val cityMs: Long,
    val clipMs: Long,
) {
    fun json(): String = NavigationBackgroundPolicy.json.encodeToString(this)
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(json().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(12)
}

/** Public NaviHelper 3.0.5 has no road-class field. These are conservative environment inferences. */
object NavigationBackgroundPolicy {
    const val VERSION = "navi-background-v1"
    const val FRESH_MS = 15_000L
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val highway = Regex("고속도로|고속화도로|도시고속|자동차전용|\\b(expressway|motorway|freeway)\\b", RegexOption.IGNORE_CASE)
    private val road = Regex("[가-힣]+(?:대로|로|길)(?:\\d+(?:번길|길)?)?(?=\\s|$|[,·])|\\b(street|road|avenue|boulevard)\\b", RegexOption.IGNORE_CASE)
    private val exits = setOf("TURN_EXIT_IC_R", "TURN_EXIT_IC_L", "TURN_EXIT_IC_C")

    // Only the current address and the nearest upcoming maneuver are inputs. Never use destination names.
    fun infer(currentAddress: String?, nextTurn: String? = null): RoadObservation = when {
        currentAddress != null && highway.containsMatchIn(currentAddress) -> RoadObservation(RoadEnvironment.HIGHWAY,"current-road-name")
        nextTurn in exits -> RoadObservation(RoadEnvironment.HIGHWAY,"upcoming-highway-exit")
        currentAddress != null && road.containsMatchIn(currentAddress) -> RoadObservation(RoadEnvironment.CITY,"current-general-road")
        else -> RoadObservation()
    }

    fun select(timeline: RoadTimeline?, startMs: Long, endMs: Long): NavigationBackgroundSelection {
        require(startMs >= 0 && endMs > startMs)
        val duration = endMs-startMs
        val rows = timeline?.intervals.orEmpty()
        val valid = timeline?.version == 1 && rows.size <= 100_000 && rows.withIndex().all { (i,r) ->
            r.startMs >= 0 && r.endMs > r.startMs && (i == 0 || rows[i-1].endMs <= r.startMs)
        }
        var city=0L;var highway=0L
        if(valid) rows.forEach { r ->
            val overlap=(minOf(endMs,r.endMs)-maxOf(startMs,r.startMs)).coerceAtLeast(0)
            when(r.observation.environment) { RoadEnvironment.CITY -> city+=overlap;RoadEnvironment.HIGHWAY -> highway+=overlap;else -> Unit }
        }
        val known=city+highway
        // At least half of the actual highlight must have fresh evidence. Missing intervals count as unknown.
        val environment=if(known < duration/2.0) RoadEnvironment.UNKNOWN else if(highway>city) RoadEnvironment.HIGHWAY else RoadEnvironment.CITY
        return NavigationBackgroundSelection(environment=environment,asset=when(environment) {
            RoadEnvironment.HIGHWAY -> "highway_rearview_loop_10s.mp4"
            RoadEnvironment.CITY -> "city_rearview_loop_10s.mp4"
            RoadEnvironment.UNKNOWN -> "city-road-loop.mp4"
        },reason=if(environment==RoadEnvironment.UNKNOWN) "insufficient-navigation-data" else "dominant-recorded-environment",
            knownMs=known,highwayMs=highway,cityMs=city,clipMs=duration)
    }
}

/** Called by the audio worker with accumulated audio time, excluding paused wall-clock time. */
class RecordingRoadTimeline {
    private val intervals=mutableListOf<RoadInterval>()
    private var elapsed=0L
    fun advanceTo(audioMs: Long, observation: RoadObservation) {
        if(audioMs<=elapsed)return
        val last=intervals.lastOrNull()
        if(last?.observation==observation) intervals[intervals.lastIndex]=last.copy(endMs=audioMs)
        else intervals.add(RoadInterval(elapsed,audioMs,observation))
        elapsed=audioMs
    }
    fun snapshot()=RoadTimeline(intervals=intervals.toList())
}
