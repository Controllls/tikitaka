package com.example.hd_project.tiki

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.hd_project.AnimationFiles
import com.example.hd_project.MainActivity
import com.example.hd_project.RecordingStore
import com.example.hd_project.pleos.DrivingRestrictions
import com.example.hd_project.pleos.applyPleosInsets
import org.json.JSONObject
import java.io.File

/** Driving-safe notice only: no video, audio player, keyboard, settings or episode browsing. */
class DrivingPauseActivity : AppCompatActivity() {
    private lateinit var art: TikiArt
    private lateinit var root: FrameLayout
    private var screen: TikiDrivingPauseScreen?=null
    private var movie=""
    private var episodeTitle=""
    private var lastSize=""
    private var visible=false
    private val destinations=mutableListOf<View>()
    internal var drivingState=DrivingRestrictions.State()
        private set
    private val restrictions by lazy { DrivingRestrictions(this) { drivingState=it;update() } }
    private val observer: (com.example.hd_project.RecordingState)->Unit={ update() }
    private fun dp(value: Float)=TikiDesign.dp(this,value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        movie=savedInstanceState?.getString("movie") ?: intent.getStringExtra("episodeMovie").orEmpty()
        val folder=AnimationFiles.directory(this).canonicalFile
        val file=File(folder,movie).canonicalFile
        if(file.parentFile!=folder || !file.isFile) { finish();return }
        episodeTitle=runCatching { JSONObject(File(file.path+".json").readText()).optString("title") }.getOrDefault("").ifBlank { "영상 감상" }
        art=TikiArt(this)
        window.decorView.setBackgroundColor(TikiDesign.background)
        root=FrameLayout(this).apply { background=TikiDesign.shape(TikiDesign.background,40f,0xffc9cacd.toInt(),this@DrivingPauseActivity);clipToOutline=true }
        setContentView(root);root.applyPleosInsets()
        root.addOnLayoutChangeListener { _,l,t,r,b,_,_,_,_ ->
            val size="${r-l}:${b-t}:${root.paddingLeft}:${root.paddingTop}:${root.paddingRight}:${root.paddingBottom}"
            if(lastSize!=size) { lastSize=size;root.post { if(!isFinishing)render() } }
        }
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(drivingState.videoAllowed)open("library") else home() }
        })
    }
    override fun onStart() { super.onStart();visible=true;restrictions.start();RecordingStore.observe(observer) }
    override fun onStop() { visible=false;RecordingStore.remove(observer);restrictions.stop();super.onStop() }
    override fun onDestroy() { if(::art.isInitialized)art.clear();super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("movie",movie);super.onSaveInstanceState(out) }

    private fun update() {
        val parked=visible && drivingState.videoAllowed
        screen?.update(drivingState.available,parked,true,RecordingStore.state.busy)
        destinations.forEach { it.isEnabled=parked;it.alpha=if(parked)1f else .4f }
    }
    private fun home() {
        if(drivingState.videoAllowed)open("home")
        else {
            // The host's home remains available while the parked-only application screens are restricted.
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
    private fun open(destination: String, resume: Boolean=false, resetLibrary: Boolean=false) {
        if(!visible || !drivingState.videoAllowed || resume && RecordingStore.state.busy)return
        val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(destination=="video")intent.putExtra("episodeMovie",movie).putExtra("resumeSelectedVideo",resume)
        else intent.putExtra("tikiDestination",destination).putExtra("resetLibrary",resetLibrary)
        startActivity(intent);finish()
    }
    private fun render() {
        val density=resources.displayMetrics.density
        val width=(root.width-root.paddingLeft-root.paddingRight)/density
        val height=(root.height-root.paddingTop-root.paddingBottom)/density
        root.removeAllViews();destinations.clear()
        fun put(parent: FrameLayout,view: View,x: Float,y: Float,w: Float,h: Float) {
            parent.addView(view,FrameLayout.LayoutParams(dp(w),dp(h)).apply { leftMargin=dp(x);topMargin=dp(y) })
        }
        val navHeight=height.coerceAtLeast(650f)
        val nav=FrameLayout(this)
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false;addView(nav,FrameLayout.LayoutParams(dp(130f),dp(navHeight))) }
        put(root,scroll,0f,0f,130f,height)
        put(nav,art.image("home-wide","imgTikiTakaHomeLatestVerticalLogo","티키타카"),37.3f,51f,58.8f,60f)
        val first=if(navHeight>=1100)356f else (navHeight*.3f).coerceAtLeast(170f)
        val gap=minOf(182f,((navHeight-230-first)/2).coerceAtLeast(100f))
        listOf(Triple("home","홈","imgTikiTakaCurrentLineIconHome"),Triple("library","보관함","imgTikiTakaCurrentLineIconLibrary"),Triple("character","캐릭터","imgFrame"))
            .forEachIndexed { i,(destination,label,key) ->
                val item=FrameLayout(this).apply {
                    contentDescription=label;isFocusable=true;tag="nav_$destination"
                    background=TikiDesign.press(this@DrivingPauseActivity,TikiDesign.background,16f)
                    setOnClickListener { if(destination=="home")home() else open(destination,resetLibrary=destination=="library") }
                }
                put(item,art.image("home-wide",key),24f,6f,32f,32f)
                put(item,art.text(label,16f).apply { gravity=Gravity.CENTER },0f,42f,80f,24f)
                put(nav,item,26.7f,first+i*gap,80f,72f)
                if(destination!="home")destinations.add(item)
            }
        val settings=FrameLayout(this).apply {
            contentDescription="설정";isFocusable=true;tag="nav_settings";setOnClickListener { open("settings") }
        }
        put(settings,art.image("consent-wide","imgFrame"),25.28f,9.28f,29.44f,29.44f)
        put(nav,settings,26.7f,navHeight-98,80f,72f);destinations.add(settings)
        screen=TikiDrivingPauseScreen(this,art,episodeTitle,
            onBack={ if(drivingState.videoAllowed)open("library") else home() },onHome={ home() },onResume={ open("video",true) })
        put(root,screen!!,131f,0f,width-131f,height)
        update()
    }
}
