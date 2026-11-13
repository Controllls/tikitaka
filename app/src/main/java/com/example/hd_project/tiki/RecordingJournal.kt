package com.example.hd_project.tiki

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.AtomicFile
import com.example.hd_project.RecordingFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Durable recovery journal. No pending microphone session is ever restarted automatically. */
object RecordingJournal {
    private fun file(context: Context) = AtomicFile(File(RecordingFiles.directory(context), "session-journal.json"))
    @Synchronized fun read(context: Context): JSONObject? = runCatching {
        JSONObject(file(context).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
    @Synchronized fun save(context: Context, state: JSONObject) {
        if(!state.has("sessionId"))state.put("sessionId",java.util.UUID.randomUUID().toString())
        if(!state.has("createdAt"))state.put("createdAt",System.currentTimeMillis())
        val target = file(context)
        val out = target.startWrite()
        try { out.write(state.toString().toByteArray()); target.finishWrite(out);archive(context,state) }
        catch (error: Exception) { target.failWrite(out); throw error }
    }
    private fun archive(context: Context,state: JSONObject) {
        val id=state.getString("sessionId");require(id.matches(Regex("[a-f0-9-]{36}")))
        val directory=File(RecordingFiles.directory(context),"journals").apply { mkdirs() }
        val target=AtomicFile(File(directory,"$id.json"));val out=target.startWrite()
        try { out.write(state.toString().toByteArray(Charsets.UTF_8));target.finishWrite(out) }
        catch(e: Exception) { target.failWrite(out);throw e }
    }
    @Synchronized fun all(context: Context): List<JSONObject> {
        val saved=File(RecordingFiles.directory(context),"journals").listFiles().orEmpty().filter { it.extension=="json" }.mapNotNull {
            runCatching { JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        }
        return (listOfNotNull(read(context))+saved).distinctBy { it.optString("sessionId",it.optString("pending")) }.sortedByDescending { it.optLong("createdAt") }
    }
    fun drafts(context: Context)=all(context).filter { state ->
        val directory=RecordingFiles.directory(context).canonicalFile;val parts=state.optJSONArray("segments") ?: JSONArray()
        state.optString("state") in listOf("recording","paused","saving","failed") &&
            ((0 until parts.length()).map { parts.getString(it) }+state.optString("pending")).any { name ->
                runCatching { File(directory,name).canonicalFile.let { it.parentFile==directory && it.isFile } }.getOrDefault(false)
            }
    }
    /** Cold-start recovery is file-driven and never reopens the microphone or produces a partial recording. */
    @Synchronized fun restoreInterrupted(context: Context) {
        if(com.example.hd_project.RecordingStore.state.busy)return
        reconcileFinalized(context)
        val state=read(context) ?: return
        if(state.optString("state") !in listOf("recording","paused","saving","failed"))return
        val directory=RecordingFiles.directory(context).canonicalFile
        val parts=state.optJSONArray("segments") ?: JSONArray()
        val names=(0 until parts.length()).map { parts.getString(it) }+state.optString("pending")
        val recoverable=names.any { name -> runCatching {
            val candidate=File(directory,name).canonicalFile
            candidate.parentFile==directory && candidate.isFile && duration(candidate)>=100
        }.getOrDefault(false) }
        if(recoverable)runCatching { recover(context) }
        else save(context,state.put("state","unrecoverable").put("autoProductionEligible",false).put("finishedAt",System.currentTimeMillis()))
    }
    @Synchronized fun active(context: Context, pending: File, segments: List<File>, only: Boolean, elapsed: Long, state: String) {
        val previous=read(context)
        val continuing=previous!=null && (previous.optString("pending")==pending.name || segments.any { it.name==previous.optString("pending") }) && previous.optString("state") in listOf("recording","paused","saving")
        if(previous!=null && !previous.has("sessionId"))save(context,previous)
        save(context,(if(continuing)previous!! else JSONObject()).put("state",state).put("pending",pending.name)
            .put("segments",JSONArray(segments.map { it.name })).put("recordOnly",only).put("elapsedMs",elapsed).put("autoProductionEligible",false))
    }
    fun finished(context: Context, source: File?, pending: File?, only: Boolean, reason: String, stopTrigger: String? = null) =
        save(context, (read(context) ?: JSONObject()).put("state", reason).put("source", source?.name)
            .put("pending", pending?.name).put("recordOnly", only).put("finishedAt", System.currentTimeMillis())
            .put("autoProductionEligible",reason=="saved" && !only && source?.isFile==true && runCatching { duration(source)>=800 }.getOrDefault(false))
            .apply { if(stopTrigger != null) put("stopTrigger", stopTrigger) })
    /** Repair a crash after the verified M4A rename but before the final journal commit. */
    @Synchronized fun reconcileFinalized(context: Context) {
        val state=read(context) ?: return
        if(state.optString("state")!="saving" || !state.has("finalCandidate"))return
        val directory=RecordingFiles.directory(context).canonicalFile
        val candidate=File(directory,state.getString("finalCandidate")).canonicalFile
        if(candidate.parentFile==directory && candidate.isFile && runCatching { duration(candidate)>=800 }.getOrDefault(false))
            finished(context,candidate,null,state.optBoolean("recordOnly"),if(state.optBoolean("normalExpected"))"saved" else "recovered")
    }
    fun duration(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("재생 가능한 음성이 없어요.")
            extractor.selectTrack(index)
            // AAC encoder priming may give the first valid packet a negative timestamp.
            require(extractor.readSampleData(ByteBuffer.allocate(1024 * 1024), 0) > 0) { "음성 데이터가 비어 있어요." }
            return extractor.getTrackFormat(index).getLong(MediaFormat.KEY_DURATION) / 1000
        } finally { extractor.release() }
    }
    /** Concatenate encoded AAC access units without decoding or losing source quality. */
    fun join(parts: List<File>, output: File): Long {
        require(parts.isNotEmpty()) { "저장할 녹음 구간이 없어요." }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var offset = 0L
        var sampleRate = 44100
        var channels = 1
        try {
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (part in parts) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.path)
                    val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    val format = extractor.getTrackFormat(index)
                    if (track < 0) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        track = muxer.addTrack(format); muxer.start()
                    } else require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == sampleRate &&
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels) { "녹음 구간 형식이 달라요." }
                    extractor.selectTrack(index)
                    val first = extractor.sampleTime
                    var last = offset
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        last = offset + (extractor.sampleTime - first).coerceAtLeast(0)
                        // These are complete AAC packets, not video key frames or codec config.
                        info.set(0, size, last, 0)
                        muxer.writeSampleData(track, buffer, info)
                        extractor.advance()
                    }
                    offset = last + 1024_000_000L / sampleRate
                } finally { extractor.release() }
            }
            muxer.stop()
        } finally { muxer.release() }
        return duration(output)
    }

    /** Called only by a recovery button. Partial recovery never schedules production. */
    @Synchronized fun recover(context: Context, sessionId: String?=null): File {
        check(!com.example.hd_project.RecordingStore.state.busy) { "진행 중인 녹음을 먼저 마쳐 주세요." }
        val state = requireNotNull(if(sessionId==null)read(context) else all(context).firstOrNull { it.optString("sessionId")==sessionId })
        require(state.optString("state") in listOf("recording", "paused", "saving", "failed"))
        val directory = RecordingFiles.directory(context).canonicalFile
        fun safe(name: String): File = File(directory, name).canonicalFile.also {
            require(it.parentFile == directory && it.isFile)
        }
        val candidates = state.optJSONArray("segments") ?: JSONArray()
        val names = (0 until candidates.length()).map { candidates.getString(it) } + state.optString("pending")
        val valid = names.distinct().mapNotNull { name -> runCatching { safe(name).takeIf { duration(it) >= 100 } }.getOrNull() }
        require(valid.isNotEmpty()) { "복구 가능한 음성 구간이 없어요. 임시 파일은 그대로 보관합니다." }
        val pending = RecordingFiles.pending(context)
        val duration = join(valid, pending)
        val output = RecordingFiles.finish(pending, duration)
        save(context,state);finished(context, output, null, true, "recovered")
        return output
    }
}
