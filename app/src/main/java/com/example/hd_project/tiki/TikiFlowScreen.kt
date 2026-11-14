package com.example.hd_project.tiki

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.min

/** Shared native layout for the archived A/O/G/E frames. Text reflows; content can scroll on short windows. */
class TikiFlowScreen(context: Context, private val art: TikiArt, title: String, subtitle: String = "",
                     onBack: (() -> Unit)? = null) : ScrollView(context) {
    private val content = FrameLayout(context)
    private val heading = art.text(title,36f,600).apply { tag="flow_heading";isAccessibilityHeading=true }
    private val subheading = art.text(subtitle,24f,color=TikiDesign.secondary).apply { tag="flow_subheading" }
    private val back = onBack?.let { action -> FrameLayout(context).apply {
        contentDescription="뒤로";tag="flow_back";isFocusable=true;background=TikiDesign.press(context,Color.WHITE,32f)
        setOnClickListener { action() }
        addView(art.image("record-active","imgFrame2"),FrameLayout.LayoutParams(dp(28f),dp(28f),Gravity.CENTER))
    } }
    val body = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;tag="flow_body" }
    private val bodyItems=mutableListOf<Triple<View,Int,Int>>()
    private var primary: TextView?=null
    private var secondary: TextView?=null
    private var actionWidth=340f
    private fun dp(n: Float)=TikiDesign.dp(context,n)
    init {
        isFillViewport=true;isVerticalScrollBarEnabled=false
        addView(content,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        content.addView(heading);content.addView(subheading);back?.let(content::addView);content.addView(body)
    }
    fun item(view: View, width: Int=-1, height: Int=-2, gap: Int=24): View {
        body.addView(view,LinearLayout.LayoutParams(if(width<0)width else dp(width.toFloat()),if(height<0)height else dp(height.toFloat())).apply {
            topMargin=if(body.childCount==0)0 else dp(gap.toFloat());gravity=Gravity.CENTER_HORIZONTAL
        })
        bodyItems.add(Triple(view,width,height));return view
    }
    fun copy(value: String, size: Float=24f, weight: Int=400, muted: Boolean=false): TextView =
        art.text(value,size,weight,if(muted)TikiDesign.secondary else TikiDesign.ink).apply {
            gravity=Gravity.CENTER;setLineSpacing(dp(6f).toFloat(),1f)
        }
    fun actions(primaryText: String, primaryEnabled: Boolean=true, onPrimary: ()->Unit,
                secondaryText: String?=null, onSecondary: ()->Unit={}, buttonWidth: Float=340f) {
        actionWidth=buttonWidth
        primary=art.button(primaryText,true,onPrimary).apply { tag="flow_primary";isEnabled=primaryEnabled;alpha=if(primaryEnabled)1f else .4f }
        content.addView(primary)
        if(secondaryText!=null) { secondary=art.button(secondaryText,false,onSecondary).apply { tag="flow_secondary" };content.addView(secondary) }
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)/resources.displayMetrics.density
        val height=MeasureSpec.getSize(heightMeasureSpec)/resources.displayMetrics.density
        val pad=if(width>=900)48f else 32f
        val available=(width-pad*2).coerceAtLeast(1f)
        fun measureText(v: TextView,w: Float): Float {
            v.measure(MeasureSpec.makeMeasureSpec(dp(w),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            return v.measuredHeight/resources.displayMetrics.density
        }
        fun place(v: View,x: Float,y: Float,w: Float,h: Float) { v.layoutParams=FrameLayout.LayoutParams(dp(w),dp(h)).apply { leftMargin=dp(x);topMargin=dp(y) } }
        val top=if(height<900)32f else 48f
        val headingX=pad+if(back!=null)88 else 0
        val titleHeight=maxOf(48f,measureText(heading,width-headingX-pad))
        place(heading,headingX,top,width-headingX-pad,titleHeight)
        back?.let { place(it,pad,top,64f,64f) }
        val subtitleHeight=if(subheading.text.isEmpty())0f else measureText(subheading,width-headingX-pad)
        subheading.visibility=if(subtitleHeight==0f)GONE else VISIBLE
        place(subheading,headingX,top+titleHeight+12,width-headingX-pad,subtitleHeight)
        val bodyTop=top+maxOf(if(back!=null)64f else 48f,titleHeight+if(subtitleHeight>0)12+subtitleHeight else 0f)+32
        val bodyWidth=min(1120f,available)
        bodyItems.forEach { (view,targetWidth,targetHeight) ->
            val params=view.layoutParams as LinearLayout.LayoutParams
            val actualWidth=if(targetWidth<0)bodyWidth else min(targetWidth.toFloat(),bodyWidth)
            params.width=dp(actualWidth)
            params.height=when {
                view.tag=="detail_poster" -> dp(actualWidth*9/16)
                targetHeight>0 && targetWidth>0 -> dp(targetHeight*actualWidth/targetWidth)
                targetHeight>0 -> dp(targetHeight.toFloat())
                else -> targetHeight
            }
            view.layoutParams=params
        }
        body.measure(MeasureSpec.makeMeasureSpec(dp(bodyWidth),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val bodyHeight=body.measuredHeight/resources.displayMetrics.density
        val buttons=listOfNotNull(secondary,primary)
        val gap=24f
        val buttonWidth=min(actionWidth,(available-gap*(buttons.size-1))/buttons.size.coerceAtLeast(1))
        val stacked=buttons.size==2 && buttons.any { it.paint.measureText(it.text.toString())/resources.displayMetrics.density+32>buttonWidth }
        val bw=if(stacked)min(440f,available) else buttonWidth
        val bh=maxOf(if(height<900)64f else 88f,buttons.maxOfOrNull { measureText(it,bw)+16 } ?: 0f)
        val actionHeight=if(buttons.isEmpty())0f else if(stacked)bh*buttons.size+16*(buttons.size-1) else bh
        val bottom=if(height>=1100)160f else 32f
        val total=maxOf(height,bodyTop+bodyHeight+32+actionHeight+bottom)
        val actionTop=total-bottom-actionHeight
        place(body,(width-bodyWidth)/2,bodyTop+(actionTop-32-bodyTop-bodyHeight).coerceAtLeast(0f)/2,bodyWidth,bodyHeight)
        buttons.forEachIndexed { i,v ->
            place(v,if(stacked)(width-bw)/2 else (width-bw*buttons.size-gap*(buttons.size-1))/2+i*(bw+gap),
                actionTop+if(stacked)i*(bh+16) else 0f,bw,bh)
        }
        content.layoutParams=LayoutParams(LayoutParams.MATCH_PARENT,dp(total))
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}

object TikiFlowScreens {
    fun permissions(context: Context, art: TikiArt, back: ()->Unit, next: ()->Unit) =
        TikiFlowScreen(context,art,"시작하기 전에","대화를 남기는 데 필요한 권한을 확인해요.",back).apply {
            tag="page_A-01"
            item(copy("마이크 · 필수",24f,500));item(copy("대화를 녹음하고 캐릭터 영상으로 만들 때 사용해요.",muted=true),gap=12)
            item(View(context).apply { setBackgroundColor(TikiDesign.line) },height=1,gap=32)
            item(copy("위치 · 선택",24f,500),gap=32)
            item(copy("이동 거리와 여정 정보를 담아요. 허용하지 않아도 녹음할 수 있어요.",muted=true),gap=12)
            item(copy("권한과 위치 사용 선택은 설정에서 언제든 바꿀 수 있어요.",20f,muted=true),gap=40)
            actions("권한 확인",onPrimary=next)
        }
    fun microphone(context: Context, art: TikiArt, back: ()->Unit, allow: ()->Unit, later: ()->Unit) =
        TikiFlowScreen(context,art,"마이크 사용을 허용해 주세요","티키타카가 대화를 녹음할 수 있도록 허용해 주세요.",back).apply {
            tag="page_A-02"
            item(ImageView(context).apply { setImageResource(com.example.hd_project.R.drawable.tiki_recording_mic);contentDescription="마이크" },88,88)
            item(copy("녹음에 필요한 권한이에요",32f,500))
            item(copy("녹음을 시작하면 마이크를 사용해요.\n허용 여부는 기기 설정에서 바꿀 수 있어요.",muted=true))
            actions("허용하기",onPrimary=allow,secondaryText="나중에",onSecondary=later)
        }
    fun location(context: Context, art: TikiArt, back: ()->Unit, choose: (Boolean)->Unit) =
        TikiFlowScreen(context,art,"위치 정보를 사용해도 될까요?","이동 거리와 여정을 이야기에 함께 담아요.",back).apply {
            tag="page_A-03"
            item(copy("위치 없이도 녹음할 수 있어요",32f,500))
            item(copy("위치 사용은 선택이에요.\n허용하지 않아도 대화와 영상을 남길 수 있어요.",muted=true))
            item(copy("허용하면 녹음 중 내비 위치로 배경과 이동 정보를 정해요.\n주소와 위치 경로는 저장하지 않아요.",20f,muted=true),gap=32)
            actions("허용하기",onPrimary={choose(true)},secondaryText="위치 없이 계속",onSecondary={choose(false)})
        }
    fun profileError(context: Context, art: TikiArt, character: Int, saving: Boolean, edit: ()->Unit, retry: ()->Unit) =
        TikiFlowScreen(context,art,"설정을 저장하지 못했어요").apply {
            tag="page_O-03";item(art.character(character),280,280)
            item(copy("입력한 이름과 고른 캐릭터는 그대로예요.\n잠시 후 다시 저장해 주세요.",muted=true))
            actions(if(saving)"저장 중" else "다시 저장",!saving,retry,"설정으로",edit)
        }
    fun setupPaused(context: Context, art: TikiArt, character: Int, canContinue: Boolean, home: ()->Unit, resume: ()->Unit) =
        TikiFlowScreen(context,art,"주차 후 이어서 설정해 주세요").apply {
            tag="page_O-04";item(art.character(character),280,280)
            item(copy("지금까지 입력한 이름과 고른 캐릭터는 남겨둘게요.\n주차하면 이어서 설정할 수 있어요.",muted=true))
            actions("설정 이어하기",canContinue,resume,"홈으로",home)
        }
    fun connection(context: Context, art: TikiArt, busy: Boolean, back: ()->Unit, network: Boolean=false, retry: ()->Unit) =
        TikiFlowScreen(context,art,"연결이 끊겼어요",onBack=back).apply {
            tag="page_G-02";item(art.image("waiting","imgArtworkTransparentPng"),400,400)
            item(copy("연결되면 이어서 만들게요",32f,500))
            item(copy(if(network)"원본은 녹음 탭에 남아 있어요.\n네트워크 연결을 확인해 주세요." else "음성 인식 서비스 연결을 확인해 주세요.\n원본은 녹음 탭에 남아 있어요.",muted=true))
            actions(if(busy)"연결 확인 중" else "다시 연결하기",!busy,retry,"보관함",back)
        }
}
