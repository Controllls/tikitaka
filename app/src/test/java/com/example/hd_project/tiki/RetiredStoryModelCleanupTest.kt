package com.example.hd_project.tiki

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetiredStoryModelCleanupTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun removesOnlyRetiredModelAndPartialDownloadAndCanRunAgain() {
        val root=temp.newFolder("no-backup")
        val folder=File(root,"story-models").apply { mkdirs() }
        val retired=listOf("Qwen3-1.7B-Q4_K_M.gguf","Qwen3-1.7B-Q4_K_M.gguf.download").map { name ->
            File(folder,name).apply { writeText("obsolete model") }
        }
        val kept=listOf(File(folder,"another-model.gguf"),File(root,"recording.m4a"),File(root,"episode.mp4"),File(root,"analysis.json"))
            .onEach { it.writeText("preserve ${it.name}") }.associateWith { it.readBytes().toList() }
        repeat(2) { RetiredStoryModelCleanup.removeFiles(root) }
        assertTrue(retired.none { it.exists() })
        kept.forEach { (file,bytes) -> assertEquals(bytes,file.readBytes().toList()) }
    }

    @Test fun aFreshInstallNeedsNoModelDirectory() {
        val root=temp.newFolder("fresh")
        RetiredStoryModelCleanup.removeFiles(root)
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
