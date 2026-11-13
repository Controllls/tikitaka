package com.example.hd_project.tiki

import android.content.Context
import java.io.File

/** Figma's ten supplied 2D thumbnails; stable across every surface for the same episode. */
object TikiThumbnails {
    fun index(context: Context,movie: File): Int {
        val prefs=context.getSharedPreferences("tikitaka-thumbnails",0)
        return prefs.getInt(movie.name,0).takeIf { it in 1..10 } ?: (Math.floorMod(movie.name.hashCode(),10)+1).also {
            prefs.edit().putInt(movie.name,it).apply()
        }
    }
    fun asset(context: Context,movie: File)="tiki/episode-thumbnails/${index(context,movie).toString().padStart(2,'0')}.png"
    fun file(context: Context,movie: File): File {
        val target=File(context.cacheDir,"tiki-thumbnail-${index(context,movie)}.png")
        if(!target.isFile)context.assets.open(asset(context,movie)).use { input->target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
