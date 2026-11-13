package com.example.hd_project.momo

import com.example.hd_project.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.network.InstallationAuthenticationException
import com.example.hd_project.momo.storage.*
import kotlinx.coroutines.*

suspend fun ProjectRepository.registerRecording(recording: SavedRecording): SceneProject = withContext(Dispatchers.IO) {
    require(recording.file.isFile && recording.durationMs in 100..600000) { "0.1초~10분 녹음을 선택해 주세요." }
    val id=MomoVideoSession.projectId(recording.file.name)
    val hash=sha256(recording.file)
    val old=dao.project(id)?.let { scene(id) }
    val project=old?.takeIf { it.sourceAudioSha256==hash } ?: SceneProject(id,recording.title,recording.file.path,hash,recording.durationMs*1000)
    save(project)
    dao.putAudio(AudioAssetEntity(id,recording.file.path,hash,"audio/mp4",recording.durationMs*1000))
    project
}

suspend fun ProjectRepository.awaitAnalysis(id: String, requireVoice: Boolean, progress: (String)->Unit): AnalysisBundle {
    var analysis=loadAnalysis(id)
    if(analysis!=null && (!requireVoice || analysis.result.voiceModelId!=null))return analysis
    require(!RecordingStore.state.busy) { "녹음을 마친 뒤 분석해 주세요." }
    val endpoint=BuildConfig.MOMO_API_URL
    require(endpoint.isNotBlank()) { "분석 서버가 아직 준비되지 않았어요." }
    client(endpoint).requireAnalysisReady()
    if(analysis!=null)dao.deleteAnalysis(id) // Legacy analysis lacks persistent voice features.
    enqueue(id,endpoint)
    while(true) {
        currentCoroutineContext().ensureActive()
        // Also resume an upload after WorkManager KEEP races with a finishing older request.
        runCatching { syncOnce(id) }.onFailure {
            if(it is InstallationAuthenticationException && it.permanent)throw it
            if(it is CancellationException || (it is retrofit2.HttpException && it.code() in listOf(400,401,403,404,410,413,415,422)))throw it
        }
        val row=checkNotNull(dao.project(id))
        progress(row.message.ifBlank { "목소리를 분석하고 있어요." })
        analysis=loadAnalysis(id)
        if(analysis!=null) {
            require(!requireVoice || analysis.result.voiceModelId!=null) { "서버가 목소리 등록을 지원하지 않아요. 분석 서버를 업데이트해 주세요." }
            return analysis
        }
        if(dao.operation(id)==null || row.state=="connection_error")error(row.message.ifBlank { "분석을 완료하지 못했어요. 다시 시도해 주세요." })
        delay(2000)
    }
}
