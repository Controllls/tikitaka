package com.example.hd_project.tiki

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.example.hd_project.R

/** E-03 / N E-03: a dedicated paused screen, retaining the selected episode and its playback position. */
class TikiDrivingPauseScreen(
    context: Context, art: TikiArt, episodeTitle: String,
    onBack: () -> Unit, onHome: () -> Unit, onResume: () -> Unit
) : ScrollView(context) {
    private fun dp(value: Float)=TikiDesign.dp(context,value)
    private val content=FrameLayout(context)
    private val back=FrameLayout(context).apply {
        contentDescription="보관함으로 돌아가기";isFocusable=true
        background=TikiDesign.press(context,Color.WHITE,32f)
        setOnClickListener { onBack() }
        addView(art.image("consent-wide","imgFrame2"),FrameLayout.LayoutParams(dp(28f),dp(28f),Gravity.CENTER))
    }
    private val episode=art.text(episodeTitle,36f,600).apply {
        tag="driving_episode_title";setLineHeight(dp(48f));maxLines=2;ellipsize=TextUtils.TruncateAt.END
        isAccessibilityHeading=true
    }
    private val icon=FrameLayout(context).apply {
        tag="driving_lock";background=TikiDesign.shape(0xfff4f3f3.toInt(),56f,context=context)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ImageView(context).apply { setImageResource(R.drawable.tiki_lock) },
            FrameLayout.LayoutParams(dp(48f),dp(48f),Gravity.CENTER))
    }
    private val title=art.text("주차 후 이어서 볼 수 있어요",36f,600).apply {
        tag="driving_pause_title";gravity=Gravity.CENTER;setLineHeight(dp(48f));isAccessibilityHeading=true
    }
    private val message=art.text("주행을 시작해 영상을 잠시 멈췄어요.\n주차하면 멈춘 곳부터 이어서 볼 수 있어요.",24f,color=0xff5d5d5d.toInt()).apply {
        tag="driving_pause_message";gravity=Gravity.CENTER;setLineHeight(dp(32f))
    }
    private val home=art.button("홈으로",false,onHome).apply {
        tag="driving_home";background=TikiDesign.press(context,0xfff4f3f3.toInt())
    }
    val resume=art.button("재생 이어하기",true,onResume).apply {
        tag="driving_resume";isEnabled=false;alpha=.4f
    }

    init {
        tag="video_driving_blocker";isFillViewport=true;isVerticalScrollBarEnabled=false
        setBackgroundColor(TikiDesign.background)
        isClickable=true;isFocusable=true
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(content,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        listOf(back,episode,icon,title,message,home,resume).forEach { content.addView(it,FrameLayout.LayoutParams(1,1)) }
        listOf(home,resume).forEach { it.setPadding(dp(32f),0,dp(32f),0);it.setLineHeight(dp(32f)) }
    }

    fun update(available: Boolean, parked: Boolean, prepared: Boolean, recording: Boolean) {
        back.contentDescription=if(parked)"보관함으로 돌아가기" else "홈으로"
        title.text=if(available)"주차 후 이어서 볼 수 있어요" else "차량 상태를 확인하고 있어요"
        message.text=when {
            !available -> "차량 상태가 확인되면\n멈춘 곳부터 이어서 볼 수 있어요."
            parked && recording -> "진행 중인 녹음을 마친 뒤\n멈춘 곳부터 이어서 볼 수 있어요."
            parked -> "주차 중이에요. 재생 이어하기를 눌러\n멈춘 곳부터 이어서 볼 수 있어요."
            else -> "주행을 시작해 영상을 잠시 멈췄어요.\n주차하면 멈춘 곳부터 이어서 볼 수 있어요."
        }
        resume.isEnabled=parked && prepared && !recording
        resume.alpha=if(resume.isEnabled)1f else .4f
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val height=MeasureSpec.getSize(heightMeasureSpec)
        val pad=dp(if(width/resources.displayMetrics.density>=900f)48f else 32f)
        val available=(width-pad*2).coerceAtLeast(1)
        fun measured(view: TextView,w: Int,minHeight: Int): Int {
            view.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            return maxOf(minHeight,view.measuredHeight)
        }
        val headerHeight=measured(episode,(available-dp(88f)).coerceAtLeast(1),dp(64f))
        val titleWidth=minOf(available,dp(1100f));val messageWidth=minOf(available,dp(1080f))
        val titleHeight=measured(title,titleWidth,dp(48f));val messageHeight=measured(message,messageWidth,dp(64f))
        val gap=dp(24f)
        val stacked=(available-gap)/2 < resume.paint.measureText(resume.text.toString())+dp(64f)
        val buttonWidth=minOf(dp(440f),if(stacked)available else (available-gap)/2)
        val actionsHeight=if(stacked)dp(200f) else dp(88f)
        val bottom=dp(if(height/resources.displayMetrics.density>=1000f)160f else 32f)
        val bodyHeight=dp(112f+32f+32f)+titleHeight+messageHeight
        val contentHeight=maxOf(height,dp(48f)+headerHeight+dp(64f)+bodyHeight+dp(100f)+actionsHeight+bottom)
        val actionsY=contentHeight-bottom-actionsHeight
        val iconY=actionsY-dp(100f)-bodyHeight
        fun place(view: View,x: Int,y: Int,w: Int,h: Int) {
            (view.layoutParams as FrameLayout.LayoutParams).apply { this.width=w;this.height=h;leftMargin=x;topMargin=y }
        }
        place(back,pad,dp(48f)+(headerHeight-dp(64f))/2,dp(64f),dp(64f))
        place(episode,pad+dp(88f),dp(48f),available-dp(88f),headerHeight)
        place(icon,(width-dp(112f))/2,iconY,dp(112f),dp(112f))
        place(title,(width-titleWidth)/2,iconY+dp(144f),titleWidth,titleHeight)
        place(message,(width-messageWidth)/2,iconY+dp(176f)+titleHeight,messageWidth,messageHeight)
        val actionsWidth=if(stacked)buttonWidth else buttonWidth*2+gap
        val actionsX=(width-actionsWidth)/2
        place(home,actionsX,actionsY,buttonWidth,dp(88f))
        place(resume,if(stacked)actionsX else actionsX+buttonWidth+gap,if(stacked)actionsY+dp(112f) else actionsY,buttonWidth,dp(88f))
        content.layoutParams.height=contentHeight
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
