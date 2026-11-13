package com.example.hd_project.tiki

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.example.hd_project.momo.model.AnalysisBundle
import com.example.hd_project.momo.model.SceneProject
import com.example.hd_project.momo.render.MotionEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Local, time-indexed evidence. Scores are not calibrated probabilities or speaker identities. */
object PlaybackDiagnostics {
    const val VERSION="playback-diagnostics-v1"
    const val STEP_MS=100L
    data class DetectionState(val laughter: Boolean?,val self: Boolean?,val unknown: Boolean?,
        val selfLabel: String,val unknownLabel: String)
    fun emphasizeSpeakers(value: String): CharSequence = SpannableString(value).apply {
        var start=0
        for(line in value.split('\n')) {
            if(line.startsWith("화자 ") && line.contains("● 감지됨")) {
                val color=if(line.startsWith("화자 불분명"))0xffffd180.toInt() else 0xff8be9a8.toInt()
                setSpan(ForegroundColorSpan(color),start,start+line.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD),start,start+line.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start+=line.length+1
        }
    }
    fun file(movie: File)=File(movie.path+".diagnostics.json")
    private fun rows(array: JSONArray)=(0 until array.length()).map { array.getJSONObject(it) }
    internal fun frame(value: LaughFrame,offset: Long=0)=JSONObject()
        .put("startMs",value.startMs+offset).put("endMs",value.endMs+offset)
        .put("raw",value.raw).put("filtered",value.filtered).put("vocal",value.vocal).put("noise",value.noise)
        .put("accepted",value.accepted).put("reason",value.reason)
        .put("normalized",value.normalized ?: JSONObject.NULL).put("gain",value.gain).put("family",value.family)
        .put("previousAccepted",value.previousAccepted)
        .put("cough",value.cough).put("filteredCough",value.filteredCough).put("filteredNoise",value.filteredNoise)
        .put("support",value.support).put("filteredSupport",value.filteredSupport).put("filteredFamily",value.filteredFamily)

    /** Backfill only laugh scores for the selected clip. Existing STT/events/plan remain unchanged. */
    fun laughter(context: Context,source: File,root: File,startMs: Long,endMs: Long,threshold: Double,cancelled: ()->Boolean): JSONArray {
        val all=JSONArray()
        LocalLaughter(context).use { detector ->
            for(start in startMs/30000*30000 until endMs step 30000) {
                check(!cancelled()) { "cancelled" }
                val trace=File(root,"laugh-trace-v3-$start.json")
                val data=if(trace.isFile)JSONObject(trace.readText()) else {
                    val duration=RecordingJournal.duration(source)
                    LocalLaughBlock.read(context,source,root,start,minOf(start+30000,duration),duration,cancelled).use { block ->
                        val analysis=block.trim(detector.analyze(block.pcm,threshold,cancelled))
                        JSONObject().put("frames",JSONArray(analysis.frames.map { frame(it) })).put("source",LocalLaughter.VERSION)
                            .also { MetadataEpisodes.write(trace,it) }
                    }
                }
                rows(data.getJSONArray("frames")).filter { it.getLong("startMs")<endMs && it.getLong("endMs")>startMs }.forEach {
                    all.put(JSONObject(it.toString()).put("startMs",maxOf(0,it.getLong("startMs")-startMs))
                        .put("endMs",minOf(endMs-startMs,it.getLong("endMs")-startMs)))
                }
            }
        }
        return all
    }

    fun build(scene: SceneProject,bundle: AnalysisBundle,offsetMs: Long,threshold: Double,sttStatus: String,
              voice: JSONArray,laughter: JSONArray,diarization: DiarizationResult? = null): JSONObject {
        val motion=MotionEngine(scene,bundle)
        val frames=JSONArray()
        for(ms in 0 until (bundle.result.durationUs+999)/1000 step STEP_MS) {
            val us=ms*1000
            val speech=if(diarization!=null)diarization.active(ms).mapNotNull { it.speaker?.let { i->"performance${i+1}" } }
                else bundle.result.captions.filter { it.captionId.startsWith("c") && us>=it.startUs && us<it.endUs }.flatMap { it.speakerIds }.distinct()
            val event=motion.eventAt(us)
            frames.put(JSONObject().put("timeMs",ms).put("energy",bundle.tracks.mixedVoiceEnergy[(us/bundle.tracks.frameStepUs).toInt()])
                .put("laughterScore",event?.score ?: 0.0)
                .put("actors",JSONArray(scene.people.map { person ->
                    val state=motion.motionAt(person.personId,us)
                    val speaker=scene.speakerMapping.entries.firstOrNull { it.value==person.personId }?.key
                    JSONObject().put("speech",speaker in speech).put("laughAssigned",speaker in event?.laughingSpeakerIds.orEmpty())
                        .put("mouth",state.mouth).put("smile",state.laughing)
                        // Supplied sprites prioritize happy/talk over a requested side pose, just as SceneRenderer does.
                        .put("gaze",if(state.laughing || state.mouth>.18)"front" else motion.gazeAt(person.personId,us) ?: "front")
                })))
        }
        return JSONObject().put("schema",VERSION).put("renderer",scene.rendererVersion).put("sourceOffsetMs",offsetMs)
            .put("durationMs",bundle.result.durationUs/1000).put("stepMs",STEP_MS).put("threshold",threshold)
            .put("sttStatus",sttStatus).put("detector",LocalLaughter.VERSION).put("captionTiming","approximate-within-sdk-segment")
            .put("diarization",diarization?.json() ?: JSONObject.NULL)
            .put("castNames",JSONArray(scene.people.map { it.name })).put("voice",voice).put("laughter",laughter).put("frames",frames)
    }

    class Timeline(val data: JSONObject) {
        init { require(data.getString("schema")==VERSION) }
        private val frames=rows(data.getJSONArray("frames"))
        private val laughter=rows(data.getJSONArray("laughter"))
        private val voice=rows(data.getJSONArray("voice"))
        private val names=data.getJSONArray("castNames")
        private val diarization=data.optJSONObject("diarization")
        private val turns=diarization?.optJSONArray("turns")?.let(::rows).orEmpty()
        private fun active(ms: Long)=if(ms>=data.getLong("durationMs"))emptyList() else turns.filter { ms>=it.getLong("startMs") && ms<it.getLong("endMs") }
        private fun speakerLines(ms: Long): List<String> {
            val active=active(ms)
            val count=diarization!!.getInt("speakerCount")
            return (0 until maxOf(2,names.length())).map { index ->
                val rows=active.filter { !it.isNull("speaker") && it.getInt("speaker")==index }
                val score=rows.mapNotNull { if(it.isNull("score"))null else it.getDouble("score") }.maxOrNull()
                val self=if(!diarization.isNull("selfSpeaker") && diarization.getInt("selfSpeaker")==index)" · 나" else ""
                "화자 ${index+1}: "+when { rows.isNotEmpty()->"● 감지됨 · 점수 ${score?.let(::n) ?: "—"}$self"
                    index>=count->"— 검출된 화자 없음";else->"대기$self" }
            }
        }
        private fun at(list: List<JSONObject>,ms: Long,start: String,end: String?=null): JSONObject? {
            var lo=0;var hi=list.lastIndex;var found=-1
            while(lo<=hi) { val mid=(lo+hi) ushr 1;if(list[mid].getLong(start)<=ms) { found=mid;lo=mid+1 } else hi=mid-1 }
            return list.getOrNull(found)?.takeIf { end==null || ms<it.getLong(end) }
        }
        private fun n(value: Double)=String.format(Locale.US,"%.3f",value)
        /** Only saved voice verification establishes identity. Staged actor IDs never do. */
        fun detection(positionMs: Long): DetectionState {
            val ms=positionMs.coerceAtLeast(0)
            if(ms>=data.getLong("durationMs"))return DetectionState(false,false,false,"없음","없음")
            val current=at(frames,ms,"timeMs") ?: return DetectionState(null,null,null,"기록 없음","기록 없음")
            if(diarization!=null) {
                val active=active(ms)
                val self=if(diarization.isNull("selfSpeaker"))null else active.any {
                    !it.isNull("speaker") && it.getInt("speaker")==diarization.getInt("selfSpeaker")
                }
                val unknown=active.any { it.isNull("speaker") }
                return DetectionState(current.optDouble("laughterScore",0.0)>0,self,unknown,
                    if(self==true)"감지됨" else if(self==null)"일치 미확인" else "없음",if(unknown)"감지됨 · 발화" else "없음")
            }
            val check=at(voice,ms,"startMs","endMs")
            val laugh=current.optDouble("laughterScore",0.0)>0
            val actors=current.optJSONArray("actors") ?: JSONArray()
            val inCaption=(0 until actors.length()).any { actors.getJSONObject(it).optBoolean("speech") }
            // Approximate speech activity only: captions + local audio energy, not diarization or VAD.
            val audibleSpeech=inCaption && current.optDouble("energy",0.0)>.00008
            val enrolled=if(check!=null)check.optString("reason") !in listOf("not_enrolled","model_mismatch","")
                else voice.any { it.optString("reason") !in listOf("not_enrolled","model_mismatch","") }
            val matched=audibleSpeech && check?.optString("reason")=="matched"
            val self=if(!enrolled)null else matched
            val unclearSpeech=audibleSpeech && !matched
            val unknown=laugh || unclearSpeech // Laughter ownership has no identity evidence.
            val selfLabel=when {
                !enrolled -> "등록 없음"
                matched -> "감지됨"
                audibleSpeech -> "일치 미확인"
                else -> "없음"
            }
            val unknownLabel=when {
                laugh && unclearSpeech -> "감지됨 · 웃음/발화 추정"
                laugh -> "감지됨 · 웃음"
                unclearSpeech -> "감지됨 · 발화 추정"
                else -> "없음"
            }
            return DetectionState(laugh,self,unknown,selfLabel,unknownLabel)
        }
        private fun reason(frame: JSONObject?): String = when(frame?.optString("reason")) {
            "accepted" -> "검출"
            "band_rescue" -> "대역 보완 검출"
            "gain_rescue" -> "음량 보완 검출"
            "family_consensus" -> "복수 웃음 항목 일치"
            "mixed_laughter" -> "다른 발성과 함께 웃음 검출"
            "continuation" -> "주변 웃음 이어짐"
            "inaudible" -> "음량 부족"
            "noise" -> "환경음 우세"
            "other_vocal" -> "다른 발성 우세"
            "low_score" -> "점수 부족"
            "duration_or_contrast" -> "지속·점수 대비 부족"
            else -> "측정 없음"
        }
        fun summary(positionMs: Long,audioTracks: Int): String {
            val ms=positionMs.coerceAtLeast(0);val state=detection(ms)
            val current=if(ms<data.getLong("durationMs"))at(frames,ms,"timeMs") else null
            val laugh=at(laughter,ms,"startMs","endMs")
            val version=if(data.optString("detector")==LocalLaughter.VERSION)"개선 분석" else "이전 분석"
            val self=when(state.self) {
                true -> "● 감지됨 · 내 목소리"
                false -> if(state.selfLabel=="없음")"대기" else "대기 · ${state.selfLabel}"
                null -> "— ${state.selfLabel}"
            }
            if(diarization!=null) {
                val unknown=active(ms).any { it.isNull("speaker") }
                return (listOf(
                    "재생 ${n(ms/1000.0)}s · 원본 ${n((ms+data.getLong("sourceOffsetMs"))/1000.0)}s",
                    "웃음 원점수: ${laugh?.let { n(it.getDouble("raw")) } ?: "—"} · 필터 ${laugh?.let { n(it.getDouble("filtered")) } ?: "—"}",
                    "최종 반영: ${current?.let { n(it.optDouble("laughterScore",0.0)) } ?: "—"} · 감도 ${n(data.getDouble("threshold"))}",
                    "분석 판정: ${reason(laugh)}"
                )+speakerLines(ms)+listOf(
                    "화자 불분명: ${if(unknown)"● 감지됨 · 발화" else "대기"}",
                    "기기 내 화자 구분 · 웃음 주인 미확인",
                    "오디오 ${if(audioTracks>=0)audioTracks.toString() else "미확인"}트랙 · 점수≠확률"
                )).joinToString("\n")
            }
            return listOf(
                "재생 ${n(ms/1000.0)}s · 원본 ${n((ms+data.getLong("sourceOffsetMs"))/1000.0)}s",
                "웃음 원점수: ${laugh?.let { n(it.getDouble("raw")) } ?: "—"} · 필터 ${laugh?.let { n(it.getDouble("filtered")) } ?: "—"}",
                "최종 반영: ${current?.let { n(it.optDouble("laughterScore",0.0)) } ?: "—"} · 감도 ${n(data.getDouble("threshold"))}",
                "분석 판정: ${reason(laugh)}",
                "화자 1: $self",
                "화자 2: 구분 꺼짐",
                "화자 불분명: ${if(state.unknown==true)"● ${state.unknownLabel}" else if(state.unknown==false)"대기" else "— 기록 없음"}",
                "발화 시점 추정 · 점수≠확률",
                "오디오 ${if(audioTracks>=0)audioTracks.toString() else "미확인"}트랙 · $version"
            ).joinToString("\n")
        }
        fun text(positionMs: Long,audioTracks: Int): String {
            val ms=positionMs.coerceAtLeast(0)
            val current=if(ms<data.getLong("durationMs"))at(frames,ms,"timeMs") else null
            val laugh=at(laughter,ms,"startMs","endMs");val check=at(voice,ms,"startMs","endMs")
            val out=mutableListOf("재생 ${n(ms/1000.0)}s · 원본 ${n((ms+data.getLong("sourceOffsetMs"))/1000.0)}s",
                "오디오 ${if(audioTracks>=0)audioTracks.toString() else "미확인"}트랙 · 캐릭터 ${names.length()}명 배치")
            out.add(if(laugh==null)"웃음 원점수 — · 해당 시각 측정 없음" else "웃음 원점수 ${n(laugh.getDouble("raw"))} / 필터 ${n(laugh.getDouble("filtered"))}")
            out.add(if(laugh==null)"다른 발성 — / 환경음 —" else "다른 발성 ${n(laugh.getDouble("vocal"))} / 환경음 ${n(laugh.getDouble("noise"))}")
            if(data.optString("detector")==LocalLaughter.VERSION)out.add("음량 보정 ${if(laugh!=null && !laugh.isNull("normalized"))n(laugh.getDouble("normalized")) else "—"} · ${n(laugh?.optDouble("gain",1.0) ?: 1.0)}배")
            out.add(if(data.optString("detector")==LocalLaughter.VERSION)
                "초기 판정 ${laugh?.let { n(it.optDouble("previousAccepted")) } ?: "—"} → 보완 ${laugh?.let { n(it.getDouble("accepted")) } ?: "—"} / 감도 ${n(data.getDouble("threshold"))}"
                else "재측정 판정 ${laugh?.let { n(it.getDouble("accepted")) } ?: "—"} / 감도 ${n(data.getDouble("threshold"))}")
            val score=current?.optDouble("laughterScore")
            out.add("최종 웃음 구간: ${if((score ?: 0.0)>0)"감지됨" else "없음"}")
            out.add("현재 분석 창의 판정 이유: ${reason(laugh)}")
            out.add("음성 에너지 ${current?.let { String.format(Locale.US,"%.5f",it.getDouble("energy")) } ?: "—"} · 100ms 갱신")
            val who=when(check?.optString("reason")) { "not_enrolled"->"등록 없음";"model_mismatch"->"등록 모델 다름";"too_short"->"3초 미만";"matched"->"내 목소리 일치";"below_threshold"->"일치 미확인";"inference_error"->"추론 실패";else->"대사 없음" }
            out.add(if(diarization==null)"화자: $who · 자동 다중 분리 꺼짐" else
                "기기 내 분리 ${diarization.getInt("speakerCount")}명 · ${n(diarization.getLong("elapsedMs")/1000.0)}초 분석")
            out.add("내 목소리 유사도 ${if(check!=null && !check.isNull("score"))n(check.getDouble("score")) else "—"} / ${n(LocalVoice.THRESHOLD)}")
            repeat(names.length()) { i ->
                val a=current?.optJSONArray("actors")?.optJSONObject(i)
                out.add("캐릭터 ${i+1} · 대사 배정 ${if(a?.optBoolean("speech")==true)"있음" else "없음"} · 웃음 연출 ${if(a?.optBoolean("laughAssigned")==true)"있음" else "없음"}")
                out.add("   입 ${a?.let { n(it.optDouble("mouth")) } ?: "—"} · 표정 ${if(a?.optBoolean("smile")==true)"웃음" else "기본"} · ${when(a?.optString("gaze")) { "left"->"왼쪽";"right"->"오른쪽";else->"정면" }}")
            }
            out.add("자막 시간 추정 · ${if(data.optString("sttStatus")=="complete")"전사 완료" else "부분 인식"} · 점수≠확률")
            return out.joinToString("\n")
        }
    }
}
