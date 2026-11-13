package com.example.hd_project.momo.people

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hd_project.*
import com.example.hd_project.momo.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.storage.ProjectRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class PeopleState(val profiles: List<PersonProfile> = emptyList(),val draft: PersonProfile?=null,
    val recording: SavedRecording?=null,val analysis: AnalysisBundle?=null,val busy: Boolean=false,
    val message: String="등록한 인물은 다음 녹음에서도 목소리로 찾아요. 처음 듣는 목소리는 기본 캐릭터를 사용해요.")

class PeopleViewModel(application: Application): AndroidViewModel(application) {
    private val context=getApplication<Application>()
    private val people=PeopleRepository(context)
    val projects=ProjectRepository(context)
    private val mutable=MutableStateFlow(PeopleState())
    val state=mutable.asStateFlow()
    private var work: Job?=null
    private var analysisId: String?=null
    init { refresh() }
    private fun refresh()=viewModelScope.launch {
        try { val list=withContext(Dispatchers.IO) { people.list() };mutable.update { it.copy(profiles=list) } }
        catch(e: Exception) { error(e) }
    }
    fun edit(profile: PersonProfile?=null) {
        if(state.value.busy)return
        mutable.update { it.copy(draft=profile ?: PersonProfile(UUID.randomUUID().toString(),"",CharacterCatalog.ids.first()),
            analysis=null,recording=null,message="이름과 캐릭터를 정하고 목소리를 등록해 주세요. 목소리는 나중에 등록해도 돼요.") }
    }
    fun name(value: String) { mutable.update { it.copy(draft=it.draft?.copy(name=value)) } }
    fun character(value: String) { mutable.update { it.copy(draft=it.draft?.copy(characterId=value)) } }
    fun closeEditor() { if(!state.value.busy)mutable.update { it.copy(draft=null,analysis=null,recording=null) } }
    fun clearVoice() { mutable.update { it.copy(draft=it.draft?.copy(voice=null),message="저장하면 등록 목소리가 해제돼요.") } }
    fun select(signature: SpeakerSignature) {
        val state=state.value
        val result=state.analysis?.result ?: return
        if(state.busy)return
        try {
            require(signature in result.speakerSignatures && signature.speechUs>=6_000_000) { "혼자 말한 목소리가 6초 이상 필요해요. 더 길고 또렷한 녹음을 선택해 주세요." }
            val reference=VoiceReference(requireNotNull(result.voiceModelId),signature.embedding,signature.speechUs,
                state.recording!!.title,System.currentTimeMillis())
            mutable.update { it.copy(draft=it.draft?.copy(voice=reference),message="${signature.speakerId} 목소리를 선택했어요. 저장하면 다음 영상부터 적용돼요.") }
        } catch(e: Exception) { error(e) }
    }
    fun save() {
        if(state.value.busy)return
        val draft=state.value.draft?.let { it.copy(name=it.name.trim()) } ?: return
        viewModelScope.launch {
            mutable.update { it.copy(busy=true) }
            try {
                withContext(Dispatchers.IO) {
                    draft.validate()
                    draft.voice?.let { voice ->
                        val duplicate=people.list().firstOrNull { other -> other.id!=draft.id && other.voice?.let {
                            it.modelId==voice.modelId && it.embedding.zip(voice.embedding).sumOf { (a,b) -> a*b }>=VoiceMatcher.MIN_SCORE
                        }==true }
                        require(duplicate==null) { "${duplicate?.name}의 등록 목소리와 비슷해요. 같은 인물이라면 기존 인물을 수정하고, 다른 인물이라면 더 또렷한 녹음으로 등록해 주세요." }
                    }
                    people.save(draft)
                }
                mutable.update { it.copy(draft=null,analysis=null,recording=null,message="${draft.name}을 저장했어요. 새로 만드는 영상부터 적용돼요.") };refresh()
            } catch(e: Exception) { error(e) }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun delete(profile: PersonProfile)=viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { people.delete(profile.id) }
            mutable.update { it.copy(message="${profile.name}의 인물 정보와 등록 목소리를 삭제했어요. 기존 녹음과 영상은 그대로 남아요.") };refresh()
        } catch(e: Exception) { error(e) }
    }
    fun analyze(recording: SavedRecording) {
        if(work?.isActive==true || state.value.draft==null)return
        work=viewModelScope.launch {
            mutable.update { it.copy(busy=true,recording=recording,analysis=null,message="등록할 목소리를 분석하고 있어요.") }
            try {
                require(!RecordingStore.state.busy) { "녹음을 마친 뒤 등록해 주세요." }
                val project=projects.registerRecording(recording);analysisId=project.projectId
                val bundle=projects.awaitAnalysis(project.projectId,true) { text -> mutable.update { it.copy(message=text) } }
                mutable.update { it.copy(analysis=bundle,message=if(bundle.result.speakerSignatures.any { s -> s.speechUs>=6_000_000 })
                    "미리 듣고 이 인물의 목소리를 선택해 주세요." else "등록 가능한 목소리가 부족해요. 한 사람이 혼자 10~20초 말한 녹음으로 다시 시도해 주세요.") }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error(e) }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun cancel() {
        work?.cancel()
        analysisId?.let { id -> viewModelScope.launch { runCatching { if(projects.dao.operation(id)!=null)projects.cancel(id) }.onFailure(::error) } }
        mutable.update { it.copy(message="분석 취소를 요청했어요.") }
    }
    private fun error(e: Throwable) {
        val message=if(e is retrofit2.HttpException) "분석 서버 연결을 확인해 주세요. (${e.code()})" else e.message ?: "인물 정보를 처리하지 못했어요."
        mutable.update { it.copy(message=message) }
    }
}
