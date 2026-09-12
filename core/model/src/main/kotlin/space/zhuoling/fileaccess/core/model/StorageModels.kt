package space.zhuoling.fileaccess.core.model

/** Connection configuration never contains a password or a session token. */
data class ConnectionConfig(
    val id: String,
    val name: String,
    val protocol: String = "smb",
    val host: String,
    val port: Int = 445,
    val share: String,
    val rootPath: String = "",
    val username: String = "",
    val domain: String = "",
    val requireEncryption: Boolean = false,
    val revision: Long = 1,
)

/** Ephemeral credential material. Do not put this object in UI state or saved state. */
class Credentials(
    val username: String,
    val password: CharArray,
    val domain: String = "",
) : AutoCloseable {
    override fun close() { password.fill('\u0000') }
    override fun toString(): String = "Credentials([redacted])"
}

/** opaqueId belongs to the adapter: callers must not concatenate or normalize it. */
data class EntryRef(val connectionId: String, val opaqueId: String)

data class RemoteEntry(
    val ref: EntryRef,
    val name: String,
    val isDirectory: Boolean,
    val parent: EntryRef? = null,
    val size: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
    val mimeType: String? = null,
    val revision: String? = null,
)

enum class TransportProtection { UNKNOWN, SIGNED, ENCRYPTED }

data class StorageCapabilities(
    val upload: Boolean = false,
    val createDirectory: Boolean = false,
    val rename: Boolean = false,
    val delete: Boolean = false,
    val rangeRead: Boolean = false,
    val resumableUpload: Boolean = false,
    val trash: Boolean = false,
    val transportProtection: TransportProtection = TransportProtection.UNKNOWN,
)

enum class StorageError {
    AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, NETWORK,
    UNSUPPORTED, SOURCE_CHANGED, CORRUPT_DATA, OUTCOME_UNKNOWN,
    INVALID_CONFIGURATION, QUOTA_EXCEEDED,
}

class StorageException(
    val error: StorageError,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

/** Shared task states are independent of WorkManager or a particular protocol SDK. */
enum class TransferState {
    QUEUED, PREPARING, RUNNING, VERIFYING, COMMITTING, RECONCILING,
    WAITING, PAUSED, SUCCEEDED, FAILED, CANCELLED,
}

enum class TransferDirection { UPLOAD, DOWNLOAD }
