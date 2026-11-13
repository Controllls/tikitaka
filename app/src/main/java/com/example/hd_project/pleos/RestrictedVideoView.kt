package com.example.hd_project.pleos

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/** Also gates MediaController, media-key and prepared-playback calls to start(). */
class RestrictedVideoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : VideoView(context, attrs) {
    var canStart: () -> Boolean = { false }
    var playbackAllowed: Boolean = false
        set(value) { field=value; if(!value) pause() }
    override fun start() {
        if (playbackAllowed && canStart()) super.start() else pause()
    }
    override fun seekTo(msec: Int) {
        if (playbackAllowed && canStart()) super.seekTo(msec)
    }
    override fun resume() {
        if (playbackAllowed && canStart()) super.resume() else pause()
    }
}
