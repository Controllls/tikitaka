package com.example.hd_project.tiki

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TikiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { LocalOnlyUpgrade.apply(this@TikiApplication) }
                .onFailure { android.util.Log.w("LocalOnlyUpgrade", "Will retry retired work cleanup at next process start", it) }
        }
    }
}

/** Upgrade only: no companion UI, worker, credentials or upload client remains. */
internal object LocalOnlyUpgrade {
    @Synchronized fun apply(context: Context) {
        val prefs = context.getSharedPreferences("tikitaka-upgrades", 0)
        if (prefs.getBoolean("local-only-v1", false)) return
        val work = WorkManager.getInstance(context)
        for (name in listOf("tikitaka-phone-sync", "tikitaka-phone-periodic")) work.cancelUniqueWork(name).result.get()
        check(context.getSharedPreferences("tikitaka-phone", 0).edit().clear().commit())
        // Only obsolete connection settings are retired. No recordings, videos or checkpoints are removed.
        check(prefs.edit().putBoolean("local-only-v1", true).commit())
    }
}
