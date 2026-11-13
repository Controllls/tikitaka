package com.example.hd_project.tiki

import android.content.Context
import androidx.work.*
import com.example.hd_project.AnimationFiles
import com.example.hd_project.BuildConfig
import com.example.hd_project.momo.network.ApiClient
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object PhoneSync {
    fun preferences(context: Context)=context.getSharedPreferences("tikitaka-phone",0)
    fun enabled(context: Context)=preferences(context).getBoolean("enabled",false)
    fun enqueue(context: Context) {
        if(!enabled(context))return
        WorkManager.getInstance(context).enqueueUniqueWork("tikitaka-phone-sync",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhoneSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun enable(context: Context) {
        preferences(context).edit().putBoolean("enabled",true).apply();enqueue(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("tikitaka-phone-periodic",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PhoneSyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun disable(context: Context) {
        preferences(context).edit().putBoolean("enabled",false).remove("devices").remove("code").apply()
        WorkManager.getInstance(context).cancelUniqueWork("tikitaka-phone-sync");WorkManager.getInstance(context).cancelUniqueWork("tikitaka-phone-periodic")
    }
}

class PhoneSyncWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if(!PhoneSync.enabled(applicationContext))return@withContext Result.success()
        try {
            val context=applicationContext;val prefs=PhoneSync.preferences(context);val api=ApiClient(context,BuildConfig.MOMO_API_URL)
            val state=api.phone("state");val devices=state.getJSONArray("devices")
            prefs.edit().putString("devices",devices.toString()).putString("url",state.getString("url")).apply()
            if((0 until devices.length()).none { devices.getJSONObject(it).optBoolean("approved") })return@withContext Result.success()
            val jobs=JSONArray(EpisodeWork.all(context).filter { it.optString("state")!="completed" }.take(100).map {
                JSONObject().put("state",it.optString("state")).put("message",it.optString("message")).put("id",EpisodeWork.id(it.optString("source"))) })
            api.phone("jobs","PUT",jobs.toString().toRequestBody("application/json".toMediaType()))
            val movies=AnimationFiles.directory(context).listFiles().orEmpty().filter { it.extension=="mp4" && !it.name.endsWith(".pending.mp4") && File(it.path+".json").isFile }
            for(movie in movies) {
                if(isStopped || !PhoneSync.enabled(context))return@withContext Result.success()
                val sidecar=JSONObject(File(movie.path+".json").readText());val hash=sha256(movie)
                if(prefs.getBoolean("sent-$hash",false))continue
                val duration=RecordingJournal.duration(movie)
                if(duration !in 1..125000)continue
                val captions=File(movie.path+".captions.json")
                val info=JSONObject().put("sha256",hash).put("title",sidecar.optString("title","함께 나눈 이야기").take(200))
                    .put("number",EpisodeWork.episodeNumber(context,movie)).put("durationMs",duration)
                    .put("captions",if(captions.isFile)JSONArray(captions.readText()) else JSONArray())
                val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("metadata",info.toString())
                    .addFormDataPart("video","episode.mp4",movie.asRequestBody("video/mp4".toMediaType()))
                val poster=TikiThumbnails.file(context,movie)
                if(poster.isFile)body.addFormDataPart("poster","poster.png",poster.asRequestBody("image/png".toMediaType()))
                api.phone("episodes","POST",body.build())
                prefs.edit().putBoolean("sent-$hash",true).apply()
            }
            prefs.edit().putString("syncMessage","완성 영상을 휴대폰 보관함에 보관했어요.").apply();Result.success()
        } catch(e: Exception) {
            PhoneSync.preferences(applicationContext).edit().putString("syncMessage","연결이 되면 영상 전송을 다시 시도해요.").apply()
            if(runAttemptCount<5)Result.retry() else Result.failure()
        }
    }
}
