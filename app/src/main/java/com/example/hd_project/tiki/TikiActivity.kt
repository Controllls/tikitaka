package com.example.hd_project.tiki

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hd_project.*
import com.example.hd_project.momo.people.PersonProfile
import com.example.hd_project.pleos.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlin.math.min

/** Native Figma screens, laid out against the actual inset-adjusted app window in dp. */
open class TikiActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var art: TikiArt
    private lateinit var profile: PersonProfile
    private var route="home"
    private var tab="episodes"
    private var character=3
    private var draft=""
    private var recordOnly=false
    private var pendingStart=false
    private var pendingVoice=false
    private var voiceOrigin="settings"
    private var characterOrigin="character"
    private var carouselBusy=false
    private var productionStarting=false
    private var renderedVoiceStage=""
    private var recoveryId: String?=null
    private var recoveryMissing=false
    private var videoPoster: ImageView?=null
    private var videoBlocker: TikiDrivingPauseScreen?=null
    private var videoContent: View?=null
    private var videoResumeRequired=false
    private var drivingNoticeLaunched=false
    private var pendingVideoResume=false
    private var videoGateAllowed=false
    private var playbackKey=""
    private var pendingGleo=false
    private var w=0f
    private var h=0f
    private var lastSize=""
    private var renderedPhase: RecordingPhase?=null
    private var timer: TextView?=null
    private var wave: TikiWave?=null
    private var scrollView: ScrollView?=null
    private val scrolls=mutableMapOf<String,Int>()
    private var source=""
    private var movie=""
    private var player: MediaPlayer?=null
    private var video: RestrictedVideoView?=null
    private var position=0
    private var play: TextView?=null
    private var seek: SeekBar?=null
    private var mediaStatus: TextView?=null
    private var captionView: TextView?=null
    private var captions=JSONArray()
    private var diagnosticView: TextView?=null
    private var diagnosticDetails: TextView?=null
    private var quickPlay: TextView?=null
    private var diagnosticTimeline: PlaybackDiagnostics.Timeline?=null
    private var diagnosticAudioTracks=-1
    private var playbackGeneration=0
    private var playbackPrepared=false
    private var playbackSeekPending=false
    private var seenFinish=0L
    private var visible=false
    private var episodeSignature=""
    private val handler=Handler(Looper.getMainLooper())
    private val voiceModel: TikiVoiceModel by viewModels()
    private val prefs by lazy { getSharedPreferences("tikitaka-ui",MODE_PRIVATE) }
    private val focus by lazy { MediaPlaybackFocus(this) { play?.text="재생" } }
    private val restrictions by lazy { DrivingRestrictions(this) { state ->
        voiceModel.drivingState(state.animationAllowed)
        applyVideoRestriction(state)
    } }
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) { if(pendingVoice) { pendingVoice=false;voiceModel.start() } else startRecording() }
        else {
            pendingStart=false;pendingVoice=false;if(route=="consent")render()
            go("microphone")
        }
    }
    private val observer: (RecordingState)->Unit = { state ->
        if(state.busy)runCatching {
            if(video?.isPlaying==true || player?.isPlaying==true) { video?.pause();player?.pause();focus.release();play?.text="재생" }
        }
        if(route=="video")applyVideoRestriction(restrictions.state)
        timer?.text=formatDuration(state.durationMs());wave?.level=state.level
        if(pendingGleo && state.phase==RecordingPhase.PAUSED) {
            pendingGleo=false;handler.postDelayed({ if(visible && !GleoLauncher.requestListening(this))toast("Gleo를 실행하지 못했어요.") },300)
        }
        if(!state.busy && route=="record") {
            val journal=RecordingJournal.read(this);val time=journal?.optLong("finishedAt") ?: 0
            if(time>seenFinish) {
                seenFinish=time;prefs.edit().putLong("seenFinish",time).apply();source=journal?.optString("source").orEmpty();go("saved")
                if(journal?.optString("state")=="saved")handler.postDelayed({
                    if(visible && route=="saved") { tab=if(journal.optBoolean("recordOnly"))"recordings" else "episodes";go("library") }
                },1500)
            }
        }
        if(route=="record" && renderedPhase!=state.phase)render()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);art=TikiArt(this)
        try { profile=TikiProfile.get(this) } catch(error: Exception) { toast("내 정보를 불러오지 못했어요. 저장 자료를 확인해 주세요.");finish();return }
        route=savedInstanceState?.getString("route") ?: if(prefs.getBoolean("setup",false))"home" else "welcome"
        if(route=="phone")route="settings" // Restore removed legacy destination safely.
        tab=savedInstanceState?.getString("tab") ?: "episodes"
        character=savedInstanceState?.getInt("character") ?: TikiProfile.characters.indexOf(profile.characterId).coerceAtLeast(0)
        draft=savedInstanceState?.getString("draft") ?: if(prefs.getBoolean("setup",false))profile.name else ""
        recordOnly=savedInstanceState?.getBoolean("only") ?: false
        pendingStart=savedInstanceState?.getBoolean("pendingStart") ?: false;pendingVoice=savedInstanceState?.getBoolean("pendingVoice") ?: false
        voiceOrigin=savedInstanceState?.getString("voiceOrigin") ?: "settings"
        characterOrigin=savedInstanceState?.getString("characterOrigin") ?: "character"
        source=savedInstanceState?.getString("source").orEmpty();movie=savedInstanceState?.getString("movie").orEmpty()
        if(savedInstanceState==null)intent.getStringExtra("episodeMovie")?.let { name ->
            val file=File(AnimationFiles.directory(this),name).canonicalFile
            if(file.parentFile==AnimationFiles.directory(this).canonicalFile && file.isFile) { movie=file.name;route="video" }
        }
        if(savedInstanceState==null) {
            intent.getStringExtra("tikiDestination")?.takeIf { it in listOf("home","library","character","settings") }?.let { route=it }
            pendingVideoResume=route=="video" && intent.getBooleanExtra("resumeSelectedVideo",false)
        }
        recoveryId=savedInstanceState?.getString("recoveryId");recoveryMissing=savedInstanceState?.getBoolean("recoveryMissing") ?: false
        position=savedInstanceState?.getInt("position") ?: 0;seenFinish=savedInstanceState?.getLong("seenFinish") ?: prefs.getLong("seenFinish",0)
        videoResumeRequired=savedInstanceState?.getBoolean("videoResumeRequired") ?: false
        playbackKey=if(route=="audio")"audio:$source" else if(route=="video")"video:$movie" else ""
        if(savedInstanceState==null && playbackKey.isNotEmpty())position=prefs.getInt("position:$playbackKey",0).coerceAtLeast(0)
        savedInstanceState?.getBundle("scrolls")?.let { b->b.keySet().forEach { scrolls[it]=b.getInt(it) } }
        window.decorView.setBackgroundColor(TikiDesign.background)
        root=FrameLayout(this).apply { tag="tiki_root";background=TikiDesign.shape(TikiDesign.background,40f,0xffc9cacd.toInt(),this@TikiActivity);clipToOutline=true }
        setContentView(root);root.applyPleosInsets()
        root.addOnLayoutChangeListener { _,l,t,r,b,_,_,_,_ ->
            val key="${r-l}:${b-t}:${root.paddingLeft}:${root.paddingTop}:${root.paddingBottom}:${root.paddingRight}"
            if(lastSize!=key) { lastSize=key;root.post { if(!isFinishing)render() } }
        }
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { when(route) {
                "home"->moveTaskToBack(true)
                "welcome","profile"->go("welcome")
                "consent"->{ pendingStart=false;go("record") }
                "voice"->go(voiceOrigin)
                "character"->go(if(characterOrigin=="profile")"profile" else "home")
                "name","privacy"->go("settings")
                "audio","video","progress"->go("library")
                else->go("home")
            } }
        })
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { AutomaticHighlights.resume(applicationContext);EpisodeWork.resume(applicationContext);RecordingJournal.restoreInterrupted(applicationContext) } }
            if(savedInstanceState==null && route=="home" && !RecordingStore.state.busy) {
                val journal=RecordingJournal.read(this@TikiActivity)
                if(journal?.optString("state") in listOf("recording","paused","saving","failed")) {
                    recoveryId=journal?.optString("sessionId");go("saved")
                } else if(journal!=null && journal.optLong("finishedAt")>seenFinish && journal.optString("state") in listOf("recovered","silent","unrecoverable")) {
                    source=journal.optString("source");recoveryMissing=journal.optString("state")=="unrecoverable"
                    seenFinish=journal.optLong("finishedAt");prefs.edit().putLong("seenFinish",seenFinish).apply();go("saved")
                }
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { voiceModel.state.collect { state ->
                if(state.stage=="success")profile=withContext(Dispatchers.IO) { TikiProfile.get(this@TikiActivity) }
                if(route=="voice") { if(renderedVoiceStage!=state.stage)render();wave?.level=state.level;timer?.text="${state.seconds}초" }
            } }
            launch { while(isActive) {
                if(route in listOf("home","library","progress")) {
                    val signature=EpisodeWork.all(this@TikiActivity).joinToString { it.toString() }
                    if(signature!=episodeSignature) { episodeSignature=signature;render() }
                }
                if(route in listOf("audio","video")) {
                    if(route=="video" && !restrictions.state.videoAllowed) { delay(250);continue }
                    val current=if(playbackPrepared && !playbackSeekPending)runCatching { video?.currentPosition ?: player?.currentPosition ?: position }.getOrDefault(position) else position
                    seek?.progress=current;timer?.text=formatDuration(current.toLong())
                    quickPlay?.text=if(runCatching { video?.isPlaying==true }.getOrDefault(false))"멈춤" else "재생"
                    diagnosticTimeline?.let { timeline -> diagnosticView?.let { view ->
                        val value=timeline.summary(current.toLong(),diagnosticAudioTracks)
                        if(view.text.toString()!=value)view.text=PlaybackDiagnostics.emphasizeSpeakers(value)
                        diagnosticDetails?.let { details ->
                            val description=timeline.text(current.toLong(),diagnosticAudioTracks)
                            if(details.text.toString()!=description)details.text=description
                        }
                    } }
                    captionView?.let { view ->
                        val lines=if(prefs.getBoolean("captions",true)) (0 until captions.length()).map { captions.getJSONObject(it) }
                            .filter { current>=it.getLong("startMs") && current<it.getLong("endMs") }.map { it.getString("text") }.distinct() else emptyList()
                        val value=lines.joinToString(" ")
                        if(view.text.toString()!=value)view.text=value
                        view.visibility=if(value.isBlank())View.INVISIBLE else View.VISIBLE
                    }
                };delay(if(route in listOf("audio","video"))100 else 1000)
            } }
        } }
        BoardingCoordinator.initialize(this)
    }
    override fun onStart() {
        super.onStart();visible=true;drivingNoticeLaunched=false;restrictions.start();RecordingStore.observe(observer);BoardingCoordinator.setScreenVisible(true)
        if(route=="video" && video==null)render()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent);setIntent(intent)
        intent.getStringExtra("episodeMovie")?.let { name ->
            val file=File(AnimationFiles.directory(this),name).canonicalFile
            if(file.parentFile==AnimationFiles.directory(this).canonicalFile && file.isFile) {
                movie=file.name;go("video");pendingVideoResume=intent.getBooleanExtra("resumeSelectedVideo",false)
            }
        }
        intent.getStringExtra("tikiDestination")?.takeIf { it in listOf("home","library","character","settings") }?.let {
            if(it=="library" && intent.getBooleanExtra("resetLibrary",false))openLibrary() else go(it)
        }
    }
    override fun onResume() { super.onResume();if(pendingStart && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording() }
    override fun onStop() {
        visible=false;pendingGleo=false;pendingVideoResume=false;RecordingStore.remove(observer);restrictions.stop();BoardingCoordinator.setScreenVisible(false)
        capturePosition()
        if(video!=null)releasePlayback() else runCatching { player?.pause() }
        focus.release();play?.text="재생"
        if(!isChangingConfigurations)voiceModel.stopCapture();super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null);releasePlayback();if(::art.isInitialized)art.clear();super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        rememberScroll();capturePosition()
        out.putString("route",route);out.putString("tab",tab);out.putInt("character",character);out.putString("draft",draft)
        out.putBoolean("only",recordOnly);out.putBoolean("pendingStart",pendingStart);out.putBoolean("pendingVoice",pendingVoice)
        out.putString("voiceOrigin",voiceOrigin);out.putString("characterOrigin",characterOrigin)
        out.putString("recoveryId",recoveryId);out.putBoolean("recoveryMissing",recoveryMissing)
        out.putString("source",source);out.putString("movie",movie);out.putInt("position",position);out.putLong("seenFinish",seenFinish)
        out.putBoolean("videoResumeRequired",videoResumeRequired)
        out.putBundle("scrolls",Bundle().apply { scrolls.forEach { (k,v)->putInt(k,v) } });super.onSaveInstanceState(out)
    }
    private fun dp(value: Float)=TikiDesign.dp(this,value)
    private fun toast(value: String) { val toast=Toast.makeText(this,value,Toast.LENGTH_LONG);toast.show();handler.postDelayed({ toast.cancel() },2400) }
    private fun put(parent: FrameLayout,view: View,x: Float,y: Float,width: Float,height: Float): View {
        parent.addView(view,FrameLayout.LayoutParams(dp(width.coerceAtLeast(1f)),dp(height.coerceAtLeast(1f))).apply { leftMargin=dp(x);topMargin=dp(y) });return view
    }
    private fun text(p: FrameLayout,value: String,x: Float,y: Float,width: Float,height: Float,size: Float=24f,weight: Int=400,
                     center: Boolean=false,color: Int=TikiDesign.ink)=art.text(value,size,weight,color).also {
        if(center)it.gravity=Gravity.CENTER;put(p,it,x,y,width,height)
    }
    private fun button(p: FrameLayout,value: String,x: Float,y: Float,width: Float,primary: Boolean=true,action: ()->Unit)=
        art.button(value,primary,action).also { put(p,it,x,y,width,88f) }
    private fun divider(p: FrameLayout,y: Float,x: Float=0f,width: Float=w) { put(p,View(this).apply { setBackgroundColor(TikiDesign.line) },x,y,width,1f) }
    private fun rememberScroll() { scrollView?.let { scrolls["$route:$tab"]=it.scrollY } }
    private fun openLibrary() {
        rememberScroll()
        // Detach the old scroll state before go/render can save it under the default tab.
        scrollView=null
        tab="episodes";scrolls.remove("library:episodes");scrolls.remove("library:recordings")
        go("library")
    }
    private fun go(next: String) { rememberScroll();releasePlayback();position=0;videoResumeRequired=false;pendingVideoResume=false
        playbackKey=if(next=="audio")"audio:$source" else if(next=="video")"video:$movie" else ""
        if(playbackKey.isNotEmpty())position=prefs.getInt("position:$playbackKey",0)
        if(route=="voice" && next!="voice") { pendingVoice=false;voiceModel.stopCapture() }
        if(route=="consent" && next!="consent")pendingStart=false
        route=next;render() }
    private fun render() {
        if(!::root.isInitialized || root.width==0)return
        val editing = root.findFocus()?.tag == "profile_name"
        rememberScroll();capturePosition();releasePlayback();timer=null;wave=null;seek=null;play=null;mediaStatus=null;captionView=null;captions=JSONArray();scrollView=null
        diagnosticView=null;diagnosticDetails=null;diagnosticTimeline=null;diagnosticAudioTracks=-1;quickPlay=null;videoBlocker=null;videoContent=null;videoGateAllowed=false
        root.removeAllViews()
        val width=(root.width-root.paddingLeft-root.paddingRight)/resources.displayMetrics.density
        h=(root.height-root.paddingTop-root.paddingBottom)/resources.displayMetrics.density
        val side=if(route in listOf("welcome","profile") || route=="character" && characterOrigin=="profile")0f else if(route in listOf("consent","video"))131f else 130f;w=width-side
        if(side>0)navigation()
        val body=FrameLayout(this).apply { clipChildren=true;tag="tiki_$route" };put(root,body,side,0f,w,h)
        when(route) {
            "home"->home(body);"welcome"->welcome(body);"profile"->name(body,true)
            "record"->record(body);"saved"->saved(body);"library"->library(body);"character"->characters(body)
            "consent"->consent(body)
            "microphone"->microphone(body)
            "settings"->settings(body);"name"->name(body,false);"voice"->voice(body);"privacy"->privacy(body)
            "progress"->progress(body);"audio"->audio(body);"video"->video(body)
        }
        if(editing)root.findViewWithTag<EditText>("profile_name")?.let { input ->
            input.requestFocus();input.setSelection(input.text.length)
        }
    }
    private fun navigation() {
        val navHeight=h.coerceAtLeast(650f)
        val nav=FrameLayout(this)
        val navScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }
        navScroll.addView(nav,FrameLayout.LayoutParams(dp(130f),dp(navHeight)))
        put(root,navScroll,0f,0f,130f,h)
        put(nav,art.image("home-wide","imgTikiTakaHomeLatestVerticalLogo","티키타카"),if(route=="consent")37.3f else 37f,51f,58.8f,60f)
        val y0=if(navHeight>=1100)356f else (navHeight*.30f).coerceAtLeast(170f)
        val gap=min(182f,((navHeight-230-y0)/2).coerceAtLeast(100f))
        listOf(Triple("home","홈","imgTikiTakaCurrentLineIconHome"),Triple("library","보관함","imgTikiTakaCurrentLineIconLibrary"),Triple("character","캐릭터","imgFrame"))
            .forEachIndexed { i,(destination,label,key)->
                val item=FrameLayout(this).apply { contentDescription=label;isFocusable=true;tag="nav_$destination"
                    background=TikiDesign.press(this@TikiActivity,TikiDesign.background,16f)
                    setOnClickListener {
                        if(destination=="character") { characterOrigin="character";character=TikiProfile.characters.indexOf(profile.characterId).coerceAtLeast(0) }
                        if(destination=="library")openLibrary() else go(destination)
                    } }
                put(item,art.image("home-wide",key),24f,6f,32f,32f)
                text(item,label,0f,42f,80f,24f,16f,if(route==destination || (route=="consent" && destination=="home"))600 else if(route=="consent")500 else 400,true,
                    if(route=="consent")if(destination=="home")0xff333333.toInt() else 0xff595959.toInt() else TikiDesign.ink)
                    .apply { if(route=="consent")paint.isFakeBoldText=false }
                put(nav,item,if(route=="consent")26.7f else 25f,y0+i*gap,80f,72f)
            }
        val settings=FrameLayout(this).apply { contentDescription="설정";tag="nav_settings";isFocusable=true
            background=TikiDesign.press(this@TikiActivity,TikiDesign.background,20f);setOnClickListener { go("settings") } }
        if(route=="consent")put(settings,art.image("consent-wide","imgFrame"),25.28f,9.28f,29.44f,29.44f)
        else put(settings,art.image("home-wide","imgFrame1"),24f,8f,32f,32f)
        put(nav,settings,if(route=="consent")26.7f else 25f,navHeight-98,80f,72f)
        if(route!="home" && route!="consent")put(nav,View(this).apply { setBackgroundColor(TikiDesign.line) },129f,0f,1f,navHeight)
    }
    private fun scroll(p: FrameLayout,minHeight: Float=h): FrameLayout {
        val view=ScrollView(this).apply { isFillViewport=true;isVerticalScrollBarEnabled=false }
        val content=FrameLayout(this);view.addView(content,FrameLayout.LayoutParams(-1,dp(minHeight)))
        put(p,view,0f,0f,w,h);scrollView=view;view.post { view.scrollTo(0,scrolls["$route:$tab"] ?: 0) };return content
    }
    private fun heading(p: FrameLayout,title: String,subtitle: String="",back: Boolean=false) {
        val pad=if(w>=900)48f else 32f;val x=pad+if(back)88f else if(route in listOf("character","library"))0f else 32f
        if(back)put(p,art.button("‹",false) { onBackPressedDispatcher.onBackPressed() }.apply { contentDescription="뒤로" },pad,62f,64f,64f)
        text(p,title,x,48f,w-x-pad,52f,36f,600).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        if(subtitle.isNotEmpty())text(p,subtitle,x,110f,w-x-pad,68f,24f,color=TikiDesign.secondary)
    }
    private fun welcome(p: FrameLayout) {
        // A-00: keep the supplied artwork proportions and allow shorter windows to scroll.
        val height=h.coerceAtLeast(1028f);val c=scroll(p,height)
        val pad=if(w>=900f)48f else 32f
        val muted=0xff5d5d5d.toInt()
        text(c,"오늘 나눈 대화를 한 편의 영상으로",pad,48f,w-pad*2,48f,36f,600)
        text(c,"차 안에서 나눈 이야기를 캐릭터 영상으로 남겨요.",pad,108f,w-pad*2,32f,24f,color=muted)
        val bodyTop=172f+(height-1028f)/2f
        put(c,art.image("welcome","imgTikiTakaLogoRoundExploration0110","티키타카"),(w-300)/2,bodyTop,300f,100f)
        put(c,art.character(3),(w-380)/2,bodyTop+132f,380f,380f)
        val copyWidth=min(1100f,w-pad*2)
        text(c,"가장 우리다운 순간을 한 편으로 남기다",(w-copyWidth)/2,bodyTop+544f,copyWidth,32f,24f,center=true,color=muted)
        val bw=min(340f,w-pad*2);button(c,"시작하기",(w-bw)/2,height-248f,bw) { go("profile") }
    }
    private fun home(p: FrameLayout) {
        val wide=w>=1100;val c=if(wide)p else scroll(p,1800f.coerceAtLeast(h));val hw=if(wide)w*.6246f else w
        val hh=if(wide)min(919.5f,h*.777f) else min(630f,hw*.94f)
        put(c,TikiHero(this,art,!wide),0f,0f,hw,hh)
        val recovery=false // Recovery is represented by persisted drafts in the recording library.
        val cy=if(wide)min(946f,h-if(recovery)280 else 200) else hh+5
        button(c,if(RecordingStore.state.busy)"진행 중인 녹음 보기" else "대화 녹음 시작",(hw-340)/2,cy,340f) {
            if(!RecordingStore.state.busy)recordOnly=false;go("record")
        }.apply { tag="record_start";setPadding(dp(36f),0,0,0) }
        put(c,art.image("home-wide","imgFrame2"),(hw-340)/2+64,cy+32,24f,24f)
        text(c,"녹음이 끝나면 영상으로 만들어드려요",24f,cy+106,hw-48,28f,16f,center=true,color=TikiDesign.secondary)
        val x=if(wide)hw+48 else 48f;val y=if(wide)54f else cy+170;val rw=if(wide)w-hw-112 else w-96
        val movies=movies();text(c,"최근 에피소드",x,y,rw-110,48f,if(wide)34f else 28f,500)
        text(c,"${movies.size}편",x+min(205f,rw-120),y+12,60f,28f,16f,color=TikiDesign.secondary)
        val more=art.button("전체 보기  ›",false) { tab="episodes";go("library") }.apply {
            background=TikiDesign.press(this@TikiActivity,TikiDesign.background,12f);setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,16f) }
        put(c,more,x+rw-112,y+if(wide)48 else 0,112f,64f)
        val top=y+if(wide)86 else 60
        if(movies.isEmpty()) {
            divider(c,top,x,rw);put(c,art.image("home-wide","imgTikiTakaCurrentLineIconLibrary"),x+(rw-64)/2,top+100,64f,64f)
            text(c,"아직 에피소드가 없어요.\n대화를 녹음하고 첫 이야기를 남겨보세요.",x,top+190,rw,110f,22f,center=true,color=TikiDesign.secondary)
        } else movies.take(4).forEachIndexed { i,file->
            val rh=if(wide)min(240f,(h-top-70)/4).coerceAtLeast(150f) else 160f
            episodeRow(c,file,i,x,top+i*rh,rw,rh,!wide)
        }
        if(wide)text(c,"영상은 주차 중에만 볼 수 있어요",x,h-72,rw,28f,14f,center=true,color=TikiDesign.secondary)
        if(recovery)
            button(c,"이전 녹음 복구",(hw-340)/2,cy+152,340f,false) { recover() }
    }
    private fun movies()=AnimationFiles.directory(this).listFiles().orEmpty().filter {
        it.extension=="mp4" && !it.name.endsWith(".pending.mp4") && (!it.name.startsWith("recording-") || File(it.path+".json").isFile)
    }.sortedByDescending { it.lastModified() }
    private fun movieTitle(file: File)=runCatching { JSONObject(File(file.path+".json").readText()).optString("title").takeIf { it.isNotBlank() } }.getOrNull()
        ?: RecordingFiles.list(this).firstOrNull { it.file.nameWithoutExtension==file.nameWithoutExtension }?.title ?: "함께 나눈 이야기"
    private fun episodeRow(p: FrameLayout,file: File,index: Int,x: Float,y: Float,width: Float,height: Float,alignRight: Boolean=false) {
        val row=FrameLayout(this).apply { background=TikiDesign.press(this@TikiActivity,TikiDesign.background,12f)
            contentDescription=movieTitle(file);isFocusable=true;setOnClickListener { movie=file.name;go("video") } }
        val iw=min(if(alignRight)176f else 220f,width*.48f);val key=listOf("imgThumbnail","imgThumbnail1","imgThumbnail2","imgThumbnail3")[index%4]
        put(row,art.poster(file,"home-wide",key),0f,(height-iw*.6f)/2,iw,iw*.6f)
        text(row,"EP. ${EpisodeWork.episodeNumber(this,file).toString().padStart(2,'0')}",iw+48,height/2-50,width-iw-48,30f,18f,color=TikiDesign.secondary)
            .apply { if(alignRight)gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        text(row,movieTitle(file),iw+48,height/2-16,width-iw-48,80f,22f)
            .apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;if(alignRight)gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        val playCircle=FrameLayout(this).apply { background=TikiDesign.shape(Color.WHITE,28f,context=this@TikiActivity);elevation=dp(3f).toFloat() }
        put(playCircle,art.image("home-wide","imgFrame4"),16f,16f,20f,20f)
        put(row,playCircle,iw-22,(height+iw*.6f)/2-30,52f,52f)
        divider(row,height-1,0f,width);put(p,row,x,y,width,height)
    }
    private fun record(p: FrameLayout) {
        val state=RecordingStore.state;renderedPhase=state.phase;val height=h.coerceAtLeast(1184f);val c=scroll(p,height)
        heading(c,if(state.busy)"대화를 기록하고 있어요" else "대화 녹음","오늘 나눈 대화를 한 편의 영상으로 남겨요.",true)
        val size=min(400f,w-80);put(c,art.character(TikiProfile.characters.indexOf(profile.characterId).coerceAtLeast(0)),(w-size)/2,220f,size,size)
        val y=650f
        if(state.busy) {
            timer=text(c,formatDuration(state.durationMs()),32f,y,w-64,70f,48f,500,true)
            wave=TikiWave(this).also { put(c,it,(w-300)/2,y+78,300f,48f) }
            text(c,if(state.phase==RecordingPhase.PAUSED)"잠시 쉬고 있어요. 준비되면 다시 시작하세요." else "지금 이 순간의 이야기를 담고 있어요.",32f,y+135,w-64,60f,20f,center=true)
            val bw=min(330f,(w-88)/2);val ready=state.phase in listOf(RecordingPhase.RECORDING,RecordingPhase.PAUSED)
            button(c,if(state.phase==RecordingPhase.PAUSED)"녹음 이어가기" else "잠시 멈추기",(w-bw*2-24)/2,height-248,bw) {
                command(if(state.phase==RecordingPhase.PAUSED)RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE)
            }.isEnabled=ready
            button(c,"녹음 마치기",(w+24)/2,height-248,bw,false) {
                if(restrictions.state.animationAllowed)command(RecordingService.ACTION_STOP) else toast("주차 후 녹음을 마칠 수 있어요.")
            }.isEnabled=ready
            put(c,art.button("Gleo 부르기",false) { requestGleo() }.apply { isEnabled=ready;tag="gleo" },(w-240)/2,height-100,240f,64f)
        } else {
            text(c,"함께 이야기할 준비가 됐나요?",32f,y,w-64,40f,24f,center=true)
            text(c,"녹음이 끝나면 영상으로 만들어드려요.",32f,y+48,w-64,40f,24f,center=true,color=TikiDesign.secondary)
            val check=CheckBox(this).apply { text="이번에는 녹음만 보관";typeface=art.sans;setTextColor(TikiDesign.ink)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,24f);isChecked=recordOnly;tag="record_only";setOnCheckedChangeListener { _,v->recordOnly=v } }
            put(c,check,(w-360)/2,y+100,360f,64f)
            val bw=min(340f,w-64);button(c,"대화 녹음 시작",(w-bw)/2,height-248,bw) { requestRecording() }.tag="record_confirm"
        }
    }
    private fun requestRecording() {
        if(RecordingStore.state.busy || pendingStart)return
        if(prefs.getString("consentVersion","")=="tikitaka-local-v3") {
            pendingStart=true
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        } else go("consent")
    }
    private fun consent(p: FrameLayout) {
        val view=TikiRecordingConsent(this,art,recordOnly,
            onBack={ pendingStart=false;go("record") },
            onConfirm={
                if(!RecordingStore.state.busy && !pendingStart && route=="consent") {
                    prefs.edit().putString("consentVersion","tikitaka-local-v3").apply()
                    pendingStart=true
                    if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            })
        put(p,view,0f,0f,w,h);scrollView=view
        view.post { view.scrollTo(0,scrolls["$route:$tab"] ?: 0) }
    }
    private fun startRecording() {
        if(!pendingStart || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || RecordingStore.state.busy)return
        pendingStart=false
        runCatching { ContextCompat.startForegroundService(this,Intent(this,RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_RECORD_ONLY,recordOnly)) }
            .onSuccess { go("record") }.onFailure { render();toast("녹음을 시작하지 못했어요. 마이크 권한을 확인해 주세요.") }
    }
    private fun command(action: String) { runCatching { startService(Intent(this,RecordingService::class.java).setAction(action)) }.onFailure { toast("녹음 상태를 변경하지 못했어요.") } }
    private fun requestGleo() {
        if(!GleoLauncher.isAvailable(this)) { toast("이 기기에서 Gleo를 찾을 수 없어요.");return }
        if(RecordingStore.state.phase==RecordingPhase.PAUSED)GleoLauncher.requestListening(this)
        else { pendingGleo=true;command(RecordingService.ACTION_PAUSE) }
    }
    private fun saved(p: FrameLayout) {
        val height=h.coerceAtLeast(1000f);val c=scroll(p,height);val data=recoveryId?.let { id->RecordingJournal.all(this).firstOrNull { it.optString("sessionId")==id } } ?: RecordingJournal.read(this)
        val silent=data?.optString("state")=="silent"
        val recovered=data?.optString("state")=="recovered"
        val success=data?.optString("state") in listOf("saved","recovered","silent") && !recoveryMissing;val only=data?.optBoolean("recordOnly")==true || silent || recovered
        heading(c,"대화 기록",back=true);put(c,art.image("saved","imgArtworkTransparentPng"),(w-360)/2,240f,360f,320f)
        text(c,if(recoveryMissing)"녹음을 복구하지 못했어요" else if(silent)"음성이 충분히 들리지 않았어요" else if(recovered)"이전 녹음을 복구했어요" else if(success)"대화를 안전하게 보관했어요" else "녹음을 저장하지 못했어요",32f,590f,w-64,80f,32f,500,true)
        text(c,if(recoveryMissing)"재생 가능한 음성을 찾지 못했어요. 새 녹음을 시작할 수 있어요." else if(!success)"남아 있는 녹음 구간을 다시 저장할 수 있어요." else if(recovered)"저장된 구간을 확인한 뒤 직접 영상으로 만들 수 있어요." else if(only)"이번 녹음은 보관함에서 다시 들을 수 있어요." else "이야기를 골라 영상으로 만들어드릴게요.",32f,690f,w-64,100f,24f,center=true,color=TikiDesign.secondary)
        val bw=min(480f,w-64);button(c,if(recoveryMissing)"새 녹음 시작" else if(success && only)"녹음 보관함으로" else if(success)"에피소드 보관함으로" else "다시 저장하기",(w-bw)/2,height-160,bw) {
            if(recoveryMissing) { recoveryMissing=false;recoveryId=null;recordOnly=false;go("record") }
            else if(success) { recoveryId=null;tab=if(only)"recordings" else "episodes";go("library") } else recover()
        }
    }
    private fun recover() { lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { RecordingJournal.recover(this@TikiActivity,recoveryId) } }
            .onSuccess { source=it.name;recoveryId=null;recoveryMissing=false;go("saved") }
            .onFailure { recoveryMissing=true;go("saved") }
    } }
    private fun library(p: FrameLayout) {
        val records=RecordingFiles.list(this);val movies=movies();val pad=if(w>900)48f else 32f
        val drafts=if(RecordingStore.state.busy)emptyList() else RecordingJournal.drafts(this)
        val jobs=EpisodeWork.all(this).filter { it.optString("state") !in listOf("completed","dismissed") }
        val count=if(tab=="recordings")records.size+drafts.size else jobs.size+movies.size
        val columns=if(w>=1200)4 else if(w>=880)3 else 2
        val tileWidth=(w-pad*2-16-(columns-1)*32)/columns
        val tileHeight=tileWidth*9/16+164
        val contentHeight=if(tab=="recordings")370+count*160f else 400+jobs.size*180+((movies.size+columns-1)/columns)*tileHeight
        val c=scroll(p,contentHeight.coerceAtLeast(h))
        heading(c,"보관함","에피소드와 녹음한 대화를 모아봤어요.")
        listOf("episodes" to "에피소드","recordings" to "녹음").forEachIndexed { i,(value,label)->
            val total=if(value=="recordings")records.size else movies.size
            put(c,art.button("$label $total",false) { rememberScroll();tab=value;render() }.apply {
                tag="tab_$value";background=TikiDesign.press(this@TikiActivity,TikiDesign.background,0f)
            },pad+i*216,172f,200f,72f)
            if(tab==value)put(c,View(this).apply { setBackgroundColor(TikiDesign.ink) },pad+i*216,241f,200f,3f)
        }
        divider(c,244f,pad,w-pad*2)
        if(count==0) {
            text(c,if(tab=="recordings")"아직 녹음한 대화가 없어요." else "아직 완성된 에피소드가 없어요.",pad,410f,w-pad*2,70f,30f,500,true)
            val bw=min(340f,w-64);button(c,"대화 녹음 시작",(w-bw)/2,550f,bw) { recordOnly=false;go("record") }
        }
        if(tab=="recordings") {
            drafts.forEachIndexed { i,draft ->
                val row=FrameLayout(this).apply { background=TikiDesign.press(this@TikiActivity,Color.WHITE,0f);setOnClickListener { recoveryId=draft.optString("sessionId");recoveryMissing=false;go("saved") } }
                text(row,"저장 확인이 필요한 녹음",8f,24f,w-pad*2-170,60f,24f,500)
                text(row,"남아 있는 구간을 확인해 주세요.",8f,90f,w-pad*2-170,40f,18f,color=TikiDesign.secondary)
                button(row,"다시 저장",w-pad*2-160,36f,152f,false) { recoveryId=draft.optString("sessionId");recoveryMissing=false;go("saved") }
                put(c,row,pad,348f+(records.size+i)*160,w-pad*2,160f)
            }
            text(c,"녹음 ${records.size}개",pad,270f,w-pad*2,72f,24f,500)
            records.forEachIndexed { i,recording->
            val rw=w-pad*2;val roomy=rw>900;val aw=if(roomy)272f else 152f;val tx=if(roomy)100f else 8f
            val row=FrameLayout(this).apply { background=TikiDesign.shape(Color.WHITE,0f,context=this@TikiActivity) }
            val state=EpisodeWork.status(this,recording.file.name)?.optString("state")?.takeUnless { it=="dismissed" }
            val badge=when(state) { "completed"->"영상 완성";"failed"->"제작 실패";"empty"->"후보 없음";"waiting"->"처리 대기";"queued","running"->"제작 중";else->"미제작" }
            if(roomy) {
                val icon=FrameLayout(this).apply { background=TikiDesign.shape(0xfff4f3f3.toInt(),16f,context=this@TikiActivity) }
                put(icon,ImageView(this).apply { setImageResource(R.drawable.tiki_recording_mic) },20f,20f,24f,24f);put(row,icon,8f,48f,64f,64f)
            }
            val tw=rw-aw-tx-if(roomy)180 else 24
            text(row,recording.title,tx,32f,tw,50f,24f,500).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
            text(row,if(roomy)recording.details else "$badge · ${recording.details}",tx,88f,tw,40f,18f,color=TikiDesign.secondary).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
            if(roomy)text(row,badge,rw-aw-160,60f,136f,40f,18f,center=true).background=TikiDesign.shape(0xfff4f3f3.toInt(),20f,context=this)
            val label=when(state) { "completed","queued","running"->"녹음 듣기";else->if(roomy)"동영상 생성하기" else "영상 만들기" }
            put(row,art.button(label,state==null) { source=recording.file.name
                if(state==null)startProduction(recording.file) else if(state in listOf("failed","waiting","empty"))go("progress") else go("audio")
            }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,if(roomy)24f else 20f) },rw-aw-8,36f,aw,88f)
            divider(row,159f,0f,rw)
            row.setOnClickListener { source=recording.file.name;go("audio") };put(c,row,pad,348f+i*160,rw,160f)
        } } else {
            jobs.forEachIndexed { i,job->
                val canDelete=job.optString("state")=="failed"
                val textWidth=w-pad*2-64-if(canDelete)168f else 0f
                val row=FrameLayout(this).apply { background=TikiDesign.press(this@TikiActivity,Color.WHITE,24f)
                    setOnClickListener { source=job.getString("source");go("progress") } }
                text(row,when(job.optString("state")) { "failed"->"영상 제작 확인이 필요해요";"empty"->"하이라이트 후보를 찾지 못했어요";"waiting"->"저장된 작업부터 제작을 이어갈게요";else->"에피소드를 만들고 있어요" },32f,22f,textWidth,52f,28f,500).apply {
                    maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                }
                text(row,job.optString("message"),32f,84f,textWidth,50f,20f,color=TikiDesign.secondary).apply {
                    maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                }
                if(canDelete)button(row,"삭제",w-pad*2-184f,34f,152f,false) {
                    MaterialAlertDialogBuilder(this).setTitle("제작 항목을 삭제할까요?")
                        .setMessage("에피소드 목록에서 이 항목을 삭제해요. 원본 녹음은 보관되며 녹음 탭에서 다시 영상을 만들 수 있어요.")
                        .setNegativeButton("취소",null)
                        .setPositiveButton("삭제") { _,_ ->
                            runCatching { EpisodeWork.dismissFailed(this,job.getString("source")) }
                                .onSuccess { deleted ->
                                    rememberScroll();render()
                                    toast(if(deleted)"제작 항목을 삭제했어요." else "제작 상태가 변경되어 삭제하지 않았어요.")
                                }.onFailure { toast("삭제하지 못했어요. 다시 시도해 주세요.") }
                        }.show()
                }
                put(c,row,pad,300f+i*180,w-pad*2,156f)
            }
            val top=300f+jobs.size*180
            if(movies.isNotEmpty())text(c,"완성된 에피소드 ${movies.size}편",pad,top,w-pad*2,40f,24f)
            movies.forEachIndexed { i,file->
                val tile=FrameLayout(this).apply { contentDescription=movieTitle(file);isFocusable=true
                    background=TikiDesign.press(this@TikiActivity,TikiDesign.background,20f)
                    setOnClickListener { movie=file.name;go("video") } }
                val key=listOf("imgOriginalImageFit","imgOriginalImageFit1","imgOriginalImageFit2","imgOriginalImageFit3")[i%4]
                put(tile,art.poster(file,"library",key),0f,0f,tileWidth,tileWidth*9/16)
                text(tile,"EP. ${EpisodeWork.episodeNumber(this,file).toString().padStart(2,'0')}",0f,tileWidth*9/16+16,tileWidth,28f,18f,color=TikiDesign.secondary)
                text(tile,movieTitle(file),0f,tileWidth*9/16+60,tileWidth,88f,24f).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }
                put(c,tile,pad+8+(i%columns)*(tileWidth+32),top+64+(i/columns)*tileHeight,tileWidth,tileHeight)
            }
        }
    }
    private fun startProduction(file: File) {
        if(productionStarting)return
        if(!restrictions.state.animationAllowed) { toast("주차 상태에서 영상을 만들어 주세요.");return }
        if(RecordingStore.state.busy) { toast("진행 중인 녹음을 마친 뒤 제작해 주세요.");return }
        if(EpisodeWork.status(this,file.name)?.optString("state") in listOf("queued","running","waiting")) { source=file.name;go("progress");return }
        productionStarting=true
        lifecycleScope.launch {
            try {
                EpisodeWork.createAutomatically(this@TikiActivity,file)
                source=file.name;go("progress")
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { toast(error.message ?: "영상 제작을 시작하지 못했어요.") }
            finally { productionStarting=false }
        }
    }
    private fun productionOptions() {
        if(!restrictions.state.animationAllowed) { toast("주차 상태에서 제작 설정을 변경해 주세요.");return }
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24f),dp(8f),dp(24f),dp(8f)) }
        fun label(value: String) { content.addView(art.text(value,20f),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12f) }) }
        fun select(values: List<String>,index: Int): Spinner = Spinner(this).apply {
            adapter=ArrayAdapter(this@TikiActivity,android.R.layout.simple_spinner_dropdown_item,values)
            setSelection(index);content.addView(this,LinearLayout.LayoutParams(-1,dp(64f)))
        }
        label("등장 인원은 영상 구간에서 감지한 화자 수에 맞춰 자동으로 정해요. 확인하기 어려운 구간은 2명의 임시 캐릭터로 연출하고 화자 미확정으로 표시해요.")
        label("하이라이트 한 편의 최대 길이")
        val lengths=listOf(20,40,60,90,120)
        val length=select(lengths.map { "${it}초" },lengths.indexOf(ProductionSettings.seconds(this)).coerceAtLeast(0)).apply { contentDescription="하이라이트 최대 길이" }
        label("웃음 감도 · 값이 작을수록 약한 웃음까지 감지")
        val laughter=select(LaughterSensitivity.labels,LaughterSensitivity.values.indexOf(MetadataEpisodes.threshold(this)).coerceAtLeast(0))
            .apply { contentDescription="웃음 감지 기준값" }
        label("감지된 화자마다 캐릭터를 연결하며 최대 4명까지 지원해요. 웃음의 주인이 불분명하면 말하지 않는 캐릭터에게 배정합니다.")
        val body=ScrollView(this).apply { addView(content) }
        MaterialAlertDialogBuilder(this).setTitle("영상 제작 설정").setView(body)
            .setNegativeButton("취소",null).setPositiveButton("설정 저장") { _,_->
                if(!restrictions.state.animationAllowed || RecordingStore.state.busy) { toast("주차 상태와 녹음 종료를 확인해 주세요.");return@setPositiveButton }
                ProductionSettings.saveDuration(this,lengths[length.selectedItemPosition])
                MetadataEpisodes.threshold(this,LaughterSensitivity.values[laughter.selectedItemPosition])
                render()
            }.show()
    }
    private fun progress(p: FrameLayout) {
        val job=EpisodeWork.status(this,source);val state=job?.optString("state") ?: "queued"
        val message=(job?.optString("message") ?: "작업을 준비하고 있어요.") +
            if(job?.optString("engine")==MetadataEpisodes.ENGINE && job.has("laughterCount"))
                "\n웃음 ${job.optInt("laughterCount")}구간 · 대사 ${job.optInt("captionCount")}구간" else ""
        val messageView=art.text(message,24f,color=TikiDesign.secondary).apply { gravity=Gravity.CENTER }
        val messageHeight=maxOf(90f,android.text.StaticLayout.Builder.obtain(message,0,message.length,messageView.paint,dp(w-64))
            .setIncludePad(false).build().height/resources.displayMetrics.density+8)
        val height=maxOf(h,1000f,700f+messageHeight+220f);val c=scroll(p,height)
        heading(c,"에피소드 제작",back=true);put(c,art.image(if(state=="waiting")"waiting" else "generating","imgArtworkTransparentPng"),(w-380)/2,250f,380f,330f)
        text(c,when(state) { "failed"->"제작을 마치지 못했어요";"completed"->"영상이 준비됐어요";"empty"->"원본 녹음을 보관했어요";"waiting"->"잠시 기다리고 있어요";else->"우리 이야기를 만들고 있어요" },32f,620f,w-64,60f,32f,500,true)
        put(c,messageView,32f,700f,w-64,messageHeight)
        val bw=min(480f,w-64);button(c,when(state) { "failed"->"다시 시도";"empty"->"다시 분석해 만들기";"completed"->"에피소드 보기";else->"보관함으로" },(w-bw)/2,height-160,bw) {
            when(state) {
                "failed"->lifecycleScope.launch { runCatching { EpisodeWork.enqueue(this@TikiActivity,File(RecordingFiles.directory(this@TikiActivity),source),retry=true) }.onSuccess { render() }.onFailure { toast("작업을 다시 시작하지 못했어요.") } }
                "empty"->startProduction(File(RecordingFiles.directory(this),source))
                "completed"->{ movie=EpisodeWork.latestMovie(this,source)?.name.orEmpty();if(movie.isNotEmpty())go("video") else go("library") }
                else->go("library")
            }
        }.tag="episode_result"
    }
    private fun characters(p: FrameLayout) {
        val height=h.coerceAtLeast(1184f);val c=scroll(p,height);heading(c,"내 캐릭터 선택","영상 속 나를 골라주세요.")
        val wide=w>=1100;val colors=listOf(0xffe5d2b5,0xffd99b7a,0xffa3c5d9,0xffcfb3bf,0xffdbbe80,0xffbac88f,0xffce9ca1,0xffbbb1d5).map { it.toInt() }
        fun card(index: Int,size: Float)=FrameLayout(this).apply {
            background=TikiDesign.shape(colors[index],size*.28f,if(index==character)TikiDesign.ink else null,this@TikiActivity)
            if(index==character)(background as android.graphics.drawable.GradientDrawable).setStroke(dp(4f*size/190),TikiDesign.ink)
            elevation=dp(6f).toFloat()
            tag="character-card-$index";contentDescription=TikiProfile.labels[index];isFocusable=true;setOnClickListener { rotateCharacter(index) }
            put(this,art.character(index),size*.1f,size*.1f,size*.8f,size*.8f)
            if(index==character) {
                val check=art.text("✓",26f*size/190,400,Color.WHITE).apply { gravity=Gravity.CENTER
                    background=TikiDesign.shape(TikiDesign.ink,23f,Color.WHITE,this@TikiActivity) }
                put(this,check,size-58*size/190,4f*size/190,46f*size/190,46f*size/190)
            }
        }
        if(wide) for(offset in -3..3) {
            val index=(character+offset+8)%8
            val delta=listOf(-594f,-448.8f,-250.8f,0f,250.8f,448.8f,594f)[offset+3]
            val x=w/2+delta*(w-190)/1380-95
            val y=170f+when(kotlin.math.abs(offset)) { 0->0f;1->40f;2->190f;else->430f }
            put(c,card(index,190f),x,y,190f,190f)
        } else for(offset in -3..3) {
            val index=(character+offset+8)%8;val tile=if(w>=650)96f else 80f
            val delta=listOf(-270f,-210f,-118f,0f,118f,210f,270f)[offset+3]
            val x=w/2+delta*(w-tile)/614-tile/2
            val y=when(kotlin.math.abs(offset)) { 0->184f;1->226f;2->332f;else->472f }
            put(c,card(index,tile),x,y,tile,tile)
        }
        val size=min(if(wide)500f else 340f,w-158);put(c,art.character(character).apply { tag="character-hero" },(w-size)/2,if(wide)391f else 499f,size,size)
        val ay=if(wide)605f else 633f
        put(c,art.button("‹",false) { rotateCharacter((character+7)%8) }.apply { contentDescription="이전 캐릭터" },(w-size)/2-72,ay,72f,72f)
        put(c,art.button("›",false) { rotateCharacter((character+1)%8) }.apply { contentDescription="다음 캐릭터" },(w+size)/2,ay,72f,72f)
        val bw=min(if(wide)480f else 360f,w-64);button(c,"이 캐릭터로 선택",(w-bw)/2,height-248,bw) {
            if(characterOrigin=="profile")go("profile")
            else saveProfile(profile.copy(characterId=TikiProfile.characters[character])) { toast("다음 영상부터 적용돼요.");render() }
        }.tag="character_save"
    }
    private fun rotateCharacter(target: Int) {
        if(carouselBusy || target==character)return
        val forward=(target-character+8)%8;val step=if(forward<=4)1 else -1;val count=minOf(forward,8-forward)
        if(!restrictions.state.animationAllowed) { character=target;render();return }
        carouselBusy=true
        fun advance(left: Int) {
            if(route!="character") { carouselBusy=false;return }
            val previous=(0..7).mapNotNull { i -> root.findViewWithTag<View>("character-card-$i")?.let {
                val lp=it.layoutParams as FrameLayout.LayoutParams;i to (lp.leftMargin to lp.topMargin)
            } }.toMap()
            character=(character+step+8)%8;render()
            val duration=if(count==1)350L else 240L
            (0..7).forEach { i->root.findViewWithTag<View>("character-card-$i")?.let { view ->
                val lp=view.layoutParams as FrameLayout.LayoutParams;val old=previous[i]
                if(old!=null) { view.translationX=(old.first-lp.leftMargin).toFloat();view.translationY=(old.second-lp.topMargin).toFloat() }
                else view.alpha=0f
                view.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(duration).start()
            } }
            root.findViewWithTag<View>("character-hero")?.let { it.alpha=.35f;it.animate().alpha(1f).setDuration(duration).start() }
            handler.postDelayed({ if(left>1)advance(left-1) else carouselBusy=false },duration)
        }
        advance(count)
    }
    private fun settings(p: FrameLayout) {
        val c=scroll(p,1080f.coerceAtLeast(h));heading(c,"설정");val pad=if(w>=900)48f else 32f;val rw=min(1100f,w-pad*2)
        fun row(title: String,value: String,y: Float,destination: String) {
            val box=FrameLayout(this).apply { background=TikiDesign.press(this@TikiActivity,TikiDesign.background,0f)
                contentDescription="$title, $value";isFocusable=true;setOnClickListener {
                    if(destination=="production")productionOptions()
                    else if(destination=="laughter") {
                        if(!restrictions.state.animationAllowed)toast("주차 상태에서 감도를 변경해 주세요.")
                        else MaterialAlertDialogBuilder(this@TikiActivity).setTitle("웃음 감도 · 다음 제작부터 적용")
                            .setSingleChoiceItems(LaughterSensitivity.labels.toTypedArray(),LaughterSensitivity.values.indexOf(MetadataEpisodes.threshold(this@TikiActivity))) { dialog,index ->
                                MetadataEpisodes.threshold(this@TikiActivity,LaughterSensitivity.values[index]);dialog.dismiss();render()
                            }.setNegativeButton("취소",null).show()
                    } else { if(destination=="name")draft=profile.name;if(destination=="voice") { voiceOrigin="settings";voiceModel.reset() };go(destination) }
                } }
            text(box,title,32f,0f,rw-230,110f);text(box,"$value   ›",rw-210,0f,178f,110f,18f,color=TikiDesign.secondary).gravity=Gravity.END or Gravity.CENTER_VERTICAL
            divider(box,111f,0f,rw);put(c,box,pad,y,rw,112f)
        }
        row("내 이름",profile.name,136f,"name");row("내 목소리 확인",if(profile.voice?.modelId==LocalVoice.MODEL)"확인 완료" else "확인하기",248f,"voice")
        row("녹음과 개인정보","안내 보기",360f,"privacy")
    }
    private fun name(p: FrameLayout,onboarding: Boolean) {
        val height=h.coerceAtLeast(900f);val c=scroll(p,height);heading(c,if(onboarding)"어떻게 불러드릴까요?" else "내 이름",if(onboarding)"이름과 내 캐릭터를 정해 주세요." else "영상에 표시할 내 이름을 알려주세요.",!onboarding)
        val iw=min(720f,w-64);val input=EditText(this).apply {
            setText(draft);hint="이름을 입력해 주세요";setSingleLine(true);typeface=art.sans;setTextColor(TikiDesign.ink)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,32f);setPadding(dp(28f),0,dp(28f),0)
            background=TikiDesign.shape(Color.WHITE,24f,TikiDesign.line,this@TikiActivity);tag="profile_name"
            filters=arrayOf(android.text.InputFilter.LengthFilter(30));addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int){}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { draft=s.toString();root.findViewWithTag<View>("profile_save")?.isEnabled=draft.trim().isNotEmpty() && draft.none { it.isISOControl() } }
                override fun afterTextChanged(s: Editable?){}
            })
        };put(c,input,(w-iw)/2,250f,iw,112f)
        if(onboarding) {
            put(c,art.character(character),(w-180)/2,400f,180f,180f)
            button(c,"내 캐릭터 선택  ›",(w-320)/2,600f,320f,false) { characterOrigin="profile";go("character") }
        }
        val bw=min(480f,iw);button(c,if(onboarding)"다음" else "저장",(w-bw)/2,height-160,bw) {
            saveProfile(profile.copy(name=draft.trim(),characterId=if(onboarding)TikiProfile.characters[character] else profile.characterId)) {
                if(onboarding) { prefs.edit().putBoolean("setup",true).apply();voiceOrigin="home";voiceModel.reset();go("voice") }
                else { toast("다음 영상부터 적용돼요.");go("settings") }
            }
        }.apply { tag="profile_save";isEnabled=draft.trim().isNotEmpty() && draft.none { it.isISOControl() } }
    }
    private fun saveProfile(value: PersonProfile,done: ()->Unit) { lifecycleScope.launch {
        if(!restrictions.state.animationAllowed) { toast("주차 상태에서 내 정보를 저장해 주세요.");return@launch }
        runCatching { withContext(Dispatchers.IO) { TikiProfile.save(this@TikiActivity,value) } }.onSuccess { profile=value;done() }.onFailure { toast(it.message ?: "내 정보를 저장하지 못했어요.") }
    } }
    private fun voice(p: FrameLayout) {
        val state=voiceModel.state.value;renderedVoiceStage=state.stage
        val height=h.coerceAtLeast(1184f);val c=scroll(p,height);val recording=state.stage=="recording"
        val title=when(state.stage) { "recording"->"내 목소리를 듣고 있어요";"processing"->"내 목소리를 확인하고 있어요";"success"->"내 목소리를 확인했어요";"error"->"목소리를 확인하지 못했어요";else->"내 목소리 확인" }
        heading(c,title,if(recording)"평소 이야기하는 목소리로 문장을 읽어주세요." else if(state.stage=="intro")"내 목소리를 확인하면 내 캐릭터로 이야기를 전할 수 있어요." else "",true)
        if(state.stage in listOf("intro","success")) {
            val size=if(state.stage=="intro")220f else 280f
            put(c,art.character(TikiProfile.characters.indexOf(profile.characterId).coerceAtLeast(0)),(w-size)/2,270f,size,size)
        }
        when(state.stage) {
            "intro","recording"->{
                val y=if(recording)320f else 540f
                text(c,if(recording)"지금 내 목소리를 듣고 있어요." else "아래 문장을 평소 목소리로 읽어주세요.",32f,y,w-64,60f,24f,center=true)
                text(c,"“오늘 나눈 이야기를\n티키타카에 남겨볼까요?”",32f,y+80,w-64,130f,36f,600,true)
                if(recording) {
                    wave=TikiWave(this).also { put(c,it,(w-min(432f,w-96))/2,y+240,min(432f,w-96),88f) }
                    timer=text(c,"${state.seconds}초",32f,y+340,w-64,40f,18f,center=true)
                }
                text(c,if(recording)"문장을 다 읽으면 읽기 완료를 눌러주세요." else "주차한 뒤 조용한 곳에서 시작해 주세요.\n나중에 설정에서도 할 수 있어요.",32f,800f,w-64,70f,18f,center=true,color=TikiDesign.secondary)
            }
            "success"->text(c,"다음 녹음부터 내 목소리를 찾는 데 사용해요.\n내 목소리로 확인된 대사는 선택한 내 캐릭터가 전해요.",32f,620f,w-64,130f,24f,center=true)
            "error"->{ put(c,ImageView(this).apply { setImageResource(R.drawable.tiki_recording_mic) },(w-80)/2,370f,80f,80f)
                text(c,"주변 소리를 줄이고 문장을 다시 읽어주세요.\n지금 확인하지 않아도 녹음과 영상 만들기를 이용할 수 있어요.",32f,550f,w-64,150f,24f,center=true)
                text(c,state.message,32f,720f,w-64,90f,18f,center=true,color=TikiDesign.secondary) }
            else->text(c,"기기에서 목소리를 확인하고 있어요.",32f,500f,w-64,100f,24f,center=true)
        }
        val bw=min(440f,(w-88)/2);val y=height-248
        button(c,when(state.stage){"success"->"다시 확인";"recording","processing"->"취소";else->"나중에 할게요"},(w-bw*2-24)/2,y,bw,false) {
            if(state.stage in listOf("success","recording","processing"))voiceModel.reset() else go(voiceOrigin)
        }
        button(c,when(state.stage){"success"->"완료";"recording"->"읽기 완료";"error"->"다시 확인";"processing"->"확인 중";else->"확인 시작"},(w+24)/2,y,bw) {
            when(state.stage) {
                "success"->go(voiceOrigin);"recording"->voiceModel.finishCapture()
                else->{ pendingVoice=true;if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { pendingVoice=false;voiceModel.start() } else permission.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }.isEnabled=state.stage!="processing"
    }
    private fun privacy(p: FrameLayout) {
        val message="함께 있는 분들의 동의를 받아주세요.\n녹음은 버튼을 눌렀을 때만 시작합니다. 원치 않을 때는 일시정지할 수 있어요.\n\n이번에는 녹음만 보관\n원음을 기기에 저장하고 자동 전송·제작하지 않습니다.\n\n영상 만들기\n음성 인식·웃음 감지·화자 구분·하이라이트 선택·영상 제작을 기기 안에서 처리합니다. 원음·전사문·분석 결과·영상은 외부로 전송하지 않습니다.\n\n내 목소리 확인\n확인용 음성은 메모리에서 처리 후 버리며 특징 정보만 기기에 암호화해 저장합니다. 내 목소리로 확인되지 않은 발화는 일반 캐릭터가 연기합니다. 짧은 발화·소음·동시 발화에서는 식별이 어려울 수 있어요.\n\n보관\n기기의 원음·영상·복구 자료와 목소리 특징은 직접 삭제하거나 앱 데이터를 삭제할 때까지 보관합니다. 자동 일괄 삭제는 하지 않습니다."
        val textView=art.text(message,24f)
        val contentHeight=android.text.StaticLayout.Builder.obtain(message,0,message.length,textView.paint,dp(w-96)).setIncludePad(true).build().height/resources.displayMetrics.density+80
        val c=scroll(p,(190f+contentHeight+180f).coerceAtLeast(h));heading(c,"녹음과 개인정보",back=true)
        put(c,textView,48f,190f,w-96,contentHeight)
        button(c,"앱 권한 관리",48f,230f+contentHeight,min(360f,w-96),false) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
    }
    private fun microphone(p: FrameLayout) {
        val height=h.coerceAtLeast(1000f);val c=scroll(p,height);heading(c,"마이크 권한이 필요해요",back=true)
        put(c,ImageView(this).apply { setImageResource(R.drawable.tiki_recording_mic) },(w-100)/2,300f,100f,100f)
        text(c,"대화를 녹음하려면\n마이크 사용을 허용해 주세요.",32f,480f,w-64,160f,30f,500,true)
        val bw=min(400f,(w-88)/2)
        button(c,"나중에",(w-bw*2-24)/2,height-160,bw,false) { go("home") }
        button(c,"앱 설정 열기",(w+24)/2,height-160,bw) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
    }
    private fun audio(p: FrameLayout) {
        val file=File(RecordingFiles.directory(this),source).canonicalFile
        if(file.parentFile!=RecordingFiles.directory(this).canonicalFile || !file.isFile) { text(p,"녹음 파일을 찾을 수 없어요.",32f,200f,w-64,100f);return }
        val recording=RecordingFiles.list(this).firstOrNull { it.file.name==source } ?: return
        val c=scroll(p,1000f.coerceAtLeast(h));heading(c,recording.title,"원본 녹음",true)
        put(c,art.character(TikiProfile.characters.indexOf(profile.characterId).coerceAtLeast(0)),(w-280)/2,220f,280f,280f)
        val audio=MediaPlayer();player=audio
        val generation=playbackGeneration
        audio.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        audio.setOnPreparedListener { if(player===audio && generation==playbackGeneration) {
            seek?.max=it.duration;playbackSeekPending=position>0
            it.setOnSeekCompleteListener { if(player===audio && generation==playbackGeneration)playbackSeekPending=false }
            it.seekTo(position);playbackPrepared=true
        } }
        audio.setOnCompletionListener { if(player===audio) { play?.text="재생";focus.release() } }
        audio.setOnErrorListener { _,_,_->toast("음성을 재생하지 못했어요.");true };audio.setDataSource(file.path);audio.prepareAsync()
        mediaControls(c,570f) { runCatching {
            if(audio.isPlaying) { audio.pause();focus.release();play?.text="재생" }
            else if(!RecordingStore.state.busy && focus.acquire(audio)) { audio.start();play?.text="일시정지" }
        }.onFailure { toast("음성을 준비하고 있어요. 잠시 후 다시 눌러주세요.") } }
        val bw=min(480f,w-64)
        button(c,if(EpisodeWork.status(this,source)?.optString("state")=="completed")"새 버전 영상 만들기" else "영상 만들기",(w-bw)/2,860f,bw) {
            if(audio.isPlaying) { audio.pause();focus.release();play?.text="재생" }
            startProduction(file)
        }.tag="audio_produce"
    }
    private fun video(p: FrameLayout) {
        val file=File(AnimationFiles.directory(this),movie).canonicalFile
        if(file.parentFile!=AnimationFiles.directory(this).canonicalFile || !file.isFile) { text(p,"영상 파일을 찾을 수 없어요.",32f,200f,w-64,100f);return }
        val info=runCatching { JSONObject(File(file.path+".json").readText()) }.getOrNull()
        val diagnosticsEnabled=com.example.hd_project.BuildConfig.PLAYBACK_DIAGNOSTICS
        val show=diagnosticsEnabled && prefs.getBoolean("playbackDiagnostics",false)
        diagnosticTimeline=if(show)runCatching { PlaybackDiagnostics.Timeline(JSONObject(PlaybackDiagnostics.file(file).readText())) }.getOrNull() else null
        diagnosticAudioTracks=if(show)runCatching {
            val extractor=android.media.MediaExtractor()
            try { extractor.setDataSource(file.path);(0 until extractor.trackCount).count { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/")==true } }
            finally { extractor.release() }
        }.getOrDefault(-1) else -1
        val count=(diagnosticTimeline?.data?.optJSONArray("castNames")?.length() ?: info?.optInt("castCount",1) ?: 1).coerceIn(1,4)
        val details=show && diagnosticTimeline!=null && prefs.getBoolean("playbackDiagnosticsDetails",false)
        val summaryHeight=324f+maxOf(0,count-2)*36f
        val detailsHeight=if(details)(14+count*2)*28f else 0f
        val diagnosticHeight=if(show)summaryHeight+56+detailsHeight else 0f
        // Figma E-02: 1120x630; N/E-02: 645x362.813. The film stays 16:9 in both app orientations.
        val vw=min(1120f,w-if(w>=900)96f else 64f);val vh=vw*9f/16f
        val videoTop=180f+diagnosticHeight
        val playback=FrameLayout(this).also { put(p,it,0f,0f,w,h);videoContent=it }
        val c=scroll(playback,maxOf(h,videoTop+vh+440f))
        heading(c,"EP. ${EpisodeWork.episodeNumber(this,file).toString().padStart(2,'0')} · ${movieTitle(file)}",back=true)
        if(diagnosticsEnabled)put(c,art.button(if(show)"진단 켜짐" else "진단 꺼짐",false) {
            prefs.edit().putBoolean("playbackDiagnostics",!show).apply();render()
        }.apply { tag="diagnostics_toggle" },32f,110f,156f,64f)
        val quickWidth=min(156f,w-400)
        quickPlay=art.button("재생",false) { play?.performClick() }.also {
            it.tag="diagnostics_play";put(c,it,(w-quickWidth)/2,110f,quickWidth,64f)
        }
        if(show)diagnosticView=text(c,diagnosticTimeline?.summary(position.toLong(),diagnosticAudioTracks)
            ?: "이전 영상 · 검출 기록 없음\n웃음 원점수: —\n최종 반영: —\n분석 판정: 기록 없음\n화자 1: 기록 없음\n화자 2: 기록 없음\n화자 불분명: 기록 없음\n새로 만든 에피소드를 열어 주세요.",
            32f,180f,w-64,summaryHeight,20f,color=Color.WHITE).apply {
            tag="playback_diagnostics";setPadding(dp(12f),dp(8f),dp(12f),dp(8f))
            setBackgroundColor(0xff202a34.toInt());setLineSpacing(dp(3f).toFloat(),1f)
            text=PlaybackDiagnostics.emphasizeSpeakers(text.toString())
        }
        if(show && diagnosticTimeline!=null) {
            button(c,if(details)"자세한 수치 접기" else "자세한 수치 보기",32f,180f+summaryHeight,w-64,false) {
                prefs.edit().putBoolean("playbackDiagnosticsDetails",!details).apply();render()
            }.apply { tag="diagnostics_details_toggle";layoutParams.height=dp(56f) }
            if(details)diagnosticDetails=text(c,diagnosticTimeline!!.text(position.toLong(),diagnosticAudioTracks),
                32f,180f+summaryHeight+56,w-64,detailsHeight,18f,color=Color.WHITE).apply {
                tag="playback_diagnostics_details";setPadding(dp(12f),dp(8f),dp(12f),dp(8f))
                setBackgroundColor(0xff202a34.toInt());setLineSpacing(dp(3f).toFloat(),1f)
            }
        }
        val viewport=FrameLayout(this).apply {
            tag="episode_video_frame";background=TikiDesign.shape(Color.WHITE,20f,context=this@TikiActivity);clipToOutline=true
        }
        put(c,viewport,(w-vw)/2,videoTop,vw,vh)
        val generation=playbackGeneration
        val view=RestrictedVideoView(this).apply {
            tag="episode_video"
            playbackAllowed=restrictions.state.videoAllowed;canStart={ visible && restrictions.state.videoAllowed && !RecordingStore.state.busy }
            setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE)
            setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        };video=view
        view.setOnPreparedListener { media->if(video===view && generation==playbackGeneration) {
            player=media;seek?.max=media.duration;playbackSeekPending=position>0
            media.setOnSeekCompleteListener { if(video===view && generation==playbackGeneration) {
                playbackSeekPending=false
                if(view.currentPosition>0 && restrictions.state.videoAllowed)videoPoster?.visibility=View.GONE
            } }
            view.seekTo(position);view.pause();playbackPrepared=true;applyVideoRestriction(restrictions.state)
        } }
        view.setOnCompletionListener { if(video===view) { play?.text="재생";focus.release() } }
        view.setOnErrorListener { _,_,_->if(video===view)toast("영상을 재생하지 못했어요.");true }
        viewport.addView(view,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT,Gravity.CENTER))
        view.setVideoPath(file.path)
        videoPoster=art.poster(file,"library","").also {
            it.scaleType=ImageView.ScaleType.FIT_CENTER // Legacy portrait files are preserved without stretching or cropping.
            put(viewport,it,0f,0f,vw,vh);it.visibility=if(position==0)View.VISIBLE else View.GONE
        }
        videoPoster?.apply { contentDescription="영상 재생";setOnClickListener { play?.performClick() } }
        val captionFile=File(file.path+".captions.json")
        if(captionFile.isFile) {
            captions=runCatching { JSONArray(captionFile.readText()) }.getOrDefault(JSONArray())
            val captionWidth=if(vw>=1000)900f else vw-48
            captionView=text(viewport,"",(vw-captionWidth)/2,vh-128,captionWidth,112f,24f,center=true,color=Color.WHITE).apply {
                background=TikiDesign.shape(0xe6343434.toInt(),context=this@TikiActivity)
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(24f),dp(16f),dp(24f),dp(16f));visibility=View.INVISIBLE
            }
            put(c,art.button(if(prefs.getBoolean("captions",true))"자막 켜짐" else "자막 꺼짐",false) {
                val enabled=!prefs.getBoolean("captions",true);prefs.edit().putBoolean("captions",enabled).apply()
                root.findViewWithTag<TextView>("captions_toggle")?.text=if(enabled)"자막 켜짐" else "자막 꺼짐"
                if(!enabled)captionView?.visibility=View.INVISIBLE
            }.apply { tag="captions_toggle" },w-188,110f,156f,64f)
        }
        mediaStatus=text(c,if(restrictions.state.videoAllowed)"" else "영상은 주차 중에만 볼 수 있어요.",32f,videoTop+vh,w-64,56f,20f,center=true,color=TikiDesign.secondary)
        mediaControls(c,videoTop+70+vh,(w-vw)/2,vw) {
            if(view.isPlaying) { view.pause();focus.release();play?.text="재생" }
            else if(restrictions.state.videoAllowed && !RecordingStore.state.busy && player?.let { focus.acquire(it) }==true) { videoPoster?.visibility=View.GONE;view.start();play?.text="일시정지" }
            else toast("주차 상태와 재생 준비를 확인해 주세요.")
        }
        videoBlocker=TikiDrivingPauseScreen(this,art,movieTitle(file),
            onBack={ go("library") },onHome={ go("home") },onResume={
                if(visible && restrictions.state.videoAllowed && playbackPrepared && !RecordingStore.state.busy) {
                    videoResumeRequired=false
                    applyVideoRestriction(restrictions.state)
                    play?.performClick()
                }
            }).also { put(p,it,0f,0f,w,h) }
        applyVideoRestriction(restrictions.state)
        val original=info?.optString("source")?.takeIf { it.isNotBlank() }?.let { File(RecordingFiles.directory(this),it).canonicalFile }
        if(diagnosticsEnabled && original!=null && original.parentFile==RecordingFiles.directory(this).canonicalFile && original.isFile)
            button(c,"진단용 새 버전 만들기",(w-min(420f,w-64))/2,videoTop+vh+320,min(420f,w-64),false) {
                view.pause();focus.release();play?.text="재생";startProduction(original)
            }.tag="diagnostic_rebuild"
    }
    private fun mediaControls(p: FrameLayout,y: Float,x: Float=32f,width: Float=w-64,toggle: ()->Unit) {
        timer=text(p,formatDuration(position.toLong()),x,y,width,48f,24f,center=true)
        seek=SeekBar(this).apply { contentDescription="재생 위치";setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?,progress: Int,user: Boolean) { if(user && (video==null || visible && restrictions.state.videoAllowed)) { position=progress;playbackSeekPending=playbackPrepared;runCatching { if(video!=null)video?.seekTo(progress) else player?.seekTo(progress) } } }
            override fun onStartTrackingTouch(bar: SeekBar?){};override fun onStopTrackingTouch(bar: SeekBar?){}
        }) }.also { put(p,it,x+16,y+54,width-32,64f) }
        val bw=min(340f,width);play=button(p,"재생",x+(width-bw)/2,y+140,bw,true,toggle)
    }
    private fun applyVideoRestriction(state: DrivingRestrictions.State) {
        val view=video ?: return
        val allowed=visible && state.videoAllowed
        val wasAllowed=videoGateAllowed
        if(visible && state.available && !state.videoAllowed) { videoResumeRequired=true;pendingVideoResume=false }
        if(!allowed) {
            capturePosition();view.playbackAllowed=false;focus.release()
            play?.text="재생";quickPlay?.text="재생";captionView?.visibility=View.INVISIBLE
        } else {
            view.playbackAllowed=true
            if(!wasAllowed && playbackPrepared && position>0 && !RecordingStore.state.busy) {
                playbackSeekPending=true;view.seekTo(position)
            }
        }
        videoGateAllowed=allowed
        val message=if(!state.available)"차량 상태를 확인하고 있어요.\n확인될 때까지 영상이 잠겨 있어요."
            else "주행 중에는 영상을 볼 수 없어요.\n주차 후 재생 버튼을 눌러 주세요."
        val blocked=!allowed || videoResumeRequired
        videoBlocker?.apply {
            update(state.available,allowed,playbackPrepared,RecordingStore.state.busy)
            visibility=if(blocked)View.VISIBLE else View.GONE
        }
        videoContent?.importantForAccessibility=if(blocked)View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        mediaStatus?.text=if(allowed)"주차 중이에요. 재생 버튼을 눌러 이어서 보세요." else message.replace('\n',' ')
        val enabled=allowed && playbackPrepared && !videoResumeRequired
        listOf(play,quickPlay,seek).forEach { it?.isEnabled=enabled }
        videoPoster?.apply { isEnabled=enabled;importantForAccessibility=if(allowed)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        view.importantForAccessibility=if(allowed)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        listOf(diagnosticView,diagnosticDetails).forEach { it?.visibility=if(allowed)View.VISIBLE else View.INVISIBLE }
        for(tag in listOf("diagnostics_toggle","diagnostics_details_toggle","captions_toggle"))
            root.findViewWithTag<View>(tag)?.isEnabled=allowed
        if(visible && state.available && !state.videoAllowed && !drivingNoticeLaunched) {
            capturePosition();drivingNoticeLaunched=true
            startActivity(Intent(this,DrivingPauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("episodeMovie",movie))
        }
        if(pendingVideoResume && allowed && playbackPrepared && !RecordingStore.state.busy) {
            pendingVideoResume=false;videoResumeRequired=false
            applyVideoRestriction(state)
            play?.performClick()
        }
    }
    private fun capturePosition() { runCatching {
        // Once gated, preserve the pause snapshot: a detached VideoView reports zero after its surface is destroyed.
        if(playbackPrepared && !playbackSeekPending && (video==null || video?.playbackAllowed==true))
            position=player?.currentPosition ?: video?.currentPosition ?: position
        if(playbackKey.isNotEmpty())prefs.edit().putInt("position:$playbackKey",position).apply()
    } }
    private fun releasePlayback() {
        capturePosition();playbackGeneration++;playbackPrepared=false;playbackSeekPending=false;focus.release()
        val oldVideo=video;val oldPlayer=player;video=null;player=null
        if(oldVideo!=null)runCatching { oldVideo.stopPlayback() } else runCatching { oldPlayer?.release() }
    }
}
