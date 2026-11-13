package com.example.hd_project.tiki

import android.content.Context
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class LocalCaption(val startMs: Long,val endMs: Long,val text: String)
data class LocalTranscript(val captions: List<LocalCaption>,val complete: Boolean,val error: String?=null)

/** Only documented ON_DEVICE file-input APIs. No mic request and no network fallback. */
object PleosLocalStt {
    suspend fun recognize(context: Context, pcm: FloatArray): LocalTranscript {
        val ready=CompletableDeferred<Unit>();val ended=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
        val texts=mutableListOf<String>();val epds=mutableListOf<Pair<Long,Long>>()
        val lock=Any()
        var failure: String?=null
        var inputFinished=false
        var stopping=false
        val stt=withContext(Dispatchers.Main) { SpeechToText(context.applicationContext,Mode.ON_DEVICE) }
        val listener=object: ResultListener {
            override fun onUpdated(stt: String,completed: Boolean) { if(completed && stt.isNotBlank()) synchronized(lock) { texts.add(stt.trim()) } }
            override fun onUpdatedEpdData(on: Long,off: Long) { if(off>on && on>=0) synchronized(lock) { epds.add(on/32 to off/32) } }
            override fun onStartedRecognition()=Unit
            override fun onEndedRecognition() { synchronized(lock) {
                if(stopping)stopped.complete(Unit) else if(inputFinished)ended.complete(Unit)
            } }
            override fun onReady()=Unit
            override fun onUpdatedRms(rms: Double)=Unit
            override fun onError(errorType: ErrorType) { synchronized(lock) { failure=errorType.name };ready.complete(Unit);ended.complete(Unit) }
        }
        var complete=false
        try {
            withContext(Dispatchers.Main) {
                stt.addListener(listener)
                stt.initialize(object: OnInitializeListener {
                    override fun onSuccess() { ready.complete(Unit) }
                    override fun onFailure(errorType: ErrorType) { synchronized(lock) { failure=errorType.name };ready.complete(Unit);ended.complete(Unit) }
                })
            }
            withTimeout(12_000) { ready.await() }
            if(synchronized(lock) { failure }==null) {
                withContext(Dispatchers.Main) { stt.setLocale(Locale.KOREA) }
                // Small Binder messages; the documented API also accepts a complete buffer.
                for(offset in pcm.indices step 1600) {
                    currentCoroutineContext().ensureActive()
                    if(synchronized(lock) { failure }!=null) break
                    val count=minOf(1600,pcm.size-offset)
                    val bytes=ByteBuffer.allocate(count*2).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(count) { bytes.putShort((pcm[offset+it]*32767).toInt().coerceIn(-32768,32767).toShort()) }
                    withContext(Dispatchers.Main) { stt.sendAudio(bytes.array()) }
                    // Use the documented 100ms cadence: this emulator queues rapid
                    // writes asynchronously and can return a previous segment late.
                    delay(100)
                }
                synchronized(lock) { inputFinished=true }
                withContext(Dispatchers.Main) { stt.completeAudioSend() }
                withTimeout(8_000) { ended.await() }
                delay(500) // Final text/EPD may arrive on a different Binder callback.
                complete=synchronized(lock) { failure==null }
            }
        } catch(e: TimeoutCancellationException) { failure="TIMEOUT" }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { failure=e.javaClass.simpleName }
        finally { withContext(NonCancellable) {
            synchronized(lock) { stopping=true }
            withContext(Dispatchers.Main) { runCatching { stt.stop() } }
            withTimeoutOrNull(2_000) { stopped.await() }
            withContext(Dispatchers.Main) { runCatching { stt.removeListener(listener) };runCatching { stt.release() } }
        } }
        return synchronized(lock) {
            val duration=pcm.size/16L
            val exactTiming=texts.size==epds.size && epds.zipWithNext().all { (a,b)->a.second<=b.first } && epds.all { it.first<duration && it.second<=duration }
            val timed=if(exactTiming) {
                texts.mapIndexedNotNull { i,text -> val (a,b)=epds[i];if(a<duration && b>a) LocalCaption(a,b.coerceAtMost(duration),text) else null }
            } else if(texts.isNotEmpty()) listOf(LocalCaption(0,duration,texts.joinToString(" "))) else emptyList()
            LocalTranscript(timed,complete,failure ?: if(texts.isNotEmpty() && !exactTiming) "APPROXIMATE_TIMING" else null)
        }
    }
}
