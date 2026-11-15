package com.example.hd_project.tiki

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.hd_project.MainActivity
import com.example.hd_project.pleos.DrivingRestrictions
import com.example.hd_project.pleos.applyPleosInsets

/** O-04 contains only a static notice, system home and a parked-only return to setup. */
class SetupPauseActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var art: TikiArt
    private var visible=false
    private var leaving=false
    private var firstFrame=true
    private val restrictions by lazy { DrivingRestrictions(this) { if(visible)render() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);art=TikiArt(this)
        root=FrameLayout(this).apply { setBackgroundColor(TikiDesign.background) }
        setContentView(root);root.applyPleosInsets()
        root.renderBeforeFirstDraw { firstFrame=false;root.afterNextFrame { closeParkedTaskSoon() } }
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) { override fun handleOnBackPressed()=home() })
        render()
    }
    override fun onStart() {
        super.onStart();visible=true;restrictions.start()
        if(!firstFrame)closeParkedTaskSoon()
    }
    // Reopened while it was still leaving: stay, and close the screen it was leaving for.
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);leaving=false;LeavingScreen.clear(this);closeParkedTaskSoon() }
    private fun closeParkedTaskSoon() { root.postDelayed({ if(visible && !leaving)closeParkedTask() },TASK_SWITCH_MS) }
    override fun onStop() { visible=false;restrictions.stop();if(leaving)finish();super.onStop() }
    override fun onDestroy() { art.clear();super.onDestroy() }
    private fun home() {
        if(restrictions.state.animationAllowed) {
            startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tikiDestination","home"))
            leave()
        } else { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));finish() }
    }
    /** Stays until the next screen is shown (it finishes this one); finishing at once would show an empty window in between. */
    private fun leave() { leaving=true;LeavingScreen.set(this);root.postDelayed({ if(leaving)finish() },1500) }
    private fun render() {
        if(!::root.isInitialized)return
        root.removeAllViews()
        val character=getSharedPreferences("tikitaka-ui",MODE_PRIVATE).getInt("setupCharacter",3).coerceIn(0,7)
        root.addView(TikiFlowScreens.setupPaused(this,art,character,restrictions.state.animationAllowed,::home) {
            if(restrictions.state.animationAllowed) {
                startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("resumeSetup",true))
                leave()
            }
        },FrameLayout.LayoutParams(-1,-1))
    }
}
