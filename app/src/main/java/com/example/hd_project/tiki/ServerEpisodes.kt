package com.example.hd_project.tiki

import android.content.Context
import com.example.hd_project.AnimationFiles
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.network.AnalysisApi
import com.example.hd_project.momo.people.PeopleRepository
import com.example.hd_project.momo.people.PersonProfile
import com.example.hd_project.momo.people.VoiceMatcher
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.ProjectRepository
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Registered voice vectors never leave this device. Only the selected cast is submitted. */
object EpisodeCast {
    fun build(analysis: JSONObject, profiles: List<PersonProfile>): JSONObject {
        val speakers=MomoJson.decodeFromString<List<Speaker>>(analysis.getJSONArray("speakers").toString())
        val signatures=MomoJson.decodeFromString<List<SpeakerSignature>>(analysis.getJSONArray("speaker_signatures").toString())
        require(speakers.map { it.speakerId }.distinct().size==speakers.size && speakers.size<=8)
        require(signatures.all { it.embedding.size==192 && it.embedding.all(Double::isFinite) &&
            kotlin.math.abs(it.embedding.sumOf { v->v*v }-1.0)<.02 && it.speechUs>=0 }) { "목소리 분석 결과를 확인하지 못했어요." }
        val model=analysis.optString("voice_model_id").takeIf { it.isNotBlank() && it!="null" }
        val matches=VoiceMatcher.match(speakers,signatures,model,profiles).sortedByDescending { it.profile!=null }.take(4)
        require(matches.isNotEmpty()) { "대화 화자를 찾지 못했어요. 원음은 보관되어 있어요." }
        val used=matches.mapNotNull { it.profile?.characterId }.toMutableSet()
        val people=JSONArray()
        matches.forEachIndexed { index,match ->
            val character=match.profile?.characterId ?: TikiProfile.characters.first { it !in used }.also { used.add(it) }
            people.put(JSONObject().put("speaker",match.speakerId).put("name",match.profile?.name ?: "함께한 사람 ${index+1}")
                .put("character",character))
        }
        return JSONObject().put("people",people)
    }
}

object ServerEpisodes {
    private class ExpiredResult: IllegalStateException("서버 보관기간이 만료되었어요. 원음에서 다시 제작할 수 있어요.")
    private fun atomicJson(file: File, value: String) {
        val atomic=android.util.AtomicFile(file)
        val stream=atomic.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(error: Exception) { atomic.failWrite(stream);throw error }
    }
    private fun metadata(context: Context, source: File)=EpisodeWork.status(context,source.name)!!
    private fun patch(context: Context, source: File, action: (JSONObject)->Unit)=EpisodeWork.patch(context,source.name,action)
    private fun json(value: JSONObject)=MomoJson.decodeFromString<JsonObject>(value.toString())

    suspend fun run(context: Context, source: File) {
        try { produce(context,source) }
        catch(error: Exception) {
            val expired=error is ExpiredResult || (error is retrofit2.HttpException && error.code()==410)
            if(!expired || !metadata(context,source).optBoolean("retryRequested"))throw error
            // Explicit retry after server retention expired: retain the local original/profile.
            patch(context,source) {
                for(key in listOf("analysisJobId","renderJobId","cast","profileRevision","analysisJobIdRetryKey","renderJobIdRetryKey"))it.remove(key)
                it.put("submitKey",UUID.randomUUID().toString()).put("renderKey",UUID.randomUUID().toString()).put("retryRequested",false)
            }
            produce(context,source)
        }
    }

