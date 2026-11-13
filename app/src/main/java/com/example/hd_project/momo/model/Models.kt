package com.example.hd_project.momo.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.math.ceil

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val MomoJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable data class AnalysisOptions(val language: String = "ko", val speakerCountHint: Int = 0,
    val asrProfile: String = "standard", val consentVersion: String = "server-audio-v1")
@Serializable data class Speaker(val speakerId: String, val firstSeenUs: Long)
@Serializable data class AudioInfo(val sha256: String, val originalMime: String,
    val analysisSampleRate: Int = 16000, val analysisChannels: Int = 1, val decodedTimeOriginUs: Long = 0)
@Serializable data class SoundEvent(val eventId: String, val startUs: Long, val endUs: Long, val kind: String,
    val activeSpeakerIds: List<String> = emptyList(), val laughingSpeakerIds: List<String> = emptyList(),
    val score: Double = 0.0, val requiresReview: Boolean = false, val reviewReasons: List<String> = emptyList(),
    val energyReference: Double = .0002)
@Serializable data class Word(val startUs: Long, val endUs: Long, val text: String)
@Serializable data class Caption(val captionId: String, val startUs: Long, val endUs: Long, val text: String,
    val speakerIds: List<String> = emptyList(), val requiresReview: Boolean = false, val words: List<Word> = emptyList())
@Serializable data class ModelInfo(val name: String, val revision: String, val runtime: String, val precision: String)
@Serializable data class AnalysisWarning(val code: String, val message: String)
@Serializable data class TrackRef(val endpoint: String, val frameStepUs: Long = 20000, val frameCount: Int)
@Serializable data class VoiceSegment(val startUs: Long, val endUs: Long)
@Serializable data class SpeakerSignature(val speakerId: String, val embedding: List<Double>, val speechUs: Long,
    val segments: List<VoiceSegment>)
@Serializable data class AnalysisResult(val schemaVersion: String = "1.0", val pipelineVersion: String = "momo-server-v1",
    val jobId: String, val durationUs: Long, val dataOrigin: String, val audio: AudioInfo,
    val speakers: List<Speaker>, val events: List<SoundEvent>, val captions: List<Caption>,
    val models: List<ModelInfo> = emptyList(), val tracks: TrackRef, val warnings: List<AnalysisWarning> = emptyList(),
    val voiceModelId: String? = null, val speakerSignatures: List<SpeakerSignature> = emptyList())
@Serializable data class SpeakerTrack(val speakerId: String, val activity: List<Double>,
    val laughterActivity: List<Double>, val energySource: String = "activity_weighted_mixture")
@Serializable data class MotionTracks(val schemaVersion: String = "1.0", val jobId: String, val durationUs: Long,
    val frameStepUs: Long = 20000, val frameCount: Int, val mixedVoiceEnergy: List<Double>, val speakerTracks: List<SpeakerTrack>)
@Serializable data class ApiError(val code: String, val message: String, val retryable: Boolean = false,
    val requestId: String = "")
@Serializable data class ErrorResponse(val error: ApiError)
@Serializable data class JobStatus(val jobId: String, val parentJobId: String? = null, val status: String,
    val stage: String, val progress: Double, val createdAt: String, val updatedAt: String,
    val sourceExpiresAt: String? = null, val resultExpiresAt: String? = null, val pollAfterSeconds: Int = 2,
    val error: ApiError? = null)
@Serializable data class JobPage(val items: List<JobStatus>, val nextCursor: String? = null)

val SeatIds = listOf("driver", "front_passenger", "rear_left", "rear_right")
val SeatLabels = listOf("운전석", "조수석", "뒷좌석 왼쪽", "뒷좌석 오른쪽")
@Serializable data class Person(val personId: String, val name: String, val seat: String,
    val assetRef: String? = null, val parts: Map<String, String> = emptyMap(), val manifestRef: String? = null, val pose: String = "front")
@Serializable data class CabinLayout(val carLeft: Float = -120f, val carTop: Float = 220f, val carWidth: Float = 780f,
    val frontHeight: Float = 900f, val rearHeight: Float = 620f, val frontRootY: Float = 1000f, val rearRootY: Float = 730f)
@Serializable data class EventOverride(val eventId: String, val personIds: List<String>? = null,
    val kindOverride: String? = null, val acknowledged: Boolean = false)
