package com.example.hd_project.momo.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.work.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.network.ApiClient
import com.example.hd_project.momo.network.InstallationAuthenticationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

class ProjectRepository(val context: Context) {
    val database = MomoDatabase.get(context)
    val dao = database.dao()
    val projects = dao.observeProjects()
    private val root = File(context.noBackupFilesDir, "momo-projects").apply { mkdirs() }
    companion object {
        private val clients = mutableMapOf<String, ApiClient>()
        private val syncLocks = mutableMapOf<String, Mutex>()
    }
    fun client(url: String) = synchronized(clients) { clients.getOrPut(url.trim().trimEnd('/')) { ApiClient(context.applicationContext, url) } }
    suspend fun scene(id: String): SceneProject = MomoJson.decodeFromString(dao.project(id)!!.sceneJson)
    suspend fun save(project: SceneProject) = withContext(Dispatchers.IO) {
        val previous = dao.project(project.projectId)
        dao.putProject(ProjectEntity(project.projectId, project.title, MomoJson.encodeToString(project),
            System.currentTimeMillis(), previous?.state ?: "local", previous?.message ?: ""))
        dao.putOverrides(LocalOverrideEntity(project.projectId, MomoJson.encodeToString(project.eventOverrides), System.currentTimeMillis()))
    }
    suspend fun importAudio(uri: Uri): SceneProject = withContext(Dispatchers.IO) {
        var name = "새로운 이동 기록"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        val file = File(directory, "original.audio")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) { val read = input.read(buffer); if (read < 0) break; count += read
                    require(count <= 80_000_000) { "80MB 이하 파일을 선택해 주세요." }; out.write(buffer, 0, read) }
            } }
            val retriever = MediaMetadataRetriever()
            val duration: Long
            val mime: String
            try {
                retriever.setDataSource(file.path)
                duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)*1000
                mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "application/octet-stream"
                require(duration in 100000..600000000) { "0.1초~10분 오디오를 선택해 주세요." }
                require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "오디오 트랙이 없습니다." }
            } finally { retriever.release() }
            val project = SceneProject(id, name.substringBeforeLast('.'), file.path, sha256(file), duration)
            save(project)
            dao.putAudio(AudioAssetEntity(id, file.path, project.sourceAudioSha256, mime, duration))
            project
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    suspend fun loadAnalysis(id: String): AnalysisBundle? = withContext(Dispatchers.IO) {
        val ref = dao.analysis(id) ?: return@withContext null
        if (ref.resultPath == null || ref.tracksPath == null) return@withContext null
        val resultFile = File(ref.resultPath)
        require(sha256(resultFile) == ref.resultSha256) { "저장된 분석 결과가 손상되었습니다." }
        AnalysisBundle(MomoJson.decodeFromString(resultFile.readText()), MomoJson.decodeFromString(File(ref.tracksPath).readText())).also {
            it.validate()
            if(it.result.audio.sha256!=scene(id).sourceAudioSha256)return@withContext null
        }
    }
    suspend fun cache(id: String, url: String, bundle: AnalysisBundle) = withContext(Dispatchers.IO) {
        bundle.validate()
        val project = scene(id)
        require(bundle.result.audio.sha256 == project.sourceAudioSha256) { "분석 결과가 선택한 원본과 다릅니다." }
        val directory = File(root, id)
        directory.mkdirs()
        val result = File(directory, "${bundle.result.jobId}-result.json")
        val tracks = File(directory, "${bundle.result.jobId}-tracks.json")
        val resultTemp = File(directory, "result.tmp"); val tracksTemp = File(directory, "tracks.tmp")
        resultTemp.writeText(MomoJson.encodeToString(bundle.result)); tracksTemp.writeText(MomoJson.encodeToString(bundle.tracks))
        check(resultTemp.renameTo(result)); check(tracksTemp.renameTo(tracks))
        database.withTransaction {
            dao.putAnalysis(AnalysisRefEntity(id, bundle.result.jobId, url, result.path, tracks.path, bundle.result.schemaVersion, sha256(result),
                if (url == "fixture") null else client(url).installationId))
            // Preserve mappings and local edits when a sync finishes.
            val latest = scene(id)
            save(latest.copy(analysisJobId = bundle.result.jobId, durationUs = bundle.result.durationUs))
            dao.state(id, "completed", "분석 결과를 저장했어요. 이제 영상을 만들 수 있어요.")
            dao.finishOperation(id)
            com.example.hd_project.momo.MomoVideoSession.pending(context,id,false)
        }
    }
    suspend fun enqueue(id: String, url: String, hint: Int = 0) = withContext(Dispatchers.IO) {
        client(url).ensureAuthenticated()
        val existing = dao.operation(id)
        if (existing == null) {
            val operation = UUID.randomUUID().toString()
            dao.putOperation(PendingOperation(id, operation, operation, client(url).baseUrl, MomoJson.encodeToString(AnalysisOptions(speakerCountHint = hint)),
                installationId = client(url).installationId))
        }
        dao.state(id, "upload_pending", "업로드 대기 중")
        com.example.hd_project.momo.MomoVideoSession.pending(context,id,true)
        schedule(id)
    }
    fun schedule(id: String, delay: Long = 0) {
        val work = OneTimeWorkRequestBuilder<AnalysisSyncWorker>()
            .setInputData(workDataOf("project_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("analysis-$id", ExistingWorkPolicy.KEEP, work)
    }
    suspend fun syncOnce(id: String): Boolean = synchronized(syncLocks) { syncLocks.getOrPut(id) { Mutex() } }.withLock { syncUnlocked(id) }
    private suspend fun syncUnlocked(id: String) = withContext(Dispatchers.IO) {
        var operation = dao.operation(id) ?: return@withContext true
        val server = client(operation.serverUrl)
        if (operation.installationId != server.installationId) {
            // An upgrade from a shared development account must not reuse its job IDs or keys.
            // Keep the original recording and cached result; resume only explicitly pending work.
            operation = database.withTransaction {
                val latest = dao.operation(id) ?: return@withTransaction null
                if (latest.kind.startsWith("cancel:")) {
                    dao.finishOperation(id)
                    com.example.hd_project.momo.MomoVideoSession.pending(context,id,false)
                    dao.state(id, "local", "이전 분석 요청을 이 기기에서 중단했어요.")
                    null
                } else {
                    val key = UUID.randomUUID().toString()
                    latest.copy(operationId=key, idempotencyKey=key, jobId=null, bytesSent=0, kind="submit",
                        optionsJson=if(latest.optionsJson=="{}") MomoJson.encodeToString(AnalysisOptions()) else latest.optionsJson,
                        installationId=server.installationId).also { dao.putOperation(it) }
                }
            } ?: return@withContext true
        }
        val project = scene(id)
        val api = server.api
        var jobId = operation.jobId
        if (jobId == null) {
            // Persist the intent before sending. After a lost response, replay the same key to find
            // the accepted server job, including when the user cancelled during that upload.
            val submitAllowed = database.withTransaction {
                val latest = dao.operation(id) ?: return@withTransaction false
                if (latest.kind.startsWith("cancel:") && latest.bytesSent == 0L) {
                    dao.finishOperation(id)
                    com.example.hd_project.momo.MomoVideoSession.pending(context,id,false)
                    dao.state(id, "local", "업로드를 취소했어요.")
                    false
                } else { dao.markSubmissionStarted(id); true }
            }
            if (!submitAllowed) return@withContext true
            val audio = File(project.audioLocalRef)
            require(audio.isFile && sha256(audio) == project.sourceAudioSha256) { "원본 오디오를 확인해 주세요." }
            dao.state(id, "uploading", "원본 업로드 중")
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = audio.length()
                override fun writeTo(sink: BufferedSink) {
                    audio.inputStream().use { source -> val buffer = ByteArray(65536); var sent = 0L; var last = 0L
                        while (true) { val read = source.read(buffer); if (read < 0) break; sink.write(buffer, 0, read); sent += read
                            if (sent-last > 524288 || sent == audio.length()) { last = sent
                                kotlinx.coroutines.runBlocking { dao.state(id, "uploading", "업로드 ${(sent*100/audio.length()).toInt()}%") }
                            }
                        }
                    }
                }
            }
            val submissionKind = operation.kind.removePrefix("cancel:")
            val job = if (submissionKind.startsWith("retry:")) api.retry(submissionKind.removePrefix("retry:"), operation.idempotencyKey)
                else api.submit(operation.idempotencyKey, MultipartBody.Part.createFormData("audio", "audio", body),
                    project.sourceAudioSha256.toRequestBody("text/plain".toMediaType()), operation.optionsJson.toRequestBody("text/plain".toMediaType()))
            jobId = job.jobId
            database.withTransaction {
                dao.setJob(id, jobId)
                dao.putAnalysis(AnalysisRefEntity(id, jobId, operation.serverUrl, installationId=server.installationId))
                save(scene(id).copy(analysisJobId = jobId))
            }
        }
        val status = if (dao.operation(id)?.kind?.startsWith("cancel:") == true) api.cancel(jobId) else api.status(jobId)
        when (status.status) {
            "completed" -> { cache(id, operation.serverUrl, AnalysisBundle(api.result(jobId), api.tracks(jobId))); true }
            "failed", "cancelled" -> { dao.state(id, status.status, status.error?.message ?: "분석을 취소했어요."); dao.finishOperation(id); com.example.hd_project.momo.MomoVideoSession.pending(context,id,false); true }
            else -> { dao.state(id, status.status, "${stageLabel(status.stage)} ${(status.progress*100).toInt()}%"); false }
        }
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        val ref = dao.analysis(id)
        val pending = dao.operation(id)
        if (pending != null) {
            dao.requestCancel(id)
            dao.state(id, "cancel_requested", "취소 요청을 저장했어요. 서버 확인 후 마무리합니다.")
            schedule(id)
        } else if (ref != null && ref.serverUrl != "fixture" && ref.installationId == client(ref.serverUrl).installationId) {
            val key = UUID.randomUUID().toString()
            dao.putOperation(PendingOperation(id, key, key, ref.serverUrl, "{}", jobId=ref.jobId, kind="cancel:submit", installationId=ref.installationId))
            dao.state(id, "cancel_requested", "취소 요청을 저장했어요.")
            schedule(id)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork("analysis-$id")
            dao.finishOperation(id)
            dao.state(id, "local", "업로드를 취소했어요.")
        }
    }
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val ref = dao.analysis(id) ?: error("다시 업로드해 주세요.")
        require(ref.serverUrl != "fixture")
        val key = UUID.randomUUID().toString()
        dao.putOperation(PendingOperation(id, key, key, ref.serverUrl, "{}", kind = "retry:${ref.jobId}", installationId=ref.installationId))
        schedule(id)
    }
    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        val ref = dao.analysis(id) ?: return@withContext
        require(ref.serverUrl != "fixture") { "서버에 전송하지 않은 샘플입니다." }
        require(ref.installationId == client(ref.serverUrl).installationId) { "이전 개발 계정의 서버 자료는 해당 계정으로 관리해야 해요." }
        client(ref.serverUrl).api.delete(ref.jobId)
        dao.finishOperation(id)
        dao.state(id, "server_deleted", "서버 삭제를 요청했어요. 내려받은 로컬 편집본은 사용할 수 있어요.")
    }
    suspend fun deleteLocal(id: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("analysis-$id")
        database.withTransaction { dao.finishOperation(id); dao.deleteAnalysis(id); dao.deleteAudio(id); dao.deleteVisuals(id); dao.deleteOverrides(id); dao.deleteProject(id) }
        File(root, id).deleteRecursively()
    }
    suspend fun importVisual(id: String, uri: Uri, role: String): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: ""
        val video = mime == "video/mp4"
        require(mime in listOf("image/png", "image/jpeg", "image/webp") || (role == "background" && video)) { "PNG/JPEG/WebP 또는 배경 MP4를 선택해 주세요." }
        val key = UUID.randomUUID().toString()
        val file = File(File(root, id), "$key.${if (video) "mp4" else "image"}")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) { val read = input.read(buffer); if (read < 0) break; count += read
                    require(count <= if (video) 80_000_000 else 5_000_000) { "에셋 크기를 초과했어요." }; output.write(buffer, 0, read) }
            } }
            dao.putVisual(VisualAssetEntity(key, id, file.path, role))
            file.path
        } catch (e: Exception) { file.delete(); throw e }
    }
}

