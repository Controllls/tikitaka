package com.example.hd_project

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.hd_project.pleos.applyPleosInsets
import com.example.hd_project.pleos.MediaPlaybackFocus
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RecordingsActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playbackDialog: AlertDialog? = null
    private var progress: Runnable? = null
    private var playbackId: String? = null
    private var playbackPosition = 0
    private var retainingPlayback = false
    private val audioFocus by lazy { MediaPlaybackFocus(this) {
        playbackDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(R.string.play)
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackId=savedInstanceState?.getString("playback_id")
        playbackPosition=savedInstanceState?.getInt("playback_position") ?: 0
        setContentView(R.layout.activity_recordings)
        val root = findViewById<View>(R.id.archive_root)
        root.applyPleosInsets()
        rows = findViewById(R.id.recording_rows)
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.highlights_button).setOnClickListener {
            startActivity(android.content.Intent(this, HighlightsActivity::class.java))
        }
        findViewById<View>(R.id.archive_home).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        if (RecordingStore.state.busy) { finish(); return }
        renderList()
        playbackId?.let { id -> RecordingFiles.list(this).firstOrNull { it.file.name==id }?.let(::openRecording) }
    }

    private fun renderList() {
        rows.removeAllViews()
        val recordings = runCatching { RecordingFiles.list(this) }.getOrElse {
            findViewById<TextView>(R.id.archive_summary).setText(R.string.archive_load_failed)
            return
        }
        findViewById<TextView>(R.id.archive_summary).text = getString(R.string.archive_summary,
            recordings.size, formatDuration(recordings.sumOf { it.durationMs }))
        if (recordings.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.archive_empty, rows, false)
            empty.findViewById<View>(R.id.return_to_recorder).setOnClickListener { finish() }
            rows.addView(empty)
        }
        recordings.forEachIndexed { index, recording ->
            val row = layoutInflater.inflate(R.layout.item_recording, rows, false)
            row.findViewById<TextView>(R.id.item_number).text = getString(R.string.recording_number, recordings.size - index)
            row.findViewById<TextView>(R.id.item_title).text = recording.title
            row.findViewById<TextView>(R.id.item_details).text = recording.details
            row.findViewById<View>(R.id.make_animation).setOnClickListener {
                startActivity(android.content.Intent(this, AnimationActivity::class.java)
                    .putExtra("sourceId", recording.file.name))
            }
            row.setOnClickListener { openRecording(recording) }
            rows.addView(row)
        }
    }

    private fun openRecording(recording: SavedRecording) {
        releasePlayer()
        if(playbackId!=recording.file.name) playbackPosition=0
        playbackId=recording.file.name
        var prepared = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(recording.title)
            .setMessage(R.string.playback_loading)
            .setPositiveButton(R.string.play, null)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.delete, null)
            .create()
        playbackDialog = dialog
        dialog.setOnDismissListener {
            releasePlayer(); playbackDialog = null
            if(!retainingPlayback) { playbackId=null;playbackPosition=0 }
        }
        dialog.show()
        val playButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        playButton.isEnabled = false
        playButton.setOnClickListener {
            val audio = player ?: return@setOnClickListener
            if (!prepared) return@setOnClickListener
            runCatching {
                if (audio.isPlaying) {
                    audio.pause()
                    audioFocus.release()
                    playButton.setText(R.string.play)
                } else {
                    if(audioFocus.acquire(audio)) { audio.start();playButton.setText(R.string.pause) }
                    else Toast.makeText(this, R.string.pleos_audio_unavailable, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_LONG).show() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (com.example.hd_project.momo.MomoVideoSession.isBusy(this, recording.file.name)) {
                Toast.makeText(this, R.string.animation_busy, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    if (!RecordingFiles.delete(recording))
                        Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
                    renderList()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        try {
            val audio = MediaPlayer()
            player = audio
            audio.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            audio.setDataSource(recording.file.absolutePath)
            audio.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                if(playbackPosition>0) it.seekTo(playbackPosition)
                playButton.isEnabled = true
                progress = object : Runnable {
                    override fun run() {
                        val current = player ?: return
                        runCatching {
                            dialog.setMessage(getString(R.string.playback_progress,
                                formatDuration(current.currentPosition.toLong()), formatDuration(current.duration.toLong())))
                        }
                        handler.postDelayed(this, 250)
                    }
                }.also { handler.post(it) }
            }
            audio.setOnCompletionListener { audioFocus.release();playButton.setText(R.string.play) }
            audio.setOnErrorListener { _, _, _ ->
                dialog.setMessage(getString(R.string.playback_failed))
                playButton.isEnabled = false
                releasePlayer()
                true
            }
            audio.prepareAsync()
        } catch (_: Exception) {
            dialog.setMessage(getString(R.string.playback_failed))
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        audioFocus.release()
        progress?.let(handler::removeCallbacks)
        progress = null
        player?.release()
        player = null
    }

    override fun onStop() {
        rememberPlayback()
        retainingPlayback=true
        playbackDialog?.dismiss()
        retainingPlayback=false
        releasePlayer()
        super.onStop()
    }

    private fun rememberPlayback() {
        runCatching { player?.currentPosition }.getOrNull()?.takeIf { it>0 }?.let { playbackPosition=it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberPlayback()
        outState.putString("playback_id",playbackId)
        outState.putInt("playback_position",playbackPosition)
        super.onSaveInstanceState(outState)
    }

}
