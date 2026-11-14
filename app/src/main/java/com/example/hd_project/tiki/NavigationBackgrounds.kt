package com.example.hd_project.tiki

import android.content.Context
import java.io.File

object NavigationBackgrounds {
    fun select(context: Context, source: File, startMs: Long, endMs: Long): NavigationBackgroundSelection {
        val timeline=runCatching {
            val journal=RecordingJournal.all(context).firstOrNull { it.optString("source")==source.name }
            journal?.optJSONObject("navigationBackground")?.let {
                NavigationBackgroundPolicy.json.decodeFromString<RoadTimeline>(it.toString())
            }
        }.getOrNull()
        return NavigationBackgroundPolicy.select(timeline,startMs,endMs.coerceAtLeast(startMs+1))
    }
    @Synchronized fun file(context: Context, root: File, selection: NavigationBackgroundSelection): File {
        val source=when(selection.asset) {
            "city-road-loop.mp4" -> "tiki/video/backgrounds/${selection.asset}"
            "city_rearview_loop_10s.mp4", "highway_rearview_loop_10s.mp4" -> "momo/resources/backgrounds/${selection.asset}"
            else -> error("Unsupported navigation background")
        }
        val output=File(root,"backgrounds/${selection.asset}")
        if(!output.isFile || output.length()==0L) {
            output.parentFile!!.mkdirs()
            val pending=File(output.path+".pending")
            context.assets.open(source).use { input -> pending.outputStream().use { input.copyTo(it) } }
            check(pending.renameTo(output))
        }
        return output
    }
}
