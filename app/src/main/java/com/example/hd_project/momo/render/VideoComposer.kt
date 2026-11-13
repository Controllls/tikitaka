@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.hd_project.momo.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.example.hd_project.momo.model.*
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PreparedScene(val composition: Composition, val renderer: SceneRenderer, val width: Int, val height: Int)

object VideoComposer {
    suspend fun prepare(context: Context, project: SceneProject, analysis: AnalysisBundle): PreparedScene = withContext(Dispatchers.IO) {
        analysis.validate()
        require(File(project.audioLocalRef).isFile) { "원본 오디오가 없습니다." }
        require(project.people.size in 1..4 && project.people.map { it.seat }.distinct().size==project.people.size) { "좌석이 중복되었어요." }
        require(project.people.all { it.seat in SeatIds })
        require(!project.sampleVisuals && project.people.all { it.manifestRef!=null }) { "영상 리소스를 먼저 준비해 주세요." }
        val width=project.width;val height=project.height
        require((width to height) in setOf(540 to 960,1280 to 720)) { "지원하지 않는 영상 크기입니다." }
        val renderer=SceneRenderer(project,analysis)
        val blank=File(context.cacheDir,"scene-base-$width-$height.png")
        if(!blank.exists()) {
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            blank.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
        val audio=EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(project.audioLocalRef))))
            .setDurationUs(analysis.result.durationUs).setRemoveVideo(true)
            .setEffects(Effects(listOf(androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }),emptyList())).build()
        val background=if(project.backgroundIsVideo && project.backgroundRef!=null) {
            val reader=android.media.MediaMetadataRetriever()
            val duration=try { reader.setDataSource(project.backgroundRef); (reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)*1000 } finally { reader.release() }
            require(duration>0) { "배경 동영상 길이를 읽을 수 없어요." }
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(project.backgroundRef)))).setDurationUs(duration).setRemoveAudio(true).setFrameRate(30)
                .setEffects(Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))).build()
        } else {
            val item=MediaItem.Builder().setUri(Uri.fromFile(blank)).setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs((analysis.result.durationUs+999)/1000).build()
            EditedMediaItem.Builder(item).setDurationUs(analysis.result.durationUs).setFrameRate(30).setRemoveAudio(true).build()
        }
        val overlay=object: CanvasOverlay(false) {
            init { setCanvasSize(width,height) }
            override fun onDraw(canvas: Canvas,presentationTimeUs: Long) { renderer.draw(canvas,presentationTimeUs) }
        }
        val videoSequence=EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(background).setIsLooping(project.backgroundIsVideo).build()
        val audioSequence=EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO)).addItem(audio).build()
        val composition=Composition.Builder(videoSequence,audioSequence).setTransmuxAudio(false)
            .setEffects(Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT),OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay))))).build()
        PreparedScene(composition,renderer,width,height)
    }

    suspend fun exportFile(context: Context, scene: PreparedScene, file: File, onProgress: (Int)->Unit) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val handler=Handler(Looper.getMainLooper())
            lateinit var transformer: Transformer
            val progress=ProgressHolder()
            val tick=object: Runnable {
                override fun run() {
                    if(continuation.isActive) { if(transformer.getProgress(progress)==Transformer.PROGRESS_STATE_AVAILABLE)onProgress(progress.progress); handler.postDelayed(this,250) }
                }
            }
            transformer=Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setPortraitEncodingEnabled(true).setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
                .addListener(object: Transformer.Listener {
                    override fun onCompleted(composition: Composition,result: ExportResult) {
                        handler.removeCallbacks(tick)
                        scene.renderer.close()
                        if(continuation.isActive)continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition,result: ExportResult,exception: ExportException) {
                        handler.removeCallbacks(tick); file.delete();scene.renderer.close()
                        if(continuation.isActive)continuation.resumeWithException(IllegalStateException("영상 인코딩에 실패했어요 (${exception.errorCodeName}).",exception))
                    }
                }).build()
            continuation.invokeOnCancellation { handler.post { handler.removeCallbacks(tick); transformer.cancel(); file.delete();scene.renderer.close() } }
            try { transformer.start(scene.composition,file.path); handler.post(tick) }
            catch(e: Exception) { handler.removeCallbacks(tick); file.delete();scene.renderer.close(); if(continuation.isActive)continuation.resumeWithException(e) }
        }
        withContext(Dispatchers.IO) { validateMp4(file,scene.width,scene.height) }
    }
    fun validateMp4(file: File, expectedWidth: Int? = null, expectedHeight: Int? = null) {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video=formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)==MimeTypes.VIDEO_H264 }
            require(video!=null) { "H.264 영상 트랙을 확인하지 못했어요." }
            val size=video.getInteger(MediaFormat.KEY_WIDTH) to video.getInteger(MediaFormat.KEY_HEIGHT)
            require(size in setOf(540 to 960,1280 to 720)) { "지원하는 크기의 영상 트랙을 확인하지 못했어요." }
            if(expectedWidth!=null && expectedHeight!=null)require(size==(expectedWidth to expectedHeight)) { "요청한 영상 크기와 다릅니다." }
            require(formats.any { it.getString(MediaFormat.KEY_MIME)==MimeTypes.AUDIO_AAC }) { "AAC 오디오 트랙이 없습니다." }
        } finally { extractor.release() }
    }
    @androidx.annotation.RequiresApi(29)
    suspend fun publish(context: Context,file: File,title: String): Uri = withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME,"MOMO_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/MOMO")
            put(MediaStore.Video.Media.IS_PENDING,1)
        }
        val uri=requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values))
        try {
            resolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)
            uri
        } catch(e: Exception) { resolver.delete(uri,null,null); throw e }
    }
}
