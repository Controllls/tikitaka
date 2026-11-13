package com.example.hd_project.tiki

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.example.hd_project.AutomaticHighlights
import com.example.hd_project.RecordingFiles
import com.example.hd_project.momo.storage.HighlightRequest
import com.example.hd_project.momo.storage.MomoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.UUID

class RetiredSyncSentinel(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = error("Retired sync must never execute")
}

@RunWith(AndroidJUnit4::class)
class LocalOnlyUpgradeTest {
    @Test fun oldServerQueueReusesCompletedLocalJobWithoutNetwork() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(RecordingFiles.directory(context), "migration-${UUID.randomUUID()}.m4a")
        val dao = MomoDatabase.get(context).dao()
        val prefs = context.getSharedPreferences("automatic-highlights", 0)
        try {
            source.writeBytes(byteArrayOf(1,2,3)) // Completed job must not decode or upload this sentinel.
            dao.putHighlight(HighlightRequest(source.name, "http://127.0.0.1:1/", "retired", 1))
            EpisodeWork.update(context,source.name,"completed","기존 영상 보존")
            val saved=EpisodeWork.status(context,source.name).toString()
            AutomaticHighlights.migrateToLocal(context,source.name)
            assertEquals("migrated",dao.highlight(source.name)!!.state)
            assertTrue(dao.pendingHighlights().none { it.sourceId==source.name })
            assertEquals(saved,EpisodeWork.status(context,source.name).toString())
            assertEquals("기존 영상 보존",AutomaticHighlights.message(context,source.name))
            assertFalse(AutomaticHighlights.isPending(context,source.name))
            AutomaticHighlights.migrateToLocal(context,source.name)
            assertArrayEquals(byteArrayOf(1,2,3),source.readBytes())
        } finally {
            dao.deleteHighlight(source.name)
            context.getSharedPreferences("tikitaka-episodes",0).edit().remove(source.name).commit()
            prefs.edit().remove("state:${source.name}").remove("message:${source.name}").commit()
            source.delete()
        }
    }

    @Test fun cancelsBothLegacySchedulesAndClearsOnlyConnectionSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val upgrades = context.getSharedPreferences("tikitaka-upgrades", 0)
        val phone = context.getSharedPreferences("tikitaka-phone", 0)
        val existingFiles = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.toSet()
        val once = OneTimeWorkRequestBuilder<RetiredSyncSentinel>().setInitialDelay(1, TimeUnit.DAYS).build()
        val periodic = PeriodicWorkRequestBuilder<RetiredSyncSentinel>(15, TimeUnit.MINUTES).setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniqueWork("tikitaka-phone-sync", ExistingWorkPolicy.REPLACE, once).result.get()
        manager.enqueueUniquePeriodicWork("tikitaka-phone-periodic", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodic).result.get()
        phone.edit().putBoolean("enabled", true).putString("code", "retired-test").commit()
        upgrades.edit().remove("local-only-v1").commit()
        LocalOnlyUpgrade.apply(context)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(once.id).get()!!.state)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(periodic.id).get()!!.state)
        assertTrue(phone.all.isEmpty())
        assertTrue(upgrades.getBoolean("local-only-v1", false))
        LocalOnlyUpgrade.apply(context) // Idempotent across process restarts.
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.containsAll(existingFiles))
    }
}
