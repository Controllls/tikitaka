package com.example.hd_project

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

data class SavedRecording(val file: File, val durationMs: Long) {
    val title: String get() = runCatching {
        JSONObject(File(file.parentFile, file.nameWithoutExtension + ".json").readText())
            .getString("title").take(100).takeIf { it.isNotBlank() }
    }.getOrNull() ?: SimpleDateFormat("M월 d일 · HH:mm", Locale.KOREA).format(Date(file.lastModified()))
    val details: String get() = formatDuration(durationMs) + "  ·  " +
        String.format(Locale.KOREA, "%.1f MB", file.length() / 1_000_000.0)
}

object RecordingFiles {
    // Android excludes noBackupFilesDir from both backup and device transfer.
    fun directory(context: Context): File = File(context.noBackupFilesDir, "recordings")
        .also { check(it.isDirectory || it.mkdirs()) { "Cannot create recordings directory" } }

    fun pending(context: Context): File = File(directory(context),
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + ".pending")

    fun finish(pending: File, durationMs: Long): File {
        val destination = File(pending.parentFile,
            pending.nameWithoutExtension + "_" + durationMs + ".m4a")
        check(pending.renameTo(destination)) { "Cannot finalize recording" }
        return destination
    }

    fun list(context: Context): List<SavedRecording> = directory(context).listFiles()
        .orEmpty().filter { it.isFile && it.extension == "m4a" }
        .sortedByDescending { it.lastModified() }
        .map { SavedRecording(it, it.nameWithoutExtension.substringAfterLast('_').toLongOrNull() ?: 0) }

    fun subtitles(source: File) = File(source.parentFile, source.nameWithoutExtension + ".srt")

    fun delete(recording: SavedRecording): Boolean {
        if (!recording.file.delete()) return false
        subtitles(recording.file).delete()
        File(recording.file.parentFile, recording.file.nameWithoutExtension + ".json").delete()
        return true
    }
}
