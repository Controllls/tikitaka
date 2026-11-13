package com.example.hd_project

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaExtractor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.render.SceneRenderer
import com.example.hd_project.momo.render.VideoComposer
import com.example.hd_project.momo.storage.sha256
import com.example.hd_project.momo.storage.ProjectRepository
import com.example.hd_project.momo.storage.HighlightRequest
import com.example.hd_project.momo.MomoVideoSession
import com.example.hd_project.momo.registerRecording
import androidx.work.WorkManager
import com.example.hd_project.tiki.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TikiIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun fixture(name: String)=instrumentation.context.assets.open(name)
    private fun waitFor(condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000
        while(!condition() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        assertTrue("Timed out",condition())
    }
    private fun click(scenario: ActivityScenario<MainActivity>,tag: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val view=activity.window.decorView.findViewWithTag<View>(tag)
            assertNotNull("Missing $tag",view);view.performClick()
        };instrumentation.waitForIdleSync()
    }
    @Test fun nativeNavigationDraftAndRecordOnlySurviveRecreation() {
        val prefs=context.getSharedPreferences("tikitaka-ui",0)
        val wasSetup=prefs.getBoolean("setup",false);prefs.edit().putBoolean("setup",true).commit()
        val profile=TikiProfile.repository(context).list()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click(scenario,"record_start")
            scenario.onActivity { it.window.decorView.findViewWithTag<CheckBox>("record_only").isChecked=true }
            scenario.recreate();instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<CheckBox>("record_only").isChecked) }
            click(scenario,"nav_settings")
            scenario.onActivity { activity ->
                fun find(view: View): View? {
                    if(view.contentDescription?.startsWith("내 이름,")==true)return view
                    if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let { return it }
                    return null
                }
                assertNotNull(find(activity.window.decorView));find(activity.window.decorView)!!.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("profile_name").setText("검증용 임시 이름") }
            scenario.recreate();instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("검증용 임시 이름",it.window.decorView.findViewWithTag<EditText>("profile_name").text.toString()) }
            assertEquals("Draft must not change saved profile",profile,TikiProfile.repository(context).list())
        } } finally { prefs.edit().putBoolean("setup",wasSetup).commit() }
    }

    @Test fun joinedAacHasContinuousMonotonicTimeAndLeavesOriginalIntact() {
        val a=File(context.cacheDir,"tiki-join-a.m4a");val out=File(context.cacheDir,"tiki-join-output.m4a")
        try {
            fixture("momo-synthetic-korean.m4a").use { input->a.outputStream().use { input.copyTo(it) } }
            val hash=sha256(a);val original=RecordingJournal.duration(a);val duration=RecordingJournal.join(listOf(a,a),out)
            assertTrue(duration in original*2-100..original*2+100);assertEquals(hash,sha256(a))
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(out.path);extractor.selectTrack(0);var previous=-1L;var count=0
                while(extractor.sampleTime>=0) { assertTrue(extractor.sampleTime>previous);previous=extractor.sampleTime;count++;extractor.advance() }
                assertTrue(count>100)
            } finally { extractor.release() }
        } finally { a.delete();out.delete() }
    }

    @Test fun recordOnlyPauseReleasesMicrophoneAndNeverEnqueuesProduction() {
        assertFalse("Do not interrupt a user's recording",RecordingStore.state.busy)
        val journal=File(RecordingFiles.directory(context),"session-journal.json")
        val journalBackup=journal.takeIf { it.isFile }?.readBytes()
        val existing=RecordingFiles.list(context).map { it.file.name }.toSet()
        val latest=AutomaticHighlights.latestSource(context)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
        val prefs=context.getSharedPreferences("tikitaka-ui",0);val wasSetup=prefs.getBoolean("setup",false)
        prefs.edit().putBoolean("setup",true).commit()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.startForegroundService(Intent(it,RecordingService::class.java).setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_RECORD_ONLY,true)) }
            waitFor { RecordingStore.state.phase==RecordingPhase.RECORDING };SystemClock.sleep(1800)
            scenario.onActivity { it.startService(Intent(it,RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE)) }
            waitFor { RecordingStore.state.phase==RecordingPhase.PAUSED }
            val paused=RecordingStore.state.durationMs();SystemClock.sleep(500);assertEquals(paused,RecordingStore.state.durationMs())
            val microphone=android.media.MediaRecorder(context)
            val probe=File(context.cacheDir,"tiki-microphone-release.m4a")
            try {
                microphone.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);microphone.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                microphone.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);microphone.setOutputFile(probe.path)
                microphone.prepare();microphone.start();SystemClock.sleep(500);microphone.stop()
            } finally { microphone.release();probe.delete() }
            scenario.onActivity { it.startService(Intent(it,RecordingService::class.java).setAction(RecordingService.ACTION_RESUME)) }
            waitFor { RecordingStore.state.phase==RecordingPhase.RECORDING };SystemClock.sleep(1800)
            scenario.onActivity { it.startService(Intent(it,RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) }
            waitFor { !RecordingStore.state.busy }
            val added=RecordingFiles.list(context).filter { it.file.name !in existing }
            assertEquals(1,added.size);assertTrue(RecordingJournal.duration(added.single().file) in 3000..7000)
            assertNull(EpisodeWork.status(context,added.single().file.name));assertEquals(latest,AutomaticHighlights.latestSource(context))
        } } finally {
            context.startService(Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            waitFor { !RecordingStore.state.busy }
            RecordingFiles.list(context).filter { it.file.name !in existing }.forEach { RecordingFiles.delete(it) }
            if(journalBackup==null)journal.delete() else journal.writeBytes(journalBackup)
            prefs.edit().putBoolean("setup",wasSetup).commit()
        }
    }

    @Test fun allEightDesignerSpritesAndRealThreeSecondExport() = runBlocking {
        val result=MomoJson.decodeFromString<AnalysisResult>(fixture("result-3.json").bufferedReader().use { it.readText() })
        val tracks=MomoJson.decodeFromString<MotionTracks>(fixture("tracks-3.json").bufferedReader().use { it.readText() })
        val analysis=AnalysisBundle(result,tracks)
        val audio=File(context.cacheDir,"tiki-render-input.wav")
        fixture("sample-3.wav").use { input->audio.outputStream().use { input.copyTo(it) } }
        val folder=File(context.filesDir,"tikitaka-validation").apply { mkdirs() }
        try {
            val base=SceneProject("tiki-test","합성 음성 검증",audio.path,sha256(audio),result.durationUs)
            val scene=TikiScene.build(context,base,analysis,emptyList())
            val root=TikiScene.install(context)
            for(character in TikiProfile.characters) {
                val current=scene.copy(people=scene.people.map { it.copy(manifestRef=File(root,"characters/$character/manifest.json").path) })
                val renderer=SceneRenderer(current,analysis)
                val bitmap=Bitmap.createBitmap(540,960,Bitmap.Config.ARGB_8888)
                renderer.draw(Canvas(bitmap),850000)
                File(folder,"sprite-$character.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                renderer.close();bitmap.recycle()
            }
            val output=File(folder,"tiki-three-seconds.mp4").apply { delete() }
            val started=SystemClock.elapsedRealtime()
            VideoComposer.exportFile(context,VideoComposer.prepare(context,scene,analysis),output) {}
            VideoComposer.validateMp4(output)
            File(folder,"export.json").writeText(JSONObject().put("durationMs",3000).put("exportMs",SystemClock.elapsedRealtime()-started).toString())
        } finally { audio.delete() }
    }

    @Test fun durableEpisodePipelineUsesSavedAnalysisAndProducesOnlyOneMovie() = runBlocking {
        val name="tiki-pipeline-${java.util.UUID.randomUUID()}_3000.m4a"
        val original=File(RecordingFiles.directory(context),name)
        val batch=File(context.noBackupFilesDir,"highlights/test-$name").apply { mkdirs() }
        val input=File(context.noBackupFilesDir,"tikitaka-episode-inputs/${EpisodeWork.id(name)}-1.m4a")
        val repository=ProjectRepository(context)
        val projectId=MomoVideoSession.projectId(input.name)
        val output=AnimationFiles.output(context,input)
        try {
            fixture("tiki-synthetic-3.m4a").use { data->original.outputStream().use { data.copyTo(it) } }
            val hash=sha256(original)
            original.copyTo(File(batch,"clip_1.m4a"))
            File(batch,"index.json").writeText(JSONObject().put("sourceId",name).put("createdAt",System.currentTimeMillis())
                .put("clips",JSONArray().put(JSONObject().put("file","clip_1.m4a").put("title","합성 음성 자동 제작 검증")
                    .put("startMs",0).put("endMs",3000).put("score",1.0))).toString())
            repository.dao.putHighlight(HighlightRequest(name,"fixture","test-key",System.currentTimeMillis(),state="completed",audioSha256=hash))
            input.parentFile!!.mkdirs();original.copyTo(input)
            repository.registerRecording(SavedRecording(input,3000))
            val result=MomoJson.decodeFromString<AnalysisResult>(fixture("result-3.json").bufferedReader().use { it.readText() })
            val tracks=MomoJson.decodeFromString<MotionTracks>(fixture("tracks-3.json").bufferedReader().use { it.readText() })
            repository.cache(projectId,"fixture",AnalysisBundle(result.copy(audio=result.audio.copy(sha256=hash),voiceModelId="fixture-model"),tracks))
            // A previously queued legacy production must still resume its cached analyses.
            EpisodeWork.update(context,name,"queued","이전 버전 작업 복원 검증")
            EpisodeWork.enqueue(context,original);EpisodeWork.enqueue(context,original)
            val deadline=SystemClock.elapsedRealtime()+90000
            while(SystemClock.elapsedRealtime()<deadline && EpisodeWork.status(context,name)?.optString("state") !in listOf("completed","failed"))
                kotlinx.coroutines.delay(250)
            val state=EpisodeWork.status(context,name)!!
            assertEquals(state.optString("message"),"completed",state.getString("state"))
            VideoComposer.validateMp4(output)
            assertEquals(1,state.getJSONArray("movies").length())
            val modified=output.lastModified();EpisodeWork.enqueue(context,original,true)
            assertEquals(modified,output.lastModified());assertEquals(hash,sha256(original))
            assertEquals(1,WorkManager.getInstance(context).getWorkInfosForUniqueWork("tikitaka-"+EpisodeWork.id(name)).get().size)
            val report=File(context.filesDir,"tikitaka-validation/pipeline.json")
            report.parentFile!!.mkdirs();report.writeText(state.put("fixtureOnly",true).put("duplicateWorkCount",1).toString(2))
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("tikitaka-"+EpisodeWork.id(name)).result.get()
            WorkManager.getInstance(context).cancelUniqueWork(AutomaticHighlights.workName(name)).result.get()
            repository.deleteLocal(projectId);repository.dao.deleteHighlight(name)
            context.getSharedPreferences("tikitaka-episodes",0).edit().remove(name).commit()
            context.getSharedPreferences("automatic-highlights",0).edit().remove("state:$name").remove("message:$name").commit()
            original.delete();input.delete();output.delete();File(output.path+".json").delete();File(output.path+".captions.json").delete()
            File(batch,"index.json").delete();File(batch,"clip_1.m4a").delete();batch.delete()
            val profile=File(context.noBackupFilesDir,"tikitaka-job-${EpisodeWork.id(name)}/profiles.enc")
            profile.delete();profile.parentFile?.delete()
        }
    }

    @Test fun nativeLayoutsAtTwelvePleosAppWindowPresets() {
        val presets=listOf("s17-land" to (1700 to 1184),"s17-port" to (840 to 1184),"s146-land" to (1360 to 948),"s146-port" to (672 to 948),
            "s129-land" to (1274 to 855),"s129-port" to (628 to 855),"wide-land" to (1640 to 810),"wide-port" to (800 to 810),
            "extended-land" to (1640 to 1400),"extended-port" to (800 to 1400),"waterfall-land" to (1256 to 800),"waterfall-port" to (648 to 800))
        val prefs=context.getSharedPreferences("tikitaka-ui",0);val previous=prefs.getBoolean("setup",false);prefs.edit().putBoolean("setup",true).commit()
        val report=JSONArray();val folder=File(context.filesDir,"tikitaka-validation").apply { mkdirs() }
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val filter=InstrumentationRegistry.getArguments().getString("layoutScreens")?.split(",")
            for((preset,size) in presets)for(screen in listOf("home","character","library","recordings","settings","voice","voice-error","voice-success","voice-recording","profile","microphone","progress","saved","phone","name","privacy").filter { filter==null || it in filter }) {
                val actual=if(screen=="recordings")"library" else if(screen.startsWith("voice"))"voice" else screen
                if(screen in listOf("home","character","library","recordings","settings")) {
                    click(scenario,"nav_${if(screen=="recordings")"library" else screen}")
                    if(screen=="recordings")click(scenario,"tab_recordings") else if(screen=="library")click(scenario,"tab_episodes")
                } else scenario.onActivity { activity ->
                    TikiActivity::class.java.getDeclaredMethod("go",String::class.java).apply { isAccessible=true }.invoke(activity,actual)
                    if(actual=="voice") {
                        val model=TikiActivity::class.java.getDeclaredMethod("getVoiceModel").apply { isAccessible=true }.invoke(activity) as TikiVoiceModel
                        model.state.value=VoiceSetup(screen.substringAfter("-","intro"),6,"주변 소음을 줄이고 다시 읽어 주세요.")
                    }
                }
                scenario.onActivity { activity ->
                    val root=activity.window.decorView.findViewWithTag<FrameLayout>("tiki_root")
                    val density=activity.resources.displayMetrics.density
                    val width=(size.first*density).toInt();val height=(size.second*density).toInt()
                    root.setPadding(0,0,0,0)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,width,height)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root=activity.window.decorView.findViewWithTag<FrameLayout>("tiki_root")
                    val density=activity.resources.displayMetrics.density;val width=(size.first*density).toInt();val height=(size.second*density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
                    // The window manager can restore the live Activity's size between frames.
                    // Render synchronously after applying this explicit static validation size.
                    TikiActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
                    val body=root.findViewWithTag<View>("tiki_$actual")
                    assertEquals((size.first-if(actual=="profile")0 else 130)*density,body.width.toFloat(),2f)
                    val errors=JSONArray()
                    fun check(view: View) {
                        if(view!==root && view.parent is FrameLayout && view.parent !is android.widget.HorizontalScrollView && view.width>0) {
                            val parent=view.parent as FrameLayout
                            if(view.left < -2 || view.right>parent.width+2)errors.put("Horizontal bounds: ${view.contentDescription ?: view.tag ?: view.javaClass.simpleName}")
                        }
                        if(view is TextView && view !is EditText && view.text.isNotEmpty()) {
                            val layout=view.layout
                            if(layout!=null && layout.height>view.height-view.paddingTop-view.paddingBottom+2)
                                errors.put("Text clipped: ${view.text.take(60)}")
                        }
                        if(view is ViewGroup)for(i in 0 until view.childCount)check(view.getChildAt(i))
                    };check(root)
                    val entry=JSONObject().put("preset",preset).put("screen",screen).put("widthDp",size.first).put("heightDp",size.second)
                        .put("density",density).put("textClips",errors);report.put(entry)
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
                    File(folder,"$preset-$screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
            }
        } } finally { prefs.edit().putBoolean("setup",previous).commit();File(folder,"layouts.json").writeText(report.toString(2)) }
        val clips=(0 until report.length()).sumOf { report.getJSONObject(it).getJSONArray("textClips").length() }
        assertEquals("See tikitaka-validation/layouts.json",0,clips)
    }

    @Test fun actualNarrowVirtualDisplayUsesPortraitLayoutAndDensity() {
        val prefs=context.getSharedPreferences("tikitaka-ui",0);val previous=prefs.getBoolean("setup",false);prefs.edit().putBoolean("setup",true).commit()
        val reader=android.media.ImageReader.newInstance(1050,1480,android.graphics.PixelFormat.RGBA_8888,2)
        reader.setOnImageAvailableListener({ source->source.acquireLatestImage()?.close() },android.os.Handler(android.os.Looper.getMainLooper()))
        val manager=context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display=manager.createVirtualDisplay("tikitaka-test-840dp",1050,1480,200,reader.surface,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        assertNotNull(display)
        try {
            val options=android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.display.displayId)
            val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            ActivityScenario.launch<MainActivity>(intent,options.toBundle()).use { scenario ->
                instrumentation.waitForIdleSync();SystemClock.sleep(400)
                scenario.onActivity { activity ->
                    val root=activity.window.decorView.findViewWithTag<FrameLayout>("tiki_root")
                    val body=root.findViewWithTag<View>("tiki_home")
                    val density=activity.resources.displayMetrics.density
                    assertEquals(1.25f,density,.01f)
                    assertEquals(display.display.displayId,activity.display!!.displayId)
                    val width=(root.width-root.paddingLeft-root.paddingRight)/density
                    assertEquals(840f,width,2f);assertEquals(710f,body.width/density,2f)
                    val folder=File(context.filesDir,"tikitaka-validation").apply { mkdirs() }
                    File(folder,"virtual-display.json").writeText(JSONObject().put("displayId",activity.display!!.displayId)
                        .put("density",density).put("windowWidthDp",width).put("windowHeightDp",(root.height-root.paddingTop-root.paddingBottom)/density).toString())
                    val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
                    File(folder,"virtual-840dp-home.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
            }
        } catch(error: SecurityException) {
            File(context.filesDir,"tikitaka-validation/virtual-display.json").writeText(JSONObject().put("status","not_verified")
                .put("reason","Pleos image denied launch on the test-created secondary display").toString())
            org.junit.Assume.assumeNoException("Run the official FormFactorConsole on this image",error)
        } finally { display.release();reader.close();prefs.edit().putBoolean("setup",previous).commit() }
    }
}
