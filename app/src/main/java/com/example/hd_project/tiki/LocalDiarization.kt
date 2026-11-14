package com.example.hd_project.tiki

import android.content.Context
import android.os.SystemClock
import androidx.annotation.Keep
import com.example.hd_project.momo.people.VoiceReference
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject

/** A speaker index is local to this clip, not a person's identity. Null means insufficient evidence. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int?, val score: Double?, val reason: String = "clustered")
data class DiarizationResult(val turns: List<SpeakerTurn>, val speakerCount: Int, val rawSpeakerCount: Int,
    val elapsedMs: Long, val selfSpeaker: Int? = null, val voice: JSONArray = JSONArray()) {
    fun active(ms: Long) = turns.filter { ms >= it.startMs && ms < it.endMs }
    fun json() = JSONObject().put("version",LocalDiarization.VERSION).put("clusteringThreshold",LocalDiarization.CLUSTER_THRESHOLD)
        .put("speakerCount",speakerCount).put("rawSpeakerCount",rawSpeakerCount).put("elapsedMs",elapsedMs)
        .put("selfSpeaker",selfSpeaker ?: JSONObject.NULL).put("scope","selected-clip").put("turns",JSONArray(turns.map {
            JSONObject().put("startMs",it.startMs).put("endMs",it.endMs).put("speaker",it.speaker ?: JSONObject.NULL)
                .put("score",it.score ?: JSONObject.NULL).put("reason",it.reason)
        }))
}

/** Pyannote segmentation + ERes2Net embeddings + automatic clustering. All PCM stays on this device. */
object LocalDiarization {
    const val VERSION = "pyannote3-eres2net-local-v2"
    const val CLUSTER_THRESHOLD = .9f
    // Project heuristics, not Pleos requirements. Weak/brief clusters remain unknown, never forced into two people.
    private const val MIN_CLUSTER_MS = 2000L
    private const val MIN_SCORE = .35

    /** Keep clean portions of a turn instead of discarding it because of a brief interruption. */
    internal fun isolatedRanges(turns: List<SpeakerTurn>, speaker: Int, durationMs: Long): List<LongRange> {
        val boundaries=turns.flatMap { listOf(it.startMs.coerceIn(0,durationMs),it.endMs.coerceIn(0,durationMs)) }.distinct().sorted()
        val ranges=mutableListOf<LongRange>()
        boundaries.zipWithNext().forEach { (a,b) ->
            val active=turns.filter { it.startMs<b && it.endMs>a }
            if(b>a && active.isNotEmpty() && active.all { it.speaker==speaker }) {
                val previous=ranges.lastOrNull()
                if(previous!=null && previous.last+1==a)ranges[ranges.lastIndex]=previous.first until b
                else ranges.add(a until b)
            }
        }
        return ranges
    }

    internal fun isolatedPcm(samples: FloatArray, turns: List<SpeakerTurn>, speaker: Int): FloatArray {
        val ranges=isolatedRanges(turns,speaker,samples.size/16L)
        val pcm=FloatArray(ranges.sumOf { ((it.last+1-it.first)*16).toInt() })
        var used=0
        ranges.forEach { range ->
            val start=(range.first*16).toInt();val end=((range.last+1)*16).toInt()
            samples.copyInto(pcm,used,start,end);used+=end-start
        }
        return pcm
    }

    /** Include below-threshold rivals in the margin check, too. */
    internal fun selectSelf(checks: Map<Int,VoiceCheck>): Int? {
        val ranked=checks.entries.filter { it.value.score?.isFinite()==true }.sortedByDescending { it.value.score }
        val best=ranked.firstOrNull() ?: return null
        if(!best.value.mine)return null
        if(ranked.size>1 && best.value.score!!-ranked[1].value.score!!<LocalVoice.MIN_MARGIN)return null
        return best.key
    }

    internal fun resolveChecks(checks: Map<Int,VoiceCheck>): Map<Int,VoiceCheck> {
        val self=selectSelf(checks)
        return checks.mapValues { (speaker,check) ->
            if(check.mine && speaker!=self)check.copy(mine=false,reason=if(self==null)"ambiguous_speakers" else "other_speaker") else check
        }
    }

    internal fun verifySpeakers(context: Context, samples: FloatArray, turns: List<SpeakerTurn>, speakerCount: Int,
        reference: VoiceReference?, cancelled: () -> Boolean = { false }): Map<Int,VoiceCheck> =
        (0 until speakerCount).associateWith { index ->
            if(cancelled())throw kotlinx.coroutines.CancellationException("화자 분석 취소")
            val pcm=isolatedPcm(samples,turns,index)
            try { LocalVoice.verify(context,pcm,reference) } finally { pcm.fill(0f) }
        }

