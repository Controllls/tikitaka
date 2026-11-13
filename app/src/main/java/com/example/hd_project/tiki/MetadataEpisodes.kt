package com.example.hd_project.tiki

import android.content.Context
import android.util.AtomicFile
import com.example.hd_project.*
import com.example.hd_project.momo.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/** Raw audio and PCM stay on the device. Each 30-second analysis block is a durable checkpoint. */
object MetadataEpisodes {
    const val ENGINE="local-metadata-v1"
    private const val BLOCK=30_000L
    private val analysisLock=kotlinx.coroutines.sync.Mutex()
    fun threshold(context: Context)=LaughterSensitivity.fromStored(context.getSharedPreferences("local-analysis",0)
        .getFloat("laughterThreshold",LaughterSensitivity.DEFAULT.toFloat()).toDouble())
    fun threshold(context: Context, value: Double) {
        require(value in LaughterSensitivity.values)
        check(context.getSharedPreferences("local-analysis",0).edit().putFloat("laughterThreshold",value.toFloat()).commit())
    }
    internal fun write(file: File, value: JSONObject) {
        file.parentFile!!.mkdirs()
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(e: Exception) { atomic.failWrite(stream);throw e }
    }
    private fun read(file: File)=JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
    suspend fun run(context: Context, source: File) = analysisLock.withLock { runLocked(context,source) }
    private suspend fun runLocked(context: Context, source: File) {
        val job=currentCoroutineContext()
        ProductionSettings.snapshot(context,source.name)
        val settings=EpisodeWork.status(context,source.name)!!
        val castCount=settings.getInt("castCount").coerceIn(1,4)
        val clipSeconds=settings.getInt("clipSeconds").coerceIn(10,120)
        val profile=ProductionSettings.profile(context,source.name)
        val hash=withContext(Dispatchers.IO) { sha256(source) }
        val duration=withContext(Dispatchers.IO) { RecordingJournal.duration(source) }
        require(duration in 100..43_200_000) { "12시간 이하의 녹음이 필요해요. 원음은 보관되어 있어요." }
        val threshold=EpisodeWork.patch(context,source.name) {
            if(!it.has("laughterThreshold")) it.put("laughterThreshold",threshold(context))
            it.put("engine",ENGINE).put("audioSha256",hash)
        }.getDouble("laughterThreshold")
        val attempt=EpisodeWork.status(context,source.name)?.optInt("localAttempt") ?: 0
        val legacyRoots=listOf(3,2).map { File(context.noBackupFilesDir,"metadata-episodes/$hash/$threshold-v$it-$attempt") }
        val root=File(context.noBackupFilesDir,"metadata-episodes/$hash/$threshold-v4-$attempt").apply { mkdirs() }
        val captions=JSONArray();val laughter=mutableListOf<LocalLaugh>();val warnings=mutableSetOf<String>()
        var complete=0;var blocks=0
        var unavailable: String?=null
        withContext(Dispatchers.IO) {
            LocalLaughter(context).use { detector ->
                for(start in 0 until duration step BLOCK) {
                    job.ensureActive()
                    while(RecordingStore.state.busy) { EpisodeWork.update(context,source.name,"waiting","녹음이 끝나면 기기 분석을 이어갈게요.");delay(2000) }
                    val end=minOf(duration,start+BLOCK);blocks++
                    val saved=File(root,"block-$start.json")
                    val data=if(saved.isFile) read(saved) else {
                        EpisodeWork.update(context,source.name,"running","기기에서 웃음·대사를 분석해요 · ${start*100/duration}%")
                        LocalLaughBlock.read(context,source,root,start,end,duration) { !job.isActive }.use { block ->
                            val analysis=block.trim(detector.analyze(block.pcm,threshold) { !job.isActive })
                            write(File(root,"laugh-trace-v3-$start.json"),JSONObject().put("source",LocalLaughter.VERSION)
                                .put("frames",JSONArray(analysis.frames.map { PlaybackDiagnostics.frame(it) })))
                            // Upgrade laughter independently. Explicit reanalysis advances localAttempt, so it bypasses this cache.
                            val previous=legacyRoots.map { File(it,"block-$start.json") }.firstOrNull { it.isFile }?.let(::read)
                            val transcript=if(previous!=null)LocalTranscript(emptyList(),previous.getBoolean("complete"),previous.optString("stt_error").takeIf { it.isNotEmpty() })
                                else unavailable?.let { LocalTranscript(emptyList(),false,it) } ?: block.speechPcm().let { pcm ->
                                    try { PleosLocalStt.recognize(context,pcm) } finally { pcm.fill(0f) }
                                }
                            if(transcript.error in listOf("ON_DEVICE_ENGINE_ERROR","PERMISSION_DENIED","NOT_SUPPORTED_API","UNAVAILABLE_LANGUAGE")) unavailable=transcript.error
                            val texts=previous?.getJSONArray("captions") ?: JSONArray();val events=JSONArray()
                            transcript.captions.forEach { c ->
                                val a=maxOf(start,start+c.startMs);val b=minOf(end,start+c.endMs)
                                if(b>a) texts.put(JSONObject().put("start_ms",a).put("end_ms",b).put("text",c.text.take(2000)))
                            }
                            analysis.events.forEach { l ->
                                val a=l.startMs;val b=l.endMs
                                if(b>a) events.put(JSONObject().put("start_ms",a).put("end_ms",b).put("score",l.score))
                            }
                            JSONObject().put("captions",texts).put("laughter",events).put("complete",transcript.complete)
                                .put("stt_error",transcript.error ?: "").put("stt_reused",previous!=null).put("detector",LocalLaughter.VERSION).also { write(saved,it) }
                        }
                    }
                    if(data.getBoolean("complete")) complete++
                    if(data.optString("stt_error").isNotEmpty()) warnings.add(data.getString("stt_error"))
                    val texts=data.getJSONArray("captions");repeat(texts.length()) { captions.put(texts.getJSONObject(it)) }
                    val events=data.getJSONArray("laughter");repeat(events.length()) {
                        val e=events.getJSONObject(it);val item=LocalLaugh(e.getLong("start_ms"),e.getLong("end_ms"),e.getDouble("score"))
                        val last=laughter.lastOrNull()
                        if(last!=null && item.startMs<=last.endMs+200) laughter[laughter.lastIndex]=last.copy(endMs=item.endMs,score=maxOf(last.score,item.score))
                        else laughter.add(item)
                    }
                    EpisodeWork.patch(context,source.name) { it.put("localAnalyzedMs",end).put("laughterCount",laughter.size).put("captionCount",captions.length()) }
                }
            }
        }
        val status=if(complete==blocks && warnings.isEmpty())"complete" else if(captions.length()>0)"partial" else "unavailable"
        val events=JSONArray();laughter.forEach { events.put(JSONObject().put("start_ms",it.startMs).put("end_ms",it.endMs).put("score",it.score)) }
        val request=JSONObject().put("schema_version",ENGINE).put("consent_version","transcript-events-v1")
            .put("source_sha256",hash).put("duration_ms",duration).put("stt_status",status).put("detector",LocalLaughter.VERSION)
            .put("laughter_threshold",threshold).put("captions",captions).put("laughter",events).put("clip_seconds",clipSeconds).put("max_clips",3)
        withContext(Dispatchers.IO) { write(File(root,"metadata.json"),request) }
        EpisodeWork.patch(context,source.name) { it.put("localMetadata",File(root,"metadata.json").path).put("sttStatus",status)
            .put("sttWarnings",JSONArray(warnings.toList())).put("laughterCount",events.length()).put("captionCount",captions.length()) }
        if(status!="complete" && captions.length()==0 && events.length()==0) error("기기 음성 인식을 사용할 수 없고 웃음 구간도 감지되지 않았어요. 원음은 보관되어 있어요. (${warnings.joinToString()})")
        // Retain the existing 40-second plan and analysis checkpoints when only staging changes.
        val planFile=File(root,if(clipSeconds==40)"plan.json" else "plan-$clipSeconds.json")
        val plan=if(planFile.isFile) withContext(Dispatchers.IO) { read(planFile) } else {
            EpisodeWork.update(context,source.name,"running","기기에서 대사·웃음 구간으로 하이라이트를 구성해요.")
            withContext(Dispatchers.Default) { JSONObject(LocalHighlightPlanner.plan(request.toString())) }.also {
                validatePlan(it,hash,duration);withContext(Dispatchers.IO) { write(planFile,it) }
            }
        }
        validatePlan(plan,hash,duration)
        val clips=plan.getJSONArray("clips")
        val savedClips=mutableListOf<SavedHighlight>()
        var basicStoryCount=0
        if(clips.length()==0) { EpisodeWork.update(context,source.name,"empty","대사·웃음 하이라이트를 찾지 못했어요. 원음은 보관했어요.");return }
        for(i in 0 until clips.length()) {
            job.ensureActive()
            val clip=clips.getJSONObject(i)
            val key="${EpisodeWork.id(source.name)}-local-${plan.getString("plan_id").take(12)}-${i+1}-story8-${settings.optInt("renderGeneration") }"
            val input=File(root,"$key.m4a");val output=AnimationFiles.output(context,input)
            val range=withContext(Dispatchers.IO) {
                AudioClipper.export(source,input,HighlightRange(clip.getLong("start_ms"),clip.getLong("end_ms"),clip.getDouble("score").toFloat())) { !job.isActive }
            }
            val clipDuration=withContext(Dispatchers.IO) { RecordingJournal.duration(input) }*1000
            val storyFile=File(root,"$key-story.json")
            val cachedStory=withContext(Dispatchers.IO) { runCatching {
                DialogueStoryRules.json.decodeFromString<DialogueStory>(read(storyFile).toString())
            }.getOrNull()?.takeIf { it.version==DialogueStoryRules.VERSION && it.model==LocalStoryModel.MODEL && it.status=="generated" } }
            val story=cachedStory ?: run {
                val rows=clip.getJSONArray("captions")
                val lines=(0 until rows.length()).mapNotNull { row -> val c=rows.getJSONObject(row)
                    val a=((c.getLong("start_ms")-range.startMs)*1000).coerceIn(0,clipDuration)
                    val b=((c.getLong("end_ms")-range.startMs)*1000).coerceIn(0,clipDuration)
                    if(b>a)StoryLine(a,b,c.getString("text")) else null
                }
                if(DialogueStoryRules.scenes(lines).isEmpty())DialogueStoryRules.fallback(clip.getString("title"),"no_transcript")
                else try {
                    LocalStoryModel.plan(context,lines,clipDuration) { EpisodeWork.update(context,source.name,"running",it) }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    android.util.Log.w("MetadataEpisodes","Dialogue staging unavailable: ${e.javaClass.simpleName}")
                    EpisodeWork.patch(context,source.name) { it.put("storyStatus","failed").put("storyModel",LocalStoryModel.MODEL) }
                    throw IllegalStateException("대화 제목·장면을 아직 만들지 못했어요. ${e.message ?: "모델 준비 상태를 확인한 뒤 다시 시도해 주세요."} 원음과 분석은 보관되어 있어요.",e)
                } catch(e: LinkageError) {
                    throw IllegalStateException("대화 분석 모델을 실행하지 못했어요. 앱 업데이트 후 다시 시도해 주세요. 원음과 분석은 보관되어 있어요.",e)
                }
            }
            withContext(Dispatchers.IO) { write(storyFile,JSONObject(DialogueStoryRules.json.encodeToString(story))) }
            if(story.status!="generated")basicStoryCount++
            EpisodeWork.patch(context,source.name) { it.put("storyStatus",story.status).put("storyModel",story.model)
                .put("storyElapsedMs",story.elapsedMs).put("basicStoryCount",basicStoryCount) }
            savedClips.add(SavedHighlight(input,story.title,range.startMs,range.endMs,clip.getDouble("score")))
            val sidecar=File(output.path+".json")
            if(output.isFile && sidecar.isFile && PlaybackDiagnostics.file(output).isFile && File(output.path+".captions.json").isFile &&
                withContext(Dispatchers.IO) { runCatching { val saved=read(sidecar)
                    saved.getString("video")==sha256(output) && saved.optJSONObject("story")?.toString()==JSONObject(DialogueStoryRules.json.encodeToString(story)).toString()
                }.getOrDefault(false) }) {
                withContext(Dispatchers.IO) { poster(output) }
                EpisodeWork.update(context,source.name,"running","저장된 영상을 확인했어요.",output);continue
            }
            val audioHash=withContext(Dispatchers.IO) { sha256(input) }
            val pcm=FloatArray((clipDuration*16000/1_000_000+16000).toInt());var pcmCount=0
            val energy=withContext(Dispatchers.IO) {
                val sums=DoubleArray(ceil(clipDuration/20000.0).toInt());val counts=IntArray(sums.size);var sample=0
                LaughterAnalyzer(context).decode(input,{!job.isActive},{},{}) { value ->
                    if(pcmCount<pcm.size)pcm[pcmCount++]=value
                    val frame=sample++/320
                    if(frame<sums.size) { sums[frame]+=value.toDouble()*value;counts[frame]++ }
                }
                sums.indices.map { if(counts[it]==0)0.0 else (sums[it]/counts[it]).coerceIn(0.0,1.0) }
            }
            EpisodeWork.update(context,source.name,"running","기기에서 발화 구간과 화자를 분석해요.")
            val diarization=try {
                withContext(Dispatchers.Default) {
                    val samples=pcm.copyOf(pcmCount)
                    try { LocalDiarization.analyze(context,samples,profile.voice,{ !job.isActive }) { percent ->
                        EpisodeWork.update(context,source.name,"running","기기에서 화자를 구분해요 · $percent%")
                    }.let { result -> result.copy(turns=result.turns.mapNotNull { turn ->
                        val end=minOf(turn.endMs,clipDuration/1000)
                        if(end<=turn.startMs)null else turn.copy(endMs=end)
                    }) } } finally { samples.fill(0f) }
                }
            } finally { pcm.fill(0f) }
            job.ensureActive()
            val automatic=settings.optString("castMode")==ProductionSettings.AUTO_CAST
            val renderCount=ProductionSettings.renderCount(diarization.speakerCount,castCount,automatic)
            val castAssignment=if(automatic && diarization.speakerCount==0)"unknown-voice-fallback" else "detected-speaker-turns"
            EpisodeWork.patch(context,source.name) { it.put("detectedSpeakerCount",diarization.speakerCount)
                .put("renderCastCount",renderCount).put("castAssignment",castAssignment) }
            val bundle=bundle(clip,key,range.startMs,clipDuration,audioHash,renderCount,energy,diarization=diarization)
            val base=SceneProject(key,story.title,input.path,audioHash,clipDuration,storyBeats=story.beats,mentionedPlace=story.mentionedPlace)
            val scene=withContext(Dispatchers.IO) {
                val staged=TikiScene.buildPerformance(context,base,profile,renderCount)
                staged.copy(people=TikiScene.placeSelfAtDriver(staged.people,diarization.selfSpeaker).mapIndexed { index,person ->
                    val self=diarization.selfSpeaker
                    person.copy(name=when { index==self -> "화자 ${index+1} · ${profile.name}"
                        index<diarization.speakerCount -> "화자 ${index+1}"
                        automatic && diarization.speakerCount==0 -> "화자 미확정 · 캐릭터 ${index+1}"
                        else -> "대기 캐릭터 ${index+1}" })
                })
            }
            EpisodeWork.update(context,source.name,"running","웃음·화자 진단 수치를 준비해요.")
            val diagnostics=withContext(Dispatchers.IO) {
                val trace=PlaybackDiagnostics.laughter(context,source,root,range.startMs,range.endMs,threshold) { !job.isActive }
                PlaybackDiagnostics.build(scene,bundle,range.startMs,threshold,status,diarization.voice,trace,diarization)
            }
            EpisodeWork.renderLock.withLock {
                val pending=AnimationFiles.pending(context,input)
                MomoVideoSession.beginExport(input.name)
                try {
                    pending.delete()
                    VideoComposer.exportFile(context,VideoComposer.prepare(context,scene,bundle),pending) {
                        EpisodeWork.update(context,source.name,"running","${i+1}/${clips.length()} · 기기에서 영상을 만들어요 $it%")
                    }
                    withContext(Dispatchers.IO) {
                        VideoComposer.validateMp4(pending,scene.width,scene.height)
                        check(pending.renameTo(output))
                        val subtitles=JSONArray();bundle.result.captions.forEach { subtitles.put(JSONObject().put("startMs",it.startUs/1000).put("endMs",it.endUs/1000).put("text",it.text)
                            .put("speakerIds",JSONArray(it.speakerIds)).put("requiresReview",it.requiresReview)) }
                        val captionsFile=AtomicFile(File(output.path+".captions.json"));val stream=captionsFile.startWrite()
                        try { stream.write(subtitles.toString().toByteArray(Charsets.UTF_8));captionsFile.finishWrite(stream) }
                        catch(e: Exception) { captionsFile.failWrite(stream);throw e }
                        write(PlaybackDiagnostics.file(output),diagnostics)
                        write(sidecar,JSONObject().put("title",base.title).put("source",source.name).put("audio",hash).put("video",sha256(output))
                            .put("renderer",scene.rendererVersion).put("width",scene.width).put("height",scene.height).put("planId",plan.getString("plan_id")).put("speakerIdentity","local-diarization").put("diarization",diarization.json()).put("sttStatus",status)
                            .put("castCount",renderCount).put("speakerCount",diarization.speakerCount).put("castAssignment",castAssignment)
                            .put("castMode",if(automatic)ProductionSettings.AUTO_CAST else "legacy-staging").put("voiceModel",profile.voice?.modelId ?: "none")
                            .put("captionTiming","approximate-within-sdk-segment").put("castNames",JSONArray(scene.people.map { it.name })).put("diagnostics",PlaybackDiagnostics.VERSION)
                            .put("story",JSONObject(DialogueStoryRules.json.encodeToString(story))))
                        poster(output)
                        EpisodeWork.episodeNumber(context,output)
                    }
                    EpisodeWork.update(context,source.name,"running","완성한 영상을 저장했어요.",output)
                } finally { pending.delete();MomoVideoSession.endExport(input.name) }
            }
        }
        withContext(Dispatchers.IO) { HighlightStore.saveLocal(context,source.name,plan.getString("plan_id"),savedClips) }
        EpisodeWork.update(context,source.name,"completed",when {
            basicStoryCount>0 -> "영상이 준비됐어요. $basicStoryCount 개는 대화 연출을 사용할 수 없어 기본 제목·구도로 만들었어요."
            status=="complete" -> "대화에 맞춘 제목과 장면으로 영상이 준비됐어요."
            else -> "영상이 준비됐어요. 기기 음성 인식이 제한되어 자막이 일부 없을 수 있어요."
        })
    }

