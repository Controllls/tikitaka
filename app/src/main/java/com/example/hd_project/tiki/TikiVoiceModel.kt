package com.example.hd_project.tiki

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import com.example.hd_project.RecordingStore

data class VoiceSetup(val stage: String="intro",val seconds: Int=0,val message: String="",val level: Float=0f)
/** Microphone PCM is memory-only; successful enrollment replaces the encrypted local template. */
class TikiVoiceModel(application: Application): AndroidViewModel(application) {
    val state=MutableStateFlow(VoiceSetup())
    @Volatile private var allowed=false
    @Volatile private var capturing=false
    private var task: Job?=null
    private var generation=0
    @Volatile private var microphone: AudioRecord?=null
    private val commitLock=Any()
    fun drivingState(value: Boolean) { allowed=value;if(!value)stopCapture() }
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if(!allowed || RecordingStore.state.busy || task?.isActive==true) {
            state.value=VoiceSetup("error",message="주차한 뒤 진행 중인 녹음을 마쳐 주세요.");return
        }
        val version=++generation;capturing=true;state.value=VoiceSetup("recording")
        task=viewModelScope.launch {
            try {
                val pcm=withContext(Dispatchers.IO) {
                    val size=maxOf(3200,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
                    val recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2)
                    microphone=recorder
                    val samples=FloatArray(16000*30);var used=0
                    try {
                        check(recorder.state==AudioRecord.STATE_INITIALIZED) { "마이크를 사용할 수 없어요." };recorder.startRecording()
                        val block=ShortArray(1600)
                        while(capturing && used<samples.size) {
                            ensureActive();val count=recorder.read(block,0,minOf(block.size,samples.size-used));check(count>0) { "마이크 입력이 중단됐어요." }
                            var peak=0f;repeat(count) { val value=block[it]/32768f;samples[used++]=value;peak=maxOf(peak,kotlin.math.abs(value)) }
                            if(version==generation)state.value=VoiceSetup("recording",used/16000,level=peak)
                        }
                        samples.copyOf(used)
                    } finally { runCatching { recorder.stop() };recorder.release();if(microphone===recorder)microphone=null;samples.fill(0f) }
                }
                if(version!=generation || !allowed) { pcm.fill(0f);return@launch }
                state.value=VoiceSetup("processing",message="내 목소리를 확인하고 있어요.")
                val reference=try { withContext(Dispatchers.Default) { LocalVoice.enroll(getApplication(),pcm) } } finally { pcm.fill(0f) }
                if(version!=generation || !allowed)return@launch
                withContext(Dispatchers.IO) {
                    synchronized(commitLock) {
                        if(version==generation && allowed) {
                            val current=TikiProfile.get(getApplication());TikiProfile.save(getApplication(),current.copy(voice=reference));state.value=VoiceSetup("success")
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(version==generation)state.value=VoiceSetup("error",message=e.message ?: "목소리를 확인하지 못했어요.") }
            finally { if(version==generation)capturing=false }
        }
    }
    fun finishCapture() { capturing=false }
    fun stopCapture() { synchronized(commitLock) {
        generation++;capturing=false;runCatching { microphone?.stop() };task?.cancel();task=null
        if(state.value.stage in listOf("recording","processing"))state.value=VoiceSetup()
    } }
    fun reset() { stopCapture();state.value=VoiceSetup() }
    override fun onCleared() { stopCapture();super.onCleared() }
}
