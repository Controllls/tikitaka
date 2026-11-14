package com.example.hd_project.tiki

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.*
import com.example.hd_project.pleos.DrivingRestrictions
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MissingUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun isolated() { check(context.packageName.endsWith(".verification")) { "Never run against the user's app" } }
    private fun field(a: TikiActivity,name: String)=TikiActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun route(a: TikiActivity)=field(a,"route") as String
    private fun go(a: TikiActivity,route: String)=TikiActivity::class.java.getDeclaredMethod("go",String::class.java).apply { isAccessible=true }.invoke(a,route)
    private fun root(a: TikiActivity)=a.findViewById<ViewGroup>(android.R.id.content)
    private fun button(a: TikiActivity,tag: String)=requireNotNull(root(a).findViewWithTag<View>(tag))
    private fun waitFor(s: ActivityScenario<MainActivity>, condition: (MainActivity)->Boolean) {
        val end=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<end) { var done=false;s.onActivity { done=condition(it) };if(done)return;SystemClock.sleep(100) }
        s.onActivity { assertTrue("Timed out at ${route(it)}",condition(it)) }
    }
    private fun findText(view: View,text: String): TextView? {
        if(view is TextView && view.text.toString()==text)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findText(view.getChildAt(i),text)?.let { return it }
        return null
    }
    @Test fun onboardingPermissionChoiceDraftRecoveryAndRealSaveFailureRetry() {
        isolated();check(!RecordingStore.state.busy)
        val prefs=context.getSharedPreferences("tikitaka-ui",0)
        prefs.edit().clear().commit();context.getSharedPreferences("tikitaka-location",0).edit().clear().commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val storage=File(context.noBackupFilesDir,"tikitaka-profile")
        check(!storage.exists()) { "Use a fresh isolated install for this persistence failure test" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { route(it)=="welcome" && root(it).width>0 }
            scenario.onActivity { requireNotNull(findText(root(it),"시작하기")).performClick() }
            waitFor(scenario) { root(it).findViewWithTag<View>("page_A-01")!=null }
            scenario.onActivity { button(it,"flow_primary").performClick() }
            waitFor(scenario) { route(it)=="microphone_intro" }
            scenario.onActivity { button(it,"flow_secondary").performClick() }
            waitFor(scenario) { route(it)=="microphone" }
            scenario.onActivity { requireNotNull(findText(root(it),"나중에")).performClick() }
            waitFor(scenario) { route(it)=="location" }
            assertFalse(JourneyPreferences.decided(context))
            waitFor(scenario) {
                (TikiActivity::class.java.getDeclaredMethod("getRestrictions").apply { isAccessible=true }.invoke(it) as DrivingRestrictions).state.animationAllowed
            }
            scenario.onActivity { button(it,"flow_secondary").performClick() }
            waitFor(scenario) { route(it)=="profile" }
            assertTrue(JourneyPreferences.decided(context));assertFalse(JourneyPreferences.enabled(context))
            scenario.onActivity { (button(it,"profile_name") as EditText).setText("검증 운전자") }
            scenario.recreate()
            waitFor(scenario) { route(it)=="profile" && root(it).findViewWithTag<View>("profile_name")!=null }
            scenario.onActivity { assertEquals("검증 운전자",(button(it,"profile_name") as EditText).text.toString()) }
            storage.writeText("Deliberate isolated-app storage failure")
            try {
                waitFor(scenario) {
                    (TikiActivity::class.java.getDeclaredMethod("getRestrictions").apply { isAccessible=true }.invoke(it) as DrivingRestrictions).state.animationAllowed
                }
                scenario.onActivity { button(it,"profile_save").performClick() }
                waitFor(scenario) { route(it)=="profile_error" }
                scenario.recreate()
                waitFor(scenario) { root(it).findViewWithTag<View>("page_O-03")!=null }
                assertTrue(prefs.getBoolean("profileSavePending",false))
                assertEquals("검증 운전자",prefs.getString("pendingProfileName",""))
            } finally { check(storage.isFile);storage.delete() }
            waitFor(scenario) {
                (TikiActivity::class.java.getDeclaredMethod("getRestrictions").apply { isAccessible=true }.invoke(it) as DrivingRestrictions).state.animationAllowed
            }
            scenario.onActivity { button(it,"flow_primary").performClick() }
            waitFor(scenario) { route(it)=="voice" }
            assertTrue(prefs.getBoolean("setup",false));assertFalse(prefs.getBoolean("profileSavePending",true))
            assertEquals("검증 운전자",TikiProfile.get(context).name)
        }
    }
    @Test fun everyRequestedStateFitsTwelvePleosSizes() {
        isolated()
        val presets=listOf(1700 to 1184,840 to 1184,1360 to 948,672 to 948,1274 to 855,628 to 855,
            1640 to 810,800 to 810,1640 to 1400,800 to 1400,1256 to 800,648 to 800)
        instrumentation.runOnMainSync {
            val art=TikiArt(context)
            try { for((width,height) in presets) {
                val variants=listOf(
                    "A-01" to TikiFlowScreens.permissions(context,art,{},{}),
                    "A-02" to TikiFlowScreens.microphone(context,art,{},{},{}),
                    "A-03" to TikiFlowScreens.location(context,art,{},{}),
                    "O-03" to TikiFlowScreens.profileError(context,art,3,false,{},{}),
                    "O-04" to TikiFlowScreens.setupPaused(context,art,3,false,{},{}),
                    "G-02" to TikiFlowScreens.connection(context,art,false,{},true,{}),
                    "E-01" to EpisodeDetails(File(context.filesDir,"layout-preview.mp4"),"집으로 가는 길, 끝나지 않는 이야기",2,1794524400000,30000,null).screen(context,art,{},{}))
                for((name,view) in variants) {
                    measure(view,if(name.startsWith("G-") || name.startsWith("E-"))width-130 else width,height)
                    verify(view,"$name $width x $height")
                    if(name=="O-04")assertFalse(view.findViewWithTag<View>("flow_primary").isEnabled)
                    if(width in listOf(1700,840) && height==1184)capture(view,"$name-$width")
                }
                val recording=TikiRecordingScreen(context,art,3,false,{},{},{},{},{},{})
                val state=RecordingState(RecordingPhase.RECORDING,SystemClock.elapsedRealtime()-255000,location=RecordingLocation.UNAVAILABLE)
                recording.update(state,DrivingRestrictions.State(true,true));measure(recording,width-131,height)
                verify(recording,"R-05 $width x $height")
                assertEquals(View.VISIBLE,recording.findViewWithTag<View>("record_location_notice").visibility)
                assertEquals(View.GONE,recording.finish.visibility)
                if(width in listOf(1700,840) && height==1184)capture(recording,"R-05-$width")
                recording.update(state.copy(location=RecordingLocation.AVAILABLE),DrivingRestrictions.State(true,true))
                assertEquals(View.GONE,recording.findViewWithTag<View>("record_location_notice").visibility)
            } } finally { art.clear() }
        }
    }
    private fun measure(view: View,w: Int,h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(TikiDesign.dp(context,w.toFloat()),View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(TikiDesign.dp(context,h.toFloat()),View.MeasureSpec.EXACTLY))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        view.viewTreeObserver.dispatchOnPreDraw()
        view.findViewWithTag<TextView>("record_timer")?.viewTreeObserver?.dispatchOnPreDraw()
    }
    private fun verify(view: View,label: String) {
        if(view.visibility!=View.VISIBLE)return
        if(view is TextView) {
            val layout=view.layout ?: return
            assertTrue("$label text height ${view.text}",layout.height<=view.height-view.paddingTop-view.paddingBottom)
            for(i in 0 until layout.lineCount)assertTrue("$label text width ${view.text}",layout.getLineMax(i)<=view.width-view.paddingLeft-view.paddingRight+1)
        }
        if(view is ViewGroup)for(i in 0 until view.childCount) {
            val child=view.getChildAt(i);if(child.visibility!=View.VISIBLE)continue
            assertTrue("$label horizontal bounds ${child.tag}",child.left>=0 && child.right<=view.width)
            if(view !is ScrollView)assertTrue("$label vertical bounds ${child.tag}",child.top>=0 && child.bottom<=view.height)
            verify(child,label)
        }
    }
    private fun capture(view: View,name: String) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).let { it.drawColor(TikiDesign.background);view.draw(it) }
        File(context.cacheDir,"missing-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun episodeDetailsUseSelectedFileAndNeverInventTripData() {
        isolated()
        val first=File(AnimationFiles.directory(context),"ui-detail-first.mp4")
        val second=File(AnimationFiles.directory(context),"ui-detail-second.mp4")
        for(file in listOf(first,second))instrumentation.context.assets.open("pleos-playback.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        File(first.path+".json").writeText(JSONObject().put("title","첫 에피소드").put("recordedAt",1794524400000).toString())
        val trip=JourneySummary(true,30000,20000,1200.0)
        File(second.path+".json").writeText(JSONObject().put("title","다른 에피소드").put("journey",JSONObject(NavigationBackgroundPolicy.json.encodeToString(trip))).toString())
        val a=EpisodeDetails.load(context,first.name);val b=EpisodeDetails.load(context,second.name)
        assertNull(a.journey);assertEquals(trip,b.journey);assertNotEquals(a.number,b.number);assertTrue(a.durationMs>0)
        assertEquals("첫 에피소드",a.title);assertEquals("다른 에피소드",b.title)
        context.getSharedPreferences("tikitaka-ui",0).edit().putBoolean("setup",true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { root(it).width>0 }
            scenario.onActivity {
                TikiActivity::class.java.getDeclaredField("movie").apply { isAccessible=true }.set(it,second.name);go(it,"detail")
            }
            waitFor(scenario) { root(it).findViewWithTag<View>("page_E-01")!=null }
            scenario.onActivity { assertNotNull(findText(root(it),"다른 에피소드"));assertNull(field(it,"video")) }
            scenario.recreate()
            waitFor(scenario) { root(it).findViewWithTag<View>("page_E-01")!=null }
            scenario.onActivity { assertNotNull(findText(root(it),"다른 에피소드"));assertNull(field(it,"video")) }
        }
    }
}