fun stageLabel(stage: String) = when (stage) { "queued" -> "서버 대기"; "decoding" -> "원본 읽는 중"; "acoustic" -> "대화·웃음 구분 중";
    "diarization" -> "목소리 구분 중"; "laughter_recheck" -> "웃는 사람 확인 중"; "transcription" -> "자막 만드는 중"; "assembling" -> "결과 정리 중"; "speaker_signatures" -> "목소리 특징 추출 중"; else -> stage }

class AnalysisSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("project_id") ?: return Result.failure()
        val repository = ProjectRepository(applicationContext)
        return try {
            // One upload/status/download operation; WorkManager owns bounded exponential retry timing.
            if (repository.syncOnce(id)) Result.success() else if (runAttemptCount < 48) Result.retry()
            else { repository.dao.state(id, "waiting", "앱에서 분석 진행 상황을 다시 확인해 주세요."); Result.success() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val detail = if (e is retrofit2.HttpException) runCatching { MomoJson.decodeFromString<ErrorResponse>(e.response()?.errorBody()?.string() ?: "").error }.getOrNull() else null
            val permanent = (e is retrofit2.HttpException && e.code() in listOf(400,401,403,404,410,413,415,422)) ||
                (e is InstallationAuthenticationException && e.permanent)
            val retry = !permanent && runAttemptCount < 12
            val message = when(detail?.code) {
                "MODEL_NOT_READY" -> "분석 서버를 준비하고 있어요. 잠시 후 자동으로 다시 연결합니다."
                else -> detail?.message ?: if(retry) "연결을 기다리고 있어요. 자동으로 다시 시도합니다." else "연결을 확인한 뒤 다시 영상을 만들어 주세요."
            }
            repository.dao.state(id,if(retry) "retry_wait" else "connection_error",message)
            if(retry)Result.retry() else Result.failure()
        }
    }
}
