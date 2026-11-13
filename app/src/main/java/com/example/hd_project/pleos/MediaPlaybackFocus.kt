package com.example.hd_project.pleos

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/** Platform owns focus arbitration; do not reimplement the vehicle's focus matrix. */
class MediaPlaybackFocus(context: Context, private val interrupted: () -> Unit = {}) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change ->
            when(change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    runCatching { player?.pause() }; interrupted()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { player?.setVolume(.2f, .2f) }
                AudioManager.AUDIOFOCUS_GAIN -> runCatching { player?.setVolume(1f, 1f) } // Never auto-resume.
            }
        }.build()

    fun acquire(audio: MediaPlayer): Boolean {
        if(player!==audio)runCatching { player?.pause() }
        player=audio
        val granted=manager.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(!granted) player=null
        else runCatching { audio.setVolume(1f,1f) }
        return granted
    }
    fun release() { player=null;manager.abandonAudioFocusRequest(request) }
}
