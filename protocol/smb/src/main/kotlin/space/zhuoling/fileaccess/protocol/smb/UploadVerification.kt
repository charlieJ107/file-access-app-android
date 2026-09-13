package space.zhuoling.fileaccess.protocol.smb

import java.nio.ByteBuffer
import java.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.UploadSource

internal fun SHA256Digest.snapshot(): String = SHA256Digest(this).let { copy ->
    ByteArray(32).also { copy.doFinal(it, 0) }.hex()
}

private fun SHA256Digest.savedState(): String = Base64.getEncoder().encodeToString(encodedState)

private fun restoreDigest(state: String, offset: Long): SHA256Digest = try {
    val bytes = Base64.getDecoder().decode(state)
    // BC's encoded GeneralDigest starts with xBuf[4], xBufOff[4], byteCount[8].
    if (bytes.size !in 53..256 || ByteBuffer.wrap(bytes, 8, 8).long != offset) outcomeUnknown()
    SHA256Digest(bytes)
} catch (_: IllegalArgumentException) { outcomeUnknown() }
catch (_: IndexOutOfBoundsException) { outcomeUnknown() }

/** Full byte verification can span worker lifetimes, bound to unchanged source/remote metadata.
 * Unversioned sources restart verification, since no persistent source identity is available.
 */
internal fun verifyUpload(
    source: UploadSource,
    original: UploadReceipt,
    remoteRevision: String,
    readRemote: (ByteArray, Long, Int, Int) -> Int,
    save: (UploadReceipt) -> UploadReceipt,
    check: () -> Unit,
): Pair<UploadReceipt, SHA256Digest> {
    var receipt = original
    val continuing = receipt.phase != UploadReceipt.Phase.WRITING && source.version != null &&
        receipt.verificationRevision == remoteRevision
    if (!continuing) {
        receipt = save(receipt.copy(
            phase = if (receipt.phase in setOf(UploadReceipt.Phase.WRITING, UploadReceipt.Phase.CHECKED))
                UploadReceipt.Phase.CHECKING else receipt.phase,
            verificationOffset = 0, sourceHashState = "", remoteHashState = "", verificationRevision = remoteRevision))
    }
    var position = receipt.verificationOffset
    val local = if (position == 0L) SHA256Digest() else restoreDigest(receipt.sourceHashState, position)
    val remote = if (position == 0L) SHA256Digest() else restoreDigest(receipt.remoteHashState, position)
    if (position < receipt.length) source.open(position).use { input ->
        val buffer = ByteArray(256 * 1024)
        while (position < receipt.length) {
            check()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), receipt.length - position).toInt())
            if (count < 0) throw StorageException(StorageError.SOURCE_CHANGED, "Source ended before its checkpoint")
            if (count == 0) continue
            local.update(buffer, 0, count)
            var read = 0
            while (read < count) {
                check()
                val received = readRemote(buffer, position + read, read, count - read)
                if (received <= 0) throw StorageException(StorageError.CORRUPT_DATA, "The confirmed upload prefix is incomplete")
                read += received
            }
            remote.update(buffer, 0, count)
            position += count
            if (position - receipt.verificationOffset >= UPLOAD_CHECKPOINT_BYTES || position == receipt.length) {
                receipt = save(receipt.copy(verificationOffset = position,
                    sourceHashState = local.savedState(), remoteHashState = remote.savedState()))
            }
        }
    }
    if (receipt.digest.isNotEmpty()) {
        if (remote.snapshot() != receipt.digest) throw StorageException(StorageError.CORRUPT_DATA, "The confirmed remote upload prefix changed")
        if (local.snapshot() != receipt.digest) throw StorageException(StorageError.SOURCE_CHANGED, "The source content changed")
    }
    if (receipt.phase in setOf(UploadReceipt.Phase.VERIFYING, UploadReceipt.Phase.READY, UploadReceipt.Phase.COMMITTED, UploadReceipt.Phase.RECONCILING)) {
        check()
        source.open(receipt.length).use { if (it.read() >= 0) throw StorageException(StorageError.SOURCE_CHANGED, "Source grew beyond its checkpoint") }
    }
    if (receipt.phase == UploadReceipt.Phase.CHECKING) receipt = save(receipt.copy(phase = UploadReceipt.Phase.CHECKED))
    return receipt to local
}
