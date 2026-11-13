package com.example.hd_project.momo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hd_project.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.storage.*
import com.example.hd_project.momo.people.*
import com.example.hd_project.momo.render.VideoComposer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.io.File

data class MomoVideoState(val busy: Boolean=false,val progress: Int=0,val message: String="이 녹음으로 영상을 만들어 보세요.",
    val movie: File?=null,val ready: Boolean=false)

class MomoVideoViewModel(application: Application): AndroidViewModel(application) {
    private val context=getApplication<Application>()
    val repository=ProjectRepository(context)
    private val mutable=MutableStateFlow(MomoVideoState())
    val state=mutable.asStateFlow()
    private lateinit var source: SavedRecording
    private var generation: Job?=null
    private var visible=true
    private var registered: SceneProject?=null

    fun bind(value: SavedRecording) {
        if(::source.isInitialized && source.file==value.file)return
        source=value
        viewModelScope.launch {
            try {
                val preset=FixedPreset.install(context)
                val id=MomoVideoSession.projectId(source.file.name)
                val old=repository.dao.project(id)?.let { repository.scene(id) }
                val hash=withContext(Dispatchers.IO) { sha256(source.file) }
                val base=old?.takeIf { it.sourceAudioSha256==hash } ?: SceneProject(id,source.title,source.file.path,hash,source.durationMs*1000)
                registered=preset.apply(base)
                repository.save(registered!!)
                repository.dao.putAudio(AudioAssetEntity(id,source.file.path,hash,"audio/mp4",source.durationMs*1000))
                val pending=repository.dao.operation(id)
                MomoVideoSession.pending(context,id,pending!=null)
                val movie=withContext(Dispatchers.IO) {
                    val people=PeopleScene.fingerprint(PeopleRepository(context).list())
                    val output=AnimationFiles.output(context,source.file)
                    val metadata=File(output.path+".json")
                    if(output.isFile && metadata.isFile) {
                        runCatching {
                            val saved=JSONObject(metadata.readText())
                            output.takeIf { saved.optString("people")==people && saved.optString("preset")==preset.fingerprint && saved.optString("audio")==hash && saved.optString("video")==sha256(output) }
                        }.getOrNull()
                    } else null
                }
                mutable.update { it.copy(ready=true,movie=movie,message=if(movie!=null)"영상이 준비됐어요." else if(pending!=null)"이전 분석을 이어서 영상을 만들 수 있어요." else "제공된 캐릭터와 배경으로 영상을 만들어요.") }
            } catch(e: Exception) { mutable.update { it.copy(message=friendly(e)) } }
        }
    }
    fun generate() {
        if(generation?.isActive==true || registered==null)return
        generation=viewModelScope.launch {
            var pendingFile: File?=null
            try {
                require(!RecordingStore.state.busy) { "녹음을 마친 뒤 영상을 만들어 주세요." }
                require(source.durationMs in 100..600000) { "영상 제작은 0.1초~10분 녹음을 지원해요." }
                val scene=registered!!
                mutable.update { it.copy(busy=true,progress=0,message="목소리를 분석하고 있어요.") }
                val analysis=repository.awaitAnalysis(scene.projectId,requireVoice=true) { message -> mutable.update { it.copy(message=message) } }
                ensureActive();check(visible) { "이 화면에서 영상 제작을 이어 주세요." }
                MomoVideoSession.beginExport(source.file.name)
                mutable.update { it.copy(busy=true,progress=0,message="캐릭터 영상을 만들고 있어요.") }
                val profiles=withContext(Dispatchers.IO) { PeopleRepository(context).list() }
                val cast=PeopleScene.build(registered!!.copy(durationUs=analysis.result.durationUs,analysisJobId=analysis.result.jobId),
                    analysis.result,profiles,FixedPreset.install(context))
                val current=cast.scene
                val prepared=VideoComposer.prepare(context,current,analysis)
                val pending=AnimationFiles.pending(context,source.file);pendingFile=pending
                withContext(Dispatchers.IO) { pending.delete() }
                VideoComposer.exportFile(context,prepared,pending) { percent -> mutable.update { it.copy(progress=percent,message="영상을 만들고 있어요 · $percent%") } }
                ensureActive()
                val output=withContext(Dispatchers.IO) {
                    val out=AnimationFiles.output(context,source.file)
                    check(pending.renameTo(out)) { "완성 영상을 저장하지 못했어요." }
                    val meta=JSONObject().put("preset",current.presetFingerprint).put("audio",current.sourceAudioSha256).put("video",sha256(out))
                        .put("people",PeopleScene.fingerprint(profiles))
                    File(out.path+".json").writeText(meta.toString());out
                }
                mutable.update { it.copy(busy=false,progress=100,movie=output,message="영상이 준비됐어요.\n${cast.summary}") }
            } catch(e: CancellationException) { mutable.update { it.copy(message="영상 제작을 멈췄어요. 다시 이어서 만들 수 있어요.") };throw e }
            catch(e: Exception) { mutable.update { it.copy(message=friendly(e)) } }
            finally {
                pendingFile?.delete();MomoVideoSession.endExport(source.file.name)
                mutable.update { it.copy(busy=false) }
            }
        }
    }
    fun cancel() {
        generation?.cancel()
        val id=registered?.projectId ?: return
        viewModelScope.launch {
            runCatching { if(repository.dao.operation(id)!=null)repository.cancel(id) }
                .onFailure { mutable.update { s -> s.copy(message=friendly(it)) } }
        }
    }
    fun foreground(value: Boolean) { visible=value;if(!value)generation?.cancel() }
    private fun friendly(e: Throwable): String {
        if(e is retrofit2.HttpException) {
            return runCatching { MomoJson.decodeFromString<ErrorResponse>(e.response()?.errorBody()?.string() ?: "").error.message }.getOrNull()
                ?: "분석 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
        return e.message ?: "영상을 만들지 못했어요. 다시 시도해 주세요."
    }
}
