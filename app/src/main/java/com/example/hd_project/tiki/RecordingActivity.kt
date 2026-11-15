package com.example.hd_project.tiki

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.hd_project.BoardingCoordinator
import com.example.hd_project.MainActivity
import com.example.hd_project.RecordingPhase
import com.example.hd_project.RecordingService
import com.example.hd_project.RecordingState
import com.example.hd_project.RecordingStore
import com.example.hd_project.pleos.DrivingRestrictions
import com.example.hd_project.GleoLauncher
import com.example.hd_project.pleos.applyPleosInsets

/** Only ongoing recording controls are driving-safe. Setup, settings and playback stay in MainActivity. */
class RecordingActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var art: TikiArt
    private var screen: TikiRecordingScreen? = null
    private var visible = false
    private var leaving = false
    private var pendingGleo = false
    private var lastSize = ""
    private val handler = Handler(Looper.getMainLooper())
    private val destinations = mutableListOf<View>()
    private val restrictions by lazy { DrivingRestrictions(this) { update(RecordingStore.state) } }
    private val observer: (RecordingState) -> Unit = { update(it) }
    private fun dp(v: Float) = TikiDesign.dp(this,v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        art = TikiArt(this)
        window.decorView.setBackgroundColor(TikiDesign.background)
        root = FrameLayout(this).apply {
            tag = "tiki_record"
            background = TikiDesign.shape(TikiDesign.background,40f,0xffc9cacd.toInt(),this@RecordingActivity)
            clipToOutline = true
        }
        setContentView(root); root.applyPleosInsets()
        root.addOnLayoutChangeListener { _,l,t,r,b,_,_,_,_ ->
            val key = "${r-l}:${b-t}:${root.paddingLeft}:${root.paddingTop}:${root.paddingRight}:${root.paddingBottom}"
            if (key != lastSize) { lastSize=key;root.post { if (!isFinishing) render() } }
        }
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { home() }
        })
    }
    override fun onStart() {
        super.onStart();visible=true;leaving=false
        closeParkedTask()
        restrictions.start();RecordingStore.observe(observer);BoardingCoordinator.setScreenVisible(true)
    }
    override fun onStop() {
        visible=false;pendingGleo=false;handler.removeCallbacksAndMessages(null)
        RecordingStore.remove(observer);restrictions.stop();BoardingCoordinator.setScreenVisible(false);super.onStop()
    }
    override fun onDestroy() { if (::art.isInitialized) art.clear();super.onDestroy() }

    private fun update(state: RecordingState) {
        if (!visible || leaving) return
        val parked = restrictions.state.animationAllowed
        destinations.forEach { it.isEnabled=parked;it.alpha=if (parked) 1f else .4f }
        if (!state.busy) {
            if (parked) open("record", completed=true)
            else {
                screen?.visibility=View.GONE
                if (root.findViewWithTag<View>("record_finished") == null) {
                    root.addView(art.text(if (state.phase==RecordingPhase.ERROR) "녹음 상태를 주차 후 확인해 주세요." else "녹음을 마쳤어요. 주차 후 기록을 확인해 주세요.").apply {
                        tag="record_finished";gravity=Gravity.CENTER
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT).apply { leftMargin=dp(131f) })
                }
            }
            return
        }
        screen?.update(state,restrictions.state)
        if (pendingGleo && state.phase==RecordingPhase.PAUSED) {
            pendingGleo=false
            handler.postDelayed({ if (visible && !GleoLauncher.requestListening(this)) toast("Gleo를 실행하지 못했어요.") },300)
        }
    }
    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun command(action: String, forGleo: Boolean=false) {
        if (!RecordingStore.state.busy) return
        if (action==RecordingService.ACTION_STOP && !restrictions.state.animationAllowed) return
        runCatching { startService(Intent(this,RecordingService::class.java).setAction(action).putExtra(RecordingService.EXTRA_FOR_GLEO,forGleo)) }
            .onFailure { pendingGleo=false;toast("녹음 상태를 변경하지 못했어요.") }
    }
    private fun gleo() {
        if (!GleoLauncher.isAvailable(this)) { toast("이 기기에서 Gleo를 찾을 수 없어요.");return }
        pendingGleo=true;command(RecordingService.ACTION_PAUSE,true)
    }
    private fun home() {
        if (restrictions.state.animationAllowed) open("home")
        else {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
    private fun open(destination: String, completed: Boolean=false) {
        if (!visible || leaving || !restrictions.state.animationAllowed) return
        leaving=true
        startActivity(Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("tikiDestination",destination).putExtra("resetLibrary",destination=="library")
            .putExtra("recordingFinished",completed))
        finish()
    }
    private fun render() {
        if (root.width==0) return
        val density=resources.displayMetrics.density
        val width=(root.width-root.paddingLeft-root.paddingRight)/density
        val height=(root.height-root.paddingTop-root.paddingBottom)/density
        root.removeAllViews();destinations.clear()
        fun put(parent: FrameLayout,view: View,x: Float,y: Float,w: Float,h: Float) {
            parent.addView(view,FrameLayout.LayoutParams(dp(w),dp(h)).apply { leftMargin=dp(x);topMargin=dp(y) })
        }
        val nav=FrameLayout(this)
        put(root,nav,0f,0f,130f,height)
        put(nav,art.image("home-wide","imgTikiTakaHomeLatestVerticalLogo","티키타카"),37.3f,51f,58.8f,60f)
        val first=if (height>=1100) 356f else (height*.3f).coerceAtLeast(170f)
        val gap=minOf(182f,((height-230-first)/2).coerceAtLeast(100f))
        listOf(Triple("home","홈","imgTikiTakaCurrentLineIconHome"),Triple("library","보관함","imgTikiTakaCurrentLineIconLibrary"),Triple("character","캐릭터","imgFrame"))
            .forEachIndexed { i,(destination,label,key) ->
                val item=FrameLayout(this).apply {
                    contentDescription=label;isFocusable=true;tag="nav_$destination"
                    background=TikiDesign.press(this@RecordingActivity,TikiDesign.background,16f)
                    setOnClickListener { if (destination=="home") home() else open(destination) }
                }
                put(item,art.image("home-wide",key),24f,6f,32f,32f)
                put(item,art.text(label,16f,if (destination=="home") 600 else 500,0xff595959.toInt()).apply { gravity=Gravity.CENTER },0f,42f,80f,24f)
                put(nav,item,26.7f,first+i*gap,80f,72f)
                if (destination!="home") destinations.add(item)
            }
        val settings=FrameLayout(this).apply { contentDescription="설정";tag="nav_settings";isFocusable=true;setOnClickListener { open("settings") } }
        put(settings,art.image("consent-wide","imgFrame"),25.28f,9.28f,29.44f,29.44f)
        put(nav,settings,26.7f,height-98,80f,72f);destinations.add(settings)
        val character=TikiProfile.characters.indexOf(TikiProfile.get(this).characterId).coerceAtLeast(0)
        screen=TikiRecordingScreen(this,art,character,false,::home,{}, {},
            onPause={ command(if (RecordingStore.state.phase==RecordingPhase.PAUSED) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE) },
            onFinish={ command(RecordingService.ACTION_STOP) },onGleo=::gleo)
        put(root,screen!!,131f,0f,width-131,height)
        update(RecordingStore.state)
    }
}
