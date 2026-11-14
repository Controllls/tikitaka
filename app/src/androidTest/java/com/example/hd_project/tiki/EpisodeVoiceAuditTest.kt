package com.example.hd_project.tiki

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.people.PeopleRepository
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import java.io.File

/** Reads persisted evidence only; never renders, enrolls, deletes or changes episode numbering. */
class EpisodeVoiceAuditTest {
    @Test fun inspectSavedEpisode() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val context=ins.targetContext
        val number=InstrumentationRegistry.getArguments().getString("episode")!!.toInt()
        val prefs=context.getSharedPreferences("tikitaka-episode-numbers",0)
        val name=prefs.all.entries.single { it.key!="next" && it.value==number }.key
        val movie=File(context.noBackupFilesDir,"momo-videos/$name")
        check(movie.isFile) { "Episode file missing: $name" }
        val meta=JSONObject(File(movie.path+".json").readText())
        val diagnostic=File(movie.path+".diagnostics.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val source=meta.getString("source")
        val job=PeopleRepository(context,"tikitaka-job-"+EpisodeWork.id(source)).list().firstOrNull()
        val profile=TikiProfile.get(context)
        val report=JSONObject().put("episode",number).put("file",name)
            .put("metadata",meta).put("diagnosticAvailable",diagnostic!=null)
            .put("currentVoiceRegistered",profile.voice!=null).put("jobVoiceRegistered",job?.voice!=null)
            .put("sameVoiceAsJob",profile.voice==job?.voice)
        diagnostic?.let { d ->
            listOf("diarization","voice","castNames","speakerMapping","renderer").forEach { key ->
                if(d.has(key))report.put(key,d.get(key))
            }
            report.put("firstFrame",d.optJSONArray("frames")?.optJSONObject(0))
        }
        val captions=File(movie.path+".captions.json")
        if(captions.isFile)report.put("captions",JSONArray(captions.readText()))
        val reader=android.media.MediaMetadataRetriever()
        try {
            reader.setDataSource(movie.path)
            val frames=JSONArray()
            for(ms in listOf(1000L,3500L,7000L)) {
                val bitmap=reader.getFrameAtTime(ms*1000,android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val output=java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,70,output);bitmap.recycle()
                frames.put(JSONObject().put("timeMs",ms).put("jpeg",android.util.Base64.encodeToString(output.toByteArray(),android.util.Base64.NO_WRAP)))
            }
            report.put("previewFrames",frames)
        } finally { reader.release() }
        ins.sendStatus(0,Bundle().apply { putString("episodeAudit",report.toString()) })
    }
}
