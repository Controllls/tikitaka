package com.example.hd_project.tiki

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.Closeable
import kotlin.math.*

data class LocalLaugh(val startMs: Long, val endMs: Long, val score: Double)
data class LaughFrame(val startMs: Long,val endMs: Long,val raw: Double,val filtered: Double,val vocal: Double,val noise: Double,val accepted: Double,val reason: String,
    val normalized: Double?=null,val gain: Double=1.0,val family: Double=0.0,val previousAccepted: Double=0.0)
data class LocalLaughAnalysis(val events: List<LocalLaugh>,val frames: List<LaughFrame>)
internal data class AcousticScore(val laugh: Double, val vocal: Double, val noise: Double,
    val family: Double= laugh,val support: Int=if(laugh>=.01)1 else 0,val cough: Double=0.0)
internal data class LaughVerdict(val values: DoubleArray,val reasons: List<String>)

/** Same score contrasts as the web detector; model/runtime outputs can differ. */
internal object LaughDecision {
    /** Regression reference for previously saved V1 analyses. */
    fun accepted(raw: List<AcousticScore>, band: List<AcousticScore>, threshold: Double): DoubleArray {
        require(raw.size == band.size && threshold in .01..1.0)
        val scale = threshold / .1
        val result = DoubleArray(raw.size)
        fun groups(predicate: (Int) -> Boolean, accept: (List<Int>) -> Boolean, score: (Int) -> Double) {
            var start = 0
            while(start < raw.size) {
                if(!predicate(start)) { start++; continue }
                var end = start + 1
                while(end < raw.size && predicate(end)) end++
                val indices = (start until end).toList()
                if(accept(indices)) indices.forEach { result[it] = maxOf(result[it],score(it)) }
                start = end
            }
        }
        groups({ i -> raw[i].let { it.laugh >= .05*scale && it.laugh >= it.vocal*1.15 && it.laugh >= it.noise*.9 } },
            { ids -> val peak=ids.maxOf { raw[it].laugh }; peak>=.24*scale || (peak>=.12*scale && ids.size>=2) || ids.count { raw[it].laugh>=.085*scale }>=3 },
            { raw[it].laugh })
        groups({ i -> val r=raw[i];val b=band[i]
            r.laugh>=.006*scale && b.laugh>=.05*scale && b.laugh>=r.laugh*1.4 &&
                r.vocal<.03 && b.vocal<.03 && r.noise<.1 && b.noise<.1 },
            { ids -> ids.size>=3 && ids.maxOf { raw[it].laugh }>=.025*scale &&
                ids.maxOf { band[it].laugh }>=.08*scale && ids.map { band[it].laugh }.average()>=.065*scale },
            { maxOf(raw[it].laugh,band[it].laugh) })
        return result
    }

