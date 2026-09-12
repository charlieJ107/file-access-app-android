package space.zhuoling.fileaccess.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.core.model.Credentials

/** One application-scoped instance must be shared by repositories. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "credentials")
    private val mutex = Mutex()

    override suspend fun get(reference: String): Credentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = secretFile(reference)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                return@withLock null
            }
            try {
                // Reading must never create a replacement key for existing ciphertext.
                val key = keyStore().getKey(alias(reference), null) as? SecretKey
                    ?: throw CredentialUnavailableException()
                val bytes = file.openRead().use { input ->
                    val bytes = input.readNBytes(CredentialEnvelope.MAX_FILE_BYTES + 1)
                    require(bytes.size <= CredentialEnvelope.MAX_FILE_BYTES)
                    bytes
                }
                CredentialEnvelope.decrypt(reference, bytes, key)
            } catch (_: Exception) {
                throw CredentialUnavailableException()
            }
        }
    }

    override suspend fun put(reference: String, credentials: Credentials) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(directory.isDirectory || directory.mkdirs()) { "Credential storage unavailable" }
            val store = keyStore()
            val key = (store.getKey(alias(reference), null) as? SecretKey) ?: createKey(reference)
            val encrypted = CredentialEnvelope.encrypt(reference, credentials, key)
            val file = secretFile(reference)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    override suspend fun delete(reference: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            secretFile(reference).delete()
            keyStore().deleteEntry(alias(reference))
        }
    }

    private fun secretFile(reference: String) = AtomicFile(File(directory, "${digest(reference)}.bin"))
    private fun alias(reference: String) = "fileaccess.credentials.v1.${digest(reference)}"

    private fun digest(reference: String): String {
        require(reference.isNotBlank() && reference.length <= 512)
        return MessageDigest.getInstance("SHA-256").digest(reference.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createKey(reference: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias(reference), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    // NAS credentials must remain usable during explicitly configured backup.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
}
