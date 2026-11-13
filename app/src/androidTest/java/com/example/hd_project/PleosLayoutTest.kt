package com.example.hd_project

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** Layout measurement at app-window dp presets; not a FormFactorConsole certification. */
class PleosLayoutTest {
    private val presets = listOf(
        Triple("s17_land", 1700, 1184), Triple("s17_port", 840, 1184),
        Triple("s146_land", 1360, 948), Triple("s146_port", 672, 948),
        Triple("s129_land", 1274, 855), Triple("s129_port", 628, 855),
        Triple("wide_land", 1640, 810), Triple("wide_port", 800, 810),
        Triple("extended_land", 1640, 1400), Triple("extended_port", 800, 1400),
        Triple("waterfall_land", 1256, 800), Triple("waterfall_port", 648, 800))

    @Test fun layoutsFitAllWindowPresetsAtNativeAndScaledDensity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val errors = mutableListOf<String>()
        val output = File(context.filesDir, "pleos-layout-checks").apply { mkdirs() }
        for (dpi in listOf(160, 200)) for ((name, width, height) in presets) {
            instrumentation.runOnMainSync {
                val config = Configuration(context.resources.configuration).apply {
                    densityDpi = dpi; screenWidthDp = width; screenHeightDp = height
                    smallestScreenWidthDp = minOf(width, height)
                    orientation = if(width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_HD_project)
                val scale = themed.resources.displayMetrics.density
                for ((label, layout) in listOf("home" to R.layout.activity_main, "recordings" to R.layout.activity_recordings,
                    "highlights" to R.layout.activity_highlights, "video" to R.layout.activity_animation)) {
                    val root = LayoutInflater.from(themed).inflate(layout, null)
                    val w = (width*scale).roundToInt(); val h = (height*scale).roundToInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, w, h)
                    fun inspect(view: View, x: Int) {
                        if (view.visibility != View.VISIBLE) return
                        val left = x + view.left
                        if (view.width > 0 && (left < -1 || left + view.width > w + 1))
                            errors.add("$name/$dpi/$label: horizontal overflow ${view.javaClass.simpleName} at $left width ${view.width}/$w")
                        if (view is TextView && view.text.isNotEmpty()) view.layout?.let { lines ->
                            val available = view.width-view.compoundPaddingLeft-view.compoundPaddingRight
                            // getLineWidth includes trailing whitespace, which is
                            // allowed beyond a wrap boundary; getLineMax is visible ink.
                            if ((0 until lines.lineCount).any { lines.getLineMax(it) > available+2 || lines.getEllipsisCount(it)>0 })
                                errors.add("$name/$dpi/$label: clipped text ${view.text}")
                            if(lines.lineCount > 0 && lines.getLineBottom(lines.lineCount-1) >
                                view.height-view.compoundPaddingTop-view.compoundPaddingBottom+2)
                                errors.add("$name/$dpi/$label: vertical clipped text ${view.text}")
                        }
                        if(view is ViewGroup) for(i in 0 until view.childCount) inspect(view.getChildAt(i), left)
                    }
                    inspect(root, 0)
                    if (dpi == 160 && label == "home") {
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
        }
        File(output, "result.txt").writeText(if(errors.isEmpty()) "PASS: 12 presets x 2 densities x 4 layouts = 96 measurements" else errors.joinToString("\n"))
        assertTrue(errors.take(25).joinToString("\n"), errors.isEmpty())
    }
}
