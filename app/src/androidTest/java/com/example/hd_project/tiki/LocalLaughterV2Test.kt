package com.example.hd_project.tiki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.LaughterAnalyzer
import com.example.hd_project.RecordingFiles
import com.example.hd_project.momo.storage.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalLaughterV2Test {
    @Test fun bundledModelWithPositiveNegativeAndOptionalUserRecording() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val temporary=File(context.cacheDir,"laugh-benchmark-${UUID.randomUUID()}.m4a")
        val threshold=InstrumentationRegistry.getArguments().getString("benchmarkThreshold")?.toDouble() ?: .1
        val report=JSONObject().put("detector",LocalLaughter.VERSION).put("threshold",threshold)
        fun decode(file: File): FloatArray {
            val samples=ArrayList<Float>()
            LaughterAnalyzer(context).decode(file,{false},{},{},samples::add)
            return samples.toFloatArray()
        }
        try {
            LocalLaughter(context).use { detector ->
                fun measure(name: String,pcm: FloatArray,expected: Boolean?,includeFrames: Boolean=false): LocalLaughAnalysis {
                    val original=pcm.copyOf();val started=System.nanoTime()
                    val analysis=detector.analyze(pcm,threshold)
                    val ms=(System.nanoTime()-started)/1_000_000
                    report.put(name,JSONObject().put("samples",pcm.size).put("elapsedMs",ms)
                        .put("oldFrames",analysis.frames.count { it.previousAccepted>0 })
                        .put("newFrames",analysis.frames.count { it.accepted>0 })
                        .put("events",JSONArray(analysis.events.map { JSONObject().put("startMs",it.startMs).put("endMs",it.endMs).put("score",it.score) }))
                        .put("reasons",JSONObject(analysis.frames.groupingBy { it.reason }.eachCount())))
                    if(includeFrames)report.getJSONObject(name).put("frames",JSONArray(analysis.frames.map { PlaybackDiagnostics.frame(it) }))
                    assertTrue("Analysis changed source PCM: $name",original.contentEquals(pcm))
                    if(expected!=null)assertEquals("Unexpected laughter: $name",expected,analysis.events.isNotEmpty())
                    return analysis
                }
                for(name in listOf("1-73123-A-26.m4a","laughing.m4a","momo-synthetic-korean.m4a",
                    "laugh-negative-coughing.m4a","laugh-negative-breathing.m4a","laugh-negative-rain.m4a",
                    "laugh-negative-engine.m4a","laugh-negative-clock_tick.m4a")) {
                    instrumentation.context.assets.open(name).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    val pcm=decode(temporary)
                    val positive=name=="1-73123-A-26.m4a" || name=="laughing.m4a"
                    measure(name,pcm,positive,true)
                    if(name=="1-73123-A-26.m4a")measure("quiet-laughter",FloatArray(pcm.size){pcm[it]*.05f},true)
                }
                measure("silence",FloatArray(80000),false)
                val random=java.util.Random(14)
                measure("noise",FloatArray(80000){(random.nextGaussian()*.015).toFloat()},false)
                InstrumentationRegistry.getArguments().getString("benchmarkSource")?.let { name ->
                    require(File(name).name==name)
                    val source=File(RecordingFiles.directory(context),name)
                    val before=sha256(source)
                    measure("user-recording",decode(source),null,true)
                    assertEquals(before,sha256(source));report.put("sourceHashPreserved",true)
                }
            }
        } finally {
            MetadataEpisodes.write(File(context.cacheDir,"laughter-v2-benchmark.json"),report)
            temporary.delete()
        }
    }
}
