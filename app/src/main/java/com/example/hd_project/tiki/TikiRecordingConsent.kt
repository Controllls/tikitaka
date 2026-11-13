package com.example.hd_project.tiki

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Figma M-01 (617:3396 / 987:33746). Measures content in the actual app window. */
@android.annotation.SuppressLint("ViewConstructor") // Created by the screen host with explicit actions, never inflated from XML.
class TikiRecordingConsent(
    context: Context,
    art: TikiArt,
    recordOnly: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) : ScrollView(context) {
    private fun dp(value: Float) = TikiDesign.dp(context, value)
    private val soft = 0xfff4f3f3.toInt()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val backLayout = LinearLayout.LayoutParams(dp(340f), dp(88f))
    private val confirmLayout = LinearLayout.LayoutParams(dp(340f), dp(88f))
    private val back = art.button("돌아가기", false, onBack).apply {
        tag = "consent_cancel"
        background = TikiDesign.press(context, soft)
    }
    private var submitted = false
    private val confirm = art.button("동의 확인 · 녹음 시작") {
        if (!submitted) {
            submitted = true
            onConfirm()
        }
    }.apply { tag = "consent_confirm" }

    init {
        tag = "recording_consent"
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val backIcon = FrameLayout(context).apply {
            tag = "consent_back"
            contentDescription = "녹음 준비로 돌아가기"
            isFocusable = true
            background = TikiDesign.press(context, Color.WHITE, 32f)
            setOnClickListener { onBack() }
            addView(art.image("consent-wide", "imgFrame2"),
                FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
        }
        header.addView(backIcon, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        val heading = art.text("녹음을 시작하기 전에", 36f, 600).apply {
            setLineHeight(dp(48f))
            minHeight = dp(48f)
            paint.isFakeBoldText = false
            isAccessibilityHeading = true
        }
        header.addView(heading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(24f)
        })
        content.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val microphone = FrameLayout(context).apply {
            tag = "consent_mic"
            background = TikiDesign.shape(soft, 56f, context = context)
            addView(art.image("consent-wide", "imgFrame3"),
                FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.CENTER))
        }
        body.addView(microphone, LinearLayout.LayoutParams(dp(112f), dp(112f)))
        body.addView(art.text("함께 탄 분들께 알려주세요", 36f, 600).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(1100f)
            minHeight = dp(48f)
            paint.isFakeBoldText = false
            setLineHeight(dp(48f))
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32f) })
        // Keep the real storage/transfer disclosure when adapting the Figma copy.
        body.addView(art.text(if (recordOnly)
            "이번 녹음은 기기에만 보관해요.\n동승자의 동의를 확인한 뒤 시작해 주세요."
        else "원음은 기기에 보관하고, 전사문·웃음 구간만 서버로 보내요.\n동승자의 동의를 확인한 뒤 시작해 주세요.", 24f, color = 0xff5d5d5d.toInt()).apply {
            tag = "consent_message"
            gravity = Gravity.CENTER
            maxWidth = dp(1080f)
            minHeight = dp(64f)
            setLineHeight(dp(32f))
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32f) })
        content.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(32f)
            bottomMargin = dp(32f)
        })
        actions.addView(back, backLayout)
        actions.addView(confirm, confirmLayout)
        content.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listOf(back, confirm).forEach {
            it.setPadding(dp(32f), 0, dp(32f), 0)
            it.setLineHeight(dp(32f))
            it.paint.isFakeBoldText = false
            it.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val horizontal = dp(if (width / density >= 900f) 48f else 32f)
        val bottom = dp(if (height / density >= 1000f) 160f else 32f)
        content.setPadding(horizontal, dp(48f), horizontal, bottom)
        val available = (width - horizontal * 2).coerceAtLeast(1)
        val gap = dp(24f)
        val required = confirm.paint.measureText(confirm.text.toString()) + dp(64f)
        val stacked = (available - gap) / 2f < required
        actions.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val buttonWidth = minOf(dp(340f), if (stacked) available else (available - gap) / 2)
        backLayout.width = buttonWidth
        confirmLayout.width = buttonWidth
        confirmLayout.topMargin = if (stacked) gap else 0
        confirmLayout.marginStart = if (stacked) 0 else gap
        confirmLayout.resolveLayoutDirection(layoutDirection)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
