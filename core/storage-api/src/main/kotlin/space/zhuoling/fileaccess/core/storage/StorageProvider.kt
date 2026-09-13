package space.zhuoling.fileaccess.core.storage

import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.Credentials
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageCapabilities

interface StorageProvider {
    val type: String
    suspend fun connect(config: ConnectionConfig, credentials: Credentials): StorageSession
}

interface StorageSession : Closeable {
    val root: RemoteEntry
    val capabilities: StorageCapabilities

    /** Emits one listing snapshot. Failure means the listing is incomplete, never empty. */
    fun list(directory: EntryRef): Flow<RemoteEntry>
    suspend fun stat(ref: EntryRef): RemoteEntry

    /** Caller reads on an IO dispatcher and closes the stream, including on cancellation. */
    suspend fun openRead(
        ref: EntryRef,
        offset: Long = 0,
        expectedRevision: String? = null,
    ): InputStream
}

interface UploadSource {
    val length: Long?
    val version: String? get() = null
    fun currentVersion(): String? = version
    fun open(): InputStream

    /** Position a repeatable source without allocating or staging its prefix. */
    fun open(offset: Long): InputStream {
        require(offset >= 0)
        val input = open()
        try {
            var remaining = offset
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else if (input.read() < 0) throw space.zhuoling.fileaccess.core.model.StorageException(
                    space.zhuoling.fileaccess.core.model.StorageError.SOURCE_CHANGED, "Source ended before its checkpoint")
                else remaining--
            }
            return input
        } catch (error: Exception) { input.close(); throw error }
    }
}

data class UploadRequest(
    val operationId: String,
    val parent: EntryRef,
    val name: String,
)

/** Non-overwriting uploads. A resumable provider durably records checkpoints before onProgress. */
interface UploadCapability {
    /** Reconcile this operation's temporary/committed object before retrying an unknown result. */
    suspend fun upload(
        request: UploadRequest,
        source: UploadSource,
        onProgress: (Long) -> Unit,
        onCommit: () -> Unit = {},
    ): RemoteEntry
}

interface UploadCleanupCapability {
    /** Caller must retain a durable terminal task and never retry its operation after cleanup.
     * completed=true retires only a committed receipt; false aborts an uncommitted payload.
     * Never removes the published target. Ambiguous ownership must fail and retain data.
     */
    suspend fun cleanupUpload(request: UploadRequest, completed: Boolean)
}

interface MutationCapability {
    suspend fun createDirectory(parent: EntryRef, name: String): RemoteEntry
    /** Must fail if the target exists. Never implement rename by deleting the target. */
    suspend fun rename(ref: EntryRef, name: String, expectedRevision: String? = null): RemoteEntry
    /** Only a file or an empty directory. Recursive deletion requires a separate planned capability. */
    suspend fun delete(ref: EntryRef, expectedRevision: String? = null)
}

class ProviderRegistry(providers: Set<StorageProvider>) {
    private val providersByType = providers.associateBy { it.type }.also {
        require(it.size == providers.size) { "Provider type IDs must be unique" }
    }

    fun provider(type: String): StorageProvider = providersByType[type]
        ?: throw space.zhuoling.fileaccess.core.model.StorageException(
            space.zhuoling.fileaccess.core.model.StorageError.UNSUPPORTED,
            "This storage protocol is not installed",
        )

    val supportedTypes: Set<String> get() = providersByType.keys
}
