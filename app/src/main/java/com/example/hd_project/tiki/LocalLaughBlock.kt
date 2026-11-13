package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.AudioClipper
import com.example.hd_project.HighlightRange
import com.example.hd_project.LaughterAnalyzer
import java.io.Closeable
import java.io.File

/** Keep 640ms context on both sides; commit each event only inside its owning 30s core. */
internal class LocalLaughBlock(val pcm: FloatArray,val offsetMs: Long,val startMs: Long,val endMs: Long): Closeable {
    fun speechPcm(): FloatArray {
        val first=((startMs-offsetMs)*16).toInt().coerceIn(0,pcm.size)
        val last=((endMs-offsetMs)*16).toInt().coerceIn(first,pcm.size)
        return pcm.copyOfRange(first,last)
    }
    fun trim(value: LocalLaughAnalysis)=LocalLaughAnalysis(
        value.events.mapNotNull { e -> val a=maxOf(startMs,e.startMs+offsetMs);val b=minOf(endMs,e.endMs+offsetMs)
            if(b>a)e.copy(startMs=a,endMs=b) else null },
        value.frames.mapNotNull { f -> val a=maxOf(startMs,f.startMs+offsetMs);val b=minOf(endMs,f.endMs+offsetMs)
            if(b>a)f.copy(startMs=a,endMs=b) else null })
    override fun close()=pcm.fill(0f)
    companion object {
        fun read(context: Context,source: File,root: File,startMs: Long,endMs: Long,durationMs: Long,cancelled: ()->Boolean): LocalLaughBlock {
            val temporary=File(root,"laugh-context-$startMs.m4a")
            val buffer=FloatArray(16000*33);var count=0
            try {
                val range=AudioClipper.export(source,temporary,HighlightRange(maxOf(0,startMs-640),minOf(durationMs,endMs+640),0f),cancelled)
                LaughterAnalyzer(context).decode(temporary,cancelled,{},{}) { require(count<buffer.size);buffer[count++]=it }
                return LocalLaughBlock(buffer.copyOf(count),range.startMs,startMs,endMs)
            } finally { temporary.delete();buffer.fill(0f) }
        }
    }
}