    private suspend fun produce(context: Context, source: File) = withContext(Dispatchers.IO) {
        val state=metadata(context,source)
        val endpoint=state.getString("serverUrl")
        require(endpoint.isNotBlank()) { "영상 제작 서버 주소가 설정되지 않았어요." }
        val server=ProjectRepository(context).client(endpoint)
        val installation=server.installationId
        val hash=sha256(source)
        require(!state.has("sourceHash") || state.getString("sourceHash")==hash) { "제작 요청 뒤 원본 파일이 변경되었어요." }
        require(!state.has("installationId") || state.getString("installationId")==installation) { "이전 설치에서 요청한 작업입니다. 원음을 확인해 주세요." }
        patch(context,source) {
            it.put("sourceHash",hash).put("installationId",installation)
            if(!it.has("submitKey"))it.put("submitKey",UUID.randomUUID().toString())
            if(!it.has("renderKey"))it.put("renderKey",UUID.randomUUID().toString())
        }
        val api=server.api
        if(!metadata(context,source).has("analysisJobId")) {
            val capabilities=try { JSONObject(api.episodeProfile().toString()) }
                catch(error: retrofit2.HttpException) {
                    if(error.code()==404)throw IllegalStateException("서버를 업데이트해야 웹 영상 제작 기능을 사용할 수 있어요.")
                    throw error
                }
            require(source.length()<=capabilities.getLong("max_audio_bytes")) { "원본 파일이 서버 업로드 한도를 초과했어요." }
            EpisodeWork.update(context,source.name,"running","녹음을 서버로 보내고 있어요.")
            val job=api.submitEpisode(metadata(context,source).getString("submitKey"),
                MultipartBody.Part.createFormData("audio","recording.m4a",source.asRequestBody("audio/mp4".toMediaType())),
                hash.toRequestBody("text/plain".toMediaType()),
                "{\"consent_version\":\"server-audio-v1\"}".toRequestBody("application/json".toMediaType()))
            patch(context,source) { it.put("analysisJobId",job.jobId) }
        }
        val analysisId=await(context,source,api,"analysisJobId",false)
        val analysis=JSONObject(api.episodeResult(analysisId).toString())
        require(analysis.getString("audio_sha256")==hash && analysis.getString("job_id")==analysisId) { "분석 결과가 원본과 일치하지 않아요." }
        if(analysis.getJSONArray("clips").length()==0) {
            EpisodeWork.update(context,source.name,"empty","대화 하이라이트를 찾지 못했어요. 원본 녹음은 보관했어요.")
            return@withContext
        }
        if(!metadata(context,source).has("cast")) {
            val profiles=PeopleRepository(context,"tikitaka-job-"+EpisodeWork.id(source.name)).list()
            val cast=EpisodeCast.build(analysis,profiles)
            patch(context,source) { it.put("cast",cast).put("profileRevision",analysis.getString("profile_revision")) }
        }
        if(!metadata(context,source).has("renderJobId")) {
            EpisodeWork.update(context,source.name,"running","캐릭터를 연결하고 영상 제작을 요청하고 있어요.")
            val saved=metadata(context,source)
            val job=api.renderEpisode(analysisId,saved.getString("renderKey"),json(saved.getJSONObject("cast")))
            patch(context,source) { it.put("renderJobId",job.jobId) }
        }
        val renderId=await(context,source,api,"renderJobId",true)
        val result=JSONObject(api.episodeResult(renderId).toString())
        require(result.getString("job_id")==renderId && result.getString("audio_sha256")==hash && result.getString("analysis_job_id")==analysisId && !result.getBoolean("captions_burned")) {
            "완성 영상의 원본·자막 정보를 확인하지 못했어요."
        }
        val items=result.getJSONArray("items")
        require(items.length() in 1..10) { "완성 영상 목록을 확인하지 못했어요." }
        for(index in 0 until items.length()) {
            currentCoroutineContext().ensureActive()
            val item=items.getJSONObject(index)
            require(renderId.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            val output=File(AnimationFiles.directory(context),"${EpisodeWork.id(source.name)}-web-$renderId-${index+1}.mp4")
            EpisodeWork.update(context,source.name,"running","${index+1}/${items.length()} · 완성한 영상을 저장하고 있어요.")
            download(api,renderId,item.getJSONObject("video_artifact"),output)
            VideoComposer.validateMp4(output)
            download(api,renderId,item.getJSONObject("captions_artifact"),File(output.path+".captions.json"))
            download(api,renderId,item.getJSONObject("poster_artifact"),File(output.path+".poster.png"))
            atomicJson(File(output.path+".json"),JSONObject().put("title",item.getString("title"))
                .put("source",source.name).put("audio",hash).put("video",item.getJSONObject("video_artifact").getString("sha256"))
                .put("renderer",result.getString("renderer")).put("serverJobId",renderId).put("analysisJobId",analysisId).toString())
            EpisodeWork.update(context,source.name,"running","완성한 영상을 저장했어요.",output)
        }
        patch(context,source) { it.put("retryRequested",false).put("warnings",result.optJSONArray("warnings") ?: JSONArray()) }
        EpisodeWork.update(context,source.name,"completed","영상이 준비됐어요.")
    }

    private suspend fun await(context: Context, source: File, api: AnalysisApi, field: String, rendering: Boolean): String {
        while(true) {
            currentCoroutineContext().ensureActive()
            val data=metadata(context,source)
            val id=data.getString(field)
            val status=api.episodeStatus(id)
            if(status.status=="completed")return id
            if(status.status in listOf("failed","cancelled")) {
                if(status.error?.code in listOf("RESULT_EXPIRED","AUDIO_EXPIRED"))throw ExpiredResult()
                if(data.optBoolean("retryRequested")) {
                    val key=field+"RetryKey"
                    if(!data.has(key))patch(context,source) { it.put(key,UUID.randomUUID().toString()) }
                    val retry=api.retry(id,metadata(context,source).getString(key))
                    patch(context,source) { it.put(field,retry.jobId).put("retryRequested",false).remove(key) }
                    continue
                }
                throw IllegalStateException(status.error?.message ?: "제작을 완료하지 못했어요. 원음은 보관되어 있어요.")
            }
            val label=if(status.status=="queued")"서버에서 제작 순서를 기다리고 있어요." else if(rendering)"표정과 움직임을 넣어 영상을 만들고 있어요." else when(status.stage) {
                "decoding"->"녹음 파일을 읽고 있어요."
                "vad"->"대화 구간을 찾고 있어요."
                "laughter"->"웃음과 소리를 확인하고 있어요."
                "diarization","voice"->"대화 속 목소리를 확인하고 있어요."
                "stt"->"대사와 웃음을 캐릭터에 연결하고 있어요."
                "topics"->"이야기에서 하이라이트를 고르고 있어요."
                else->"서버에서 대화를 분석하고 있어요."
            }
            EpisodeWork.update(context,source.name,"running",label)
            delay(3000)
        }
    }

    private suspend fun download(api: AnalysisApi, job: String, artifact: JSONObject, target: File) {
        val filename=artifact.getString("name")
        require(filename.matches(Regex("(?:video_clip_[0-9]+\\.(?:mp4|png)|captions_[0-9]+\\.json)")))
        val expected=artifact.getLong("size")
        val hash=artifact.getString("sha256")
        require(expected in 1..1_000_000_000L && hash.matches(Regex("[a-f0-9]{64}")))
        if(target.isFile && target.length()==expected && sha256(target)==hash)return
        val pending=File(target.path+".download")
        try {
            api.episodeFile(job,filename).use { response -> response.byteStream().use { input -> pending.outputStream().use { output ->
                val buffer=ByteArray(65536);var total=0L
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer);if(count<0)break
                    total+=count;require(total<=expected) { "다운로드 용량이 결과 정보와 다릅니다." };output.write(buffer,0,count)
                }
            } } }
            require(pending.length()==expected && sha256(pending)==hash) { "완성 파일의 다운로드를 다시 확인해야 해요." }
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
    }
}
