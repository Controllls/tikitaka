package com.example.hd_project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.hd_project.pleos.applyPleosInsets
import com.example.hd_project.pleos.MediaPlaybackFocus
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hd_project.momo.people.*
import com.example.hd_project.momo.model.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject

class PeopleActivity: AppCompatActivity() {
    private val model: PeopleViewModel by viewModels()
    private lateinit var list: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var speakerList: LinearLayout
    private lateinit var status: TextView
    private lateinit var name: EditText
    private lateinit var voice: TextView
    private lateinit var save: MaterialButton
    private lateinit var choose: MaterialButton
    private lateinit var clear: MaterialButton
    private lateinit var close: MaterialButton
    private lateinit var add: MaterialButton
    private lateinit var cancel: MaterialButton
    private val choices=mutableListOf<MaterialButton>()
    private var lastProfiles: List<PersonProfile>?=null
    private var lastAnalysis: AnalysisBundle?=null
    private var lastDraft: String?=null
    private var player: MediaPlayer?=null
    private var previewJob: Job?=null
    private val audioFocus by lazy { MediaPlaybackFocus(this) { stopPreview() } }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value: String,size: Float=16f)=TextView(this).apply { text=value;textSize=size;setPadding(0,dp(8),0,dp(8)) }
    private fun button(value: String,action: ()->Unit)=MaterialButton(this).apply {
        text=value;minHeight=dp(48);setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=column()
        setContentView(root)
        root.applyPleosInsets(dp(24))
        val header=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        header.addView(button("돌아가기") { finish() })
        header.addView(text("사용자 관리",24f),LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(20) })
        add=button("사용자 추가") { stopPreview();model.edit() }.apply { id=R.id.people_add }
        header.addView(add);root.addView(header)
        val scroll=ScrollView(this)
        val body=column();scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        status=text("").apply { id=R.id.people_status;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE };body.addView(status)
        list=column().apply { id=R.id.people_list };body.addView(list)
        editor=column().apply { setPadding(dp(12),dp(8),dp(12),dp(16)) };body.addView(editor)
        editor.addView(text("인물 정보",21f))
        name=EditText(this).apply { id=R.id.people_name;hint="이름 (최대 30자)";isSingleLine=true;filters=arrayOf(InputFilter.LengthFilter(30)) }
        name.doAfterTextChanged { if(it.toString()!=model.state.value.draft?.name)model.name(it.toString()) };editor.addView(name)
        editor.addView(text("영상에 등장할 캐릭터"))
        val characters=LinearLayout(this)
        CharacterCatalog.ids.forEachIndexed { index,id ->
            val item=column()
            val preview=ImageView(this).apply { contentDescription=CharacterCatalog.labels[index];scaleType=ImageView.ScaleType.FIT_CENTER }
            item.addView(preview,LinearLayout.LayoutParams(-1,dp(120)))
            val choice=button(CharacterCatalog.labels[index]) { model.character(id) }.apply { isCheckable=true }
            choices.add(choice);item.addView(choice);characters.addView(item,LinearLayout.LayoutParams(0,-2,1f))
            lifecycleScope.launch { runCatching { withContext(Dispatchers.IO) { characterPreview(id) } }.onSuccess { preview.setImageBitmap(it) } }
        }
        editor.addView(characters)
        voice=text("");editor.addView(voice)
        editor.addView(text("조용한 곳에서 본인이 혼자 10~20초 말한 녹음을 권장해요. 등록할 때 목소리 주인에게 동의를 받아 주세요.",14f))
        choose=button("저장된 녹음에서 목소리 등록") { chooseRecording() }.apply { id=R.id.people_choose_voice };editor.addView(choose)
        clear=button("등록 목소리 해제") { model.clearVoice() };editor.addView(clear)
        cancel=button("분석 취소") { stopPreview();model.cancel() };editor.addView(cancel)
        speakerList=column();editor.addView(speakerList)
        val actions=LinearLayout(this)
        close=button("편집 닫기") { stopPreview();model.closeEditor() };actions.addView(close,LinearLayout.LayoutParams(0,-2,1f))
        save=button("저장") { stopPreview();model.save() }.apply { id=R.id.people_save };actions.addView(save,LinearLayout.LayoutParams(0,-2,1f))
        editor.addView(actions)
        body.addView(text("등록 정보는 이 기기에 암호화해 저장해요. 음성 매칭은 녹음을 마친 뒤 영상을 만들 때 적용돼요. 잡음·겹친 말·짧은 발화 때문에 확실하지 않으면 기본 캐릭터로 표시해요. 좌석 위치는 발화 순서에 따른 영상 배치이며 실제 좌석을 추정하지 않아요.",14f))
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }
    private fun render(state: PeopleState) {
        status.text=state.message
        add.isEnabled=!state.busy && state.draft==null
        editor.visibility=if(state.draft==null)View.GONE else View.VISIBLE
        list.visibility=if(state.draft==null)View.VISIBLE else View.GONE
        if(lastProfiles!=state.profiles) {
            lastProfiles=state.profiles;list.removeAllViews()
            if(state.profiles.isEmpty())list.addView(text("아직 등록한 인물이 없어요. 사용자 추가로 시작해 보세요."))
            state.profiles.forEach { profile ->
                val item=column().apply { setPadding(dp(12),dp(12),dp(12),dp(20)) }
                item.addView(text(profile.name,21f))
                item.addView(text("${CharacterCatalog.labels[CharacterCatalog.ids.indexOf(profile.characterId)]} · "+
                    (profile.voice?.let { "목소리 등록됨 · ${it.speechUs/1_000_000}초" } ?: "목소리 미등록")))
                val actions=LinearLayout(this)
                actions.addView(button("수정 / 목소리 다시 등록") { model.edit(profile) },LinearLayout.LayoutParams(0,-2,1f))
                actions.addView(button("삭제") {
                    MaterialAlertDialogBuilder(this).setTitle("${profile.name} 삭제")
                        .setMessage("이 기기의 이름·캐릭터 연결·등록 목소리를 삭제합니다. 기존 녹음·분석 결과·완성 영상은 남습니다.")
                        .setPositiveButton("삭제") { _,_ -> model.delete(profile) }.setNegativeButton("취소",null).show()
                });item.addView(actions);list.addView(item)
            }
        }
        state.draft?.let { draft ->
            if(lastDraft!=draft.id) { lastDraft=draft.id;name.setText(draft.name) }
            choices.forEachIndexed { index,b -> b.isChecked=CharacterCatalog.ids[index]==draft.characterId;b.isEnabled=!state.busy }
            voice.text=draft.voice?.let { "등록 목소리: ${it.sourceTitle} · 혼자 말한 구간 ${it.speechUs/1_000_000}초" } ?: "등록 목소리 없음 · 저장 후에도 나중에 등록할 수 있어요."
            clear.visibility=if(draft.voice==null)View.GONE else View.VISIBLE
        } ?: run { lastDraft=null }
        listOf<View>(name,save,choose,clear,close).forEach { it.isEnabled=!state.busy }
        cancel.visibility=if(state.busy)View.VISIBLE else View.GONE
        if(lastAnalysis!==state.analysis) {
            lastAnalysis=state.analysis;speakerList.removeAllViews()
            val result=state.analysis?.result
            result?.speakers?.forEach { speaker ->
                val signature=result.speakerSignatures.find { it.speakerId==speaker.speakerId }
                val caption=result.captions.firstOrNull { speaker.speakerId in it.speakerIds }?.text.orEmpty().take(100)
                speakerList.addView(text("${speaker.speakerId} · ${formatDuration(speaker.firstSeenUs/1000)}부터 · ${signature?.speechUs?.div(1_000_000) ?: 0}초의 깨끗한 목소리\n$caption"))
                if(signature!=null) {
                    speakerList.addView(button("${speaker.speakerId} 미리 듣기") { preview(signature) })
                    speakerList.addView(button("${speaker.speakerId} 목소리 선택") { stopPreview();model.select(signature) }.apply { isEnabled=signature.speechUs>=6_000_000 })
                }
            }
        }
    }
    private fun chooseRecording()=lifecycleScope.launch {
        stopPreview()
        val recordings=withContext(Dispatchers.IO) { RecordingFiles.list(this@PeopleActivity).filter { it.durationMs in 6000..600000 } }
        if(recordings.isEmpty()) {
            MaterialAlertDialogBuilder(this@PeopleActivity).setTitle("목소리 등록용 녹음이 필요해요")
                .setMessage("홈에서 본인이 혼자 10~20초 말한 녹음을 저장한 뒤 다시 선택해 주세요. 녹음은 최대 10분까지 선택할 수 있어요.")
                .setPositiveButton("확인",null).show();return@launch
        }
        MaterialAlertDialogBuilder(this@PeopleActivity).setTitle("등록할 인물이 말한 녹음 선택")
            .setItems(recordings.map { "${it.title} · ${formatDuration(it.durationMs)}" }.toTypedArray()) { _,index ->
                val recording=recordings[index]
                MaterialAlertDialogBuilder(this@PeopleActivity).setTitle("이 녹음에서 목소리 등록")
                    .setMessage("녹음을 서버에서 분석해 목소리 특징을 추출합니다. 서버 원본은 분석 종료 후 24시간, 특징을 포함한 분석 결과는 30일 보관됩니다. 선택한 인물의 등록 목소리는 이 기기에 암호화해 보관하고 다음 영상에서 캐릭터 매칭에 사용합니다. 목소리 주인의 동의를 받은 녹음만 등록해 주세요.")
                    .setPositiveButton("동의하고 분석") { _,_ -> model.analyze(recording) }.setNegativeButton("취소",null).show()
            }.setNegativeButton("취소",null).show()
    }
    private fun preview(signature: SpeakerSignature) {
        stopPreview()
        val recording=model.state.value.recording ?: return
        val range=signature.segments.maxByOrNull { it.endUs-it.startUs } ?: return
        try {
            val current=MediaPlayer();player=current
            current.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            current.setDataSource(recording.file.path)
            current.setOnErrorListener { _,_,_ -> stopPreview();Toast.makeText(this,"목소리를 재생하지 못했어요.",Toast.LENGTH_SHORT).show();true }
            current.setOnPreparedListener { it.seekTo(range.startUs/1000,MediaPlayer.SEEK_CLOSEST) }
            current.setOnSeekCompleteListener {
                if(player===it) {
                    if(!audioFocus.acquire(it)) { stopPreview();Toast.makeText(this,R.string.pleos_audio_unavailable,Toast.LENGTH_SHORT).show();return@setOnSeekCompleteListener }
                    it.start();previewJob=lifecycleScope.launch { delay((range.endUs-range.startUs)/1000);stopPreview() }
                }
            }
            current.setOnCompletionListener { stopPreview() };current.prepareAsync()
        } catch(e: Exception) { stopPreview();Toast.makeText(this,"녹음 파일을 확인해 주세요.",Toast.LENGTH_SHORT).show() }
    }
    private fun stopPreview() { audioFocus.release();previewJob?.cancel();previewJob=null;player?.release();player=null }
    private fun characterPreview(id: String): Bitmap {
        val directory="momo/resources/characters/$id/"
        val manifest=JSONObject(assets.open(directory+"manifest.json").bufferedReader().use { it.readText() })
        val parts=manifest.getJSONObject("poses").getJSONObject("front").getJSONObject("partsPng")
        val body=manifest.getJSONArray("bodyPartsPng")
        val paths=(0 until body.length()).map { body.getString(it) } + listOf("hair_back","face","hair_front","eyes_open","mouth_closed").map { parts.getString(it) }+
            listOfNotNull(parts.optString("nose").takeIf { it.isNotBlank() })
        val bitmap=Bitmap.createBitmap(128,160,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paths.forEach { path -> assets.open(directory+path).use { input ->
            val part=BitmapFactory.decodeStream(input,null,BitmapFactory.Options().apply { inSampleSize=4 })!!
            canvas.drawBitmap(part,null,RectF(0f,0f,128f,160f),paint);part.recycle()
        } }
        return bitmap
    }
    override fun onStop() { stopPreview();super.onStop() }
}
