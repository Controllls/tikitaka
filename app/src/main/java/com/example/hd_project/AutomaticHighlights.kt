package com.example.hd_project

import android.content.Context
import androidx.work.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.network.*
import com.example.hd_project.momo.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object AutomaticHighlights {
    private val locks = mutableMapOf<String, Mutex>()
    private fun lock(sourceId: String) = synchronized(locks) { locks.getOrPut(sourceId) { Mutex() } }
    private fun preferences(context: Context) = context.getSharedPreferences("automatic-highlights", Context.MODE_PRIVATE)
    fun latestSource(context: Context): String? = preferences(context).getString("latest", null)
    fun message(context: Context, sourceId: String?): String? = sourceId?.let { preferences(context).getString("message:$it", null) }
    fun isPending(context: Context, sourceId: String?): Boolean = sourceId != null &&
        preferences(context).getString("state:$sourceId", "") in listOf("pending", "uploading", "running", "waiting")

    /** Called only after a new recording is finalized. Existing recordings are never bulk uploaded. */
    suspend fun recordingSaved(context: Context, file: File, endpoint: String = BuildConfig.MOMO_API_URL) = withContext(Dispatchers.IO) {
        require(file.parentFile!!.canonicalFile == RecordingFiles.directory(context).canonicalFile && file.extension == "m4a")
        lock(file.name).withLock {
            val database = MomoDatabase.get(context)
            if (database.dao().highlight(file.name) == null) {
                val request = HighlightRequest(file.name, endpoint, UUID.randomUUID().toString(), System.currentTimeMillis())
                status(context, request)
                preferences(context).edit().putString("latest", file.name).commit()
            }
            schedule(context, file.name).result.get() // Persist WorkManager work before the recording service exits.
        }
    }

    suspend fun resume(context: Context) = withContext(Dispatchers.IO) {
        MomoDatabase.get(context).dao().pendingHighlights().forEach { schedule(context, it.sourceId) }
    }

    /** An explicit retry creates a new failed server job only; an active job keeps its id. */
    suspend fun retryFailed(context: Context, sourceId: String) = withContext(Dispatchers.IO) {
        lock(sourceId).withLock {
            val previous = MomoDatabase.get(context).dao().highlight(sourceId) ?: return@withLock
            if(previous.state == "failed") status(context, previous.copy(state="pending",jobId=null,
                idempotencyKey=UUID.randomUUID().toString(),message="하이라이트 분석을 다시 준비하고 있어요."))
        }
    }

    private fun schedule(context: Context, sourceId: String): Operation {
        val request = OneTimeWorkRequestBuilder<AutomaticHighlightWorker>()
            .setInputData(workDataOf("source_id" to sourceId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        return WorkManager.getInstance(context).enqueueUniqueWork(workName(sourceId), ExistingWorkPolicy.KEEP, request)
    }

    fun workName(sourceId: String) = "automatic-highlight-${com.example.hd_project.momo.MomoVideoSession.projectId(sourceId)}"

    private suspend fun status(context: Context, request: HighlightRequest) {
        MomoDatabase.get(context).dao().putHighlight(request)
        preferences(context).edit().putString("state:${request.sourceId}", request.state)
            .putString("message:${request.sourceId}", request.message).commit()
    }

    suspend fun syncOnce(context: Context, sourceId: String): Boolean =
        lock(sourceId).withLock {
            withContext(Dispatchers.IO) {
                val dao = MomoDatabase.get(context).dao()
                var request = dao.highlight(sourceId) ?: return@withContext true
                if (request.state in listOf("completed", "failed")) return@withContext true
                require(request.serverUrl.isNotBlank()) { "자동 분석 서버 주소가 설정되지 않았어요." }
                val client = ProjectRepository(context).client(request.serverUrl)
                val source = File(RecordingFiles.directory(context), sourceId)
                require(source.parentFile!!.canonicalFile == RecordingFiles.directory(context).canonicalFile)
                if (request.installationId != client.installationId) {
                    request = request.copy(installationId=client.installationId, jobId=null,
                        idempotencyKey=if(request.installationId==null)request.idempotencyKey else UUID.randomUUID().toString())
                    status(context, request)
                }
                if (request.jobId == null) {
                    require(source.isFile) { "원본 녹음이 삭제되어 자동 분석을 마쳤어요." }
                    require(source.length() <= 80_000_000) { "자동 분석은 80MB 이하 녹음을 지원해요. 원본은 보관되어 있어요." }
                    val hash = sha256(source)
                    require(request.audioSha256 == null || request.audioSha256 == hash) { "원본 녹음이 변경되었어요." }
                    request = request.copy(audioSha256=hash, state="uploading", message="하이라이트 분석을 위해 녹음을 전송하고 있어요.")
                    status(context, request)
                    val job = client.api.submitHighlights(request.idempotencyKey,
                        MultipartBody.Part.createFormData("audio", "audio.m4a", source.asRequestBody("audio/mp4".toMediaType())),
                        hash.toRequestBody("text/plain".toMediaType()),
                        MomoJson.encodeToString(HighlightOptions()).toRequestBody("text/plain".toMediaType()))
                    request = request.copy(jobId=job.jobId)
                    status(context, request)
                }
                val jobId = requireNotNull(request.jobId)
                val job = client.api.highlightStatus(jobId)
                when (job.status) {
                    "completed" -> {
                        val result = client.api.highlightResult(jobId)
                        require(result.jobId == jobId && result.audioSha256 == request.audioSha256)
                        HighlightStore.saveServer(context, sourceId, result, client.api)
                        status(context, request.copy(state="completed", message=if(result.clips.isEmpty())
                            "웃음이 포함된 대화 구간을 찾지 못했어요. 원본은 보관되어 있어요." else "하이라이트 ${result.clips.size}개를 저장했어요."))
                        true
                    }
                    "failed", "cancelled" -> {
                        status(context, request.copy(state="failed", message=job.error?.message ?: "서버 분석이 중단되었어요. 원본은 보관되어 있어요."))
                        true
                    }
                    else -> {
                        val stage = when(job.stage) {
                            "queued" -> "서버 분석 대기 중"
                            "highlight_laughter" -> "웃음 구간을 확인하고 있어요"
                            "highlight_transcription" -> "대화를 글로 옮기고 있어요"
                            "highlight_topics" -> "대화 주제에 맞춰 구간을 고르고 있어요"
                            "highlight_export" -> "하이라이트를 저장하고 있어요"
                            else -> "녹음을 분석하고 있어요"
                        }
                        status(context, request.copy(state="running", message="$stage · ${(job.progress*100).toInt()}%"))
                        false
                    }
                }
            }
        }

    suspend fun failed(context: Context, sourceId: String, error: Exception, attempts: Int): Boolean {
        if (error is CancellationException) throw error
        val permanent = error is IllegalArgumentException ||
            (error is retrofit2.HttpException && error.code() in listOf(400,401,403,404,410,413,415,422)) ||
            (error is InstallationAuthenticationException && error.permanent)
        val retry = !permanent && attempts < 96
        val request = MomoDatabase.get(context).dao().highlight(sourceId) ?: return false
        status(context, request.copy(state=if(retry) "waiting" else "failed", message=if(retry)
            "서버 연결을 기다리고 있어요. 연결되면 자동으로 이어서 처리해요." else
            if(error is IllegalArgumentException || error is InstallationAuthenticationException) error.message.orEmpty() else
                "자동 분석을 완료하지 못했어요. 원본 녹음은 보관되어 있어요."))
        return retry
    }
}

class AutomaticHighlightWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val source = inputData.getString("source_id") ?: return Result.failure()
        return try {
            // Healthy server jobs poll promptly; exponential backoff is for connectivity failures.
            // Yield before WorkManager's execution window expires, then resume the persisted job.
            val deadline = android.os.SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(8)
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (AutomaticHighlights.syncOnce(applicationContext, source)) return Result.success()
                delay(5_000)
            }
            Result.retry()
        } catch (e: Exception) {
            if (AutomaticHighlights.failed(applicationContext, source, e, runAttemptCount)) Result.retry() else Result.failure()
        }
    }
}
