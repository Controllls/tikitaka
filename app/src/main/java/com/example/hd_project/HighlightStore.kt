package com.example.hd_project

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.example.hd_project.momo.network.AnalysisApi
import com.example.hd_project.momo.network.HighlightResult
import com.example.hd_project.momo.storage.sha256

data class SavedHighlight(val file: File, val title: String, val startMs: Long, val endMs: Long,
    val score: Double) {
    val durationMs get() = endMs - startMs
}
data class HighlightBatch(val sourceId: String, val createdAt: Long, val clips: List<SavedHighlight>)

object HighlightStore {
    /** Publish locally clipped audio only after every entry exists; reusable on worker retry. */
    fun saveLocal(context: Context, sourceId: String, planId: String, clips: List<SavedHighlight>) {
        require(planId.matches(Regex("[a-f0-9]{64}")) && clips.size in 1..3)
        val sourceKey=com.example.hd_project.momo.MomoVideoSession.projectId(sourceId)
        val folder=File(directory(context),"local-$sourceKey-$planId").apply { mkdirs() }
        val rows=JSONArray()
        clips.forEachIndexed { i,clip ->
            val output=File(folder,"clip_${i+1}.m4a")
            if(!output.isFile || sha256(output)!=sha256(clip.file)) {
                val temporary=File(folder,"clip_${i+1}.pending")
                try { clip.file.copyTo(temporary,overwrite=true);check(temporary.renameTo(output)) }
                finally { temporary.delete() }
            }
            rows.put(JSONObject().put("file",output.name).put("title",clip.title).put("startMs",clip.startMs).put("endMs",clip.endMs).put("score",clip.score))
        }
        val atomic=AtomicFile(File(folder,"index.json"));val stream=atomic.startWrite()
        try { stream.write(JSONObject().put("version",1).put("sourceId",sourceId).put("createdAt",System.currentTimeMillis())
            .put("planId",planId).put("clips",rows).toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(e: Exception) { atomic.failWrite(stream);throw e }
    }
    /** Commit the manifest last, so interrupted downloads never appear as playable results. */
    suspend fun saveServer(context: Context, sourceId: String, result: HighlightResult, api: AnalysisApi): HighlightBatch {
        require(result.schemaVersion == "1.0" && result.threshold == .1 && result.durationMs > 0)
        require(result.jobId.matches(Regex("[a-zA-Z0-9-]{1,64}")) && result.clips.size <= 3)
        require(result.dataOrigin == "inference" || (BuildConfig.DEBUG && result.dataOrigin == "fixture"))
        var end = 0L
        result.clips.forEachIndexed { index, clip ->
            require(clip.number == index+1 && clip.startMs >= end && clip.endMs > clip.startMs && clip.endMs <= result.durationMs)
            require(clip.size in 1..80_000_000 && clip.sha256.matches(Regex("[a-f0-9]{64}")))
            require(clip.title.isNotBlank() && clip.score.isFinite() && clip.score in 0.0..1.0)
            end = clip.endMs
        }
        val folder = File(directory(context), "server-${result.jobId}").apply { mkdirs() }
        val entries = JSONArray()
        val clips = result.clips.map { clip ->
            val name = "clip_${clip.number}.m4a"
            val target = File(folder, name)
            if (!target.isFile || target.length() != clip.size || sha256(target) != clip.sha256) {
                val pending = File(folder, "$name.pending")
                try {
                    api.highlightFile(result.jobId, name).use { response -> response.byteStream().use { input ->
                        pending.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var count = 0L
                            while (true) { val read = input.read(buffer); if(read<0)break; count+=read
                                require(count <= clip.size); output.write(buffer,0,read) }
                        }
                    } }
                    require(pending.length() == clip.size && sha256(pending) == clip.sha256) { "하이라이트 파일을 확인하지 못했어요." }
                    check(pending.renameTo(target))
                } finally { pending.delete() }
            }
            entries.put(JSONObject().put("file",name).put("title",clip.title).put("startMs",clip.startMs)
                .put("endMs",clip.endMs).put("score",clip.score))
            SavedHighlight(target,clip.title,clip.startMs,clip.endMs,clip.score)
        }
        val created = System.currentTimeMillis()
        val manifest = JSONObject().put("version",1).put("sourceId",sourceId).put("createdAt",created)
            .put("serverJobId",result.jobId).put("audioSha256",result.audioSha256).put("clips",entries)
        val atomic = AtomicFile(File(folder,"index.json"))
        val stream = atomic.startWrite()
        try { stream.write(manifest.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch(e: Exception) { atomic.failWrite(stream); throw e }
        return HighlightBatch(sourceId,created,clips)
    }

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
