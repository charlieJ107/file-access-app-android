package space.zhuoling.fileaccess.core.security

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.zhuoling.fileaccess.core.model.Credentials

class CredentialEnvelopeTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripPreservesUnicodeWithoutDisplayingSecret() {
        Credentials("用户", "pāss🔒秘密".toCharArray(), "家庭").use { credential ->
            val encrypted = CredentialEnvelope.encrypt("connection-a", credential, key)
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("秘密"))
            CredentialEnvelope.decrypt("connection-a", encrypted, key).use { restored ->
                assertArrayEquals(credential.password, restored.password)
                assertTrue(restored.username == credential.username)
                assertTrue(restored.domain == credential.domain)
                assertFalse(restored.toString().contains("秘密"))
            }
        }
    }

    @Test fun repeatedWritesUseDifferentNonces() {
        Credentials("user", "secret".toCharArray()).use { credential ->
            val first = CredentialEnvelope.encrypt("a", credential, key)
            val second = CredentialEnvelope.encrypt("a", credential, key)
            assertNotEquals(first.toList(), second.toList())
        }
    }

    @Test fun copyingCiphertextToAnotherCredentialReferenceFailsAuthentication() {
        val encrypted = Credentials("user", "secret".toCharArray()).use {
            CredentialEnvelope.encrypt("account-one", it, key)
        }
        assertThrows(GeneralSecurityException::class.java) {
            CredentialEnvelope.decrypt("account-two", encrypted, key)
        }
    }

    @Test fun tamperingAndTruncatingCiphertextFailsAuthentication() {
        val encrypted = Credentials("user", "secret".toCharArray()).use {
            CredentialEnvelope.encrypt("account", it, key)
        }
        val changed = encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            CredentialEnvelope.decrypt("account", changed, key)
        }
        assertThrows(GeneralSecurityException::class.java) {
            CredentialEnvelope.decrypt("account", encrypted.copyOf(encrypted.size - 1), key)
        }
    }

    @Test fun incorrectFormatAndOversizedEnvelopesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialEnvelope.decrypt("account", ByteArray(80), key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialEnvelope.decrypt("account", ByteArray(CredentialEnvelope.MAX_FILE_BYTES + 1), key)
        }
    }

    @Test fun closingCredentialsClearsOwnedPasswordArray() {
        val credential = Credentials("user", "secret".toCharArray())
        credential.close()
        assertTrue(credential.password.all { it == '\u0000' })
    }
}
