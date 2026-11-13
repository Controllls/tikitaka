package com.example.hd_project.tiki

import androidx.annotation.Keep

@Keep class StoryCancellation(private val cancelled: () -> Boolean) {
    @Keep fun shouldAbort(): Boolean = cancelled()
}
@Keep internal object StoryNative {
    init { System.loadLibrary("tikitaka-story") }
    external fun generate(path: ByteArray, prompt: ByteArray, grammar: ByteArray, cancellation: StoryCancellation): ByteArray
}