    fun enhanced(raw: List<AcousticScore>,band: List<AcousticScore>,normalized: List<AcousticScore>,normalizedBand: List<AcousticScore>,
                 rms: List<Double>,threshold: Double): LaughVerdict {
        require(listOf(band.size,normalized.size,normalizedBand.size,rms.size).all { it==raw.size })
        val scale=threshold/.1;val result=accepted(raw,band,threshold)
        val boosted=accepted(normalized,normalizedBand,threshold)
        val reasons=raw.indices.map { i -> when {
            rms[i]<=.0006 -> "inaudible"
            result[i]>raw[i].laugh -> "band_rescue"
            result[i]>0 -> "accepted"
            max(raw[i].laugh,band[i].laugh)<.025*scale -> "low_score"
            raw[i].vocal>raw[i].laugh/1.15 -> "other_vocal"
            raw[i].noise>raw[i].laugh/.9 -> "noise"
            else -> "duration_or_contrast"
        } }.toMutableList()
        for(i in raw.indices) {
            if(rms[i]<=.0006) { result[i]=0.0;continue }
            if(result[i]==0.0 && raw[i].laugh>=.006*scale && boosted[i]>0) { result[i]=boosted[i];reasons[i]="gain_rescue" }
        }
        fun clean(s: AcousticScore)=s.vocal<=s.laugh*.8 && s.noise<=max(.05,s.laugh*1.5) && s.cough<max(.01,s.laugh*.65)
        // A weak candidate needs two views and at least two related laugh labels in each view.
        // These are corroborating heuristics, not independent probabilities.
        val weak=raw.indices.map { i -> val r=raw[i];val b=band[i]
            rms[i]>.0006 && r.laugh>=.015*scale && b.laugh>=.015*scale &&
                r.family>=.06*scale && b.family>=.06*scale && r.support>=2 && b.support>=2 && clean(r) && clean(b)
        }
        var first=0
        while(first<raw.size) {
            if(!weak[first]) { first++;continue }
            var last=first+1;while(last<raw.size && weak[last])last++
            if(last-first>=2 && (first until last).maxOf { max(raw[it].laugh,band[it].laugh) }>=.04*scale)
                for(i in first until last)if(result[i]==0.0) { result[i]=max(raw[i].laugh,band[i].laugh);reasons[i]="family_consensus" }
            first=last
        }
        val seeds=result.indices.filter { result[it]>0 }.toList()
        fun continuation(i: Int): Boolean { val r=raw[i];val b=band[i]
            return rms[i]>.0006 && r.laugh>=.006*scale && max(r.laugh,b.laugh)>=.02*scale && clean(r) && clean(b)
        }
        // Lower exit gate, bounded to 480ms from an original seed. No indefinitely expanding labels.
        for(seed in seeds)for(direction in listOf(-1,1))for(distance in 1..3) {
            val i=seed+direction*distance
            if(i !in raw.indices || !continuation(i))break
            if(result[i]==0.0) { result[i]=max(raw[i].laugh,band[i].laugh);reasons[i]="continuation" }
        }
        return LaughVerdict(result,reasons)
    }
}

