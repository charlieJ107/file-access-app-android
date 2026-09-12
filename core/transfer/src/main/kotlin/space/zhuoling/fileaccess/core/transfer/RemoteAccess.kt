package space.zhuoling.fileaccess.core.transfer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.core.data.ConnectionRepository
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.storage.StorageSession

@Singleton
class RemoteAccess @Inject constructor(
    private val connections: ConnectionRepository,
    private val registry: ProviderRegistry,
) {
    suspend fun open(connectionId: String, expectedConfigRevision: Long? = null): StorageSession =
        withContext(Dispatchers.IO) {
            val config = connections.get(connectionId)
                ?: throw StorageException(StorageError.INVALID_CONFIGURATION, "Connection was removed")
            if (expectedConfigRevision != null && config.revision != expectedConfigRevision) {
                throw StorageException(StorageError.INVALID_CONFIGURATION, "Connection changed; create a new task")
            }
            (connections.credentials(connectionId)
                ?: throw StorageException(StorageError.AUTHENTICATION, "Credentials are unavailable")).use { credentials ->
                registry.provider(config.protocol).connect(config, credentials)
            }
        }

    suspend fun <T> withSession(connectionId: String, block: suspend (StorageSession) -> T): T =
        withContext(Dispatchers.IO) { open(connectionId).use { block(it) } }
}
