package space.zhuoling.fileaccess.core.data

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.zhuoling.fileaccess.core.data.db.BackupBaselineEntity
import space.zhuoling.fileaccess.core.data.db.TransferTaskEntity
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.TransferState

class TransferRepository(private val database: AppDatabase) {
    private val dao get() = database.transfers()

    fun observeAll(): Flow<List<TransferTask>> = dao.observeAll().map { rows -> rows.map { it.model() } }
    suspend fun get(id: String): TransferTask? = dao.get(id)?.model()

    suspend fun enqueue(task: TransferTask): String {
        require(task.localUri.startsWith("content://")) { "A granted content URI is required" }
        require(task.name.isNotBlank() && task.connectionRevision > 0)
        require(task.confirmedBytes == 0L && (task.totalBytes == null || task.totalBytes >= 0))
        require(task.state == TransferState.QUEUED)
        val operationId = if (task.backupRuleId != null) {
            BackupOperationId.create(task.backupRuleId, requireNotNull(task.logicalSourceId),
                requireNotNull(task.sourceGeneration), task.remoteRef, task.connectionRevision)
        } else task.operationId
        return database.withWriteTransaction {
            val connection = database.connections().get(task.remoteRef.connectionId)
            require(connection != null && connection.revision == task.connectionRevision) {
                "Connection changed; recreate this transfer"
            }
            if (task.backupRuleId != null) {
                val rule = database.backups().get(task.backupRuleId)
                require(rule != null && rule.enabled && rule.connectionId == task.remoteRef.connectionId &&
                    rule.remoteOpaqueId == task.remoteRef.opaqueId) { "Backup rule changed; rescan it" }
            }
            dao.insert(TransferTaskEntity.from(task.copy(operationId = operationId)))
            requireNotNull(dao.byOperation(operationId)).id
        }
    }

    suspend fun runnableIds(now: Long = System.currentTimeMillis(), limit: Int = 100): List<String> =
        dao.runnableIds(now, limit.coerceIn(1, 1000))

    suspend fun claim(
        id: String, owner: String, now: Long = System.currentTimeMillis(), leaseMillis: Long = LEASE_MILLIS,
    ): TransferLease? {
        require(owner.isNotBlank() && leaseMillis in 1..MAX_LEASE_MILLIS)
        val row = dao.claim(id, owner, now, Math.addExact(now, leaseMillis)) ?: return null
        return TransferLease(row.model(), owner, row.leaseGeneration)
    }

    suspend fun renew(lease: TransferLease, now: Long = System.currentTimeMillis()): Boolean =
        dao.renew(lease.task.id, lease.owner, lease.generation, now, now + LEASE_MILLIS) == 1

    suspend fun owns(lease: TransferLease, now: Long = System.currentTimeMillis()): Boolean {
        val row = dao.get(lease.task.id) ?: return false
        return row.leaseOwner == lease.owner && row.leaseGeneration == lease.generation &&
            row.leaseExpiresAt > now && TransferState.valueOf(row.state) in TransferTransitions.active
    }

    /** Persist only remotely acknowledged bytes; optimistic UI progress belongs in memory. */
    suspend fun progress(
        lease: TransferLease, confirmedBytes: Long, totalBytes: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(confirmedBytes >= 0 && (totalBytes == null || totalBytes >= confirmedBytes))
        return dao.progress(lease.task.id, lease.owner, lease.generation, confirmedBytes, totalBytes, now) == 1
    }

    /** Reset before applying the adapter's reconciled checkpoint, or a verified download restart. */
    suspend fun resetProgress(lease: TransferLease, now: Long = System.currentTimeMillis()): Boolean =
        dao.resetProgress(lease.task.id, lease.owner, lease.generation, now) == 1

    suspend fun pinSourceRevision(lease: TransferLease, revision: String, now: Long = System.currentTimeMillis()): Boolean =
        dao.pinSourceRevision(lease.task.id, lease.owner, lease.generation, revision, now) == 1

    suspend fun releaseForReconciliation(
        lease: TransferLease, error: String?, now: Long = System.currentTimeMillis(), retryAt: Long = now + 30_000,
    ): Boolean = dao.releaseForReconciliation(
        lease.task.id, lease.owner, lease.generation, error, now, retryAt,
    ) == 1

    suspend fun transition(
        lease: TransferLease, state: TransferState, error: String? = null,
        now: Long = System.currentTimeMillis(), retryAt: Long = 0,
    ): Boolean = database.withWriteTransaction {
        val row = dao.get(lease.task.id) ?: return@withWriteTransaction false
        if (!TransferTransitions.allows(TransferState.valueOf(row.state), state)) return@withWriteTransaction false
        dao.transition(row.id, lease.owner, lease.generation, row.state, state.name,
            error, now, state !in TransferTransitions.active, retryAt) == 1
    }

    suspend fun complete(lease: TransferLease, remote: RemoteEntry, now: Long = System.currentTimeMillis()): Boolean =
        database.withWriteTransaction {
            require(remote.ref.connectionId == lease.task.remoteRef.connectionId)
            if (!transition(lease, TransferState.SUCCEEDED, now = now)) return@withWriteTransaction false
            val task = lease.task
            if (task.backupRuleId != null && task.logicalSourceId != null && task.sourceGeneration != null &&
                database.backups().get(task.backupRuleId)?.model()?.target == task.remoteRef) {
                val old = database.backups().baseline(task.backupRuleId, task.logicalSourceId)
                // Older content finishing later must not replace a more recent source baseline.
                if (old == null || old.sourceCreatedAt <= task.createdAt) {
                    database.backups().saveBaseline(BackupBaselineEntity(
                        task.backupRuleId, task.logicalSourceId, task.sourceGeneration,
                        remote.ref.connectionId, remote.ref.opaqueId, remote.revision, remote.size, now,
                        task.createdAt,
                    ))
                }
                database.backups().recordSuccess(task.backupRuleId, now)
            }
            true
        }

    suspend fun pause(id: String): Boolean = interrupt(id, TransferState.PAUSED, null)
    suspend fun cancel(id: String): Boolean = interrupt(id, TransferState.CANCELLED, null)
    suspend fun setWaiting(id: String, error: String): Boolean = interrupt(id, TransferState.WAITING, error)
    suspend fun resume(id: String): Boolean = dao.resume(id, System.currentTimeMillis()) == 1
    suspend fun makeEligibleNow(id: String): Boolean = dao.makeEligibleNow(id) == 1
    suspend fun recoverExpired(now: Long = System.currentTimeMillis()): Int = dao.recoverExpired(now)

    suspend fun uploadCleanupCandidates(now: Long = System.currentTimeMillis()): List<TransferTask> =
        dao.uploadCleanupCandidates(now, now - 7L * 24 * 60 * 60 * 1000).map { it.model() }

    suspend fun recordUploadCleanup(id: String, error: String?, now: Long = System.currentTimeMillis()) {
        dao.recordUploadCleanup(id, if (error == null) Long.MAX_VALUE else now + 30 * 60_000L, error)
    }

    private suspend fun interrupt(id: String, state: TransferState, error: String?): Boolean =
        database.withWriteTransaction {
            val now = System.currentTimeMillis()
            val changed = dao.interrupt(id, state.name, error, now) == 1
            if (!changed) dao.markPendingOutcome(id,
                error ?: "The remote commit is being checked; this operation cannot yet be withdrawn", now)
            changed
        }

    companion object {
        const val LEASE_MILLIS = 60_000L
        private const val MAX_LEASE_MILLIS = 300_000L
    }
}
