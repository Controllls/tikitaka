package com.example.hd_project.pleos

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Runtime insets only: the platform already allocates the app window. */
fun View.applyPleosInsets(horizontalPadding: Int = 0) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        view.setPadding(safe.left + horizontalPadding, safe.top, safe.right + horizontalPadding, safe.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