class LocalLaughter(context: Context): Closeable {
    companion object { const val VERSION="yamnet-local-v2";const val WINDOW=15600;const val HOP=2560 }
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(15600*4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(521*4).order(ByteOrder.nativeOrder())
    init {
        val bytes=context.assets.open("yamnet.tflite").use { it.readBytes() }
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        model.rewind()
        interpreter=Interpreter(model,Interpreter.Options().setNumThreads(2))
        check(interpreter.getInputTensor(0).numElements()==15600 && interpreter.getOutputTensor(0).numElements()==521)
    }
    fun detect(pcm: FloatArray, threshold: Double=.1, cancelled: ()->Boolean={false}) = analyze(pcm,threshold,cancelled).events
    fun analyze(pcm: FloatArray, threshold: Double=.1, cancelled: ()->Boolean={false}): LocalLaughAnalysis {
        require(pcm.size<=16_000*33) { "Analyze in bounded chunks" }
        val band=FloatArray(pcm.size)
        var previous=0.0;var high=0.0;var low=0.0
        pcm.indices.forEach { i -> high=.91*(pcm[i]-previous)+.91*high; previous=pcm[i].toDouble();low=.70*high+.30*low;band[i]=low.toFloat() }
        fun score(data: FloatArray, offset: Int,gain: Double=1.0): AcousticScore {
            check(!cancelled()) { "cancelled" }
            input.clear();repeat(15600) { input.putFloat((data.getOrElse(offset+it){0f}*gain).toFloat()) };input.rewind();output.clear()
            interpreter.run(input,output)
            fun peak(range: IntRange)=range.maxOf { output.getFloat(it*4).toDouble() }
            return AcousticScore(peak(13..18),peak(19..41),peak(42..520),
                (13..18).sumOf { output.getFloat(it*4).toDouble() },(13..18).count { output.getFloat(it*4)>=.01f },peak(42..45))
        }
        if(pcm.isEmpty())return LocalLaughAnalysis(emptyList(),emptyList())
        // Stop after the final full/partially padded window, as in the web's whole-waveform frontend.
        val offsets=List(maxOf(1,1+ceil((pcm.size-WINDOW).toDouble()/HOP).toInt())) { it*HOP }
        val raw=offsets.map { score(pcm,it) };val filtered=offsets.map { score(band,it) }
        fun level(data: FloatArray,offset: Int): Pair<Double,Double> {
            var sum=0.0;var peak=0.0
            for(i in offset until minOf(data.size,offset+WINDOW)) { val v=data[i].toDouble();sum+=v*v;peak=max(peak,abs(v)) }
            return sqrt(sum/WINDOW) to peak
        }
        val rms=offsets.map { level(pcm,it).first };val gains=DoubleArray(offsets.size) { 1.0 }
        val normalized=raw.toMutableList();val normalizedBand=filtered.toMutableList();val normalizedPeak=arrayOfNulls<Double>(offsets.size)
        for(i in offsets.indices)if(rms[i]>.0006 && (raw[i].laugh>=.006*threshold/.1 || filtered[i].laugh>=.01*threshold/.1)) {
            fun enhance(data: FloatArray,original: AcousticScore): AcousticScore {
                val (r,p)=level(data,offsets[i]);val gain=minOf(8.0,.08/max(r,1e-8),.95/max(p,1e-8)).coerceAtLeast(1.0)
                if(gain<1.25)return original
                gains[i]=max(gains[i],gain)
                return score(data,offsets[i],gain).also { normalizedPeak[i]=max(normalizedPeak[i] ?: 0.0,it.laugh) }
            }
            normalized[i]=enhance(pcm,raw[i]);normalizedBand[i]=enhance(band,filtered[i])
        }
        val verdict=LaughDecision.enhanced(raw,filtered,normalized,normalizedBand,rms,threshold)
        val previousAccepted=LaughDecision.accepted(raw,filtered,threshold)
        val accepted=verdict.values
        val energy=(pcm.indices step 320).map { first ->
            val last=minOf(pcm.size,first+320);sqrt((first until last).sumOf { pcm[it].toDouble()*pcm[it] }/(last-first))
        }
        val audibleFloor=max(.00018,min(.002,(energy.sorted().getOrNull(((energy.size-1)*.1).toInt()) ?: 0.0)*.75))
        val result=mutableListOf<LocalLaugh>()
        for(i in energy.indices) {
            val index=((i*20+10)-487.5)/160
            val left=floor(index).toInt().coerceIn(0,accepted.lastIndex);val right=ceil(index).toInt().coerceIn(0,accepted.lastIndex)
            val weight=(index-floor(index)).coerceIn(0.0,1.0)
            val value=accepted[left]*(1-weight)+accepted[right]*weight
            val start=i*20L;val end=minOf(start+20,pcm.size/16L)
            if(value>=.01*threshold/.1 && energy[i]>audibleFloor && end>start) {
                val last=result.lastOrNull()
                if(last!=null && start<=last.endMs+160) result[result.lastIndex]=last.copy(endMs=end,score=maxOf(last.score,value))
                else result.add(LocalLaugh(start,end,value))
            }
        }
        val frames=offsets.indices.mapNotNull { i ->
            val start=if(i==0)0L else (offsets[i]/16L+407).coerceAtMost(pcm.size/16L)
            val end=if(i==offsets.lastIndex)pcm.size/16L else minOf(offsets[i]/16L+567,pcm.size/16L)
            if(end<=start)null else LaughFrame(start,end,raw[i].laugh,filtered[i].laugh,raw[i].vocal,raw[i].noise,accepted[i],
                verdict.reasons[i],normalizedPeak[i],gains[i],raw[i].family,previousAccepted[i])
        }
        return LocalLaughAnalysis(result.filter { it.endMs-it.startMs>=80 },frames)
    }
    override fun close()=interpreter.close()
}
