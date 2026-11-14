package com.example.hd_project.momo.render

import com.example.hd_project.momo.model.*

/** Text rules select framing; only acoustic speaker assignments choose its subject. */
object StoryDirection {
    data class Frame(val focusPersonId: String? = null, val exchangeSpeaker: String? = null)
    fun frame(project: SceneProject, analysis: AnalysisBundle, timeUs: Long): Frame {
        val beat = project.storyBeats.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs } ?: return Frame()
        // Keep framing stable for this beat. Unknown/overlapping speech stays a group shot.
        val caption = analysis.result.captions.firstOrNull { it.endUs > beat.startUs && it.startUs < beat.endUs && it.text != "(웃음)" }
        val speaker = caption?.speakerIds?.singleOrNull()?.let(project.speakerMapping::get) ?: return Frame()
        return when (beat.shot) {
            "speaker" -> Frame(focusPersonId = speaker)
            "reaction" -> Frame(focusPersonId = project.people.firstOrNull { it.personId != speaker }?.personId)
            "exchange" -> Frame(exchangeSpeaker = speaker)
            else -> Frame()
        }
    }
}
