package com.example.hd_project.tiki

import android.content.Context
import java.io.File

/** Remove only the retired download, never media, analysis checkpoints or another model. */
internal object RetiredStoryModelCleanup {
    private const val FILE="Qwen3-1.7B-Q4_K_M.gguf"
    fun apply(context: Context) = removeFiles(context.noBackupFilesDir)

    internal fun removeFiles(noBackup: File) {
        val root=noBackup.canonicalFile
        val folder=File(root,"story-models")
        if(!folder.exists())return
        check(folder.isDirectory && folder.canonicalFile==folder.absoluteFile)
        for(name in listOf(FILE,"$FILE.download")) {
            val file=File(folder,name)
            if(!file.exists())continue
            check(file.isFile && file.canonicalFile.parentFile==folder)
            check(file.delete()) { "Could not remove obsolete model cache" }
        }
    }
}
