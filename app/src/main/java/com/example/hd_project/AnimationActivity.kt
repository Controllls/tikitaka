package com.example.hd_project

import android.content.ContentValues
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.hd_project.pleos.DrivingRestrictions
import com.example.hd_project.pleos.RestrictedVideoView
import com.example.hd_project.pleos.applyPleosInsets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hd_project.momo.MomoVideoState
import com.example.hd_project.momo.MomoVideoViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Existing recording -> one fixed MOMO video. No character/background/subtitle editor. */
class AnimationActivity: AppCompatActivity() {
    private val model: MomoVideoViewModel by viewModels()
    private lateinit var source: SavedRecording
    private lateinit var video: RestrictedVideoView
    private var playbackPosition = 0
    private var screenStarted = false
    private val restrictions by lazy { DrivingRestrictions(this) {
        if (::video.isInitialized) {
            if (!it.videoAllowed) { rememberPlayback(); video.pause() }
            render(model.state.value)
        }
    } }
    private var movie: File?=null
    private var loadedStamp=-1L
    private var exporting=false
    private val saveDocument=registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file=movie
        if(uri!=null && file!=null)lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } } }
                .onSuccess { toast("영상을 저장했어요.") }.onFailure { toast("영상을 저장하지 못했어요.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected=RecordingFiles.list(this).firstOrNull { it.file.name==intent.getStringExtra("sourceId") }
        if(selected==null) { finish();return }
        source=selected
        setContentView(R.layout.activity_animation)
        val root=findViewById<View>(R.id.animation_root)
        root.applyPleosInsets()
        playbackPosition = savedInstanceState?.getInt("video_position") ?: 0
        findViewById<TextView>(R.id.animation_source).text="${source.title}  ·  ${formatDuration(source.durationMs)}"
        video=findViewById(R.id.animation_video)
        video.canStart = { screenStarted && restrictions.state.videoAllowed }
        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        video.setOnPreparedListener {
            video.seekTo(playbackPosition.coerceAtLeast(1))
            if (!video.canStart()) video.pause()
        }
        video.setOnErrorListener { _,_,_ -> toast("영상을 재생하지 못했어요.");true }
        val frame=findViewById<View>(R.id.animation_preview_frame)
        root.addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_ ->
            val height=minOf(((view.height-view.paddingTop-view.paddingBottom)*.53f).toInt(),(560*resources.displayMetrics.density).toInt()).coerceAtLeast(1)
            val availableWidth=(view.width-view.paddingLeft-view.paddingRight-2*resources.getDimensionPixelSize(R.dimen.page_padding)).coerceAtLeast(1)
            val width=minOf(height*9/16, availableWidth).coerceAtLeast(1)
            val fittedHeight=width*16/9
            if(frame.layoutParams.height!=fittedHeight || frame.layoutParams.width!=width)
                frame.layoutParams=frame.layoutParams.apply { this.width=width;this.height=fittedHeight }
        }
        findViewById<View>(R.id.animation_back).setOnClickListener { finish() }
        findViewById<View>(R.id.animation_generate).setOnClickListener {
            val consent=getSharedPreferences("momo-consent",0)
            if(consent.getBoolean("server-voice-v1",false))model.generate()
            else MaterialAlertDialogBuilder(this).setTitle("이 녹음으로 영상 만들기")
                .setMessage("목소리·자막 분석과 등록 인물 매칭을 위해 녹음을 서버로 전송합니다. 목소리 특징도 분석 결과에 포함되며, 원본은 분석 종료 후 24시간, 결과는 30일 보관됩니다. 인물 이름·캐릭터·등록 목소리는 이 기기에서 관리합니다. 탑승자에게 이용 사실을 안내하고 동의를 받아 주세요.")
                .setPositiveButton("동의하고 만들기") { _,_ -> consent.edit().putBoolean("server-voice-v1",true).apply();model.generate() }
                .setNegativeButton("취소",null).show()
        }
        findViewById<View>(R.id.animation_cancel).setOnClickListener { model.cancel() }
        findViewById<View>(R.id.animation_play).setOnClickListener { if(video.isPlaying)video.pause() else video.start() }
        findViewById<View>(R.id.animation_export).setOnClickListener { export() }
        model.bind(source)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }
    private fun render(state: MomoVideoState) {
        movie=state.movie
        findViewById<TextView>(R.id.animation_status).text=state.message
        findViewById<ProgressBar>(R.id.animation_progress).apply { visibility=if(state.busy)View.VISIBLE else View.GONE;progress=state.progress;isIndeterminate=state.progress==0 }
        findViewById<View>(R.id.animation_generate).isEnabled=state.ready && !state.busy && !RecordingStore.state.busy
        findViewById<View>(R.id.animation_cancel).visibility=if(state.busy)View.VISIBLE else View.GONE
        val videoAllowed = screenStarted && restrictions.state.videoAllowed
        video.playbackAllowed = videoAllowed
        findViewById<View>(R.id.animation_play).isEnabled=state.movie!=null && !state.busy && videoAllowed
        findViewById<View>(R.id.animation_export).isEnabled=state.movie!=null && !state.busy && !exporting
        video.visibility=if(state.movie!=null && !state.busy && videoAllowed)View.VISIBLE else View.GONE
        findViewById<View>(R.id.animation_empty).visibility=if(video.visibility==View.VISIBLE)View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.animation_empty).setText(when {
            !restrictions.state.available -> R.string.pleos_video_checking
            !videoAllowed -> R.string.pleos_video_restricted
            else -> R.string.animation_empty
        })
        state.movie?.takeIf { videoAllowed && !state.busy && it.lastModified()!=loadedStamp }?.let { loadedStamp=it.lastModified();video.setVideoPath(it.path) }
    }
    private fun export() {
        val sourceFile=movie ?: return
        if(exporting)return
        if(Build.VERSION.SDK_INT<29) { saveDocument.launch("MOMO_${System.currentTimeMillis()}.mp4");return }
        exporting=true;render(model.state.value)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val values=ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME,"MOMO_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/이동기록")
                        put(MediaStore.Video.Media.IS_PENDING,1)
                    }
                    val uri=checkNotNull(contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values))
                    try {
                        contentResolver.openOutputStream(uri)!!.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
                        contentResolver.update(uri,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)
                    } catch(e: Exception) { contentResolver.delete(uri,null,null);throw e }
                }
                toast("Movies/이동기록에 영상을 저장했어요.")
            } catch(e: Exception) { toast("영상을 저장하지 못했어요.") }
            finally { exporting=false;render(model.state.value) }
        }
    }
    private fun rememberPlayback() {
        if (::video.isInitialized && loadedStamp != -1L) runCatching { video.currentPosition }.getOrNull()
            ?.takeIf { it > 0 }?.let { playbackPosition = it }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberPlayback();outState.putInt("video_position", playbackPosition);super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart();screenStarted=true;restrictions.start();model.foreground(true) }
    override fun onStop() {
        screenStarted=false;rememberPlayback();model.foreground(false)
        if(::video.isInitialized)video.stopPlayback()
        loadedStamp=-1;restrictions.stop();super.onStop()
    }
    private fun toast(message: String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
}
