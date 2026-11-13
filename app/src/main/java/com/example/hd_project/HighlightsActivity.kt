package com.example.hd_project

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

class HighlightsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var selectedId: String? = null
    private var pendingIntent: Intent? = null
    private var signature = ""
    private var player: MediaPlayer? = null
    private var dialog: AlertDialog? = null
    private var focus: AudioFocusRequest? = null
    private var pendingPlaybackResponse = false
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val playbackTimeout = Runnable {
        if (pendingPlaybackResponse) {
            playbackResponse(false)
            dialog?.setMessage(getString(R.string.playback_failed))
            releasePlayer()
        }
    }
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_highlights)
        findViewById<View>(R.id.highlights_root).applyPleosInsets()
        selectedId = savedInstanceState?.getString("selected")
            ?: AutomaticHighlights.latestSource(this) ?: HighlightStore.batches(this).firstOrNull()?.sourceId
            ?: RecordingFiles.list(this).firstOrNull()?.file?.name
        if (savedInstanceState == null && GleoContract.actionName(intent.action, packageName) != null) pendingIntent = intent
        findViewById<View>(R.id.highlights_back).setOnClickListener { finish() }
        findViewById<View>(R.id.choose_recording).setOnClickListener { chooseRecording() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (GleoContract.actionName(intent.action, packageName) != null) pendingIntent = intent
    }

    override fun onResume() {
        super.onResume()
        pendingIntent?.also { pendingIntent = null; handleGleo(it) }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedId)
        super.onSaveInstanceState(outState)
    }

    private fun currentBatch(): HighlightBatch? = HighlightStore.batches(this).firstOrNull { it.sourceId == selectedId }

    private fun chooseRecording() {
        val recordings = RecordingFiles.list(this)
        if (recordings.isEmpty()) {
            Toast.makeText(this, R.string.highlight_no_recordings, Toast.LENGTH_LONG).show(); return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.highlight_choose)
            .setItems(recordings.map { it.title + "  ·  " + formatDuration(it.durationMs) }.toTypedArray()) { _, which ->
                selectedId = recordings[which].file.name; signature = ""; render()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun handleGleo(intent: Intent) {
        dialog?.dismiss()
        val request = runCatching { GleoContract.parse(intent, packageName) }.getOrElse {
            val function = GleoContract.actionName(intent.action, packageName) ?: return
            GleoContract.respond(this, function, "asking", "Please provide valid recording or highlight parameters.")
            return
        }
        when (request.action) {
            GleoContract.ANALYZE -> {
                val source = request.sourceId ?: AutomaticHighlights.latestSource(this) ?: RecordingFiles.list(this).firstOrNull()?.file?.name
                val exists = RecordingFiles.list(this).any { it.file.name == source } || HighlightStore.batches(this).any { it.sourceId == source }
                selectedId = source
                GleoContract.respond(this, request.action, if(exists) "success" else "fail",
                    if(exists) "Highlights are generated automatically after recording ends. Showing saved results and current status." else "No matching saved recording is available.")
                signature = ""; render()
            }
            GleoContract.SHOW -> {
                selectedId = HighlightStore.batches(this).firstOrNull()?.sourceId
                    ?: RecordingFiles.list(this).firstOrNull()?.file?.name
                val clips = currentBatch()?.clips.orEmpty()
                val data = JSONArray()
                clips.forEachIndexed { index, clip -> data.put(JSONObject().put("highlightNumber", index + 1)
                    .put("title", clip.title).put("durationSeconds", clip.durationMs / 1000)) }
                val message = if (AutomaticHighlights.isPending(this, selectedId))
                    "Analysis is still running. Any previously saved highlight candidates are displayed."
                else if (clips.isEmpty()) "There are no saved highlight candidates for this recording."
                else "The saved laughter-based highlight candidates are displayed."
                GleoContract.respond(this, request.action, "success", message, data)
                signature = ""; render()
            }
            GleoContract.PLAY -> {
                if (request.number == null) {
                    GleoContract.respond(this, request.action, "asking", "Please specify a highlight number from one to three.")
                } else {
                    val clip = currentBatch()?.clips?.getOrNull(request.number - 1)
                    if (clip == null) GleoContract.respond(this, request.action, "fail", "The requested saved highlight does not exist.")
                    else play(clip, true)
                }
            }
        }
    }

    private fun render() {
        val recording = RecordingFiles.list(this).firstOrNull { it.file.name == selectedId }
        findViewById<TextView>(R.id.selected_recording).text = recording?.let {
            it.title + "  ·  " + formatDuration(it.durationMs)
        } ?: getString(if (selectedId == null) R.string.highlight_no_recordings else R.string.highlight_source_deleted)
        val batch = currentBatch()
        val status = AutomaticHighlights.message(this, selectedId) ?: if(batch != null)
            getString(if(batch.clips.isEmpty()) R.string.highlight_none_found else R.string.highlight_done)
            else getString(R.string.highlight_ready)
        findViewById<TextView>(R.id.highlight_status).text = status
        val clips = batch?.clips.orEmpty()
        val nextSignature = selectedId + ":" + batch?.createdAt + ":" + RecordingStore.state.busy
        if (signature == nextSignature) return
        signature = nextSignature
        findViewById<View>(R.id.highlight_empty).visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        val rows = findViewById<LinearLayout>(R.id.highlight_rows)
        rows.removeAllViews()
        clips.forEachIndexed { index, clip ->
            val row = layoutInflater.inflate(R.layout.item_recording, rows, false)
            row.findViewById<TextView>(R.id.item_number).text = getString(R.string.recording_number, index + 1)
            row.findViewById<TextView>(R.id.item_title).text = clip.title
            row.findViewById<TextView>(R.id.item_details).text = getString(R.string.highlight_clip_details,
                formatDuration(clip.durationMs), formatDuration(clip.startMs), formatDuration(clip.endMs))
            row.findViewById<View>(R.id.make_animation).visibility = View.GONE
            row.isEnabled = !RecordingStore.state.busy
            row.setOnClickListener { play(clip, false) }
            rows.addView(row)
        }
    }

    private fun play(clip: SavedHighlight, gleo: Boolean) {
        if (RecordingStore.state.busy) {
            if (gleo) GleoContract.respond(this, GleoContract.PLAY, "fail", "Please stop recording before playing a highlight.")
            return
        }
        dialog?.dismiss()
        releasePlayer()
        pendingPlaybackResponse = gleo
        val window = MaterialAlertDialogBuilder(this).setTitle(clip.title).setMessage(R.string.playback_loading)
            .setPositiveButton(R.string.pause, null).setNegativeButton(R.string.close, null).create()
        dialog = window
        window.setOnDismissListener { releasePlayer(); dialog = null }
        window.show()
        window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        try {
            val audio = MediaPlayer()
            player = audio
            audio.setAudioAttributes(attributes)
            audio.setDataSource(clip.file.absolutePath)
            audio.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                        if (change <= AudioManager.AUDIOFOCUS_LOSS) {
                            runCatching { player?.pause() }
                            window.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.play)
                        }
                    }.build()
                focus = focusRequest
                if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    window.setMessage(getString(R.string.playback_failed)); releasePlayer(); return@setOnPreparedListener
                }
                runCatching {
                    it.start()
                    window.setMessage(getString(R.string.highlight_playing, formatDuration(clip.durationMs)))
                    window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    playbackResponse(true)
                }.onFailure { window.setMessage(getString(R.string.playback_failed)); releasePlayer() }
            }
            audio.setOnCompletionListener { window.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.play) }
            audio.setOnErrorListener { _, _, _ ->
                window.setMessage(getString(R.string.playback_failed)); releasePlayer(); true
            }
            window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val current = player ?: return@setOnClickListener
                    if (current.isPlaying) { current.pause(); window.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.play) }
                    else if (focus?.let(audioManager::requestAudioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        current.start(); window.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.pause)
                    }
                }.onFailure { window.setMessage(getString(R.string.playback_failed)); releasePlayer() }
            }
            audio.prepareAsync()
            handler.postDelayed(playbackTimeout, 5000)
        } catch (_: Exception) { window.setMessage(getString(R.string.playback_failed)); releasePlayer() }
    }

    private fun playbackResponse(success: Boolean) {
        if (!pendingPlaybackResponse) return
        pendingPlaybackResponse = false
        handler.removeCallbacks(playbackTimeout)
        GleoContract.respond(this, GleoContract.PLAY, if (success) "success" else "fail",
            if (success) "Playback of the requested highlight has started." else "The highlight could not be played.",
            silent = success)
    }
    private fun releasePlayer() {
        playbackResponse(false)
        handler.removeCallbacks(playbackTimeout)
        player?.release(); player = null
        focus?.let(audioManager::abandonAudioFocusRequest); focus = null
    }
    override fun onStop() {
        handler.removeCallbacks(refresh)
        dialog?.dismiss()
        releasePlayer()
        super.onStop()
    }
}
