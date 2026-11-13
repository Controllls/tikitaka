package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.momo.people.VoiceReference
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt

data class VoiceCheck(val score: Double?,val mine: Boolean,val reason: String)

/** Local CAM++ verification, not speech-to-text or biometric authentication. Thresholds need vehicle calibration. */
object LocalVoice {
    const val MODEL="campplus-voxceleb-16k-357a834f-v1"
    const val THRESHOLD=.65
    const val DIMENSIONS=512
    @Synchronized fun embedding(context: Context, pcm: FloatArray): List<Double> {
        require(pcm.size>=32000) { "문장을 조금 더 길게 읽어 주세요." }
        val extractor=SpeakerEmbeddingExtractor(context.assets,SpeakerEmbeddingExtractorConfig(model="voice/campplus.onnx",numThreads=2,debug=false,provider="cpu"))
        try {
            val stream=extractor.createStream()
            try {
                stream.acceptWaveform(pcm,16000);stream.inputFinished();check(extractor.isReady(stream)) { "목소리가 충분히 들리지 않았어요." }
                val vector=extractor.compute(stream);val norm=sqrt(vector.sumOf { it.toDouble()*it })
                check(vector.size==DIMENSIONS && norm>1e-9 && norm.isFinite()) { "목소리 특징을 확인하지 못했어요." }
                return vector.map { it/norm }
            } finally { stream.release() }
        } finally { extractor.release() }
    }
    fun cosine(a: List<Double>,b: List<Double>)=if(a.size==b.size)a.zip(b).sumOf { (x,y)->x*y } else -1.0
    fun enroll(context: Context, pcm: FloatArray): VoiceReference {
        require(pcm.size in 64000..480000) { "문장을 4초 이상, 30초 이내로 읽어 주세요." }
        val voiced=pcm.asList().chunked(320).count { frame->frame.sumOf { it.toDouble()*it }/frame.size>.00004 }
        require(voiced>=100 && pcm.count { kotlin.math.abs(it)>.98f }<pcm.size/50) { "주변 소음을 줄이고 마이크에서 조금 떨어져 다시 읽어 주세요." }
        val mid=pcm.size/2
        val first=embedding(context,pcm.copyOfRange(0,mid));val second=embedding(context,pcm.copyOfRange(mid,pcm.size))
        require(cosine(first,second)>=.45) { "일관된 목소리를 확인하지 못했어요. 혼자 문장을 다시 읽어 주세요." }
        return VoiceReference(MODEL,embedding(context,pcm),pcm.size*1_000_000L/16000,"기기 내 목소리 확인",System.currentTimeMillis())
    }
    fun isMine(context: Context, pcm: FloatArray, reference: VoiceReference?): Boolean =
        reference?.modelId==MODEL && pcm.size>=48000 && runCatching { cosine(embedding(context,pcm),reference.embedding)>=THRESHOLD }.getOrDefault(false)

    /** Sample the complete segment; never append a <3s tail that necessarily fails isMine(). */
    internal fun windows(sampleCount: Int): List<IntRange> {
        if(sampleCount<48000)return emptyList()
        val width=minOf(sampleCount,80000)
        val count=minOf(8,((sampleCount+47999)/48000)).coerceAtLeast(1)
        return (0 until count).map { i ->
            val start=if(count==1)0 else ((sampleCount-width).toLong()*i/(count-1)).toInt()
            start until start+width
        }.distinct()
    }
    fun verify(context: Context, pcm: FloatArray, reference: VoiceReference?): VoiceCheck {
        if(reference==null)return VoiceCheck(null,false,"not_enrolled")
        if(reference.modelId!=MODEL)return VoiceCheck(null,false,"model_mismatch")
        val ranges=windows(pcm.size)
        if(ranges.isEmpty())return VoiceCheck(null,false,"too_short")
        return try {
            val scores=ranges.map { range ->
                val window=pcm.copyOfRange(range.first,range.last+1)
                try { cosine(embedding(context,window),reference.embedding).coerceIn(-1.0,1.0) } finally { window.fill(0f) }
            }
            val lowest=scores.min()
            VoiceCheck(lowest,lowest>=THRESHOLD,if(lowest>=THRESHOLD)"matched" else "below_threshold")
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(_: Exception) { VoiceCheck(null,false,"inference_error") }
    }
}
