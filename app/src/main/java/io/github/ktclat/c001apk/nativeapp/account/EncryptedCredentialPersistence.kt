package io.github.ktclat.c001apk.nativeapp.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.nio.ByteBuffer

/** Platform non-exportable AndroidKeyStore AES/GCM; no plaintext or legacy fallback.
 * Files are excluded from backup. Key loss or authentication failure requires explicit recovery.
 * Hardware-backed protection depends on the device and is not assumed.
 */
class EncryptedCredentialPersistence(context: Context) : CredentialPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account-vault.bin"))
    private val alias = "c001apk.native.account.v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun existingKey(): SecretKey = keyStore().getKey(alias, null) as? SecretKey ?: throw CredentialRecoveryRequired()
    private fun writeKey(): SecretKey {
        val store = keyStore()
        if (store.containsAlias(alias)) return existingKey()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw CredentialRecoveryRequired()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    override fun read(): ByteArray? {
        try {
            val blob = file.openRead().use { input ->
                require(file.baseFile.length() <= 2 * 1024 * 1024); input.readBytes()
            }
            return CredentialEnvelope.decrypt(blob, existingKey())
        } catch (_: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
            throw CredentialRecoveryRequired()
        } catch (_: Exception) { throw CredentialRecoveryRequired() }
    }
    override fun write(bytes: ByteArray) {
        val blob = CredentialEnvelope.encrypt(bytes, writeKey())
        val output = file.startWrite()
        try { output.write(blob); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }
    override fun reset() { file.delete(); keyStore().deleteEntry(alias) }
}

/** Authenticated envelope codec shared by platform storage and synthetic JVM integrity tests. */
internal object CredentialEnvelope {
    private const val MAGIC = 0x43304132
    private val AAD = "c001apk-native-account-vault-v2".toByteArray(Charsets.UTF_8)
    fun encrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        require(bytes.size <= 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key); updateAAD(AAD)
        }
        val encrypted = cipher.doFinal(bytes)
        require(cipher.iv.size == 12)
        return ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(MAGIC).put(cipher.iv).put(encrypted).array()
    }
    fun decrypt(blob: ByteArray, key: SecretKey): ByteArray {
        require(blob.size in 32..(2 * 1024 * 1024))
        val buffer = ByteBuffer.wrap(blob)
        require(buffer.int == MAGIC && buffer.remaining() >= 28)
        val iv = ByteArray(12).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(AAD); doFinal(ciphertext)
        }
    }
}
