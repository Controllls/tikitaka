package com.example.hd_project.tiki

import android.content.Context
import android.os.SystemClock
import com.example.hd_project.RecordingStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads only public model weights. Dialogue never enters an HTTP request. */
object LocalStoryModel {
    const val MODEL = "Qwen3-1.7B-Q4_K_M"
    const val FILE = "$MODEL.gguf"
    const val SHA256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
    const val SIZE = 1_282_439_264L
    private const val REVISION = "daeb8e2d528a760970442092f6bf1e55c3b659eb"
    private val lock = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        // A slow first download may exceed five minutes. The worker's cancellable eight-minute
        // time slice retains partial bytes; readTimeout still detects stalled connections.
        .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
    fun file(context: Context) = File(context.noBackupFilesDir, "story-models/$FILE")

    private suspend fun verify(file: File): Boolean {
        if (!file.isFile || file.length() != SIZE) return false
        val job = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == SHA256
    }

    private suspend fun prepare(context: Context, progress: (String) -> Unit): File {
        val target = file(context)
        progress("대화 연출 모델을 확인해요.")
        if (verify(target)) return target
        val job = currentCoroutineContext()
        target.parentFile!!.mkdirs()
        val pending = File(target.path + ".download")
        if (pending.length() > SIZE) pending.delete()
        // Resume a verified-size partial transfer after a worker time slice or temporary network loss.
        val offset = pending.length()
        check(target.parentFile!!.usableSpace > SIZE - offset + 80_000_000) { "대화 모델을 준비하려면 약 1.4GB의 여유 공간이 필요해요." }
        if (offset < SIZE) {
            progress("대화 연출 모델을 처음 준비해요 · 약 1.3GB · ${offset * 100 / SIZE}%")
            val request = Request.Builder()
                .url("https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/$REVISION/$FILE")
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            client.newCall(request).execute().use { response ->
                check(response.code in listOf(200, 206)) { "대화 모델 다운로드에 실패했어요. 네트워크를 확인해 주세요." }
                val resume = response.code == 206
                if (resume) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true)
                RandomAccessFile(pending, "rw").use { out ->
                    if (!resume) out.setLength(0)
                    out.seek(if (resume) offset else 0)
                    var total = out.filePointer; var lastPercent = -1
                    requireNotNull(response.body).byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            job.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            check(total + n <= SIZE) { "대화 모델 파일 크기가 올바르지 않아요." }
                            out.write(buffer, 0, n); total += n
                            val percent = (total * 100 / SIZE).toInt()
                            if (percent != lastPercent) { lastPercent = percent; progress("대화 연출 모델을 처음 준비해요 · 약 1.3GB · $percent%") }
                        }
                    }
                    out.fd.sync()
                }
            }
        }
        if (!verify(pending)) { pending.delete(); error("대화 모델 파일 검증에 실패했어요. 다시 시도해 주세요.") }
        check(pending.renameTo(target)) { "대화 모델을 저장하지 못했어요." }
        return target
    }

    suspend fun plan(context: Context, lines: List<StoryLine>, durationUs: Long, progress: (String) -> Unit): DialogueStory =
        lock.withLock { withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val model = prepare(context, progress)
            val scenes = DialogueStoryRules.scenes(lines)
            var invalidAnswers = 0
            while (true) {
                while (RecordingStore.state.busy) { progress("녹음이 끝나면 대화 연출을 이어갈게요."); delay(2000) }
                val memory = android.app.ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
                check(!memory.lowMemory && memory.availMem > 1_400_000_000L) { "대화 연출 모델을 실행할 메모리가 부족해요." }
                val start = SystemClock.elapsedRealtime()
                progress("기기에서 대화를 읽고 제목·장면을 구성해요.")
                try {
                    val result = StoryNative.generate(model.path.toByteArray(Charsets.UTF_8), DialogueStoryRules.prompt(scenes,invalidAnswers>0).toByteArray(Charsets.UTF_8),
                        DialogueStoryRules.grammar(scenes.size).toByteArray(Charsets.UTF_8),
                        StoryCancellation { !job.isActive || RecordingStore.state.busy }).toString(Charsets.UTF_8)
                    job.ensureActive()
                    try {
                        return@withContext DialogueStoryRules.parse(result, scenes, durationUs, MODEL, SystemClock.elapsedRealtime() - start)
                    } catch (invalid: IllegalArgumentException) {
                        if (++invalidAnswers>=2) throw IllegalStateException("대화에 맞는 제목을 완성하지 못했어요. 다시 시도해 주세요.",invalid)
                        progress("대화 내용과 제목을 다시 맞춰 보고 있어요.")
                    }
                } catch (error: Exception) {
                    job.ensureActive()
                    if (!RecordingStore.state.busy) throw error // A new recording is a pause, not a failed model.
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } }
}