    // The v2.0.1 JNI looks up invoke(IIJ)Ljava/lang/Integer; an indy lambda only has the erased bridge.
    @Keep
    private class Progress(private val cancelled: () -> Boolean, private val progress: (Int) -> Unit) : (Int,Int,Long) -> Int {
        var failure: Exception? = null
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            try { if(!cancelled() && failure==null)progress(if(total>0)done*100/total else 0) }
            catch(error: Exception) { failure=error } // Never leave a pending exception in JNI.
            return if(cancelled() || failure!=null)1 else 0
        }
    }

    fun analyze(context: Context, samples: FloatArray, reference: VoiceReference? = null,
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): DiarizationResult {
        require(samples.isNotEmpty() && samples.size <= 121 * 16000) { "화자 분석은 선택된 120초 이내 영상 구간에 적용합니다." }
        if(cancelled())throw kotlinx.coroutines.CancellationException("화자 분석 취소")
        val started=SystemClock.elapsedRealtime()
        if(samples.all { kotlin.math.abs(it) < .00001f })return DiarizationResult(emptyList(),0,0,0)
        val diarizer=OfflineSpeakerDiarization(context.assets,OfflineSpeakerDiarizationConfig(
            segmentation=OfflineSpeakerSegmentationModelConfig(
                pyannote=OfflineSpeakerSegmentationPyannoteModelConfig("voice/segmentation.int8.onnx"),numThreads=2),
            embedding=SpeakerEmbeddingExtractorConfig(model="voice/eres2net.onnx",numThreads=2),
            clustering=FastClusteringConfig(numClusters=-1,threshold=CLUSTER_THRESHOLD,computeConfidence=true),
            minDurationOn=.25f,minDurationOff=.3f))
        val callback=Progress(cancelled,progress)
        val raw=try {
            check(diarizer.sampleRate()==16000)
            diarizer.processWithCallback(samples,callback)
        } finally { diarizer.release() }
        if(cancelled())throw kotlinx.coroutines.CancellationException("화자 분석 취소")
        callback.failure?.let { throw it }
        val durationMs=samples.size*1000L/16000
        val valid=raw.filter { it.start.isFinite() && it.end.isFinite() && it.end>it.start && it.speaker>=0 }
        val supported=valid.groupBy { it.speaker }.filterValues { rows ->
            rows.filter { it.confidence.isFinite() && (it.confidence==-2f || it.confidence>=MIN_SCORE) }
                .sumOf { ((it.end-it.start)*1000).toLong() }>=MIN_CLUSTER_MS
        }.entries.sortedByDescending { (_,rows)->rows.sumOf { (it.end-it.start).toDouble() } }.take(4)
            .sortedBy { (_,rows)->rows.minOf { it.start } }.map { it.key }
        val turns=valid.sortedBy { it.start }.mapNotNull { row ->
            val a=(row.start*1000).toLong().coerceIn(0,durationMs)
            val b=(row.end*1000).toLong().coerceIn(0,durationMs)
            val score=row.confidence.toDouble().takeIf { it.isFinite() && it in -1.0..1.0 }
            val index=supported.indexOf(row.speaker).takeIf { it>=0 && (row.confidence==-2f || score!=null && score>=MIN_SCORE) }
            if(b<=a)null else SpeakerTurn(a,b,index,score,if(index!=null)"clustered" else "insufficient_evidence")
        }
        val candidates=verifySpeakers(context,samples,turns,supported.size,reference,cancelled)
        val self=selectSelf(candidates)
        val checks=JSONArray()
        resolveChecks(candidates).forEach { (index,check) ->
            val cleanMs=isolatedRanges(turns,index,durationMs).sumOf { it.last+1-it.first }
            turns.filter { it.speaker==index }.forEach { turn ->
                checks.put(JSONObject().put("startMs",turn.startMs).put("endMs",turn.endMs).put("speaker",index)
                    .put("score",check.score ?: JSONObject.NULL).put("reason",check.reason).put("mine",check.mine)
                    .put("cleanSpeechMs",cleanMs).put("windowScores",JSONArray(check.windowScores)))
            }
        }
        val sortedChecks=JSONArray((0 until checks.length()).map { checks.getJSONObject(it) }.sortedBy { it.getLong("startMs") })
        return DiarizationResult(turns,supported.size,valid.map { it.speaker }.distinct().size,
            SystemClock.elapsedRealtime()-started,self,sortedChecks)
    }
}
