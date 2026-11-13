package com.example.hd_project.momo.people

import com.example.hd_project.momo.InstalledPreset
import com.example.hd_project.momo.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

@Serializable data class VoiceReference(val modelId: String, val embedding: List<Double>, val speechUs: Long,
    val sourceTitle: String, val enrolledAt: Long)
@Serializable data class PersonProfile(val id: String, val name: String, val characterId: String, val voice: VoiceReference? = null) {
    fun validate() {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(name.isNotBlank() && name.length<=30 && name.none { it.isISOControl() }) { "이름은 1~30자로 입력해 주세요." }
        require(characterId in CharacterCatalog.ids || characterId in com.example.hd_project.tiki.TikiProfile.characters)
        voice?.let {
            val minimum=if(it.modelId==com.example.hd_project.tiki.LocalVoice.MODEL)4_000_000L else 6_000_000L
            require(it.modelId.isNotBlank() && it.speechUs in minimum..30_000_000L) { "혼자 문장을 조금 더 길게 읽어 주세요." }
            val dimensions=if(it.modelId==com.example.hd_project.tiki.LocalVoice.MODEL)com.example.hd_project.tiki.LocalVoice.DIMENSIONS else 192
            require(it.embedding.size==dimensions && it.embedding.all(Double::isFinite))
            require(kotlin.math.abs(it.embedding.sumOf { v -> v*v }-1.0)<.02)
        }
    }
}

object CharacterCatalog {
    val ids=listOf("character_01","character_02")
    val labels=listOf("캐릭터 1 · 기본","캐릭터 2")
    fun manifest(id: String): String { require(id in ids);return "resources/characters/$id/manifest.json" }
}

data class VoiceMatch(val speakerId: String, val profile: PersonProfile?, val reason: String)

/** Conservative open-set matching. Thresholds are provisional, not a calibrated identity guarantee. */
object VoiceMatcher {
    const val MIN_SCORE=.65
    const val MIN_MARGIN=.08
    fun match(result: AnalysisResult, profiles: List<PersonProfile>): List<VoiceMatch> {
        return match(result.speakers, result.speakerSignatures, result.voiceModelId, profiles)
    }
    fun match(speakers: List<Speaker>, signatures: List<SpeakerSignature>, modelId: String?, profiles: List<PersonProfile>): List<VoiceMatch> {
        val proposed=speakers.map { speaker ->
            val signature=signatures.find { it.speakerId==speaker.speakerId }
            if(signature==null || signature.speechUs<3_000_000L) return@map VoiceMatch(speaker.speakerId,null,"목소리 정보 부족")
            val scores=profiles.mapNotNull { p -> p.voice?.takeIf { it.modelId==modelId && it.embedding.size==signature.embedding.size }
                ?.let { v -> p to v.embedding.zip(signature.embedding).sumOf { (a,b) -> a*b } } }.sortedByDescending { it.second }
            val best=scores.firstOrNull()
            when {
                best==null -> VoiceMatch(speaker.speakerId,null,"등록된 목소리 없음")
                !best.second.isFinite() || best.second<MIN_SCORE -> VoiceMatch(speaker.speakerId,null,"일치하는 목소리 없음")
                scores.size>1 && best.second-scores[1].second<MIN_MARGIN -> VoiceMatch(speaker.speakerId,null,"여러 인물과 비슷한 목소리")
                else -> VoiceMatch(speaker.speakerId,best.first,"등록된 목소리와 매칭")
            }
        }
        val duplicates=proposed.mapNotNull { it.profile?.id }.groupingBy { it }.eachCount().filterValues { it>1 }.keys
        return proposed.map { if(it.profile?.id in duplicates) it.copy(profile=null,reason="화자 구분이 불확실함") else it }
    }
}

data class CastScene(val scene: SceneProject, val summary: String)
object PeopleScene {
    fun fingerprint(profiles: List<PersonProfile>): String = MessageDigest.getInstance("SHA-256")
        .digest(("people-v1:"+MomoJson.encodeToString(profiles.sortedBy { it.id })).toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun build(base: SceneProject, result: AnalysisResult, profiles: List<PersonProfile>, preset: InstalledPreset): CastScene {
        val matches=VoiceMatcher.match(result,profiles)
        val visible=matches.take(4)
        var unknown=0
        val people=visible.mapIndexed { index, match ->
            Person("P${index+1}",match.profile?.name ?: "새로운 사람 ${++unknown}",SeatIds[index],
                manifestRef=preset.file(CharacterCatalog.manifest(match.profile?.characterId ?: CharacterCatalog.ids.first())).path)
        }
        val mapping=visible.mapIndexed { index, m -> m.speakerId to people[index].personId }.toMap()
        val summary=if(matches.isEmpty()) "대화 목소리가 감지되지 않았어요." else visible.mapIndexed { i,m ->
            "${m.speakerId} → ${people[i].name}"+(if(m.profile==null) " · 기본 (${m.reason})" else "")
        }.joinToString("\n") + if(matches.size>4) "\n화자는 ${matches.size}명이며, 화면에는 처음 4명을 표시해요. 나머지 목소리도 오디오에 남아요." else ""
        return CastScene(preset.apply(base).copy(people=people,speakerMapping=mapping,eventOverrides=emptyList()),summary)
    }
}
