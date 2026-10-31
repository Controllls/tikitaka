package com.example.hd_project

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Amber microphone control with rings driven by the recorder's real peak amplitude. */
class RecordingButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stopBounds = RectF()
    private val primary = ContextCompat.getColor(context, R.color.sample_primary)
    private val ink = ContextCompat.getColor(context, R.color.button_text)
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)?.mutate()
        ?.apply { setTint(ink) }
    private var active = false
    private var targetLevel = 0f
    private var shownLevel = 0f
    private var face: Shader? = null
    private var halo: Shader? = null
    private var unit = 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = min(w, h) / 400f
        face = LinearGradient(w / 2f, h / 2f - 90 * unit, w / 2f, h / 2f + 90 * unit,
            intArrayOf(0xFFFFC681.toInt(), 0xFFFFAE57.toInt(), 0xFFF3913B.toInt()),
            null, Shader.TileMode.CLAMP)
        halo = RadialGradient(w / 2f, h / 2f, 196 * unit,
            intArrayOf(0x55F99439, 0x18F99439, 0x00F99439),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }

    fun show(recording: Boolean, level: Float, enabled: Boolean) {
        active = recording
        targetLevel = if (recording) level.coerceIn(0f, 1f) else 0f
        if (!recording) shownLevel = 0f
        isEnabled = enabled
        val description = context.getString(if (recording) R.string.stop_recording
            else R.string.start_recording)
        if (contentDescription != description) {
            contentDescription = description
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        shownLevel += (targetLevel - shownLevel) * 0.2f
        val time = SystemClock.uptimeMillis() / 1000.0
        val pulse = if (active) (sin(time * 2.2).toFloat() + 1f) / 2f else 0f
        paint.style = Paint.Style.FILL
        paint.shader = halo
        paint.alpha = if (active) 230 else 130
        canvas.drawCircle(cx, cy, 196 * unit, paint)
        paint.shader = null

        for (index in 1..3) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (index == 1) 1.6f else 1f) * unit
            paint.color = primary
            paint.alpha = (if (active) 48 + shownLevel * 70 else 28f).toInt()
            val radius = (101 + index * 24 + if (active) pulse * 3 + shownLevel * index * 3 else 0f) * unit
            canvas.drawCircle(cx, cy, radius, paint)
        }
        // Radial bars visualize input volume, not a fabricated frequency spectrum.
        paint.strokeWidth = 2.1f * unit
        paint.strokeCap = Paint.Cap.ROUND
        for (index in 0 until 72) {
            val angle = index * Math.PI * 2 / 72
            val shape = (sin(index * 1.8 + time * 2).toFloat() + 1) / 2
            val length = if (active) 4 + shownLevel * (6 + shape * 17) else 3f
            val inner = (147 - length / 2) * unit
            val outer = (147 + length / 2) * unit
            paint.color = primary
            paint.alpha = if (active) (65 + shownLevel * 150).toInt() else 38
            canvas.drawLine(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0B1018.toInt()
        paint.alpha = 160
        canvas.drawCircle(cx, cy + 6 * unit, 95 * unit, paint)
        paint.shader = face
        paint.alpha = if (isEnabled) 255 else 110
        canvas.drawCircle(cx, cy, (if (isPressed) 89 else 92) * unit, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * unit
        paint.color = 0xFFFFD6A5.toInt()
        paint.alpha = if (isEnabled) 210 else 80
        canvas.drawCircle(cx, cy, 91 * unit, paint)

        paint.style = Paint.Style.FILL
        paint.color = ink
        paint.alpha = 255
        if (active) {
            stopBounds.set(cx - 23 * unit, cy - 23 * unit, cx + 23 * unit, cy + 23 * unit)
            canvas.drawRoundRect(stopBounds, 7 * unit, 7 * unit, paint)
        } else {
            val half = (34 * unit).toInt()
            mic?.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            mic?.draw(canvas)
        }
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * unit
            paint.color = primary
            canvas.drawCircle(cx, cy, 103 * unit, paint)
        }
        if (active && isShown) postInvalidateDelayed(33)
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}
