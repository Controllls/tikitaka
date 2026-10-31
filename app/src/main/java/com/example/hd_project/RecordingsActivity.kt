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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RecordingsActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playbackDialog: AlertDialog? = null
    private var progress: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        val root = findViewById<View>(R.id.archive_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
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
            row.setOnClickListener { openRecording(recording) }
            rows.addView(row)
        }
    }

    private fun openRecording(recording: SavedRecording) {
        releasePlayer()
        var prepared = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(recording.title)
            .setMessage(R.string.playback_loading)
            .setPositiveButton(R.string.play, null)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.delete, null)
            .create()
        playbackDialog = dialog
        dialog.setOnDismissListener { releasePlayer(); playbackDialog = null }
        dialog.show()
        val playButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        playButton.isEnabled = false
        playButton.setOnClickListener {
            val audio = player ?: return@setOnClickListener
            if (!prepared) return@setOnClickListener
            runCatching {
                if (audio.isPlaying) {
                    audio.pause()
                    playButton.setText(R.string.play)
                } else {
                    audio.start()
                    playButton.setText(R.string.pause)
                }
            }.onFailure { Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_LONG).show() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    if (!recording.file.delete())
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
            audio.setOnCompletionListener { playButton.setText(R.string.play) }
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
        progress?.let(handler::removeCallbacks)
        progress = null
        player?.release()
        player = null
    }

    override fun onStop() {
        playbackDialog?.dismiss()
        releasePlayer()
        super.onStop()
    }

}
