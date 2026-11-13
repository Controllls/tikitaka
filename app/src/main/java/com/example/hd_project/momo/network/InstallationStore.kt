package com.example.hd_project.momo.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.hd_project.momo.model.MomoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class InstallationCredentials(val installationId: String, val installationSecret: String)
@Serializable data class InstallationState(val credentials: InstallationCredentials, val tokens: Tokens? = null)

/** Keystore-encrypted, excluded from backup, and scoped to this installation and server. */
class InstallationStore(context: Context, endpoint: String) {
    private val name = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }
    private val file = File(context.noBackupFilesDir, "momo-installation-$name.bin")
    private val atomic = AtomicFile(file)
    // Shared by every client/worker instance, including registration and refresh requests.
    val lock: Any = synchronized(locks) { locks.getOrPut(file.absolutePath) { Any() } }

    companion object {
        private val locks = mutableMapOf<String, Any>()
        private val keyLock = Any()
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "momo-installation-v1"
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): InstallationState = synchronized(lock) {
        try {
            if (!file.exists() && !File(file.path + ".bak").exists()) {
                val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val state = InstallationState(InstallationCredentials(UUID.randomUUID().toString(), secret))
                write(state) // Persist before the first request, so a lost response cannot change identity.
                state
            } else {
                val bytes = atomic.openRead().use { it.readBytes() }
                require(bytes.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                MomoJson.decodeFromString<InstallationState>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
            }
        } catch (e: Exception) {
            // Never silently replace an unreadable identity and lose ownership of server jobs.
            throw IOException("앱 인증 정보를 읽지 못했어요. 앱을 다시 실행해 주세요.", e)
        }
    }

    fun saveTokens(tokens: Tokens?) = synchronized(lock) { write(read().copy(tokens = tokens)) }

    private fun write(state: InstallationState) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.iv + cipher.doFinal(MomoJson.encodeToString(state).encodeToByteArray())
            val stream = atomic.startWrite()
            try { stream.write(encrypted); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        } catch (e: Exception) {
            throw IOException("앱 인증 정보를 저장하지 못했어요. 저장 공간을 확인해 주세요.", e)
        }
    }
}
