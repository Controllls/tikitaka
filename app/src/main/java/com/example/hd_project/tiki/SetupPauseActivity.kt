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
    private val restrictions by lazy { DrivingRestrictions(this) { if(visible)render() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);art=TikiArt(this)
        root=FrameLayout(this).apply { setBackgroundColor(TikiDesign.background) }
        setContentView(root);root.applyPleosInsets()
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) { override fun handleOnBackPressed()=home() })
        render()
    }
    override fun onStart() { super.onStart();visible=true;restrictions.start() }
    override fun onStop() { visible=false;restrictions.stop();super.onStop() }
    override fun onDestroy() { art.clear();super.onDestroy() }
    private fun home() {
        if(restrictions.state.animationAllowed) {
            startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tikiDestination","home"))
        } else startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    private fun render() {
        if(!::root.isInitialized)return
        root.removeAllViews()
        val character=getSharedPreferences("tikitaka-ui",MODE_PRIVATE).getInt("setupCharacter",3).coerceIn(0,7)
        root.addView(TikiFlowScreens.setupPaused(this,art,character,restrictions.state.animationAllowed,::home) {
            if(restrictions.state.animationAllowed) {
                startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("resumeSetup",true))
                finish()
            }
        },FrameLayout.LayoutParams(-1,-1))
    }
}
