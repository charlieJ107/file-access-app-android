package space.zhuoling.fileaccess.protocol.smb

import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.util.concurrent.CancellationException
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException

/** Public messages deliberately omit server replies, credentials, hostnames and remote filenames. */
internal fun mapSmbError(error: Throwable): StorageException {
    if (error is CancellationException) throw error
    if (error is StorageException) return error
    val chain = generateSequence(error) { it.cause }.take(12).toList()
    chain.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val status = chain.filterIsInstance<SMBApiException>().firstOrNull()?.statusCode
    val category = when (status) {
        0xC000006DL, 0xC000006AL, 0xC0000064L, 0xC0000071L, 0xC0000072L,
        0xC0000193L, 0xC0000234L, 0xC0000224L, 0xC0000203L, 0xC000035CL -> StorageError.AUTHENTICATION
        0xC0000022L, 0xC0000061L, 0xC00000A2L -> StorageError.PERMISSION
        0xC000000FL, 0xC0000034L, 0xC000003AL, 0xC00000CCL -> StorageError.NOT_FOUND
        0xC0000035L, 0xC0000043L, 0xC0000056L, 0xC0000101L -> StorageError.CONFLICT
        0xC000007FL, 0xC0000044L, 0xC0000099L -> StorageError.QUOTA_EXCEEDED
        0x8000002DL, 0xC00000BBL, 0xC0000257L, 0xC0000279L -> StorageError.UNSUPPORTED
        0xC0000033L, 0xC000000DL -> StorageError.INVALID_CONFIGURATION
        else -> when (error) {
            is IllegalArgumentException -> StorageError.INVALID_CONFIGURATION
            is SecurityException -> StorageError.PERMISSION
            is java.io.FileNotFoundException -> StorageError.NOT_FOUND
            else -> StorageError.NETWORK
        }
    }
    val message = when (category) {
        StorageError.AUTHENTICATION -> "SMB authentication failed; check the account and domain"
        StorageError.PERMISSION -> "This account is not permitted to perform the operation"
        StorageError.NOT_FOUND -> "The remote file, directory, or share no longer exists"
        StorageError.CONFLICT -> "The name already exists, the entry is in use, or the directory is not empty"
        StorageError.QUOTA_EXCEEDED -> "The remote storage is full or its quota has been reached"
        StorageError.UNSUPPORTED -> "The server does not support this operation; links and DFS referrals are not followed"
        StorageError.INVALID_CONFIGURATION -> "The SMB connection or file name is invalid"
        else -> "The SMB connection was interrupted or the server could not be reached"
    }
    // Do not retain SMBJ's exception as a cause: it can contain an entire UNC path or account name.
    return StorageException(category, message).also { it.stackTrace = error.stackTrace }
}

internal inline fun <T> smbCall(block: () -> T): T = try { block() } catch (error: Exception) {
    throw mapSmbError(error)
}

internal fun outcomeUnknown(): Nothing = throw StorageException(
    StorageError.OUTCOME_UNKNOWN,
    "The upload result cannot be proven. Existing remote files were retained; inspect the destination before retrying.",
)
