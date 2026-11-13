package com.example.hd_project.tiki

import android.content.Context
import androidx.work.*
import com.example.hd_project.*
import com.example.hd_project.momo.*
import com.example.hd_project.momo.people.PeopleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Durable local analysis checkpoints and deterministic metadata planning per recording. */
object EpisodeWork {
    val renderLock = Mutex()
    private val enqueueLock = Mutex()
    private fun preferences(context: Context) = context.getSharedPreferences("tikitaka-episodes", 0)
    fun id(source: String) = MomoVideoSession.projectId(source)
    @Synchronized fun episodeNumber(context: Context, movie: File): Int {
        val prefs=context.getSharedPreferences("tikitaka-episode-numbers",0)
        val saved=prefs.getInt(movie.name,0)
        if(saved>0)return saved
        val number=prefs.getInt("next",1)
        check(prefs.edit().putInt(movie.name,number).putInt("next",number+1).commit())
        return number
    }
    fun status(context: Context, source: String): JSONObject? = preferences(context).getString(source, null)?.let { JSONObject(it) }
    @Synchronized fun dismissFailed(context: Context, source: String): Boolean {
        val data=status(context,source) ?: return false
        if(data.optString("state")!="failed")return false
        // Retain a durable marker so startup recovery cannot recreate a deleted card.
        data.put("state","dismissed").put("message","")
        check(preferences(context).edit().putString(source,data.toString()).commit())
        return true
    }
    /** movies is append-only history. Completion must open the newest existing result. */
    fun latestMovie(context: Context, source: String): File? {
        val names=status(context,source)?.optJSONArray("movies") ?: return null
        val directory=AnimationFiles.directory(context).canonicalFile
        return (names.length()-1 downTo 0).firstNotNullOfOrNull { index ->
            runCatching { File(directory,names.getString(index)).canonicalFile }.getOrNull()
                ?.takeIf { it.parentFile==directory && it.isFile && it.extension=="mp4" }
        }
    }
    fun all(context: Context): List<JSONObject> = preferences(context).all.values.mapNotNull { value ->
        (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }.sortedByDescending { it.optLong("createdAt") }
    @Synchronized fun patch(context: Context, source: String, change: (JSONObject) -> Unit): JSONObject {
        val data=status(context,source) ?: JSONObject().put("source",source).put("createdAt",System.currentTimeMillis())
        change(data)
        check(preferences(context).edit().putString(source,data.toString()).commit())
        return data
    }
    @Synchronized fun update(context: Context, source: String, state: String, message: String, movie: File? = null) {
        val data = status(context, source) ?: JSONObject().put("source", source).put("createdAt", System.currentTimeMillis())
        data.put("state", state).put("message", message)
        if(movie != null) {
            val list = data.optJSONArray("movies") ?: JSONArray()
            if ((0 until list.length()).none { list.getString(it) == movie.name }) list.put(movie.name)
            data.put("movies", list)
        }
        check(preferences(context).edit().putString(source, data.toString()).commit())
        if(state=="completed")data.optJSONArray("movies")?.let { items ->
            repeat(items.length()) { EpisodeNotifications.completed(context,File(AnimationFiles.directory(context),items.getString(it))) }
        }
    }
    suspend fun enqueue(context: Context, source: File, retry: Boolean = false, recreate: Boolean = false, reanalyze: Boolean = false, refreshVoice: Boolean = false) = withContext(Dispatchers.IO) {
        enqueueLock.withLock {
            require(source.canonicalFile.parentFile == RecordingFiles.directory(context).canonicalFile && source.isFile)
            val previous = status(context, source.name)
            if(previous?.optString("state") in listOf("completed", "empty") && !recreate) return@withLock
            if(recreate && previous?.optString("state") in listOf("queued","running","waiting")) return@withLock
            if(previous == null) {
                // Keep the chosen profile immutable for this recording's production.
                PeopleRepository(context, "tikitaka-job-" + id(source.name)).save(TikiProfile.get(context))
                patch(context,source.name) { it.put("engine",MetadataEpisodes.ENGINE)
                    .put("castMode",ProductionSettings.AUTO_CAST).put("castCount",ProductionSettings.UNKNOWN_CAST)
                    .put("laughterThreshold",MetadataEpisodes.threshold(context))
                    .put("state","queued").put("message","영상 제작을 기다리고 있어요.") }
            } else if(retry || recreate) {
                update(context, source.name, "queued", "저장된 작업을 이어서 시작해요.")
                if(previous.optString("engine")=="web-episodes-v1")patch(context,source.name) { it.put("retryRequested",true) }
                if(reanalyze)patch(context,source.name) { it.put("localAttempt",it.optInt("localAttempt")+1).put("laughterThreshold",MetadataEpisodes.threshold(context)) }
                if(recreate)patch(context,source.name) { it.put("renderGeneration",it.optInt("renderGeneration")+1)
                    .put("castMode",ProductionSettings.AUTO_CAST).put("castCount",ProductionSettings.UNKNOWN_CAST)
                    .put("clipSeconds",ProductionSettings.seconds(context)) }
            }
            ProductionSettings.snapshot(context,source.name)
            if(recreate && refreshVoice) {
                // Explicit new productions use the current character and voice profile.
                PeopleRepository(context,"tikitaka-job-"+id(source.name)).save(TikiProfile.get(context))
            }
            patch(context,source.name) {
                if(it.optString("engine")!=MetadataEpisodes.ENGINE) it.put("previousEngine",it.optString("engine"))
                it.put("engine",MetadataEpisodes.ENGINE)
            }
            val work = OneTimeWorkRequestBuilder<EpisodeWorker>().setInputData(workDataOf("source" to source.name))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            // A previous worker may have committed "completed" but not returned yet.
            // Append explicit requests so KEEP cannot silently discard the new generation.
            val policy=if(retry || recreate)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork("tikitaka-"+id(source.name), policy, work).result.get()
        }
    }
    suspend fun createAutomatically(context: Context, source: File) =
        enqueue(context,source,retry=true,recreate=true,reanalyze=true,refreshVoice=true)
    internal suspend fun continueWork(context: Context, source: String) = withContext(Dispatchers.IO) {
        val next=OneTimeWorkRequestBuilder<EpisodeWorker>().setInputData(workDataOf("source" to source))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        // A normal time slice is not an error. Start a fresh worker without accumulated retry backoff.
        WorkManager.getInstance(context).enqueueUniqueWork("tikitaka-"+id(source),ExistingWorkPolicy.APPEND_OR_REPLACE,next).result.get()
    }
    suspend fun resume(context: Context) {
        RecordingJournal.reconcileFinalized(context)
        all(context).filter { it.optString("state") in listOf("queued","running","waiting") }.forEach { row ->
            val file = File(RecordingFiles.directory(context), row.getString("source"))
            if(file.isFile) enqueue(context, file)
        }
        // Repair the narrow crash window between finalizing an audio file and enqueuing its work.
        for(journal in RecordingJournal.all(context)) if(journal.optString("state") == "saved" && !journal.optBoolean("recordOnly")) {
            val file = File(RecordingFiles.directory(context), journal.optString("source"))
            if(file.isFile && runCatching { RecordingJournal.duration(file)>=800 }.getOrDefault(false) && status(context,file.name)==null) enqueue(context,file)
        }
    }
}

class EpisodeWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("source") ?: return Result.failure()
        val context = applicationContext
        val source = File(RecordingFiles.directory(context), name).canonicalFile
        if(source.parentFile != RecordingFiles.directory(context).canonicalFile || !source.isFile) return Result.failure()
        return try {
            withTimeout(TimeUnit.MINUTES.toMillis(8)) {
                while(RecordingStore.state.busy) {
                    EpisodeWork.update(context,name,"waiting","녹음이 끝나면 영상 제작을 이어갈게요.")
                    delay(2000)
                }
                MetadataEpisodes.run(context,source)
            }
            Result.success()
        } catch(error: TimeoutCancellationException) {
            EpisodeWork.update(context,name,"waiting","저장된 분석부터 자동으로 이어서 처리해요.")
            EpisodeWork.continueWork(context,name)
            Result.success()
        } catch(error: CancellationException) { throw error }
        catch(error: Exception) {
            // Storage/model/codec errors need attention, not an indefinite network retry loop.
            EpisodeWork.update(context,name,"failed",error.message ?: "기기에서 제작하지 못했어요. 저장 공간과 분석 상태를 확인한 뒤 다시 시도해 주세요. 원음은 보관되어 있어요.")
            Result.failure()
        }
    }
}
