package com.example.hd_project.tiki

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DrivingPauseLayoutTest {
    @Test fun figmaPauseScreenFitsWideNarrowAndCompactWindows() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val presets=listOf(1700 to 1184,840 to 1184,1360 to 948,672 to 948,1274 to 855,628 to 855,
            1640 to 810,800 to 810,1640 to 1400,800 to 1400,1256 to 800,648 to 800)
        instrumentation.runOnMainSync {
            val art=TikiArt(context)
            try { presets.forEach { (windowWidth,height) ->
                val width=windowWidth-131
                val density=context.resources.displayMetrics.density
                fun dp(v: Int)=(v*density+.5f).toInt()
                var resumed=0;var home=0
                val view=TikiDrivingPauseScreen(context,art,"집으로 가는 길, 끝나지 않는 이야기",{}, { home++ }, { resumed++ })
                view.update(true,false,true,false)
                view.measure(View.MeasureSpec.makeMeasureSpec(dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(height),View.MeasureSpec.EXACTLY))
                view.layout(0,0,view.measuredWidth,view.measuredHeight)
                val content=view.getChildAt(0) as android.view.ViewGroup
                for(i in 0 until content.childCount) {
                    val child=content.getChildAt(i)
                    assertTrue("$windowWidth x $height: horizontal clipping",child.left>=0 && child.right<=content.width)
                    assertTrue("$windowWidth x $height: vertical clipping",child.top>=0 && child.bottom<=content.height)
                    if(child is TextView)assertTrue("Text clipped: ${child.text}",child.layout.height<=child.height)
                }
                val icon=view.findViewWithTag<View>("driving_lock")
                assertEquals(dp(112),icon.width);assertEquals(dp(112),icon.height)
                assertFalse(view.resume.isEnabled)
                view.findViewWithTag<View>("driving_home").performClick();assertEquals(1,home)
                if(height==1184) {
                    assertEquals(dp(548),icon.top)
                    assertEquals(dp(936),view.findViewWithTag<View>("driving_home").top)
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val out=File(context.cacheDir,"driving-pause-$windowWidth.png")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                view.update(true,true,true,false);assertTrue(view.resume.isEnabled)
                view.resume.performClick();assertEquals(1,resumed)
                view.update(true,true,true,true);assertFalse(view.resume.isEnabled)
                view.update(false,false,true,false);assertFalse(view.resume.isEnabled)
            } } finally { art.clear() }
        }
    }
}
