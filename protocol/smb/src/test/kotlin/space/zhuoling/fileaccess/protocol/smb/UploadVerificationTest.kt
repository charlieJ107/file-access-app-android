package space.zhuoling.fileaccess.protocol.smb

import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.UploadSource

class UploadVerificationTest {
    private val bytes = ByteArray((3 * UPLOAD_CHECKPOINT_BYTES + 17).toInt()) { (it % 251).toByte() }
    private var remoteFirst = -1L
    private fun source(version: String? = "v1") = object : UploadSource {
        override val length = bytes.size.toLong()
        override val version = version
        override fun open() = ByteArrayInputStream(bytes)
    }
    private fun receipt() = UploadReceipt("a".repeat(64), UploadReceipt.Phase.VERIFYING, 42,
        bytes.size.toLong(), sha256(bytes), sourceVersionHash = sha256("v1".toByteArray()))

    private fun read(buffer: ByteArray, offset: Long, start: Int, count: Int): Int {
        if (remoteFirst < 0) remoteFirst = offset
        bytes.copyInto(buffer, start, offset.toInt(), offset.toInt() + count)
        return count
    }

    @Test fun fullVerificationSurvivesMultipleWorkerTimeSlices() {
        var saved = receipt()
        for (boundary in listOf(UPLOAD_CHECKPOINT_BYTES, 2 * UPLOAD_CHECKPOINT_BYTES)) {
            assertThrows(CancellationException::class.java) {
                verifyUpload(source(), saved, "remote-v1", ::read, {
                    saved = it
                    if (it.verificationOffset >= boundary) throw CancellationException()
                    it
                }, {})
            }
            assertEquals(boundary, saved.verificationOffset)
            // Use the encoded journal as the next process would, not an in-memory digest clone.
            saved = UploadReceipt.decode(saved.encode(), saved.token)
        }
        remoteFirst = -1
        val verified = verifyUpload(source(), saved, "remote-v1", ::read, { it }, {})
        assertEquals(2 * UPLOAD_CHECKPOINT_BYTES, remoteFirst)
        assertEquals(sha256(bytes), verified.second.snapshot())
        assertEquals(bytes.size.toLong(), verified.first.verificationOffset)
    }

    @Test fun remoteRevisionChangeOrUnversionedSourceRestartsVerification() {
        var saved = receipt()
        assertThrows(CancellationException::class.java) {
            verifyUpload(source(), saved, "before", ::read, {
                saved = it
                if (it.verificationOffset > 0) throw CancellationException()
                it
            }, {})
        }
        for ((version, revision) in listOf("v1" to "after", null to "before")) {
            remoteFirst = -1
            verifyUpload(source(version), saved, revision, ::read, { it }, {})
            assertEquals(0L, remoteFirst)
        }
    }

    @Test fun corruptedDigestStateCannotSkipVerifiedContent() {
        var saved = receipt()
        assertThrows(CancellationException::class.java) {
            verifyUpload(source(), saved, "v1", ::read, {
                saved = it
                if (it.verificationOffset > 0) throw CancellationException()
                it
            }, {})
        }
        val error = assertThrows(StorageException::class.java) {
            verifyUpload(source(), saved.copy(verificationOffset = saved.verificationOffset + 1), "v1", ::read, { it }, {})
        }
        assertEquals(StorageError.OUTCOME_UNKNOWN, error.error)
    }
}
