package com.example.hd_project

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveLayoutTest {
    @Test
    fun narrowWindowsInflateHomeAndRecordings() {
        for (widthDp in listOf(841, 899)) checkLayouts(widthDp, View.GONE)
    }

    @Test
    fun wideWindowsInflateHomeAndRecordings() {
        for (widthDp in listOf(900, 1682)) checkLayouts(widthDp, View.VISIBLE)
    }

    private fun checkLayouts(widthDp: Int, footerVisibility: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var outcome: Result<Unit>? = null
        instrumentation.runOnMainSync {
            outcome = runCatching {
                val base = instrumentation.targetContext
                val configuration = Configuration(base.resources.configuration).apply {
                    screenWidthDp = widthDp
                    screenHeightDp = 1210
                    smallestScreenWidthDp = minOf(widthDp, screenHeightDp)
                    orientation = if (widthDp < screenHeightDp) Configuration.ORIENTATION_PORTRAIT
                        else Configuration.ORIENTATION_LANDSCAPE
                }
                val context = ContextThemeWrapper(
                    base.createConfigurationContext(configuration), R.style.Theme_HD_project
                )
                val inflater = LayoutInflater.from(context)
                val home = inflater.inflate(R.layout.activity_main, null)
                assertEquals(footerVisibility, home.findViewById<View>(R.id.footer_caption).visibility)
                val recordings = inflater.inflate(R.layout.activity_recordings, null)
                assertNotNull(recordings.findViewById<View>(R.id.recording_rows))
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
}
