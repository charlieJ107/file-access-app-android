package space.zhuoling.fileaccess.protocol.smb

import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.*

class SmbErrorsTest {
    @Test fun distinguishesSharePermissionsFromAuthentication() {
        assertEquals(StorageError.AUTHENTICATION, map(0xC000006D))
        assertEquals(StorageError.PERMISSION, map(0xC0000022))
    }

    @Test fun detectsConflictQuotaMissingShareAndNoFollow() {
        assertEquals(StorageError.CONFLICT, map(0xC0000035))
        assertEquals(StorageError.CONFLICT, map(0xC0000101))
        assertEquals(StorageError.QUOTA_EXCEEDED, map(0xC000007F))
        assertEquals(StorageError.NOT_FOUND, map(0xC00000CC))
        assertEquals(StorageError.UNSUPPORTED, map(0x8000002D))
    }

    @Test fun cancellationIsNeverConvertedToAnOrdinaryFailure() {
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertThrows(CancellationException::class.java) { mapSmbError(cancellation) })
        assertSame(cancellation, assertThrows(CancellationException::class.java) { mapSmbError(IOException(cancellation)) })
    }

    @Test fun publicFailureDoesNotLeakHostAccountOrUncPath() {
        val original = SMBApiException(0xC0000022, SMB2MessageCommandCode.SMB2_CREATE,
            "secret-account@secret-host\\private\\file", null)
        val mapped = mapSmbError(IOException(original))
        assertEquals(StorageError.PERMISSION, mapped.error)
        assertFalse(mapped.toString().contains("secret"))
        assertNull(mapped.cause)
    }

    @Test fun explicitSourceChangeAndUnknownOutcomeSurviveMapping() {
        for (type in listOf(StorageError.SOURCE_CHANGED, StorageError.OUTCOME_UNKNOWN)) {
            val error = StorageException(type, "expected")
            assertSame(error, mapSmbError(error))
        }
    }

    private fun map(code: Long) = mapSmbError(SMBApiException(code, SMB2MessageCommandCode.SMB2_CREATE, null)).error
}
