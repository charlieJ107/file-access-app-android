package space.zhuoling.fileaccess.core.data

import androidx.room3.withWriteTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.zhuoling.fileaccess.core.data.db.ConnectionEntity
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.Credentials
import space.zhuoling.fileaccess.core.security.CredentialStore

class ConnectionRepository(private val database: AppDatabase, private val secrets: CredentialStore) {
    private val mutex = Mutex()
    fun observeAll(): Flow<List<ConnectionConfig>> = database.connections().observeAll()
        .map { rows -> rows.map { it.config() } }

    suspend fun get(id: String): ConnectionConfig? = database.connections().get(id)?.config()

    suspend fun credentials(id: String): Credentials? =
        database.connections().get(id)?.credentialRef?.let { secrets.get(it) }

    /** Returns the persisted configuration and its new revision. Null preserves the old secret. */
    suspend fun save(config: ConnectionConfig, credentials: Credentials? = null): ConnectionConfig =
        mutex.withLock {
            require(config.id.isNotBlank() && config.name.isNotBlank())
            require(config.port in 1..65535 && config.host.isNotBlank() && config.share.isNotBlank())
            val old = database.connections().get(config.id)
            if (old != null) require(config.revision == old.revision) { "Connection was changed; reload it" }
            if (credentials == null && old != null) {
                require(config.username == old.username && config.domain == old.domain) {
                    "Enter credentials when changing the account"
                }
            }
            if (credentials != null) {
                require(credentials.username == config.username && credentials.domain == config.domain)
            }
            val saved = config.copy(revision = (old?.revision ?: 0) + 1)
            // Publish a fresh reference only after its encrypted file is durable. A failed DB
            // transaction cannot replace credentials still referenced by the old configuration.
            val newRef = if (credentials != null) UUID.randomUUID().toString() else old?.credentialRef
            if (newRef != null && credentials != null) secrets.put(newRef, credentials)
            try {
                database.withWriteTransaction<Unit> {
                    database.connections().save(ConnectionEntity.from(saved, newRef))
                    if (old != null) database.transfers().invalidateConnection(
                        config.id, saved.revision, System.currentTimeMillis(),
                    )
                    if (old != null && (old.protocol != saved.protocol || old.host != saved.host ||
                        old.port != saved.port || old.share != saved.share || old.rootPath != saved.rootPath)) {
                        database.backups().clearConnectionBaselines(saved.id)
                    }
                    Unit
                }
            } catch (error: Exception) {
                if (credentials != null && newRef != null) secrets.delete(newRef)
                throw error
            }
            if (old?.credentialRef != null && old.credentialRef != newRef) secrets.delete(old.credentialRef)
            saved
        }

    /** Removes this device's configuration and credentials. No remote file operation occurs. */
    suspend fun delete(id: String) = mutex.withLock {
        val row = database.connections().get(id) ?: return@withLock
        database.withWriteTransaction {
            database.transfers().cancelConnection(id, System.currentTimeMillis())
            database.backups().disableConnection(id)
        }
        row.credentialRef?.let { secrets.delete(it) }
        database.connections().delete(id)
    }
}
