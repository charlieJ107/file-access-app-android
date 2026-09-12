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
}

data class UploadRequest(
    val operationId: String,
    val parent: EntryRef,
    val name: String,
)

/** Initial upload capability is non-overwriting. Resumable sessions are a separate extension. */
interface UploadCapability {
    /** Reconcile this operation's temporary/committed object before retrying an unknown result. */
    suspend fun upload(
        request: UploadRequest,
        source: UploadSource,
        onProgress: (Long) -> Unit,
        onCommit: () -> Unit = {},
    ): RemoteEntry
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
