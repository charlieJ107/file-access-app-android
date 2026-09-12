package space.zhuoling.fileaccess.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.model.Credentials
import space.zhuoling.fileaccess.core.security.CredentialUnavailableException
import space.zhuoling.fileaccess.core.security.KeystoreCredentialStore

@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun AndroidKeystoreCredentialsSurviveNewStoreAndCanBeDeleted() = runBlocking {
        val reference = "instrumentation-${UUID.randomUUID()}"
        val store = KeystoreCredentialStore(context)
        try {
            Credentials("test-user", "a-test-secret-密码".toCharArray()).use { store.put(reference, it) }
            val ciphertext = credentialFile(reference).readBytes()
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains("a-test-secret"))
            assertTrue(credentialFile(reference).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
            requireNotNull(KeystoreCredentialStore(context).get(reference)).use {
                assertArrayEquals("a-test-secret-密码".toCharArray(), it.password)
            }
            store.delete(reference)
            assertNull(store.get(reference))
            assertFalse(credentialFile(reference).exists())
        } finally {
            store.delete(reference)
        }
    }

    @Test fun corruptedCiphertextDoesNotGetSilentlyReplaced() = runBlocking {
        val reference = "instrumentation-${UUID.randomUUID()}"
        val store = KeystoreCredentialStore(context)
        try {
            Credentials("test-user", "test-password".toCharArray()).use { store.put(reference, it) }
            val changed = credentialFile(reference).readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            credentialFile(reference).writeBytes(changed)
            var unavailable = false
            try { store.get(reference) } catch (_: CredentialUnavailableException) { unavailable = true }
            assertTrue(unavailable)
            assertArrayEquals(changed, credentialFile(reference).readBytes())
        } finally {
            store.delete(reference)
        }
    }

    private fun credentialFile(reference: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(reference.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "credentials/$digest.bin")
    }
}
