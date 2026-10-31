package com.example.hd_project

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedHighlight(val file: File, val title: String, val startMs: Long, val endMs: Long,
    val score: Double) {
    val durationMs get() = endMs - startMs
}
data class HighlightBatch(val sourceId: String, val createdAt: Long, val clips: List<SavedHighlight>)

object HighlightStore {
    private fun directory(context: Context) = File(context.noBackupFilesDir, "highlights")
        .also { check(it.isDirectory || it.mkdirs()) }

    fun batches(context: Context): List<HighlightBatch> = directory(context).listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { folder ->
            runCatching {
                val json = JSONObject(AtomicFile(File(folder, "index.json")).readFully().toString(Charsets.UTF_8))
                val entries = json.getJSONArray("clips")
                val clips = (0 until entries.length()).map { i ->
                    val item = entries.getJSONObject(i)
                    val name = item.getString("file")
                    require(name.matches(Regex("clip_[1-3]\\.m4a")))
                    val file = File(folder, name)
                    check(file.isFile)
                    SavedHighlight(file, item.getString("title"), item.getLong("startMs"),
                        item.getLong("endMs"), item.getDouble("score"))
                }
                HighlightBatch(json.getString("sourceId"), json.getLong("createdAt"), clips)
            }.getOrNull()
        }.sortedByDescending { it.createdAt }

    fun create(context: Context, source: File, ranges: List<HighlightRange>, cancelled: () -> Boolean): HighlightBatch {
        val folder = File(directory(context), UUID.randomUUID().toString())
        check(folder.mkdir())
        var committed = false
        try {
            val entries = JSONArray()
            val clips = ranges.mapIndexed { index, range ->
                val file = File(folder, "clip_${index + 1}.m4a")
                val actual = AudioClipper.export(source, file, range, cancelled)
                val title = context.getString(R.string.highlight_clip_title, index + 1)
                entries.put(JSONObject().put("file", file.name).put("title", title)
                    .put("startMs", actual.startMs).put("endMs", actual.endMs).put("score", actual.score.toDouble()))
                SavedHighlight(file, title, actual.startMs, actual.endMs, actual.score.toDouble())
            }
            check(!cancelled()) { "cancelled" }
            val created = System.currentTimeMillis()
            val json = JSONObject().put("version", 1).put("sourceId", source.name)
                .put("createdAt", created).put("clips", entries)
            val atomic = AtomicFile(File(folder, "index.json"))
            val stream = atomic.startWrite()
            try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
            committed = true
            return HighlightBatch(source.name, created, clips)
        } finally {
            if (!committed) { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
        }
    }

    /** Only unfinished generations are removed; committed clips survive source deletion. */
    fun cleanIncomplete(context: Context) {
        directory(context).listFiles().orEmpty().filter {
            it.isDirectory && !File(it, "index.json").exists()
        }.forEach { folder -> folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
}
