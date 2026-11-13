package com.example.hd_project.tiki

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Offline port of the event-context-rules-v1 selector. No Android, network or model dependency. */
object LocalHighlightPlanner {
    private val json = Json { encodeDefaults = true }
    @Serializable data class Caption(
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long, val text: String)
    @Serializable data class Laugh(
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long, val score: Double)
    @Serializable data class Metadata(
        @SerialName("schema_version") val schemaVersion: String,
        @SerialName("consent_version") val consentVersion: String,
        @SerialName("source_sha256") val sourceSha256: String,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("stt_status") val sttStatus: String,
        val detector: String,
        @SerialName("laughter_threshold") val laughterThreshold: Double,
        val captions: List<Caption>, val laughter: List<Laugh>,
        @SerialName("clip_seconds") val clipSeconds: Int = 40,
        @SerialName("max_clips") val maxClips: Int = 3)
    @Serializable data class Clip(
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long, val score: Double,
        val reason: String, val title: String, val captions: List<Caption>, val laughter: List<Laugh>)
    @Serializable data class Plan(
        @SerialName("plan_id") val planId: String,
        @SerialName("source_sha256") val sourceSha256: String,
        @SerialName("duration_ms") val durationMs: Long,
        val clips: List<Clip>, val warnings: List<String>,
        @SerialName("schema_version") val schemaVersion: String = "local-plan-v1",
        val selector: String = "event-context-rules-v1",
        @SerialName("speaker_identity") val speakerIdentity: String = "unknown")
    private data class Candidate(val start: Long, val end: Long, val score: Double, val reason: String)

    fun plan(metadataJson: String): String = json.encodeToString(plan(json.decodeFromString<Metadata>(metadataJson)))

    fun plan(body: Metadata): Plan {
        require(body.schemaVersion == "local-metadata-v1" && body.consentVersion == "transcript-events-v1")
        require(body.sourceSha256.matches(Regex("[a-f0-9]{64}")))
        require(body.durationMs in 100..43_200_000 && body.clipSeconds in 10..120 && body.maxClips in 1..10)
        require(body.sttStatus in listOf("complete", "partial", "unavailable"))
        require(body.detector in listOf("yamnet-local-v1", "yamnet-local-v2", "yamnet-local-v3"))
        require(body.laughterThreshold.isFinite() && body.laughterThreshold in LaughterSensitivity.MIN..1.0)
        require(body.sttStatus != "unavailable" || body.captions.isEmpty())
        validateSpans(body.captions, body.durationMs, { it.startMs }, { it.endMs }) {
            require(it.text.isNotBlank() && it.text.codePointCount(0, it.text.length) in 1..2000)
        }
        validateSpans(body.laughter, body.durationMs, { it.startMs }, { it.endMs }) {
            require(it.score.isFinite() && it.score in 0.0..1.0)
        }
        val candidates = if (body.laughter.isNotEmpty()) body.laughter.map {
            Candidate(it.startMs, it.endMs, it.score, "laughter")
        } else body.captions.map {
            Candidate(it.startMs, it.endMs, (it.text.codePointCount(0, it.text.length) / 200.0).coerceAtMost(.8), "dialogue")
        }
        val selected = mutableListOf<Clip>()
        val length = minOf(body.durationMs, body.clipSeconds * 1000L)
        for (candidate in candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.start })) {
            var left = (candidate.start - length * 2 / 3).coerceIn(0, body.durationMs - length)
            var right = left + length
            for (clip in selected) if (left < clip.endMs && clip.startMs < right) {
                if (candidate.end <= clip.startMs) right = minOf(right, clip.startMs)
                else if (candidate.start >= clip.endMs) left = maxOf(left, clip.endMs)
                else { right = left; break }
            }
            if (right - left < minOf(10_000, body.durationMs)) continue
            val captions = body.captions.filter { it.startMs < right && it.endMs > left }
            val laughter = body.laughter.filter { it.startMs < right && it.endMs > left }
            val text = captions.firstOrNull()?.text
            val title = text?.substring(0, text.offsetByCodePoints(0, minOf(36, text.codePointCount(0, text.length)))) ?: "웃음이 담긴 순간"
            selected.add(Clip(left, right, candidate.score, candidate.reason, title, captions, laughter))
            if (selected.size >= body.maxClips) break
        }
        // Version the local hash encoding; existing server-created plan files keep their original IDs.
        val hashInput = "android-local-planner-v1\n" + json.encodeToString(body)
        val hash = MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val warnings = if (body.sttStatus == "complete") emptyList() else
            listOf("기기 음성 인식이 불완전하여 자막 또는 대화 구간이 누락될 수 있습니다.")
        return Plan(hash, body.sourceSha256, body.durationMs, selected.sortedBy { it.startMs }, warnings)
    }

    private fun <T> validateSpans(rows: List<T>, duration: Long, start: (T) -> Long, end: (T) -> Long, validate: (T) -> Unit) {
        require(rows.size <= 30_000)
        var previous = 0L
        for (row in rows) {
            require(start(row) >= previous && end(row) > start(row) && end(row) <= duration)
            validate(row)
            previous = end(row)
        }
    }
}