@Serializable data class CaptionOverride(val captionId: String, val text: String)
@Serializable data class SceneProject(val projectId: String, val title: String, val audioLocalRef: String,
    val sourceAudioSha256: String, val durationUs: Long, val analysisJobId: String? = null,
    val people: List<Person> = listOf(Person("P1", "나", "driver"), Person("P2", "동행", "front_passenger")),
    val speakerMapping: Map<String, String> = emptyMap(), val eventOverrides: List<EventOverride> = emptyList(),
    val captionOverrides: List<CaptionOverride> = emptyList(), val sampleVisuals: Boolean = false,
    val backgroundRef: String? = null, val backgroundIsVideo: Boolean = false, val vehicleRef: String? = null,
    val rendererVersion: String = "momo-android-motion-v1", val width: Int = 540, val height: Int = 960,
    val fps: Int = 30, val seed: Int = 1, val presetFingerprint: String = "", val seatbeltsRef: String? = null,
    val steeringWheelRef: String? = null, val layout: CabinLayout = CabinLayout(),
    val vehicleRearRef: String? = null, val vehicleFrontRef: String? = null, val vehicleBottomRef: String? = null,
    val burnCaptions: Boolean = true)

data class AnalysisBundle(val result: AnalysisResult, val tracks: MotionTracks) {
    fun validate() {
        require(result.schemaVersion.substringBefore('.') == "1" && tracks.schemaVersion.substringBefore('.') == "1") { "앱을 업데이트해 주세요: 지원하지 않는 분석 버전입니다." }
        require(result.durationUs in 100000..600000000 && result.jobId == tracks.jobId && result.durationUs == tracks.durationUs)
        val count = ceil(result.durationUs / 20000.0).toInt()
        require(tracks.frameStepUs == 20000L && tracks.frameCount == count && result.tracks.frameCount == count)
        val ids = result.speakers.map { it.speakerId }
        require(ids.distinct().size == ids.size && ids.size <= 8)
        require(result.speakerSignatures.map { it.speakerId }.distinct().size == result.speakerSignatures.size)
        require(result.speakerSignatures.isEmpty() || !result.voiceModelId.isNullOrBlank())
        result.speakerSignatures.forEach { s ->
            require(s.speakerId in ids && s.embedding.size == 192 && s.embedding.all { it.isFinite() })
            require(kotlin.math.abs(s.embedding.sumOf { it*it }-1.0)<.02 && s.speechUs in 3_000_000L..30_000_000L)
            require(s.segments.isNotEmpty() && s.segments.size<=20)
            require(s.segments.all { it.startUs>=0 && it.endUs>it.startUs && it.endUs<=result.durationUs })
            require(s.segments.zipWithNext().all { (a,b) -> a.endUs<=b.startUs })
            require(s.segments.sumOf { it.endUs-it.startUs }==s.speechUs)
        }
        require(result.events.map { it.eventId }.distinct().size == result.events.size)
        var end = 0L
        result.events.forEach { e ->
            require(e.startUs == end && e.endUs > e.startUs && e.endUs <= result.durationUs)
            require(e.kind in listOf("speech", "laughter", "silence", "environment"))
            require(e.activeSpeakerIds.all { it in ids } && e.laughingSpeakerIds.all { it in e.activeSpeakerIds })
            require(e.kind !in listOf("silence", "environment") || e.activeSpeakerIds.isEmpty())
            require(e.energyReference.isFinite() && e.energyReference >= .0002)
            end = e.endUs
        }
        require(end == result.durationUs)
        require(tracks.mixedVoiceEnergy.size == count && tracks.mixedVoiceEnergy.all { it.isFinite() && it >= 0 })
        require(tracks.speakerTracks.map { it.speakerId }.toSet() == ids.toSet())
        tracks.speakerTracks.forEach { t ->
            require(t.energySource == "activity_weighted_mixture")
            listOf(t.activity, t.laughterActivity).forEach { a -> require(a.size == count && a.all { it.isFinite() && it in 0.0..1.0 }) }
        }
        result.captions.forEach { require(it.startUs >= 0 && it.endUs > it.startUs && it.endUs <= result.durationUs && it.speakerIds.all { id -> id in ids }) }
    }
}
