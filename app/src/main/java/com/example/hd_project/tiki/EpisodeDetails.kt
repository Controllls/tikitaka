package com.example.hd_project.tiki

import android.content.Context
import android.media.MediaMetadataRetriever
import com.example.hd_project.AnimationFiles
import com.example.hd_project.formatDuration
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EpisodeDetails(val file: File, val title: String, val number: Int, val recordedAt: Long,
                          val durationMs: Long, val journey: JourneySummary?) {
    val subtitle: String get() = "EP. ${number.toString().padStart(2,'0')}  ·  ${if(recordedAt>0)SimpleDateFormat("yyyy.MM.dd",Locale.KOREA).format(Date(recordedAt)) else "날짜 정보 없음"}  ·  ${formatDuration(durationMs)}"
    companion object {
        fun load(context: Context, name: String): EpisodeDetails {
            val directory=AnimationFiles.directory(context).canonicalFile
            val file=File(directory,name).canonicalFile
            require(file.parentFile==directory && file.isFile && file.extension=="mp4") { "영상을 찾을 수 없어요." }
            val metadata=runCatching { JSONObject(File(file.path+".json").readText()) }.getOrDefault(JSONObject())
            val journal=RecordingJournal.all(context).firstOrNull { it.optString("source")==metadata.optString("source") }
            val journey=runCatching { (metadata.optJSONObject("journey") ?: journal?.optJSONObject("journey"))?.let {
                NavigationBackgroundPolicy.json.decodeFromString<JourneySummary>(it.toString())
            } }.getOrNull()
            val retriever=MediaMetadataRetriever()
            val duration=try { retriever.setDataSource(file.path);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                finally { retriever.release() }
            return EpisodeDetails(file,metadata.optString("title").takeIf { it.isNotBlank() } ?: "우리의 이야기",
                EpisodeWork.episodeNumber(context,file),metadata.optLong("recordedAt").takeIf { it>0 } ?: journal?.optLong("createdAt")?.takeIf { it>0 } ?: file.lastModified(),duration,journey)
        }
    }
    fun screen(context: Context, art: TikiArt, back: ()->Unit, play: ()->Unit)=
        TikiFlowScreen(context,art,title,subtitle,back).apply {
            tag="page_E-01"
            item(art.poster(file,"video-detail","").apply { tag="detail_poster";contentDescription="에피소드 재생";isFocusable=true;setOnClickListener { play() } },1050,590)
            item(copy("완성 · 보관함에 저장됨",24f,500),gap=20)
            val trip=journey?.takeIf { it.available && it.distanceMeters.isFinite() && it.distanceMeters>=0 && it.movingMs>=0 }
            item(copy(if(trip!=null)"주행 시간 ${formatDuration(trip.movingMs)}  ·  주행 거리 ${String.format(Locale.KOREA,"%.1f",trip.distanceMeters/1000)} km"
                else "주행 정보가 없는 에피소드예요",20f,muted=true),gap=12)
            if(trip!=null)item(copy("녹음 중 위치가 확인된 구간의 주행 정보예요.",16f,muted=true),gap=8)
            actions("에피소드 재생",onPrimary=play,buttonWidth=440f)
        }
}
