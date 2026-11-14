package com.example.hd_project.momo.render

import com.example.hd_project.momo.model.*
import kotlin.math.*

data class Motion(val mouth: Double = 0.0, val laughing: Boolean = false, val dx: Double = 0.0,
    val dy: Double = 0.0, val tilt: Double = 0.0, val kind: String = "rest")

/** v13 audio-events.motion port. Integer source time, independent of wall clock and UI. */
class MotionEngine(private val project: SceneProject, private val analysis: AnalysisBundle) {
    private val tracks = analysis.tracks.speakerTracks.associateBy { it.speakerId }
    private val overrides = project.eventOverrides.associateBy { it.eventId }
    fun gazeAt(personId: String,timeUs: Long): String? {
        if(project.rendererVersion !in listOf("tikitaka-local-cast-v2","tikitaka-local-voice-v3","tikitaka-local-diagnostic-v4","tikitaka-local-laugh-v5","tikitaka-local-diarization-v6","tikitaka-local-wide-v7","tikitaka-local-story-v8","tikitaka-local-story-v9","tikitaka-local-story-v10","tikitaka-local-story-v11") || project.people.size<2)return null
        val directed=StoryDirection.frame(project,analysis,timeUs).exchangeSpeaker
        val caption=analysis.result.captions.firstOrNull { timeUs>=it.startUs && timeUs<it.endUs }
        if(directed==null && (caption==null || !Regex("[?？]|왜|어떻게|어디|누구|무슨|언제|뭐|니$|나요$|까요$").containsMatchIn(caption.text)))return null
        val speaker=directed ?: caption?.speakerIds?.singleOrNull()?.let(project.speakerMapping::get) ?: return null
        val target=if(personId==speaker)project.people.firstOrNull { it.personId!=speaker } else project.people.firstOrNull { it.personId==speaker }
        val person=project.people.firstOrNull { it.personId==personId } ?: return null
        fun x(seat: String)=when(seat) { "driver"->1530;"front_passenger"->378;"rear_left"->1242;else->648 }
        return target?.let { if(x(it.seat)<x(person.seat))"left" else "right" }
    }
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