    private fun poster(movie: File) {
        val file=File(movie.path+".poster.png")
        if(file.isFile)return
        val retriever=android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(movie.path)
            val bitmap=retriever.getFrameAtTime(1_000_000,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            try {
                val target=AtomicFile(file);val stream=target.startWrite()
                try { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));target.finishWrite(stream) }
                catch(e: Exception) { target.failWrite(stream);throw e }
            } finally { bitmap.recycle() }
        } finally { retriever.release() }
    }

    internal fun validatePlan(plan: JSONObject, hash: String, duration: Long) {
        require(plan.getString("schema_version")=="local-plan-v1" && plan.getString("source_sha256")==hash && plan.getLong("duration_ms")==duration)
        require(plan.getString("plan_id").matches(Regex("[a-f0-9]{64}")))
        val clips=plan.getJSONArray("clips");require(clips.length()<=3);var previous=0L
        repeat(clips.length()) { i -> val c=clips.getJSONObject(i);val a=c.getLong("start_ms");val b=c.getLong("end_ms")
            require(a>=previous && b>a && b<=duration && b-a<=120_000 && c.getString("title").isNotBlank());previous=b
            require(c.getDouble("score").isFinite() && c.getDouble("score") in 0.0..1.0)
            listOf("captions","laughter").forEach { field -> val rows=c.getJSONArray(field);require(rows.length()<=30000)
                var last=0L;repeat(rows.length()) { val r=rows.getJSONObject(it);require(r.getLong("start_ms")>=last && r.getLong("end_ms")>r.getLong("start_ms") && r.getLong("end_ms")<=duration);last=r.getLong("end_ms")
                    if(field=="captions")require(r.getString("text").length in 1..2000)
                    else require(r.getDouble("score").isFinite() && r.getDouble("score") in 0.0..1.0)
                }
            }
        }
    }

    internal fun bundle(clip: JSONObject, id: String, offsetMs: Long, durationUs: Long, hash: String,
        castCount: Int = 2, energy: List<Double>? = null, dialogueActors: Map<Long,Int> = emptyMap(), diarization: DiarizationResult? = null): AnalysisBundle {
        require(castCount in 1..4)
        val actors=(1..castCount).map { "performance$it" }
        val dialogue=actors.first()
        // Stable pseudorandom staging: unknown laughter goes to a non-speaking companion where possible.
        val laughActor=if(castCount==1)dialogue else actors[1+Math.floorMod(id.hashCode(),castCount-1)]
        val captions=mutableListOf<Caption>();val laughs=mutableListOf<Triple<Long,Long,Double>>()
        fun rows(name: String, block:(JSONObject,Long,Long)->Unit) { val rows=clip.getJSONArray(name);repeat(rows.length()) {
            val row=rows.getJSONObject(it);val a=((row.getLong("start_ms")-offsetMs)*1000).coerceIn(0,durationUs)
            val b=((row.getLong("end_ms")-offsetMs)*1000).coerceIn(0,durationUs);if(b>a)block(row,a,b)
        } }
        rows("captions") { r,a,b ->
            if(diarization!=null) {
                captions.addAll(DiarizedCaptions.split(r.getString("text"),a/1000,b/1000,diarization.turns,"c${captions.size}"))
                return@rows
            }
            val actor=actors[(dialogueActors[r.getLong("start_ms")] ?: 0).coerceIn(0,castCount-1)]
            val pieces=readableText(r.getString("text"))
            val length=pieces.sumOf { it.length }.coerceAtLeast(1);var used=0
            pieces.forEach { text ->
                val start=a+(b-a)*used/length;used+=text.length;val end=a+(b-a)*used/length
                if(end>start)captions.add(Caption("c${captions.size}",start,end,text,listOf(actor),requiresReview=true))
            }
        }
        rows("laughter") { r,a,b -> laughs.add(Triple(a,b,r.getDouble("score"))) }
        fun laughing(a: Long,b: Long): String {
            val talking=if(diarization==null)captions.filter { it.startUs<b && it.endUs>a }.flatMap { it.speakerIds }.toSet()
                else diarization.turns.filter { it.startMs*1000<b && it.endMs*1000>a }.mapNotNull { it.speaker?.let { i->actors[i] } }.toSet()
            val choices=actors.filter { it !in talking }
            return if(choices.isEmpty())laughActor else choices[Math.floorMod(id.hashCode()+(a/1000).toInt(),choices.size)]
        }
        val boundaries=(listOf(0L,durationUs)+captions.flatMap { listOf(it.startUs,it.endUs) }+laughs.flatMap { listOf(it.first,it.second) }+diarization?.turns.orEmpty().flatMap { listOf(it.startMs*1000,it.endMs*1000) }).distinct().sorted()
        val events=boundaries.zipWithNext().mapIndexed { i,(a,b) ->
            val laugh=laughs.firstOrNull { it.first<=a && it.second>a }
            val speaking=if(diarization!=null)diarization.active(a/1000).mapNotNull { it.speaker?.let { i->actors[i] } }.distinct()
                else captions.filter { it.startUs<=a && it.endUs>a }.flatMap { it.speakerIds }.distinct()
            val speech=if(diarization!=null)diarization.active(a/1000).isNotEmpty() else speaking.isNotEmpty()
            val laughSpeaker=if(laugh!=null)laughing(laugh.first,laugh.second) else laughActor
            val kind=if(speech)"speech" else if(laugh!=null)"laughter" else "silence"
            val active=speaking+(if(laugh!=null)listOf(laughSpeaker) else emptyList())
            SoundEvent("e$i",a,b,kind,active.distinct(),
                if(laugh!=null)listOf(laughSpeaker) else emptyList(),laugh?.third ?: 0.0,true,listOf(if(diarization!=null)"local_diarization_laughter_owner_unidentified" else "local_self_verification_others_unidentified"))
        }
        laughs.filter { l -> captions.none { it.startUs<l.second && it.endUs>l.first } }.forEach { (a,b,_) -> captions.add(Caption("l${captions.size}",a,b,"(웃음)",listOf(laughing(a,b)),requiresReview=true)) }
        val count=ceil(durationUs/20000.0).toInt()
        val activity=List(count) { i -> if(events.any { it.startUs<=i*20000L && it.endUs>i*20000L && it.kind=="speech" })1.0 else 0.0 }
        val laughter=List(count) { i -> if(laughs.any { it.first<=i*20000L && it.second>i*20000L })1.0 else 0.0 }
        val result=AnalysisResult(jobId=id,durationUs=durationUs,dataOrigin="inference",pipelineVersion=ENGINE,
            audio=AudioInfo(hash,"audio/mp4"),speakers=actors.map { Speaker(it,0) },events=events,captions=captions.sortedBy { it.startUs },
            tracks=TrackRef("local",frameCount=count),warnings=listOf(if(diarization==null)AnalysisWarning("PERFORMANCE_ONLY","화자 식별이 아닌 캐릭터 연출입니다.")
                else AnalysisWarning("APPROXIMATE_CAPTION_ALIGNMENT","음향 기반 화자 구간입니다. 단어별 시각과 웃음의 주인은 확인되지 않았습니다.")))
        return AnalysisBundle(result,MotionTracks(jobId=id,durationUs=durationUs,frameCount=count,
            mixedVoiceEnergy=energy ?: List(count) { if(activity[it]>0 || laughter[it]>0).1 else 0.0 },
            speakerTracks=actors.map { actor -> SpeakerTrack(actor,
                List(count) { frame -> if(if(diarization!=null)diarization.active(frame*20L).any { it.speaker?.let { i->actors[i] }==actor }
                    else captions.any { it.captionId.startsWith("c") && actor in it.speakerIds && it.startUs<=frame*20000L && it.endUs>frame*20000L })1.0 else 0.0 },
                List(count) { frame -> if(events.any { actor in it.laughingSpeakerIds && it.startUs<=frame*20000L && it.endUs>frame*20000L })1.0 else 0.0 }) })).also { it.validate() }
    }

    /** SDK segment timing is retained; intra-segment reading times are explicitly approximate. */
    internal fun readableText(text: String): List<String> {
        val parts=mutableListOf<String>();var current=""
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { it.chunked(28) }.forEach { word ->
            if(current.isNotEmpty() && current.length+word.length+1>28) { parts.add(current);current="" }
            current=if(current.isEmpty())word else "$current $word"
        }
        if(current.isNotEmpty())parts.add(current)
        return parts
    }
}
