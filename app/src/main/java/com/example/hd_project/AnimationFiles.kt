package com.example.hd_project

import android.content.Context
import java.io.File

object AnimationFiles {
    fun directory(context: Context) = File(context.noBackupFilesDir, "momo-videos")
        .also { check(it.isDirectory || it.mkdirs()) }
    fun output(context: Context, source: File) = File(directory(context), source.nameWithoutExtension + ".mp4")
    fun pending(context: Context, source: File) = File(directory(context), source.nameWithoutExtension + ".pending.mp4")
}
