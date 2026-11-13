package com.example.hd_project.momo.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects") data class ProjectEntity(@PrimaryKey val id: String, val title: String,
    val sceneJson: String, val updatedAt: Long, val state: String = "local", val message: String = "")
@Entity(tableName = "audio_assets") data class AudioAssetEntity(@PrimaryKey val projectId: String,
    val path: String, val sha256: String, val mime: String, val durationUs: Long)
@Entity(tableName = "visual_assets") data class VisualAssetEntity(@PrimaryKey val id: String,
    val projectId: String, val path: String, val role: String)
@Entity(tableName = "analysis_refs") data class AnalysisRefEntity(@PrimaryKey val projectId: String,
    val jobId: String, val serverUrl: String, val resultPath: String? = null, val tracksPath: String? = null,
    val schemaVersion: String? = null, val resultSha256: String? = null, val installationId: String? = null)
@Entity(tableName = "local_overrides") data class LocalOverrideEntity(@PrimaryKey val projectId: String,
    val json: String, val updatedAt: Long)
@Entity(tableName = "pending_operations") data class PendingOperation(@PrimaryKey val projectId: String,
    val operationId: String, val idempotencyKey: String, val serverUrl: String, val optionsJson: String,
    val jobId: String? = null, val bytesSent: Long = 0, val kind: String = "submit", val installationId: String? = null)

@Entity(tableName = "highlight_requests") data class HighlightRequest(@PrimaryKey val sourceId: String,
    val serverUrl: String, val idempotencyKey: String, val createdAt: Long,
    val audioSha256: String? = null, val installationId: String? = null, val jobId: String? = null,
    val state: String = "pending", val message: String = "하이라이트 자동 분석을 기다리고 있어요.")

@Dao interface MomoDao {
    @Upsert suspend fun putHighlight(value: HighlightRequest)
    @Query("SELECT * FROM highlight_requests WHERE sourceId = :id") suspend fun highlight(id: String): HighlightRequest?
    @Query("SELECT * FROM highlight_requests WHERE state NOT IN ('completed', 'failed', 'migrated')") suspend fun pendingHighlights(): List<HighlightRequest>
    @Query("DELETE FROM highlight_requests WHERE sourceId = :id") suspend fun deleteHighlight(id: String)
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun observeProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Upsert suspend fun putProject(value: ProjectEntity)
    @Query("UPDATE projects SET state = :state, message = :message WHERE id = :id") suspend fun state(id: String, state: String, message: String)
    @Upsert suspend fun putAudio(value: AudioAssetEntity)
    @Upsert suspend fun putVisual(value: VisualAssetEntity)
    @Upsert suspend fun putAnalysis(value: AnalysisRefEntity)
    @Query("SELECT * FROM analysis_refs WHERE projectId = :id") suspend fun analysis(id: String): AnalysisRefEntity?
    @Upsert suspend fun putOverrides(value: LocalOverrideEntity)
    @Upsert suspend fun putOperation(value: PendingOperation)
    @Query("SELECT * FROM pending_operations WHERE projectId = :id") suspend fun operation(id: String): PendingOperation?
    @Query("UPDATE pending_operations SET jobId = :job WHERE projectId = :id") suspend fun setJob(id: String, job: String)
    @Query("UPDATE pending_operations SET bytesSent = -1 WHERE projectId = :id") suspend fun markSubmissionStarted(id: String)
    @Query("UPDATE pending_operations SET kind = 'cancel:' || kind WHERE projectId = :id AND kind NOT LIKE 'cancel:%'") suspend fun requestCancel(id: String)
    @Query("DELETE FROM pending_operations WHERE projectId = :id") suspend fun finishOperation(id: String)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String)
    @Query("DELETE FROM audio_assets WHERE projectId = :id") suspend fun deleteAudio(id: String)
    @Query("DELETE FROM visual_assets WHERE projectId = :id") suspend fun deleteVisuals(id: String)
    @Query("DELETE FROM analysis_refs WHERE projectId = :id") suspend fun deleteAnalysis(id: String)
    @Query("DELETE FROM local_overrides WHERE projectId = :id") suspend fun deleteOverrides(id: String)
}

@Database(entities = [ProjectEntity::class, AudioAssetEntity::class, VisualAssetEntity::class,
    AnalysisRefEntity::class, LocalOverrideEntity::class, PendingOperation::class, HighlightRequest::class], version = 3, exportSchema = true)
abstract class MomoDatabase : RoomDatabase() {
    abstract fun dao(): MomoDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS highlight_requests (sourceId TEXT NOT NULL PRIMARY KEY, serverUrl TEXT NOT NULL, idempotencyKey TEXT NOT NULL, createdAt INTEGER NOT NULL, audioSha256 TEXT, installationId TEXT, jobId TEXT, state TEXT NOT NULL, message TEXT NOT NULL)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_refs ADD COLUMN installationId TEXT")
                db.execSQL("ALTER TABLE pending_operations ADD COLUMN installationId TEXT")
            }
        }
        @Volatile private var instance: MomoDatabase? = null
        fun get(context: Context): MomoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MomoDatabase::class.java, "momo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
