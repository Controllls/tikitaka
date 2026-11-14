package com.example.hd_project.tiki

import android.car.drivingstate.CarUxRestrictions
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.hd_project.MainActivity
import com.example.hd_project.pleos.DrivingRestrictions
import org.junit.Assert.*
import org.junit.Test

class SetupPauseFlowTest {
    @Test fun drivingNoticePreservesDraftAndWaitsForParkedResume() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        context.getSharedPreferences("tikitaka-ui",0).edit().putBoolean("setup",true).putBoolean("profileSavePending",false).commit()
        fun restrictions(activity: Any)=activity.javaClass.let {
            val type=if(activity is TikiActivity)TikiActivity::class.java else SetupPauseActivity::class.java
            type.getDeclaredMethod("getRestrictions").apply { isAccessible=true }.invoke(activity) as DrivingRestrictions
        }
        fun state(activity: Any,driving: Boolean) {
            DrivingRestrictions::class.java.getDeclaredMethod("publish",CarUxRestrictions::class.java).apply { isAccessible=true }
                .invoke(restrictions(activity),CarUxRestrictions.Builder(driving,if(driving)CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED else 0,SystemClock.elapsedRealtimeNanos()).build())
        }
        fun waitMain(condition: ()->Boolean) {
            val until=SystemClock.elapsedRealtime()+15_000
            while(SystemClock.elapsedRealtime()<until) { var done=false;instrumentation.runOnMainSync { done=condition() };if(done)return;SystemClock.sleep(100) }
            error("Activity state did not arrive")
        }
        instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun main()=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        try {
            waitMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().any { restrictions(it).state.animationAllowed } }
            instrumentation.runOnMainSync {
                val a=main()
                TikiActivity::class.java.getDeclaredMethod("go",String::class.java).apply { isAccessible=true }.invoke(a,"profile")
                a.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("profile_name").setText("주차 후 이어갈 이름")
                TikiActivity::class.java.getDeclaredField("character").apply { isAccessible=true }.set(a,0)
                state(a,true)
            }
            var paused: SetupPauseActivity?=null
            waitMain { paused=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SetupPauseActivity>().firstOrNull();paused!=null }
            instrumentation.runOnMainSync {
                val a=requireNotNull(paused);state(a,true)
                val button=a.findViewById<View>(android.R.id.content).findViewWithTag<View>("flow_primary")
                assertFalse(button.isEnabled);button.performClick()
                assertFalse(a.isFinishing)
                state(a,false)
                val resume=a.findViewById<View>(android.R.id.content).findViewWithTag<View>("flow_primary")
                assertTrue(resume.isEnabled);resume.performClick()
            }
            waitMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().any {
                it.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("profile_name")?.text?.toString()=="주차 후 이어갈 이름"
            } }
            instrumentation.runOnMainSync { assertEquals(0,TikiActivity::class.java.getDeclaredField("character").apply { isAccessible=true }.get(main())) }
            // The setup notice can outlive MainActivity when Android reclaims its task.
            instrumentation.runOnMainSync { main().finish() }
            instrumentation.waitForIdleSync()
            instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("resumeSetup",true))
            waitMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().any {
                it.findViewById<View>(android.R.id.content).findViewWithTag<EditText>("profile_name")?.text?.toString()=="주차 후 이어갈 이름"
            } }
            instrumentation.runOnMainSync { assertEquals(0,TikiActivity::class.java.getDeclaredField("character").apply { isAccessible=true }.get(main())) }
        } finally {
            instrumentation.runOnMainSync {
                listOf(Stage.RESUMED,Stage.STARTED,Stage.STOPPED,Stage.CREATED).flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.distinct().filter { it is MainActivity || it is SetupPauseActivity }.forEach { it.finish() }
            }
        }
    }
}
