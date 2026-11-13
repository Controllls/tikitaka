package com.example.hd_project.pleos

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

/** Content breakpoint for this app's four labels; 900dp is not a Pleos platform limit. */
class NavigationBarLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private data class Original(val view: View, val params: LayoutParams, val padding: Int,
                                val minWidth: Int, val gravity: Int, val iconPadding: Int)
    private var originals: List<Original>? = null
    private var compact: Boolean? = null
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val small = MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density < 900
        if (originals == null) originals = (0 until childCount).map { index ->
            val view = getChildAt(index); val button = view as? MaterialButton
            Original(view, LayoutParams(view.layoutParams as LayoutParams), view.paddingLeft,
                view.minimumWidth, button?.iconGravity ?: 0, button?.iconPadding ?: 0)
        }
        if (compact != small) {
            compact = small
            for (original in originals.orEmpty()) {
                val button = original.view as? MaterialButton
                if (button != null) {
                    button.layoutParams = LayoutParams(original.params).apply {
                        if (small) { width=0; weight=1f; height=(72*resources.displayMetrics.density).toInt() }
                    }
                    val padding = if(small) (8*resources.displayMetrics.density).toInt() else original.padding
                    button.setPaddingRelative(padding, button.paddingTop, padding, button.paddingBottom)
                    button.minWidth = if(small) 0 else original.minWidth
                    button.minimumWidth = if(small) 0 else original.minWidth
                    button.iconGravity = if(small) MaterialButton.ICON_GRAVITY_TEXT_TOP else original.gravity
                    button.iconPadding = if(small) (4*resources.displayMetrics.density).toInt() else original.iconPadding
                } else original.view.visibility = if(small) View.GONE else View.VISIBLE
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
