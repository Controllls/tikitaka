package com.example.hd_project.tiki

import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.model.SceneProject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun completionOpensNewestExistingVideoInsteadOfTheOriginal() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val source=InstrumentationRegistry.getArguments().getString("diagnosticSource")
        org.junit.Assume.assumeTrue("Explicit completed source required",!source.isNullOrBlank())
        val job=EpisodeWork.status(context,source!!)!!;assertEquals("completed",job.getString("state"))
        val names=job.getJSONArray("movies");assertTrue(names.length()>1)
        val expected=(names.length()-1 downTo 0).map { names.getString(it) }
            .first { java.io.File(com.example.hd_project.AnimationFiles.directory(context),it).isFile }
        assertNotEquals(names.getString(0),expected)
        androidx.test.core.app.ActivityScenario.launch<com.example.hd_project.MainActivity>(android.content.Intent(context,com.example.hd_project.MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                TikiActivity::class.java.getDeclaredField("source").apply { isAccessible=true }.set(activity,source)
                TikiActivity::class.java.getDeclaredMethod("go",String::class.java).apply { isAccessible=true }.invoke(activity,"progress")
                val button=activity.window.decorView.findViewWithTag<android.view.View>("episode_result")
                assertNotNull(button);button.performClick()
                assertEquals(expected,TikiActivity::class.java.getDeclaredField("movie").apply { isAccessible=true }.get(activity))
            }
        }
    }

    @Test fun detectionDoesNotTreatAnonymousStagingAsASecondIdentifiedSpeaker() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clip=JSONObject().put("captions",JSONArray().put(JSONObject().put("start_ms",0).put("end_ms",3000).put("text","누구인지 확인되지 않은 대사")))
            .put("laughter",JSONArray().put(JSONObject().put("start_ms",1200).put("end_ms",1800).put("score",.3)))
        val bundle=MetadataEpisodes.bundle(clip,"detection-test",0,3000000,"b".repeat(64),2,List(150){if(it<10)0.0 else .01},mapOf(0L to 1))
        val scene=TikiScene.buildPerformance(context,SceneProject("detection-test","검증","unused","b".repeat(64),3000000),count=2)
        val checks=JSONArray().put(JSONObject().put("startMs",0).put("endMs",3000).put("score",JSONObject.NULL).put("reason","not_enrolled"))
        val trace=JSONArray().put(PlaybackDiagnostics.frame(LaughFrame(0,3000,.043,.059,0.0,0.0,0.0,"duration_or_contrast")))
        val data=PlaybackDiagnostics.build(scene,bundle,0,.1,"partial",checks,trace)
        val timeline=PlaybackDiagnostics.Timeline(data)
        assertEquals(false,timeline.detection(0).unknown) // A broad STT segment alone must not light up silence.
        assertEquals(true,timeline.detection(300).unknown)
        assertNull(timeline.detection(300).self)
        assertTrue(timeline.summary(300,1).contains("화자 1: — 등록 없음"))
        assertTrue(timeline.summary(300,1).contains("웃음 점수: 0.043 · 필터 0.059"))
        assertTrue(timeline.summary(300,1).contains("최종 반영: 0.000"))
        assertFalse(timeline.summary(300,1).contains("웃음 검출:"))
        assertTrue(timeline.summary(300,1).contains("화자 2: 구분 꺼짐"))
        assertFalse(timeline.summary(300,1).contains("화자 2: 감지됨"))
        assertEquals(true,timeline.detection(1400).laughter)
        checks.getJSONObject(0).put("reason","matched").put("score",.8)
        val matched=PlaybackDiagnostics.Timeline(data)
        assertEquals(true,matched.detection(300).self);assertEquals(false,matched.detection(300).unknown)
        assertTrue(matched.summary(300,1).contains("화자 1: ● 감지됨"))
        assertTrue(matched.summary(300,1).contains("화자 불분명: 대기"))
        assertTrue(matched.summary(0,1).contains("화자 1: 대기"))
        assertEquals(true,matched.detection(1400).unknown) // Matching dialogue never proves who laughed.
        assertEquals(false,matched.detection(3000).laughter);assertEquals(false,matched.detection(3000).unknown)
    }
    @Test fun acquiringAnotherPlayerPausesThePreviousOne() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val source=java.io.File(context.cacheDir,"focus-test-${java.util.UUID.randomUUID()}.m4a")
        instrumentation.context.assets.open("tiki-synthetic-3.m4a").use { input -> source.outputStream().use { input.copyTo(it) } }
        val first=android.media.MediaPlayer();val second=android.media.MediaPlayer()
        val focus=com.example.hd_project.pleos.MediaPlaybackFocus(context)
        try {
            for(player in listOf(first,second)) { player.setDataSource(source.path);player.prepare() }
            assertTrue(focus.acquire(first));first.setVolume(0f,0f);first.start();assertTrue(first.isPlaying)
            assertTrue(focus.acquire(second));assertFalse("Two app players must not continue together",first.isPlaying)
            assertFalse("Acquiring focus must not auto-start playback",second.isPlaying)
        } finally { focus.release();first.release();second.release();source.delete() }
    }
    @Test fun diagnosticMovieFitsPresetsAndSeekingUpdatesThePanel() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val name=InstrumentationRegistry.getArguments().getString("diagnosticMovie")
        org.junit.Assume.assumeTrue("Explicit local diagnostic movie required",!name.isNullOrBlank())
        val context=instrumentation.targetContext
        val movie=java.io.File(com.example.hd_project.AnimationFiles.directory(context),name!!)
        require(PlaybackDiagnostics.file(movie).isFile)
        val preferences=context.getSharedPreferences("tikitaka-ui",0)
        val old=preferences.getBoolean("playbackDiagnostics",true);preferences.edit().putBoolean("playbackDiagnostics",true).commit()
        val oldDetails=preferences.getBoolean("playbackDiagnosticsDetails",false)
        preferences.edit().putBoolean("playbackDiagnosticsDetails",false).commit()
        val folder=java.io.File(context.filesDir,"playback-diagnostics-validation").apply { mkdirs() };val report=JSONArray()
        try {
            androidx.test.core.app.ActivityScenario.launch<com.example.hd_project.MainActivity>(android.content.Intent(context,com.example.hd_project.MainActivity::class.java).putExtra("episodeMovie",name)).use { scenario ->
                fun field(activity: TikiActivity,name: String)=TikiActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
                val deadline=android.os.SystemClock.elapsedRealtime()+8000
                var prepared=false
                while(!prepared && android.os.SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { prepared=field(it,"player")!=null };android.os.SystemClock.sleep(100)
                }
                assertTrue("Video was not prepared",prepared)
                scenario.onActivity { (field(it,"video") as android.widget.VideoView).seekTo(4200) }
                android.os.SystemClock.sleep(600)
                scenario.onActivity {
                    val panel=it.window.decorView.findViewWithTag<android.widget.TextView>("playback_diagnostics")
                    assertNotNull(panel);assertTrue(panel.text.contains("오디오 1트랙"))
                    for(label in listOf("웃음 점수:","최종 반영:","화자 1:","화자 2:","화자 불분명:"))assertTrue(panel.text.contains(label))
                    assertNull(it.window.decorView.findViewWithTag<android.widget.TextView>("playback_diagnostics_details"))
                    assertNotNull(it.window.decorView.findViewWithTag<android.widget.TextView>("diagnostics_play"))
                    assertFalse(panel.text.startsWith("재생 0.000s"))
                    assertFalse((field(it,"video") as android.widget.VideoView).isPlaying)
                    assertEquals("A seek must reveal the actual frame, not the cover",android.view.View.GONE,(field(it,"videoPoster") as android.view.View).visibility)
                }
                val presets=listOf(1700 to 1184,840 to 1184,1360 to 948,672 to 948,1274 to 855,628 to 855,
                    1640 to 810,800 to 810,1640 to 1400,800 to 1400,1256 to 800,648 to 800)
                for((widthDp,heightDp) in presets)for(expanded in listOf(false,true))scenario.onActivity { activity ->
                    preferences.edit().putBoolean("playbackDiagnosticsDetails",expanded).commit()
                    val root=activity.window.decorView.findViewWithTag<android.widget.FrameLayout>("tiki_root")
                    val density=activity.resources.displayMetrics.density;val width=(widthDp*density).toInt();val height=(heightDp*density).toInt()
                    fun layout() {
                        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                        root.layout(0,0,width,height)
                    }
                    root.setPadding(0,0,0,0);layout()
                    TikiActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity);layout()
                    val viewport=root.findViewWithTag<android.widget.FrameLayout>("episode_video_frame")
                    assertNotNull(viewport)
                    assertEquals("Video viewport must remain 16:9 in both orientations",16.0/9,viewport.width.toDouble()/viewport.height,.01)
                    val contentWidth=widthDp-131f
                    val expectedWidth=minOf(1120f,contentWidth-if(contentWidth>=900)96f else 64f)
                    assertEquals("Figma media width must use the available content width",expectedWidth,viewport.width/density,2f)
                    (field(activity,"scrollView") as android.widget.ScrollView).scrollTo(0,0)
                    val errors=JSONArray()
                    fun check(view: android.view.View) {
                        if(view is android.widget.TextView && view.text.isNotEmpty() && view.layout!=null && view.layout.height>view.height-view.paddingTop-view.paddingBottom+2)errors.put(view.text.take(50))
                        if(view!==root && view.parent is android.widget.FrameLayout && view.width>0) {
                            val parent=view.parent as android.widget.FrameLayout
                            if(view.left<0 || view.right>parent.width+2)errors.put("Horizontal: ${view.tag}")
                        }
                        if(view is android.view.ViewGroup)repeat(view.childCount) { check(view.getChildAt(it)) }
                    };check(root)
                    report.put(JSONObject().put("widthDp",widthDp).put("heightDp",heightDp).put("details",expanded).put("errors",errors)
                        .put("videoWidthDp",viewport.width/density).put("videoHeightDp",viewport.height/density))
                    val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap));java.io.File(folder,"$widthDp-$heightDp-$expanded.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                assertEquals(report.toString(),0,(0 until report.length()).sumOf { report.getJSONObject(it).getJSONArray("errors").length() })
                scenario.recreate();instrumentation.waitForIdleSync()
                scenario.onActivity { assertNotNull(it.window.decorView.findViewWithTag<android.widget.TextView>("playback_diagnostics")) }
            }
        } finally { preferences.edit().putBoolean("playbackDiagnostics",old).putBoolean("playbackDiagnosticsDetails",oldDetails).commit();java.io.File(folder,"layouts.json").writeText(report.toString(2)) }
    }
    @Test fun seekShowsActualAssignedMotionAndMissingIdentityWithoutInventingScores() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clip=JSONObject().put("captions",JSONArray()
            .put(JSONObject().put("start_ms",0).put("end_ms",1000).put("text","왜 내 대사야?"))
            .put(JSONObject().put("start_ms",1000).put("end_ms",3000).put("text","다른 대사")))
            .put("laughter",JSONArray().put(JSONObject().put("start_ms",1200).put("end_ms",1800).put("score",.3)))
        val bundle=MetadataEpisodes.bundle(clip,"diagnostic-test",0,3000000,"a".repeat(64),2,List(150){.01},mapOf(0L to 0,1000L to 1))
        val scene=TikiScene.buildPerformance(context,SceneProject("diagnostic-test","검증","unused","a".repeat(64),3000000),count=2)
        val checks=JSONArray().put(JSONObject().put("startMs",0).put("endMs",1000).put("score",.8).put("reason","matched"))
            .put(JSONObject().put("startMs",1000).put("endMs",3000).put("score",JSONObject.NULL).put("reason","not_enrolled"))
        val data=PlaybackDiagnostics.build(scene,bundle,5000,.1,"partial",checks,JSONArray())
        val frames=data.getJSONArray("frames")
        assertTrue(frames.getJSONObject(2).getJSONArray("actors").getJSONObject(0).getBoolean("speech"))
        repeat(frames.length()) { i -> val actors=frames.getJSONObject(i).getJSONArray("actors")
            repeat(actors.length()) { j -> val actor=actors.getJSONObject(j)
                if(actor.getBoolean("smile") || actor.getDouble("mouth")>.18)assertEquals("front",actor.getString("gaze"))
            }
        }
        val overlap=frames.getJSONObject(14).getJSONArray("actors")
        assertTrue(overlap.getJSONObject(1).getBoolean("speech"));assertFalse(overlap.getJSONObject(0).getBoolean("speech"))
        assertTrue(overlap.getJSONObject(0).getBoolean("laughAssigned"))
        val timeline=PlaybackDiagnostics.Timeline(data)
        assertTrue(timeline.text(1400,1).contains("원본 6.400s"))
        assertTrue(timeline.text(1400,1).contains("등록 없음"))
        assertTrue(timeline.text(1400,1).contains("유사도 —"))
        assertTrue(timeline.text(200,1).contains("0.800"))
        assertFalse(timeline.text(3000,1).contains("말 ON"))
    }
}
