package com.example.hd_project.tiki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.*
import com.example.hd_project.momo.storage.sha256
import com.example.hd_project.momo.network.ApiClient
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.MotionEngine
import com.example.hd_project.momo.render.SceneRenderer
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalMetadataTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun rawUploadIsBlockedBeforeAuthenticationOrNetwork()=runBlocking {
        val api=ApiClient(context,"http://127.0.0.1:1/prefix/").api
        val audio=MultipartBody.Part.createFormData("audio","never-upload.m4a",byteArrayOf(1,2,3).toRequestBody())
        try {
            api.submitEpisode("blocked-test",audio,"a".repeat(64).toRequestBody(),"{}".toRequestBody())
            fail("Raw upload must be blocked")
        } catch(e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("원음 전송")) }
    }
    @Test fun castRestoredWithoutDuplicatingDialogueOrInventingIdentity() {
        val clip=JSONObject().put("captions",JSONArray().put(JSONObject().put("start_ms",500).put("end_ms",2000).put("text","대화")))
            .put("laughter",JSONArray().put(JSONObject().put("start_ms",1500).put("end_ms",2500).put("score",.7)))
        val bundle=MetadataEpisodes.bundle(clip,"test",1000,2_000_000,"a".repeat(64))
        bundle.validate()
        assertEquals(2,bundle.result.speakers.size)
        assertTrue(bundle.result.speakerSignatures.isEmpty())
        assertEquals(0L,bundle.result.captions.first().startUs)
        assertEquals(500_000L,bundle.result.events.first { it.laughingSpeakerIds.isNotEmpty() }.startUs)
        assertTrue(bundle.result.captions.all { it.speakerIds.size==1 })
        val overlap=bundle.result.events.first { it.kind=="speech" && it.laughingSpeakerIds.isNotEmpty() }
        assertEquals(listOf("performance2"),overlap.laughingSpeakerIds)
        val scene=TikiScene.buildPerformance(context,SceneProject("test","test","unused","a".repeat(64),2_000_000))
        assertEquals(2,scene.people.size)
        val motion=MotionEngine(scene,bundle)
        assertEquals("speech",motion.motionAt("P1",750_000).kind)
        assertEquals("laughter",motion.motionAt("P2",750_000).kind)
        assertEquals(0.0,motion.motionAt("P2",250_000).mouth,0.0)
    }
    @Test fun fourCastRenderReadableCaptionsAndSilenceGate() {
        val text="어디 가나요 오늘은 같이 차를 타고 가족들과 재미있는 이야기를 아주 길게 이야기하면서 드라이브를 해 보려고 합니다"
        val clip=JSONObject().put("captions",JSONArray().put(JSONObject().put("start_ms",0).put("end_ms",3000).put("text",text))).put("laughter",JSONArray())
        val bundle=MetadataEpisodes.bundle(clip,"cast-four",0,3_000_000,"b".repeat(64),4,List(150) { 0.0 })
        assertEquals(text.replace(" ",""),bundle.result.captions.joinToString("") { it.text }.replace(" ",""))
        assertTrue(bundle.result.captions.size>1)
        assertTrue(bundle.result.captions.all { it.text.length<=28 && it.speakerIds.size==1 })
        assertTrue(bundle.result.captions.zipWithNext().all { (a,b)->a.endUs==b.startUs })
        val profile=TikiProfile.get(context).copy(name="테스트 이름")
        val scene=TikiScene.buildPerformance(context,SceneProject("four","네 명 연출","unused","b".repeat(64),3_000_000),profile,4)
        assertEquals("테스트 이름",scene.people.first().name)
        assertEquals(4,scene.people.map { it.manifestRef }.distinct().size)
        val motion=MotionEngine(scene,bundle)
        scene.people.forEach { assertEquals(0.0,motion.motionAt(it.personId,250_000).mouth,0.0) }
        assertEquals("left",motion.gazeAt("P1",250_000));assertEquals("right",motion.gazeAt("P2",250_000))
        val bitmap=android.graphics.Bitmap.createBitmap(scene.width,scene.height,android.graphics.Bitmap.Config.ARGB_8888)
        SceneRenderer(scene,bundle).draw(android.graphics.Canvas(bitmap),250_000)
        File(context.cacheDir,"cast-four.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun realModelAndPleosSdkProbe()= runBlocking {
        val source=File(context.cacheDir,"local-probe-${UUID.randomUUID()}.m4a")
        val report=JSONObject()
        try {
            LocalLaughter(context).use { detector ->
                for(name in listOf("silence_8000.m4a","1-73123-A-26.m4a","momo-synthetic-korean.m4a")) {
                    instrumentation.context.assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
                    val samples=mutableListOf<Float>()
                    LaughterAnalyzer(context).decode(source,{false},{},{},samples::add)
                    val pcm=samples.toFloatArray();val started=System.currentTimeMillis()
                    val events=detector.detect(pcm)
                    val detectorMs=System.currentTimeMillis()-started
                    if(name.startsWith("silence"))assertTrue(events.isEmpty()) else if(name.startsWith("1-73123"))assertTrue("Known laughter missed",events.isNotEmpty())
                    val stt=PleosLocalStt.recognize(context,pcm)
                    if(name=="momo-synthetic-korean.m4a") assertTrue("Korean file-input recognition must return the fixture dialogue",
                        stt.captions.any { it.text.contains("안전벨트") && it.text.contains("드라이브") })
                    report.put(name,JSONObject().put("samples",pcm.size).put("laughCount",events.size).put("complete",stt.complete)
                        .put("error",stt.error).put("captions",JSONArray(stt.captions.map { it.text })).put("detectorMs",detectorMs).put("elapsedMs",System.currentTimeMillis()-started))
                    assertTrue(stt.complete || stt.error!=null)
                }
            }
        } finally { MetadataEpisodes.write(File(context.cacheDir,"local-metadata-probe.json"),report);source.delete() }
    }
    @Test fun metadataServerToLocalVideoPreservesAudio()= runBlocking<Unit> {
        check(!RecordingStore.state.busy)
        val source=File(RecordingFiles.directory(context),"local-e2e-${UUID.randomUUID()}.m4a")
        val report=JSONObject()
        try {
            instrumentation.context.assets.open("1-73123-A-26.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
            val hash=sha256(source)
            EpisodeWork.patch(context,source.name) { it.put("castCount",4).put("clipSeconds",120) }
            MetadataEpisodes.run(context,source)
            val state=EpisodeWork.status(context,source.name)!!
            report.put("state",state).put("sourceHashPreserved",hash==sha256(source))
            assertEquals(hash,sha256(source));assertEquals("completed",state.getString("state"))
            assertTrue(state.getInt("laughterCount")>0)
            assertFalse("A previous recognition session must not leak into the laughter file",
                File(state.getString("localMetadata")).readText().contains("안전벨트"))
            val movie=File(AnimationFiles.directory(context),state.getJSONArray("movies").getString(0))
            assertTrue(movie.length()>0)
            movie.copyTo(File(context.cacheDir,"local-metadata-e2e.mp4"),overwrite=true)
            assertEquals("local-diarization",JSONObject(File(movie.path+".json").readText()).getString("speakerIdentity"))
            assertEquals(4,JSONObject(File(movie.path+".json").readText()).getInt("castCount"))
            assertTrue(File(movie.path+".poster.png").length()>0)
            assertTrue(PlaybackDiagnostics.file(movie).length()>0)
            val diagnostics=JSONObject(PlaybackDiagnostics.file(movie).readText())
            assertEquals(4,diagnostics.getJSONArray("castNames").length())
            assertTrue(diagnostics.getJSONArray("laughter").length()>0)
            val movieHash=sha256(movie)
            MetadataEpisodes.run(context,source)
            assertEquals("Resuming must reuse the committed movie",movieHash,sha256(movie))
            assertTrue(HighlightStore.batches(context).any { it.sourceId==source.name && it.clips.isNotEmpty() })
            // Exercise the real UI's queued new-version path, retaining analysis and old output.
            EpisodeWork.enqueue(context,source,retry=true,recreate=true)
            kotlinx.coroutines.withTimeout(120_000) {
                while(EpisodeWork.status(context,source.name)?.optString("state")!="completed") {
                    val current=EpisodeWork.status(context,source.name)!!
                    check(current.optString("state")!="failed") { current.optString("message") }
                    kotlinx.coroutines.delay(500)
                }
            }
            val recreated=EpisodeWork.status(context,source.name)!!
            assertEquals(1,recreated.getInt("renderGeneration"));assertEquals(0,recreated.optInt("localAttempt"))
            assertEquals(2,recreated.getJSONArray("movies").length())
            assertEquals(movieHash,sha256(movie));assertEquals(hash,sha256(source))
            report.put("recreated",recreated)
        } finally {
            MetadataEpisodes.write(File(context.cacheDir,"local-metadata-e2e.json"),report)
            EpisodeWork.status(context,source.name)?.optJSONArray("movies")?.let { rows -> repeat(rows.length()) {
                val file=File(AnimationFiles.directory(context),rows.getString(it))
                listOf(file,File(file.path+".json"),File(file.path+".captions.json"),File(file.path+".poster.png"),PlaybackDiagnostics.file(file)).forEach { it.delete() }
            } }
            context.getSharedPreferences("tikitaka-episodes",0).edit().remove(source.name).commit()
            HighlightStore.batches(context).filter { it.sourceId==source.name }.forEach { batch ->
                batch.clips.firstOrNull()?.file?.parentFile?.let { folder ->
                    require(folder.name.startsWith("local-"));folder.listFiles().orEmpty().forEach { it.delete() };folder.delete()
                }
            }
            source.delete()
        }
    }
}
