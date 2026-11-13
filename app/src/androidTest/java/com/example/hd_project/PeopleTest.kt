package com.example.hd_project

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hd_project.momo.people.*
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
}
