package com.example.hd_project.tiki

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import kotlin.math.min

object TikiDesign {
    const val background = 0xfff7f8fa.toInt()
    const val ink = 0xff343434.toInt()
    const val secondary = 0xff666666.toInt()
    const val line = 0xffdfdfdf.toInt()
    fun dp(context: Context, value: Float) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int = background, radius: Float = 0f, stroke: Int? = null, context: Context): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius=dp(context,radius).toFloat(); stroke?.let { setStroke(dp(context,1f),it) } }
    fun press(context: Context, color: Int=ink, radius: Float=44f) = RippleDrawable(
        ColorStateList.valueOf(0x22888888),shape(color,radius,context=context),null)
}

class TikiArt(private val context: Context) {
    private val mapping=JSONObject(context.assets.open("tiki/images.json").bufferedReader().use { it.readText() })
    private val cache=object: LruCache<String,Bitmap>(36 * 1024 * 1024) {
        override fun sizeOf(key: String,value: Bitmap)=value.byteCount
    }
    val sans: Typeface=Typeface.createFromAsset(context.assets,"tiki/fonts/AstaSans[wght].ttf")
    val jua: Typeface=Typeface.createFromAsset(context.assets,"tiki/fonts/Jua-Regular.ttf")
    fun bitmap(screen: String, key: String): Bitmap {
        val file=mapping.getJSONObject("$screen-context").getString(key)
        return cache.get(file) ?: context.assets.open("tiki/$file").use { BitmapFactory.decodeStream(it) }
            .also { cache.put(file,it) }
    }
    fun image(screen: String,key: String,description: String?=null) = ImageView(context).apply {
        setImageBitmap(bitmap(screen,key));scaleType=ImageView.ScaleType.FIT_CENTER
        contentDescription=description;importantForAccessibility=if(description==null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun poster(movie: java.io.File, screen: String, key: String): ImageView {
        val file=TikiThumbnails.file(context,movie)
        val bitmap=if(file.isFile) cache.get(file.path) ?: BitmapFactory.decodeFile(file.path)?.also { cache.put(file.path,it) } else null
        return if(bitmap==null)image("home-wide","imgTikiTakaCurrentLineIconLibrary").apply {
            background=TikiDesign.shape(0xffeeeeee.toInt(),12f,context=context)
            setPadding(TikiDesign.dp(context,32f),TikiDesign.dp(context,24f),TikiDesign.dp(context,32f),TikiDesign.dp(context,24f))
        } else ImageView(context).apply {
            setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    fun character(index: Int): ImageView {
        val key=listOf("imgTikiTakaCharacter3D01","imgCharacterArtwork","imgCharacterArtwork1","imgCharacterArtwork2",
            "imgCharacterArtwork3","imgCharacterArtwork4","imgCharacterArtwork5","imgTikiTakaCharacter3D08")[index]
        val bitmap=bitmap(if(index==7) "frog" else "character",key)
        val scales=listOf(1.1021f,1.0957f,1.2003f,1.2375f,1.1377f,1.1528f,1.1065f,1.0894f)
        val offsetsX=listOf(-.04f,-.04f,-.095f,-.1158f,-.064f,-.105f,-.05f,-.0412f)
        val offsetsY=listOf(-.0555f,-.0636f,-.1097f,-.1266f,-.1024f,-.1173f,-.0718f,-.0768f)
        return object: androidx.appcompat.widget.AppCompatImageView(context) {
            init { setImageBitmap(bitmap);scaleType=ImageView.ScaleType.MATRIX;contentDescription=TikiProfile.labels[index] }
            override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                imageMatrix=Matrix().apply { setScale(w*scales[index]/bitmap.width,h*scales[index]/bitmap.height)
                    postTranslate(w*offsetsX[index],h*offsetsY[index]) }
            }
        }
    }
    fun text(value: String,size: Float=24f,weight: Int=400,color: Int=TikiDesign.ink) = TextView(context).apply {
        text=value;setTextColor(color);setTextSize(TypedValue.COMPLEX_UNIT_DIP,size)
        typeface=Typeface.create(sans,weight,false);fontVariationSettings="'wght' $weight"
        includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL
    }
    fun button(value: String,primary: Boolean=true,action: ()->Unit): TextView = text(value,24f,500,
        if(primary) Color.WHITE else TikiDesign.ink).apply {
        gravity=Gravity.CENTER;isFocusable=true;minHeight=TikiDesign.dp(context,64f)
        background=TikiDesign.press(context,if(primary) TikiDesign.ink else Color.WHITE)
        setOnClickListener { action() }
    }
    fun clear() { cache.evictAll() }
}

/** Only the supplied hero illustration scales. Text, actions and navigation reflow independently. */
class TikiHero(context: Context,private val art: TikiArt,private val narrow: Boolean=false): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst=RectF()
    override fun onDraw(canvas: Canvas) {
        if(narrow) { drawNarrow(canvas);return }
        val factor=min(width/980f,height/919.5f)
        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),0xfff4e7dc.toInt(),TikiDesign.background,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);paint.shader=null
        canvas.save();canvas.translate((width-980f*factor)/2,0f);canvas.scale(factor,factor)
        fun draw(key: String,x: Float,y: Float,w: Float,h: Float) {
            dst.set(x,y,x+w,y+h);canvas.drawBitmap(art.bitmap("home-wide",key),null,dst,paint)
        }
        draw("imgGroundShadow1",276.13f,782.63f,509.25f,127.75f)
        draw("imgGroundShadow2",115f,797.25f,528.5f,98f)
        draw("imgGroundShadow3",343.25f,800.75f,532f,98f)
        val hero=art.bitmap("home-wide","img")
        val fit=min(931f/hero.width,1197f/hero.height)
        dst.set(36.5f+(931-hero.width*fit)/2,34.2f,36.5f+(931+hero.width*fit)/2,34.2f+hero.height*fit)
        canvas.drawBitmap(hero,null,dst,paint)
        draw("img3D",215f,106.5f,545.23f,146.72f)
        paint.color=TikiDesign.ink;paint.typeface=art.jua;paint.textSize=34.8f;paint.textAlign=Paint.Align.CENTER
        canvas.drawText("오늘은 어떤 이야기를 들려줄래?",488f,178f,paint)
        canvas.restore()
    }
    private fun drawNarrow(canvas: Canvas) {
        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),0xfff4e7dc.toInt(),TikiDesign.background,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);paint.shader=null
        val factor=min(width/710f,height/630f)
        canvas.save();canvas.translate((width-710f*factor)/2,0f);canvas.scale(factor,factor)
        fun draw(key: String,x: Float,y: Float,w: Float,h: Float) {
            dst.set(x,y,x+w,y+h);canvas.drawBitmap(art.bitmap("home-narrow",key),null,dst,paint)
        }
        draw("imgGroundShadow3",272.47f,542.68f,348.992f,64.288f)
        draw("imgGroundShadow2",122.74f,540.38f,346.696f,64.288f)
        draw("imgGroundShadow1",228.44f,530.79f,334.068f,83.804f)
        val hero=art.bitmap("home-narrow","img")
        val fit=min(610.67f/hero.width,785.55f/hero.height)
        dst.set(71.2f+(610.67f-hero.width*fit)/2,39.82f,71.2f+(610.67f+hero.width*fit)/2,39.82f+hero.height*fit)
        canvas.drawBitmap(hero,null,dst,paint)
        draw("img3D",91f,44f,528f,162.55f)
        paint.color=TikiDesign.ink;paint.typeface=art.jua;paint.textSize=30f;paint.textAlign=Paint.Align.CENTER
        canvas.drawText("오늘은 어떤 이야기를 들려줄래?",355f,112f,paint)
        canvas.restore()
    }
}

class TikiWave(context: Context): View(context) {
    var level=0f;set(value) { field=value;invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=TikiDesign.ink;strokeCap=Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth=width/90f
        for(i in 0..30) {
            val envelope=(1f-kotlin.math.abs(i-15)/16f)
            val h=(4+level*height*.85f*envelope)/2
            val x=(i+1)*width/32f
            canvas.drawLine(x,height/2-h,x,height/2+h,paint)
        }
    }
}
