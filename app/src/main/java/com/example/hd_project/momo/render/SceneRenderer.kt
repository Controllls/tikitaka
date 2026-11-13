package com.example.hd_project.momo.render

import android.graphics.*
import com.example.hd_project.momo.model.*
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Fixed supplied resources only. No procedural people, sample characters or editable scene UI. */
class SceneRenderer(val project: SceneProject, private val analysis: AnalysisBundle) {
    private val motion=MotionEngine(project,analysis)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF()
    private val layout=project.layout
    private val scale=layout.carWidth/1920f
    private fun x(value: Float)=layout.carLeft+value*scale
    private fun y(value: Float)=layout.carTop+value*scale
    private val images=mutableMapOf<String,Bitmap>()
    private data class Character(val person: Person,val width: Float,val height: Float,val rootX: Float,val rootY: Float,
        val body: List<String>,val head: List<String>,val eyes: Map<String,String>,val mouths: Map<String,String>,
        val sprites: Map<String,String> = emptyMap())
    private val characters: List<Character>
    private val mouthBounds=mutableMapOf<String,Rect>()
    private val window=Path().apply {
        moveTo(x(170f),y(176f)); lineTo(x(960f),y(124f)); lineTo(x(1750f),y(176f))
        lineTo(x(1840f),y(735f)); lineTo(x(80f),y(735f)); close()
    }
    private val frontMask=Path().apply {
        addRoundRect(RectF(x(437f),y(303f),x(774f),y(503f)),64*scale,64*scale,Path.Direction.CW)
        addRoundRect(RectF(x(1158f),y(303f),x(1492f),y(503f)),64*scale,64*scale,Path.Direction.CW)
        moveTo(x(480f),y(501f)); lineTo(x(710f),y(501f)); quadTo(x(811f),y(501f),x(821f),y(601f))
        lineTo(x(840f),y(1041f)); lineTo(x(364f),y(1041f)); lineTo(x(383f),y(601f)); quadTo(x(390f),y(501f),x(480f),y(501f)); close()
        moveTo(x(1220f),y(501f)); lineTo(x(1430f),y(501f)); quadTo(x(1538f),y(501f),x(1545f),y(601f))
        lineTo(x(1566f),y(1041f)); lineTo(x(1085f),y(1041f)); lineTo(x(1104f),y(601f)); quadTo(x(1111f),y(501f),x(1220f),y(501f)); close()
        addRect(x(795f),y(958f),x(1125f),y(1080f),Path.Direction.CW)
    }
    init {
        analysis.validate()
        require(!project.sampleVisuals && project.vehicleRef!=null) { "고정 영상 리소스를 준비해 주세요." }
        characters=project.people.sortedByDescending { it.seat.startsWith("rear") }.map { person ->
            val manifest=File(requireNotNull(person.manifestRef) { "캐릭터 리소스가 없습니다." })
            val data=JSONObject(manifest.readText())
            val view=data.getJSONArray("viewBox")
            val root=data.getJSONObject("anchors").getJSONArray("root")
            fun path(value: String): String {
                val file=File(manifest.parentFile,value).canonicalFile
                require(file.path.startsWith(manifest.parentFile!!.canonicalPath+File.separator) && file.isFile)
                load(file.path,512)
                return file.path
            }
            if (data.optString("renderMode") == "sprite-sheet") {
                val sprites = data.getJSONObject("sprites")
                return@map Character(person,view.getDouble(2).toFloat(),view.getDouble(3).toFloat(),root.getDouble(0).toFloat(),root.getDouble(1).toFloat(),
                    emptyList(),emptyList(),emptyMap(),emptyMap(),sprites.keys().asSequence().associateWith { path(sprites.getString(it)) })
            }
            val pose=data.getJSONObject("poses").getJSONObject(person.pose).getJSONObject("partsPng")
            val body=data.getJSONArray("bodyPartsPng").let { a -> (0 until a.length()).map { path(a.getString(it)) } }
            val head=listOf("hair_back","face","hair_front").map { path(pose.getString(it)) }
            val eyes=listOf("eyes_open","eyes_blink","eyes_happy").associateWith { path(pose.getString(it)) }
            val mouths=listOf("mouth_closed","mouth_talk","mouth_smile").associateWith { path(pose.getString(it)) }
            mouths.values.forEach { file -> mouthBounds[file]=alphaBounds(images.getValue(file)) }
            Character(person,view.getDouble(2).toFloat(),view.getDouble(3).toFloat(),root.getDouble(0).toFloat(),root.getDouble(1).toFloat(),
                body,head+listOfNotNull(pose.optString("nose").takeIf { it.isNotBlank() }?.let(::path)),eyes,mouths)
        }
        listOfNotNull(project.vehicleRef,project.vehicleRearRef,project.vehicleFrontRef,project.vehicleBottomRef,project.seatbeltsRef,project.steeringWheelRef,project.backgroundRef?.takeUnless { project.backgroundIsVideo }).forEach { load(it,2048) }
    }
    private fun load(path: String,maxSize: Int) {
        if(path in images)return
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "영상 이미지가 손상되었어요." }
        var sample=1; while(max(bounds.outWidth,bounds.outHeight)/sample>maxSize)sample*=2
        images[path]=requireNotNull(BitmapFactory.decodeFile(path,BitmapFactory.Options().apply { inSampleSize=sample }))
    }
    private fun alphaBounds(bitmap: Bitmap): Rect {
        val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        var l=bitmap.width;var t=bitmap.height;var r=0;var b=0
        for(j in 0 until bitmap.height)for(i in 0 until bitmap.width)if(Color.alpha(pixels[j*bitmap.width+i])>24) {
            l=min(l,i);t=min(t,j);r=max(r,i+1);b=max(b,j+1)
        }
        return if(l<r && t<b)Rect(l,t,r,b) else Rect(0,0,bitmap.width,bitmap.height)
    }
    private fun image(canvas: Canvas,path: String,l: Float,t: Float,w: Float,h: Float) {
        rect.set(l,t,l+w,t+h); paint.color=Color.WHITE;paint.style=Paint.Style.FILL
        canvas.drawBitmap(images.getValue(path),null,rect,paint)
    }
    private fun car(canvas: Canvas,path: String)=image(canvas,path,layout.carLeft,layout.carTop,layout.carWidth,layout.carWidth*1080/1920)
    fun draw(canvas: Canvas,timeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        val wide=project.width*9==project.height*16
        canvas.save();canvas.scale(canvas.width/(if(wide)1920f else 540f),canvas.height/(if(wide)1080f else 960f))
        val direction=StoryDirection.frame(project,analysis,timeUs)
        canvas.save()
        if(wide)project.people.firstOrNull { it.personId==direction.focusPersonId }?.let { person ->
            val focus=when(person.seat) { "driver"->1530f;"front_passenger"->378f;"rear_left"->1242f;else->648f }
            // A restrained composition change; source artwork and acoustic animation remain intact.
            canvas.scale(1.06f,1.06f,focus,540f)
        }
        paint.color=Color.rgb(249,248,244);paint.style=Paint.Style.FILL
        canvas.save();if(project.backgroundIsVideo)canvas.clipOutPath(window);canvas.drawPaint(paint);canvas.restore()
        if(!project.backgroundIsVideo)project.backgroundRef?.let { canvas.save();canvas.clipPath(window);car(canvas,it);canvas.restore() }
        car(canvas,project.vehicleRef!!)
        project.vehicleRearRef?.let { car(canvas,it) }
        var frontDrawn=false
        fun coverRear() { if(!frontDrawn) {
            if(project.vehicleFrontRef!=null)car(canvas,project.vehicleFrontRef)
            else { canvas.save();canvas.clipPath(frontMask);car(canvas,project.vehicleRef);canvas.restore() }
            frontDrawn=true
        } }
        characters.forEachIndexed { index,character ->
            val person=character.person
            val rear=person.seat.startsWith("rear")
            if(!rear)coverRear()
            val center=if(project.vehicleFrontRef!=null)when(person.seat) { "driver"->1530f;"front_passenger"->378f;"rear_left"->1242f;else->648f }
                else when(person.seat) { "driver"->1328f;"front_passenger"->605f;"rear_left"->1120f;else->800f }
            val height=if(rear)layout.rearHeight else layout.frontHeight
            val ratio=height/character.height*scale
            val state=motion.motionAt(person.personId,timeUs)
            canvas.save();canvas.translate(x(center)+state.dx.toFloat(),y(if(rear)layout.rearRootY else layout.frontRootY)+state.dy.toFloat())
            canvas.rotate(Math.toDegrees(state.tilt).toFloat());canvas.scale(ratio,ratio);canvas.translate(-character.rootX,-character.rootY)
            fun part(path: String)=image(canvas,path,0f,0f,character.width,character.height)
            if(character.sprites.isNotEmpty()) {
                val sprite = if(state.laughing) "happy" else if(state.mouth>.18) "talk"
                    else motion.gazeAt(person.personId,timeUs)?.takeIf { it in character.sprites }
                        ?: person.pose.takeIf { it in character.sprites } ?: "neutral"
                part(character.sprites.getValue(sprite))
                canvas.restore()
                return@forEachIndexed
            }
            character.body.forEach(::part);character.head.forEach(::part)
            val blink=(timeUs/1_000_000.0+index*.83)%4.2<.13
            part(character.eyes.getValue(if(state.laughing)"eyes_happy" else if(blink)"eyes_blink" else "eyes_open"))
            val mouth=character.mouths.getValue(if(state.mouth>.18 && state.laughing)"mouth_smile" else if(state.mouth>.18)"mouth_talk" else "mouth_closed")
            val bitmap=images.getValue(mouth);val bounds=mouthBounds.getValue(mouth)
            val sx=character.width/bitmap.width;val sy=character.height/bitmap.height
            val amount=if(state.mouth>.18)(.4+.6*state.mouth).toFloat() else 1f
            val middle=bounds.exactCenterY()*sy;val hh=bounds.height()*sy*amount/2
            rect.set(bounds.left*sx,middle-hh,bounds.right*sx,middle+hh);paint.color=Color.WHITE
            canvas.drawBitmap(bitmap,bounds,rect,paint)
            canvas.restore()
        }
        coverRear();project.vehicleBottomRef?.let { car(canvas,it) };project.seatbeltsRef?.let { car(canvas,it) };project.steeringWheelRef?.let { car(canvas,it) }
        canvas.restore()
        if(wide && project.mentionedPlace.isNotBlank() && timeUs<3_000_000) {
            val label="대화 속 장소 · ${project.mentionedPlace}"
            paint.textSize=28f;paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            val width=paint.measureText(label)+48f
            paint.color=Color.argb(190,25,30,29);paint.style=Paint.Style.FILL
            canvas.drawRoundRect(RectF(44f,36f,44f+width,112f),18f,18f,paint)
            text(canvas,label,44f+width/2,86f,28f,Color.WHITE)
        }
        // The vehicle's 16:9 artwork fills the film. Title, captions and diagnostic labels live in the player UI.
        // Retain the old portrait composition for saved legacy projects.
        if(wide) { canvas.restore();return }
        text(canvas,"티키타카",270f,74f,18f,Color.rgb(83,102,82))
        wrapped(canvas,project.title,117f,25f,460f,2)
        text(canvas,"함께 달린 순간, 오래 기억해요",270f,190f,16f,Color.DKGRAY)
        val speaking=analysis.result.events.firstOrNull { timeUs>=it.startUs && timeUs<it.endUs }?.activeSpeakerIds.orEmpty()
        project.people.forEach { person ->
            val active=speaking.any { project.speakerMapping[it]==person.personId }
            val column=if(person.seat=="driver" || person.seat=="rear_left")382f else 158f
            val row=if(person.seat.startsWith("rear"))720f else 690f
            text(canvas,(if(active)"● " else "")+person.name.take(12),column,row,17f,
                if(active)Color.rgb(43,111,66) else Color.DKGRAY)
        }
        val caption=analysis.result.captions.filter { timeUs>=it.startUs && timeUs<it.endUs }.joinToString(" ") { it.text }
        if(project.burnCaptions)wrapped(canvas,caption,784f,24f,464f,3)
        text(canvas,"${timeUs/1_000_000 /60}:${(timeUs/1_000_000%60).toString().padStart(2,'0')}  ·  원본 목소리 그대로",270f,917f,14f,Color.GRAY)
        canvas.restore()
    }
    private fun text(c: Canvas,value: String,x: Float,y: Float,size: Float,color: Int=Color.rgb(44,48,43)) {
        paint.color=color;paint.textSize=size;paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL);paint.textAlign=Paint.Align.CENTER
        c.drawText(value,x,y,paint)
    }
    private fun wrapped(c: Canvas,value: String,y: Float,size: Float,width: Float,maxLines: Int) {
        paint.textSize=size
        var remainder=value;var row=0
        while(remainder.isNotEmpty() && row<maxLines) {
            val count=paint.breakText(remainder,true,width,null).coerceAtLeast(1)
            val line=remainder.take(count);remainder=remainder.drop(count).trimStart()
            text(c,line+(if(row==maxLines-1 && remainder.isNotEmpty())"…" else ""),270f,y+row*(size+12),size)
            row++
        }
    }
    fun close() { images.values.forEach { if(!it.isRecycled)it.recycle() };images.clear() }
}
