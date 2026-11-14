package com.example.hd_project.tiki

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.RecordingPhase
import com.example.hd_project.RecordingState
import com.example.hd_project.pleos.DrivingRestrictions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingScreenTest {
    @Test fun recordingStatesFitPleosWindowsAndRequireManualResume() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val parked=DrivingRestrictions.State(true,false)
        val driving=DrivingRestrictions.State(true,true)
        val presets=listOf(1700 to 1184,840 to 1184,1360 to 948,672 to 948,1274 to 855,628 to 855,
            1640 to 810,800 to 810,1640 to 1400,800 to 1400,1256 to 800,648 to 800)
        instrumentation.runOnMainSync {
            val art=TikiArt(context)
            try { presets.forEach { (windowWidth,height) ->
                val width=windowWidth-131
                fun dp(n: Int)=TikiDesign.dp(context,n.toFloat())
                var resumes=0;var stops=0
                val view=TikiRecordingScreen(context,art,3,false,{}, {}, {}, { resumes++ }, { stops++ }, {})
                val active=RecordingState(RecordingPhase.RECORDING,SystemClock.elapsedRealtime()-255000,level=1f)
                fun measure() {
                    view.measure(View.MeasureSpec.makeMeasureSpec(dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(height),View.MeasureSpec.EXACTLY))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                    view.viewTreeObserver.dispatchOnPreDraw()
                    view.timer.viewTreeObserver.dispatchOnPreDraw()
                    val content=view.getChildAt(0) as ViewGroup
                    for(i in 0 until content.childCount) {
                        val child=content.getChildAt(i)
                        if(child.visibility!=View.VISIBLE)continue
                        assertTrue("$windowWidth x $height: horizontal clipping ${child.tag}",child.left>=0 && child.right<=content.width)
                        assertTrue("$windowWidth x $height: vertical clipping ${child.tag}",child.top>=0 && child.bottom<=content.height)
                        if(child is TextView) {
                            assertTrue("$windowWidth x $height: text clipped ${child.text} (${child.layout.height} > ${child.height}, ${child.lineCount} lines)",child.layout.height<=child.height)
                            for(line in 0 until child.lineCount)assertTrue("$windowWidth x $height: text too wide: ${child.text} (${child.layout.getLineMax(line)} > ${child.width-child.paddingLeft-child.paddingRight})",child.layout.getLineMax(line)<=child.width-child.paddingLeft-child.paddingRight+1)
                        }
                    }
                    if(view.findViewWithTag<View>("record_only").visibility==View.GONE)
                        assertTrue("$windowWidth x $height: recording controls require scrolling",content.height<=view.height)
                }
                view.update(RecordingState(),parked);measure()
                view.update(active,parked);measure()
                assertEquals(View.VISIBLE,view.finish.visibility)
                view.update(active,driving);measure()
                assertEquals(View.GONE,view.finish.visibility)
                assertEquals("00:04:15",view.timer.text.toString())
                if(windowWidth in listOf(1700,840) && height==1184) {
                    assertEquals(dp(936),view.primary.top)
                    val avatar=view.findViewWithTag<View>("record_character")
                    assertEquals(dp(if(windowWidth==1700)430 else 220),avatar.width)
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(bitmap);canvas.drawColor(TikiDesign.background);view.draw(canvas)
                    File(context.cacheDir,"recording-$windowWidth.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                val paused=RecordingState(RecordingPhase.PAUSED,elapsedMs=255000,pausedForGleo=true)
                view.update(paused,driving);measure()
                assertEquals("녹음 이어하기",view.primary.text.toString())
                assertTrue(view.findViewWithTag<TextView>("record_caption").text.contains("Gleo"))
                view.update(paused,parked);measure()
                assertEquals("00:04:15",view.timer.text.toString())
                assertEquals(0,resumes)
                view.primary.performClick();assertEquals(1,resumes)
                view.finish.performClick();assertEquals(1,stops)
                view.update(paused,DrivingRestrictions.State());measure()
                assertEquals(View.GONE,view.finish.visibility)
                view.update(paused.copy(phase=RecordingPhase.STOPPING),parked)
                assertFalse(view.primary.isEnabled);assertFalse(view.finish.isEnabled);assertFalse(view.gleo.isEnabled)
            } } finally { art.clear() }
        }
    }
}
