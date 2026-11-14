package com.example.hd_project.momo.render

import com.example.hd_project.momo.model.*
import kotlin.math.*

enum class CharacterAction { REST, SPEAKING, TURNING }

data class Motion(val mouth: Double = 0.0, val laughing: Boolean = false, val dx: Double = 0.0,
    val dy: Double = 0.0, val tilt: Double = 0.0, val kind: String = "rest",
    val action: CharacterAction = CharacterAction.REST, val gaze: String? = null) {
    fun sprite(idlePose: String = "neutral"): String = when(action) {
        CharacterAction.SPEAKING -> if(laughing)"happy" else if(mouth>.18)"talk" else "neutral"
        CharacterAction.TURNING -> gaze ?: "neutral"
        // A saved side/talk pose must not bypass the action arbiter or its cooldown.
        CharacterAction.REST -> idlePose.takeUnless { it in setOf("left","right","talk") } ?: "neutral"
    }
}

/** v13 audio-events.motion port. Integer source time, independent of wall clock and UI. */
class MotionEngine(private val project: SceneProject, private val analysis: AnalysisBundle) {
    companion object { const val HEAD_TURN_COOLDOWN_US = 1_000_000L }
    private val tracks = analysis.tracks.speakerTracks.associateBy { it.speakerId }
    private val overrides = project.eventOverrides.associateBy { it.eventId }
    private data class Span(val startUs: Long, val endUs: Long)
    private data class GazeCue(val startUs: Long, val endUs: Long, val speaker: String)
    private data class HeadTurn(val startUs: Long, val endUs: Long, val direction: String)
    private val speakingSpans: Map<String,List<Span>> by lazy {
        val spans=project.people.associate { it.personId to mutableListOf<Span>() }
        fun add(people: List<String>,startUs: Long,endUs: Long) {
            val start=startUs.coerceIn(0,analysis.result.durationUs)
            val end=endUs.coerceIn(0,analysis.result.durationUs)
            if(end>start)people.distinct().forEach { spans[it]?.add(Span(start,end)) }
        }
        analysis.result.captions.forEach { caption ->
            add(caption.speakerIds.mapNotNull(project.speakerMapping::get),caption.startUs,caption.endUs)
        }
        analysis.result.events.forEach { event ->
            val override=overrides[event.eventId]
            if((override?.kindOverride ?: event.kind) in listOf("speech","laughter"))
                add(override?.personIds ?: (event.activeSpeakerIds+event.laughingSpeakerIds).mapNotNull(project.speakerMapping::get),event.startUs,event.endUs)
        }
        spans.mapValues { (_,rows) ->
            val merged=mutableListOf<Span>()
            rows.sortedBy { it.startUs }.forEach { span ->
                val last=merged.lastOrNull()
                if(last!=null && span.startUs<=last.endUs)merged[merged.lastIndex]=Span(last.startUs,max(last.endUs,span.endUs))
                else merged.add(span)
            }
            merged
        }
    }
    // A fixed timeline also keeps seeking and exported frames deterministic.
    private val gazeCues: List<GazeCue> by lazy {
        val question = Regex("[?？]|왜|어떻게|어디|누구|무슨|언제|뭐|니$|나요$|까요$")
        val candidates = analysis.result.captions.mapNotNull { caption ->
            val speaker = caption.speakerIds.singleOrNull()?.let(project.speakerMapping::get)
                ?: return@mapNotNull null
            if (!question.containsMatchIn(caption.text) &&
                StoryDirection.frame(project, analysis, caption.startUs).exchangeSpeaker == null) return@mapNotNull null
            GazeCue(caption.startUs.coerceAtLeast(0), caption.endUs.coerceAtMost(analysis.result.durationUs), speaker)
        }.sortedBy { it.startUs }
        val merged = mutableListOf<GazeCue>()
        candidates.forEach { cue ->
            val last = merged.lastOrNull()
            if (last != null && last.speaker == cue.speaker && cue.startUs <= last.endUs + 600_000)
                merged[merged.lastIndex] = last.copy(endUs = max(last.endUs, cue.endUs))
            else merged.add(cue)
        }
        merged.filter { it.endUs-it.startUs>=1_500_000 }
    }
    private val headTurns: Map<String,List<HeadTurn>> by lazy {
        val supported=project.rendererVersion in setOf("tikitaka-local-cast-v2","tikitaka-local-voice-v3","tikitaka-local-diagnostic-v4","tikitaka-local-laugh-v5","tikitaka-local-diarization-v6","tikitaka-local-wide-v7","tikitaka-local-story-v8","tikitaka-local-story-v9","tikitaka-local-story-v10","tikitaka-local-story-v11","tikitaka-local-story-v12")
        fun x(seat: String)=when(seat) { "driver"->1530;"front_passenger"->378;"rear_left"->1242;else->648 }
        project.people.associate { person ->
            val accepted=mutableListOf<HeadTurn>()
            var nextAllowedUs=0L
            if(supported && project.people.size>=2)for(cue in gazeCues) {
                if(person.personId==cue.speaker || cue.startUs<nextAllowedUs)continue
                val target=project.people.firstOrNull { it.personId==cue.speaker } ?: continue
                // Reserve the entire listening reaction. Speech/laughter, including silent parts
                // of captions, wins over turning so a head cannot flicker in and out of a cue.
                if(speakingSpans[person.personId].orEmpty().any {
                    it.startUs<cue.endUs+400_000 && it.endUs>cue.startUs-400_000
                })continue
                accepted.add(HeadTurn(cue.startUs,cue.endUs,if(x(target.seat)<x(person.seat))"left" else "right"))
                // Enforce a full second after returning to the front, per character.
                nextAllowedUs=cue.endUs+HEAD_TURN_COOLDOWN_US
            }
            person.personId to accepted
        }
    }
    /** Sentence-level speaking state, independent of mouth amplitude and silent syllables. */
    fun isSpeakingAt(personId: String, timeUs: Long): Boolean {
        if (timeUs < 0 || timeUs >= analysis.result.durationUs) return false
        return speakingSpans[personId].orEmpty().any { timeUs>=it.startUs && timeUs<it.endUs }
    }
    fun gazeAt(personId: String,timeUs: Long): String? = motionAt(personId,timeUs).gaze
    fun eventAt(timeUs: Long): SoundEvent? {
        if (timeUs < 0 || timeUs >= analysis.result.durationUs) return null
        val list = analysis.result.events
        var low = 0; var high = list.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val event = list[mid]
            when { timeUs < event.startUs -> high = mid - 1; timeUs >= event.endUs -> low = mid + 1; else -> return event }
        }
        return null
    }
    fun motionAt(personId: String, timeUs: Long): Motion {
        val sound=acousticMotionAt(personId,timeUs)
        if(isSpeakingAt(personId,timeUs) || sound.kind in listOf("speech","laughter"))
            return sound.copy(action=CharacterAction.SPEAKING)
        val turn=headTurns[personId].orEmpty().firstOrNull { timeUs>=it.startUs && timeUs<it.endUs }
        return if(turn!=null)Motion(action=CharacterAction.TURNING,gaze=turn.direction) else sound
    }
    private fun acousticMotionAt(personId: String, timeUs: Long): Motion {
        val event = eventAt(timeUs) ?: return Motion()
        val override = overrides[event.eventId]
        val kind = override?.kindOverride ?: event.kind
        if (kind != "speech" && kind != "laughter") return Motion()
        val manual = override?.personIds
        val speaker = event.activeSpeakerIds.firstOrNull { project.speakerMapping[it] == personId }
        if (manual != null) { if (personId !in manual) return Motion() }
        else if (speaker == null) return Motion()
        val index = (timeUs / analysis.tracks.frameStepUs).toInt()
        val track = if (manual == null) tracks[speaker] else null
        if (manual == null && track == null) return Motion()
        val laugh = if(manual != null) kind == "laughter" else speaker in event.laughingSpeakerIds
        val activity = if (manual != null) 1.0 else if (laugh) track!!.laughterActivity[index] else track!!.activity[index]
        if (activity < .45) return Motion()
        val energy = analysis.tracks.mixedVoiceEnergy[index] * activity.coerceIn(0.0, 1.0)
        val level = (energy / event.energyReference).coerceIn(0.0, 1.0)
        val local = (timeUs - event.startUs) / 1_000_000.0
        val phase = if (event.activeSpeakerIds.size > 1 || (manual?.size ?: 0) > 1)
            (((speaker ?: personId).filter(Char::isDigit).toIntOrNull() ?: 1) - 1) * .37 else 0.0
        val pulse = .5 + .5 * sin(local * 2 * PI * (if (laugh) 4.8 else 4.6) + phase)
        val gate = level > .12 && energy > .00008
        val mouth = if (gate) (level * (.12 + .88 * pulse).pow(1.25)).coerceIn(0.0, 1.0) else 0.0
        val ramp = min(local / .12, (event.endUs-timeUs) / 1_000_000.0 / .16).coerceIn(0.0, 1.0)
        val edge = ramp * ramp * (3-2*ramp)
        val shake = if (laugh && gate) edge * (.25+.75*level) else 0.0
        return Motion(mouth, laugh && edge > 0, shake*sin(local*27+phase)*1.5,
            shake*(sin(local*30+phase)*2.6+sin(local*60+phase)*.45), shake*sin(local*25+phase)*.012,
            if (laugh) "laughter" else "speech")
    }
}
