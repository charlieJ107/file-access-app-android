package space.zhuoling.fileaccess.protocol.smb

import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.*

class SmbSecurityConfigTest {
    @Test fun signingIsRequiredAndLegacyNegotiationAndDfsAreDisabled() {
        val config = SmbStorageProvider().clientConfig(false)
        assertTrue(config.isSigningRequired)
        assertTrue(config.isSigningEnabled)
        assertFalse(config.isUseMultiProtocolNegotiate)
        assertFalse(config.isDfsEnabled)
        assertFalse(config.isEncryptData)
        assertTrue(config.soTimeout > 0)
        assertTrue(config.readTimeout > 0)
        assertTrue(config.supportedDialects.all { it.name.startsWith("SMB_2_") || it.name.startsWith("SMB_3_") })
    }

    @Test fun requiredEncryptionDoesNotOfferSmb2Downgrade() {
        val config = SmbStorageProvider().clientConfig(true)
        assertTrue(config.isEncryptData)
        assertTrue(config.supportedDialects.all { it.isSmb3x })
    }

    @Test fun encryptedSessionsSupplyIntegrityWithoutTheSeparateSigningFlag() {
        assertEquals(TransportProtection.ENCRYPTED, validateProtection(signed = false, encrypted = true, requireEncryption = true))
        assertEquals(TransportProtection.SIGNED, validateProtection(signed = true, encrypted = false, requireEncryption = false))
        assertThrows(StorageException::class.java) { validateProtection(signed = true, encrypted = false, requireEncryption = true) }
        assertThrows(StorageException::class.java) { validateProtection(signed = false, encrypted = false, requireEncryption = false) }
    }
}
