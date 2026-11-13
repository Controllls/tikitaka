package com.example.hd_project.momo.people

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.hd_project.momo.model.MomoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable private data class PeopleDocument(val version: Int=1,val people: List<PersonProfile> = emptyList())

/** No plaintext identity files, raw voice copies, backup or server profile uploads. */
class PeopleRepository(context: Context, storageName: String="momo-people") {
    private val file=AtomicFile(File(context.noBackupFilesDir,"$storageName/profiles.enc"))
    private val alias="momo.people.$storageName.v1"
    init { require(storageName.matches(Regex("[a-z0-9-]+"))) }
    companion object { private val lock=Any() }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(!file.baseFile.exists()) { "저장된 인물 정보를 복호화할 수 없어요." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun list(): List<PersonProfile> = synchronized(lock) {
        // openRead also recovers AtomicFile's backup after an interrupted write.
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return@synchronized emptyList()
        val bytes=file.openRead().use { it.readBytes() }
        require(bytes.size>29 && bytes[0]==1.toByte()) { "저장된 인물 정보를 확인해 주세요." }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(alias.toByteArray())
        val document=MomoJson.decodeFromString<PeopleDocument>(cipher.doFinal(bytes.copyOfRange(13,bytes.size)).decodeToString())
        require(document.version==1 && document.people.size<=20 && document.people.map { it.id }.distinct().size==document.people.size)
        document.people.onEach { it.validate() }
    }
    fun save(profile: PersonProfile) = synchronized(lock) {
        profile.validate()
        val people=list().toMutableList()
        val index=people.indexOfFirst { it.id==profile.id }
        if(index<0) { require(people.size<20) { "인물은 최대 20명까지 등록할 수 있어요." };people.add(profile) } else people[index]=profile
        write(people)
    }
    fun delete(id: String) = synchronized(lock) { write(list().filterNot { it.id==id }) }
    private fun write(people: List<PersonProfile>) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(alias.toByteArray())
        val bytes=byteArrayOf(1)+cipher.iv+cipher.doFinal(MomoJson.encodeToString(PeopleDocument(people=people)).toByteArray())
        file.baseFile.parentFile!!.mkdirs()
        val out=file.startWrite()
        try { out.write(bytes);file.finishWrite(out) } catch(e: Exception) { file.failWrite(out);throw e }
    }
}
