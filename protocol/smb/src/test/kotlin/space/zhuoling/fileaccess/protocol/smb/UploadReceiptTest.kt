package space.zhuoling.fileaccess.protocol.smb

import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.*

class UploadReceiptTest {
    private val token = "a".repeat(64)

    @Test fun boundedReceiptRoundTripsOriginalIdentityAndDigest() {
        val receipt = UploadReceipt(token, UploadReceipt.Phase.READY, 987654321L, 5_000_000_000L,
            "b".repeat(64), "c".repeat(64))
        assertEquals(receipt, UploadReceipt.decode(receipt.encode(), token))
    }

    @Test fun partialReceiptNeverCountsAsCommitted() {
        val receipt = UploadReceipt(token, UploadReceipt.Phase.COMMITTED, 42, 100, "b".repeat(64))
        val bytes = receipt.encode()
        assertUnknown { UploadReceipt.decode(bytes.copyOf(bytes.size / 2), token) }
        assertUnknown { UploadReceipt.decode(ByteArray(2048), token) }
    }

    @Test fun receiptFromAnotherOperationCannotAuthorizeCleanup() {
        val bytes = UploadReceipt(token, UploadReceipt.Phase.WRITING, 42).encode()
        assertUnknown { UploadReceipt.decode(bytes, "d".repeat(64)) }
    }

    @Test fun readyReceiptWithoutDigestCannotAuthorizeRetrySuccess() {
        val bytes = UploadReceipt(token, UploadReceipt.Phase.READY, 42).encode()
        assertUnknown { UploadReceipt.decode(bytes, token) }
    }

    @Test fun tornOrEditedReceiptCannotAuthorizeCommit() {
        val bytes = UploadReceipt(token, UploadReceipt.Phase.READY, 42, 100, "b".repeat(64)).encode()
        val altered = bytes.clone()
        altered[110] = if (altered[110] == '1'.code.toByte()) '2'.code.toByte() else '1'.code.toByte()
        assertUnknown { UploadReceipt.decode(altered, token) }
    }

    private fun assertUnknown(block: () -> Unit) {
        assertEquals(StorageError.OUTCOME_UNKNOWN, assertThrows(StorageException::class.java, block).error)
    }
}
