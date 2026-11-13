package com.example.hd_project.tiki

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.*
import org.junit.Assert.*
import org.junit.Test

class TikiProductionUiTest {
    @Test fun productionSettingsAndAudioActionAreReachable() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(!RecordingStore.state.busy)
        val prefs=context.getSharedPreferences("tikitaka-ui",0);val setup=prefs.getBoolean("setup",false)
        prefs.edit().putBoolean("setup",true).commit()
        val count=ProductionSettings.count(context);val seconds=ProductionSettings.seconds(context)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Pleos keeps both apps visible; ActivityScenario alone need not move window focus.
            val location=IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(location) }
            val now=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,location[0]+160f,location[1]+24f,0)
                instrumentation.uiAutomation.injectInputEvent(event,true);event.recycle()
            }
            instrumentation.waitForIdleSync()
            val deadline=android.os.SystemClock.elapsedRealtime()+8000
            var ready=false
            while(!ready && android.os.SystemClock.elapsedRealtime()<deadline) {
                scenario.onActivity { activity ->
                    val guard=TikiActivity::class.java.getDeclaredMethod("getRestrictions").apply { isAccessible=true }.invoke(activity)
                        as com.example.hd_project.pleos.DrivingRestrictions
                    ready=activity.hasWindowFocus() && guard.state.animationAllowed
                }
                if(!ready)android.os.SystemClock.sleep(100)
            }
            assertTrue("Requires a focused parked Pleos app window",ready)
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("nav_settings").performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                fun find(view: View): View? {
                    if(view.contentDescription?.startsWith("영상 제작,")==true)return view
                    if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val row=find(activity.window.decorView);assertNotNull(row);row!!.performClick()
            }
            onView(withText("영상 제작 설정")).check(matches(isDisplayed()))
            onView(withContentDescription("등장 캐릭터 수")).check(matches(isDisplayed()))
            onView(withContentDescription("하이라이트 최대 길이")).check(matches(isDisplayed()))
            onView(withText("취소")).perform(click())
            assertEquals(count,ProductionSettings.count(context));assertEquals(seconds,ProductionSettings.seconds(context))
            val original=RecordingFiles.list(context).firstOrNull()
            if(original!=null) {
                scenario.onActivity { activity ->
                    TikiActivity::class.java.getDeclaredField("source").apply { isAccessible=true }.set(activity,original.file.name)
                    TikiActivity::class.java.getDeclaredMethod("go",String::class.java).apply { isAccessible=true }.invoke(activity,"audio")
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val action=activity.window.decorView.findViewWithTag<TextView>("audio_produce")
                    assertNotNull(action);assertTrue(action.text.contains("영상 만들기"))
                }
            }
        } } finally { prefs.edit().putBoolean("setup",setup).commit() }
    }
}
