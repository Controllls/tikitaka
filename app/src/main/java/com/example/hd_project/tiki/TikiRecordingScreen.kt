package com.example.hd_project.tiki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.hd_project.RecordingPhase
import com.example.hd_project.RecordingState
import com.example.hd_project.RecordingLocation
import com.example.hd_project.pleos.DrivingRestrictions
import java.util.Locale
import kotlin.math.min

/** Figma 04 UI / 03: R-00, R-01/04, R-02/02P, R-03/03P (2026-11-12 export).
 * Text and controls reflow in the allocated window; the supplied character alone scales.
 */
@android.annotation.SuppressLint("ViewConstructor")
class TikiRecordingScreen(
    context: Context,
    private val art: TikiArt,
    character: Int,
    recordOnly: Boolean,
    onBack: () -> Unit,
    onRecordOnly: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    onGleo: () -> Unit,
) : ScrollView(context) {
    private fun dp(value: Float) = TikiDesign.dp(context, value)
    private val content = FrameLayout(context)
    private var recording = RecordingState()
    private var vehicle = DrivingRestrictions.State()
    private var initialized = false
    private val muted = 0xff5d5d5d.toInt()
    private fun label(value: String, size: Float = 24f, weight: Int = 400, color: Int = TikiDesign.ink) =
        art.text(value, size, weight, color).apply { paint.isFakeBoldText = false }
    private val back = FrameLayout(context).apply {
        tag = "record_back"; contentDescription = "홈으로"; isFocusable = true
        background = TikiDesign.press(context, android.graphics.Color.WHITE, 32f)
        setOnClickListener { onBack() }
        addView(art.image("record-active", "imgFrame2"), FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
    }
    private val title = label("대화 녹음", 36f, 600).apply { isAccessibilityHeading = true }
    private val subtitle = label("", color = muted).apply { tag = "record_vehicle"; setLineHeight(dp(32f)) }
    private val avatar = art.character(character).apply { tag = "record_character" }
    private val status = label("", weight = 500).apply { tag = "record_status"; gravity = Gravity.CENTER }
    val timer = label("00:00:00", 104f, 500).apply {
        tag = "record_timer"; gravity = Gravity.CENTER; isFallbackLineSpacing = false; setSingleLine(true)
    }
    private val waveform = RecordingWaveform(context).apply { tag = "record_wave" }
    private val caption = label("", color = muted).apply { tag = "record_caption"; gravity = Gravity.CENTER; setLineHeight(dp(32f)) }
    private val locationNotice = label("위치 정보를 이용할 수 없어요\n대화는 계속 녹음돼요. 이번 기록에는 이동 정보가 빠질 수 있어요.",20f).apply {
        tag="record_location_notice";gravity=Gravity.CENTER;setLineHeight(dp(28f));setPadding(dp(16f),dp(16f),dp(16f),dp(16f))
        background=TikiDesign.shape(android.graphics.Color.WHITE,20f,context=context)
    }
    private val readyTitle = label("함께 이야기할 준비가 됐나요?").apply { gravity = Gravity.CENTER; setLineHeight(dp(32f)) }
    private val readyHint = label("", color = muted).apply { gravity = Gravity.CENTER; setLineHeight(dp(32f)) }
    private val only = CheckBox(context).apply {
        text = "이번에는 녹음만 보관"; typeface = art.sans; setTextColor(TikiDesign.ink)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24f)
        buttonTintList = android.content.res.ColorStateList.valueOf(TikiDesign.ink)
        isChecked = recordOnly; tag = "record_only"
        setOnCheckedChangeListener { _, value -> onRecordOnly(value); updateHint(); requestLayout() }
    }
    val primary = art.button("대화 녹음 시작") {
        if (recording.busy) onPause() else onStart()
    }.apply { tag = "record_confirm" }
    val finish = art.button("녹음 종료", false, onFinish).apply { tag = "record_finish" }
    val gleo = art.button("Gleo 사용하기", false, onGleo).apply { tag = "gleo" }

    init {
        tag = "recording_screen"; isFillViewport = true; isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listOf(back, title, subtitle, avatar, status, timer, waveform, caption, locationNotice, readyTitle, readyHint, only, primary, finish, gleo)
            .forEach { content.addView(it) }
        listOf(primary, finish, gleo).forEach {
            it.paint.isFakeBoldText = false; it.setLineHeight(dp(32f)); it.setPadding(dp(16f), 0, dp(16f), 0)
            it.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.Button::class.java.name
                }
            }
        }
        updateHint()
        update(RecordingState(), DrivingRestrictions.State())
    }

    private fun updateHint() {
        readyHint.text = if (only.isChecked) "이번 녹음은 보관함에만 저장해요." else "녹음이 끝나면 영상으로 만들어드려요."
    }

    fun update(state: RecordingState, driving: DrivingRestrictions.State) {
        val changed = !initialized || recording.phase != state.phase || recording.pausedForGleo != state.pausedForGleo || recording.location != state.location || vehicle != driving
        initialized = true
        recording = state; vehicle = driving
        val seconds = state.durationMs().coerceAtLeast(0) / 1000
        val value = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        if (timer.text.toString() != value) timer.text = value
        waveform.update(state.level, state.phase == RecordingPhase.RECORDING, driving.animationAllowed)
        if (!changed) return
        val active = state.busy
        val paused = state.phase == RecordingPhase.PAUSED
        subtitle.text = when {
            !active -> "오늘 나눈 대화를 한 편의 영상으로 남겨요."
            !driving.available -> "차량 상태를 확인하고 있어요. 녹음은 유지돼요."
            driving.animationAllowed -> "주차 중이에요. 녹음을 마칠 수 있어요."
            else -> "주행 중이에요. 녹음 종료는 주차 후에 할 수 있어요."
        }
        status.text = when (state.phase) {
            RecordingPhase.PAUSED -> "일시정지"
            RecordingPhase.STARTING -> "녹음을 준비하고 있어요"
            RecordingPhase.STOPPING -> "녹음을 저장하고 있어요"
            else -> "녹음 중"
        }
        caption.text = when {
            paused && state.pausedForGleo -> "Gleo 사용으로 녹음을 잠시 멈췄어요."
            paused -> "새로운 대화는 녹음되지 않아요."
            else -> "다른 앱을 사용해도 녹음은 계속돼요."
        }
        listOf(status, timer, waveform, caption).forEach { it.visibility = if (active) VISIBLE else GONE }
        listOf(readyTitle, readyHint, only).forEach { it.visibility = if (active) GONE else VISIBLE }
        locationNotice.visibility=if(active && state.location==RecordingLocation.UNAVAILABLE)VISIBLE else GONE
        finish.visibility = if (active && driving.animationAllowed) VISIBLE else GONE
        gleo.visibility = if (active) VISIBLE else GONE
        primary.text = if (!active) "대화 녹음 시작" else if (paused) "녹음 이어하기" else "일시정지"
        val actionable = !active || state.phase in listOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED)
        listOf(primary, finish, gleo).forEach { it.isEnabled = actionable; it.alpha = if (actionable) 1f else .4f }
        if (changed) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val width = MeasureSpec.getSize(widthMeasureSpec) / density
        val height = MeasureSpec.getSize(heightMeasureSpec) / density
        val narrow = width < 900f
        val compact = narrow && height < 1000f || locationNotice.visibility==VISIBLE && height<900f
        val inset = if (narrow) 32f else 48f
        val available = (width - inset * 2).coerceAtLeast(1f)
        fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
            view.layoutParams = FrameLayout.LayoutParams(dp(w), dp(h)).apply { leftMargin = dp(x); topMargin = dp(y) }
        }
        fun textHeight(view: TextView, w: Float, minimum: Float = 32f): Float {
            view.measure(MeasureSpec.makeMeasureSpec(dp(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return maxOf(minimum, view.measuredHeight / density)
        }
        val top = if (compact) 24f else 48f
        val subtitleHeight = textHeight(subtitle, available - 88)
        val headerHeight = 60f + subtitleHeight
        place(back, inset, top + (headerHeight - 64) / 2, 64f, 64f)
        place(title, inset + 88, top, available - 88, 48f)
        place(subtitle, inset + 88, top + 60, available - 88, subtitleHeight)

        val active = recording.busy
        val hasFinish = finish.visibility == VISIBLE
        val gap = if (compact) 16f else 24f
        val buttonHeight = if (compact) 64f else 88f
        val buttonCount = if (!active) 1 else if (hasFinish) 3 else 2
        val minButton = listOf(primary, gleo, finish).filter { it.visibility == VISIBLE }
            .maxOf { it.paint.measureText(it.text.toString()) / density + 32f }
        val twoRows = active && hasFinish && (available - 2 * gap) / 3 < minButton
        val actionsHeight = if (twoRows) buttonHeight * 2 + gap else buttonHeight
        val blockGap = if (compact) 16f else 32f
        val bottom = if (height >= 1000f) 160f else if (compact) 16f else 32f
        val bodyTop = top + headerHeight + blockGap
        val avatarSize = if (!active) min(if (compact) 180f else 360f, available)
            else if (narrow) if (compact) 96f else 220f else min(if(compact)280f else 430f, available * .34f)
        val statusWidth = if (narrow) available else min(736f, available - avatarSize - 64f - min(100f, available * .075f))
        val timerSize = if (compact) 64f else if (narrow) 88f else min(104f, statusWidth / 5.1f)
        timer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, timerSize)
        // Asta Sans Android ascent/descent is four pixels taller than the 104/120 Figma line box.
        // Preserve the design's type size while reserving its native font bounds.
        val timerHeight = maxOf(if (compact) 80f else if (narrow) 104f else 120f,
            timer.paint.fontMetricsInt.let { (it.descent-it.ascent)/density })
        timer.setLineHeight(dp(timerHeight))
        val waveHeight = if (compact) 48f else 128f
        val statusGap = if (compact) 12f else if (narrow) 16f else 32f
        val captionHeight = textHeight(caption, statusWidth)
        val infoHeight = 32 + timerHeight + waveHeight + captionHeight + statusGap * 3
        val readyTitleHeight = textHeight(readyTitle, available)
        val readyHintHeight = textHeight(readyHint, available)
        val mainGroupHeight = if (!active) avatarSize + 16 + readyTitleHeight + 16 + readyHintHeight + 16 + 64
            else if (narrow) avatarSize + (if (compact) 16 else 24) + infoHeight else maxOf(avatarSize, infoHeight)
        val noticeHeight=if(locationNotice.visibility==VISIBLE)textHeight(locationNotice,available) else 0f
        val groupHeight=mainGroupHeight+if(noticeHeight>0)noticeHeight+16 else 0f
        val total = maxOf(height, bodyTop + groupHeight + blockGap + actionsHeight + bottom)
        val actionY = total - bottom - actionsHeight
        val groupY = bodyTop + (actionY - blockGap - bodyTop - groupHeight) / 2
        if (active) {
            val rowWidth = if (narrow) available else min(1330f, available)
            val rowX = (width - rowWidth) / 2
            val infoY = if (narrow) groupY + avatarSize + (if (compact) 16 else 24) else groupY + (mainGroupHeight - infoHeight) / 2
            place(avatar, if (narrow) (width-avatarSize)/2 else rowX+statusWidth+64, groupY, avatarSize, avatarSize)
            place(status, rowX, infoY, statusWidth, 32f)
            place(timer, rowX, infoY + 32 + statusGap, statusWidth, timerHeight)
            val waveWidth = min(520f, statusWidth)
            val waveY = infoY + 32 + timerHeight + statusGap * 2
            place(waveform, rowX + (statusWidth-waveWidth)/2, waveY, waveWidth, waveHeight)
            place(caption, rowX, waveY + waveHeight + statusGap, statusWidth, captionHeight)
            if(noticeHeight>0)place(locationNotice,inset,groupY+mainGroupHeight+16,available,noticeHeight)
        } else {
            place(avatar, (width-avatarSize)/2, groupY, avatarSize, avatarSize)
            val readyY = groupY + avatarSize + 16
            place(readyTitle, inset, readyY, available, readyTitleHeight)
            place(readyHint, inset, readyY + readyTitleHeight + 16, available, readyHintHeight)
            val checkWidth = min(420f, available)
            place(only, (width-checkWidth)/2, readyY+readyTitleHeight+readyHintHeight+32, checkWidth, 64f)
        }
        if (!active) {
            val bw = min(256f, available); place(primary, (width-bw)/2, actionY, bw, buttonHeight)
        } else if (twoRows) {
            val bw = min(340f, available); place(primary, (width-bw)/2, actionY, bw, buttonHeight)
            val secondaryWidth = (available-gap)/2
            place(finish, inset, actionY+buttonHeight+gap, secondaryWidth, buttonHeight)
            place(gleo, inset+secondaryWidth+gap, actionY+buttonHeight+gap, secondaryWidth, buttonHeight)
        } else {
            val widths = if (hasFinish && narrow) List(3) { (available-gap*2)/3 }
                else if (hasFinish) listOf(340f,256f,256f)
                else if (available >= 620) listOf(340f,256f) else List(2) { (available-gap)/2 }
            val buttons = if (hasFinish) listOf(primary,finish,gleo) else listOf(primary,gleo)
            var x = (width-widths.sum()-gap*(buttonCount-1))/2
            buttons.forEachIndexed { i, v -> place(v,x,actionY,widths[i],buttonHeight);x+=widths[i]+gap }
        }
        content.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(total))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** The original 20 Figma bars. Driving freezes the indicator; no decorative motion. */
private class RecordingWaveform(context: Context) : View(context) {
    private val bars = floatArrayOf(20f,32f,52f,76f,42f,92f,112f,68f,42f,84f,128f,92f,60f,108f,76f,44f,68f,32f,52f,24f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TikiDesign.ink }
    private var amplitude = 1f
    private var recording = false
    fun update(level: Float, active: Boolean, animated: Boolean) {
        val next = if (active && animated) .25f + level.coerceIn(0f,1f)*.75f else 1f
        if (amplitude != next || recording != active) { amplitude=next;recording=active;invalidate() }
    }
    override fun onDraw(canvas: Canvas) {
        val scale = min(width/520f,height/128f)
        val left = (width-390*scale)/2
        paint.alpha = if (recording) 255 else 90
        bars.forEachIndexed { i, bar ->
            val h = if (recording) bar*scale*amplitude else 10*scale
            canvas.drawRoundRect(left+i*20*scale,(height-h)/2,left+(i*20+10)*scale,(height+h)/2,5*scale,5*scale,paint)
        }
    }
}
