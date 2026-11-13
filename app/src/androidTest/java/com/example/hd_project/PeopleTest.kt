package com.example.hd_project

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.*
import com.example.hd_project.momo.model.*
import com.example.hd_project.momo.people.*
import com.example.hd_project.momo.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PeopleTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile()=PersonProfile(UUID.randomUUID().toString(),"인물 검증",CharacterCatalog.ids[1],
        VoiceReference("test-only",List(192) { if(it==0)1.0 else 0.0 },8_000_000,"검증용",0))
    @Test fun encryptedProfilesSurviveReopenDetectTamperingAndDelete() {
        val namespace="momo-people-instrumentation"
        val repository=PeopleRepository(context,namespace)
        val file=File(context.noBackupFilesDir,"$namespace/profiles.enc")
        val profile=profile()
        try {
            repository.save(profile)
            val bytes=file.readBytes()
            assertFalse(bytes.decodeToString().contains(profile.name))
            assertFalse(bytes.decodeToString().contains("embedding"))
            assertEquals(profile,PeopleRepository(context,namespace).list().single())
            val tampered=bytes.copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            assertThrows(Exception::class.java) { repository.list() }
            file.writeBytes(bytes)
            repository.save(profile.copy(name="수정된 이름",voice=null))
            assertNull(repository.list().single().voice)
            repository.delete(profile.id);assertTrue(repository.list().isEmpty())
        } finally {
            file.delete();File(file.path+".bak").delete();File(file.path+".new").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("momo.people.$namespace.v1") }
        }
    }
    @Test fun legacyPeopleEditorPersistsAcrossRotation() {
        var id: String?=null
        val repository=PeopleRepository(context)
        try {
            ActivityScenario.launch(PeopleActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.people_add).performClick()
                    val vm=ViewModelProvider(activity)[PeopleViewModel::class.java]
                    id=vm.state.value.draft!!.id
                    activity.findViewById<EditText>(R.id.people_name).setText("테스트 인물 · 삭제 예정")
                    vm.character(CharacterCatalog.ids[1])
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val vm=ViewModelProvider(activity)[PeopleViewModel::class.java]
                    assertEquals("테스트 인물 · 삭제 예정",vm.state.value.draft!!.name)
                    assertEquals(CharacterCatalog.ids[1],vm.state.value.draft!!.characterId)
                    activity.findViewById<View>(R.id.people_save).performClick()
                }
                val deadline=SystemClock.elapsedRealtime()+10000
                while(repository.list().none { it.id==id } && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
                assertNull(repository.list().first { it.id==id }.voice)
            }
        } finally { id?.let(repository::delete) }
    }
    @Test fun syntheticVoiceEnrollmentUsesSavedCharacter() = runBlocking {
        // Only the explicitly synthetic speech fixture can be uploaded by this test.
        val source=RecordingFiles.list(context).first { it.file.name=="momo_validation_synthetic_20261.m4a" }
        val projects=ProjectRepository(context)
        val project=projects.registerRecording(source)
        val bundle=projects.awaitAnalysis(project.projectId,true) {}
        val signature=bundle.result.speakerSignatures.first { it.speechUs>=6_000_000 }
        val people=PeopleRepository(context)
        var id: String?=null
        try {
            ActivityScenario.launch<PeopleActivity>(Intent(context,PeopleActivity::class.java)).use { scenario ->
                lateinit var vm: PeopleViewModel
                scenario.onActivity { activity ->
                    vm=ViewModelProvider(activity)[PeopleViewModel::class.java]
                    vm.edit();id=vm.state.value.draft!!.id;vm.name("합성음 등록 검증");vm.character(CharacterCatalog.ids[1]);vm.analyze(source)
                }
                val deadline=SystemClock.elapsedRealtime()+15000
                while(vm.state.value.analysis==null && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
                assertNotNull(vm.state.value.analysis)
                scenario.onActivity { vm.select(signature);vm.save() }
                while(people.list().none { it.id==id } && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
                val saved=people.list().first { it.id==id }
                assertEquals(bundle.result.voiceModelId,saved.voice!!.modelId)
                val preset=FixedPreset.install(context)
                val base=projects.scene(MomoVideoSession.projectId(source.file.name))
                val cast=PeopleScene.build(base,bundle.result,listOf(saved),preset)
                assertTrue(cast.scene.people.any { it.name==saved.name && it.manifestRef!!.contains("character_02") })
                assertEquals(bundle.result.speakers.size.coerceAtMost(4),cast.scene.people.size)
                val fresh=PeopleScene.build(base,bundle.result,emptyList(),preset)
                assertTrue(fresh.scene.people.all { it.name.startsWith("새로운 사람") && it.manifestRef!!.contains("character_01") })
            }
        } finally { id?.let(people::delete) }
    }
}
