package space.zhuoling.fileaccess.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun get(id: String): ConnectionEntity?
    @Upsert suspend fun save(entity: ConnectionEntity)
    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
abstract class TransferDao {
    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<TransferTaskEntity>>
    @Query("SELECT * FROM transfer_tasks WHERE id = :id")
    abstract suspend fun get(id: String): TransferTaskEntity?
    @Query("SELECT * FROM transfer_tasks WHERE operationId = :operationId")
    abstract suspend fun byOperation(operationId: String): TransferTaskEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: TransferTaskEntity): Long

    @Query("""SELECT id FROM transfer_tasks WHERE state IN ('QUEUED', 'WAITING', 'RECONCILING')
        AND leaseOwner IS NULL AND retryAt <= :now ORDER BY createdAt LIMIT :limit""")
    abstract suspend fun runnableIds(now: Long, limit: Int): List<String>

    @Query("""UPDATE transfer_tasks SET leaseOwner = :owner, leaseGeneration = leaseGeneration + 1,
        leaseExpiresAt = :expiresAt, updatedAt = :now,
        state = CASE WHEN state = 'RECONCILING' THEN 'RECONCILING' ELSE 'PREPARING' END, error = NULL
        WHERE id = :id AND state IN ('QUEUED', 'WAITING', 'RECONCILING')
        AND leaseOwner IS NULL AND retryAt <= :now""")
    abstract suspend fun claimUpdate(id: String, owner: String, now: Long, expiresAt: Long): Int

    @Transaction
    open suspend fun claim(id: String, owner: String, now: Long, expiresAt: Long): TransferTaskEntity? {
        if (claimUpdate(id, owner, now, expiresAt) != 1) return null
        return get(id)
    }

    @Query("""UPDATE transfer_tasks SET leaseExpiresAt = :expiresAt, updatedAt = :now
        WHERE id = :id AND leaseOwner = :owner AND leaseGeneration = :generation
        AND leaseExpiresAt > :now AND state IN ('PREPARING','RUNNING','VERIFYING','COMMITTING','RECONCILING')""")
    abstract suspend fun renew(id: String, owner: String, generation: Long, now: Long, expiresAt: Long): Int

    @Query("""UPDATE transfer_tasks SET confirmedBytes = :bytes,
        totalBytes = COALESCE(:totalBytes, totalBytes), updatedAt = :now
        WHERE id = :id AND leaseOwner = :owner AND leaseGeneration = :generation
        AND leaseExpiresAt > :now AND confirmedBytes <= :bytes
        AND state IN ('PREPARING','RUNNING','VERIFYING','COMMITTING','RECONCILING')""")
    abstract suspend fun progress(
        id: String, owner: String, generation: Long, bytes: Long, totalBytes: Long?, now: Long,
    ): Int

    @Query("""UPDATE transfer_tasks SET confirmedBytes = 0, updatedAt = :now
        WHERE id = :id AND leaseOwner = :owner AND leaseGeneration = :generation
        AND leaseExpiresAt > :now AND state IN ('PREPARING','RUNNING','RECONCILING')""")
    abstract suspend fun resetProgress(id: String, owner: String, generation: Long, now: Long): Int

    @Query("""UPDATE transfer_tasks SET sourceRevision = :revision, updatedAt = :now
        WHERE id = :id AND leaseOwner = :owner AND leaseGeneration = :generation
        AND leaseExpiresAt > :now AND state IN ('PREPARING','RUNNING','RECONCILING')
        AND (sourceRevision IS NULL OR sourceRevision = :revision)""")
    abstract suspend fun pinSourceRevision(id: String, owner: String, generation: Long, revision: String, now: Long): Int

    @Query("""UPDATE transfer_tasks SET state = 'RECONCILING', error = :error,
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1,
        updatedAt = :now, retryAt = :retryAt
        WHERE id = :id AND leaseOwner = :owner AND leaseGeneration = :generation
        AND leaseExpiresAt > :now AND state IN ('PREPARING','RUNNING','VERIFYING','COMMITTING','RECONCILING')""")
    abstract suspend fun releaseForReconciliation(
        id: String, owner: String, generation: Long, error: String?, now: Long, retryAt: Long,
    ): Int

    @Query("""UPDATE transfer_tasks SET state = :target, error = :error, updatedAt = :now,
        leaseOwner = CASE WHEN :release THEN NULL ELSE leaseOwner END,
        leaseExpiresAt = CASE WHEN :release THEN 0 ELSE leaseExpiresAt END, retryAt = :retryAt,
        attempt = attempt + CASE WHEN :target = 'RUNNING' AND :expected != 'RUNNING' THEN 1 ELSE 0 END
        WHERE id = :id AND leaseOwner = :owner AND leaseGeneration = :generation
        AND leaseExpiresAt > :now AND state = :expected""")
    abstract suspend fun transition(
        id: String, owner: String, generation: Long, expected: String, target: String,
        error: String?, now: Long, release: Boolean, retryAt: Long,
    ): Int

    @Query("""UPDATE transfer_tasks SET state = :target, error = :error, updatedAt = :now,
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1
        WHERE id = :id AND state NOT IN ('SUCCEEDED','CANCELLED','COMMITTING','RECONCILING')""")
    abstract suspend fun interrupt(id: String, target: String, error: String?, now: Long): Int

    @Query("""UPDATE transfer_tasks SET error = :error, updatedAt = :now
        WHERE id = :id AND state IN ('COMMITTING','RECONCILING')""")
    abstract suspend fun markPendingOutcome(id: String, error: String, now: Long): Int

    @Query("""UPDATE transfer_tasks SET state = 'QUEUED', error = NULL, retryAt = 0, updatedAt = :now
        WHERE id = :id AND state IN ('PAUSED','WAITING','FAILED') AND leaseOwner IS NULL""")
    abstract suspend fun resume(id: String, now: Long): Int

    @Query("""UPDATE transfer_tasks SET retryAt = 0 WHERE id = :id AND leaseOwner IS NULL
        AND state IN ('QUEUED','WAITING','RECONCILING')""")
    abstract suspend fun makeEligibleNow(id: String): Int

    @Query("""UPDATE transfer_tasks SET
        state = CASE WHEN direction = 'UPLOAD' OR state IN ('COMMITTING','RECONCILING')
        THEN 'RECONCILING' ELSE 'WAITING' END,
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1,
        updatedAt = :now, error = 'Execution interrupted; checking saved progress'
        WHERE leaseOwner IS NOT NULL AND leaseExpiresAt <= :now
        AND state IN ('PREPARING','RUNNING','VERIFYING','COMMITTING','RECONCILING')""")
    abstract suspend fun recoverExpired(now: Long): Int

    @Query("""UPDATE transfer_tasks SET state = CASE WHEN state IN ('COMMITTING','RECONCILING')
        THEN 'FAILED' ELSE 'CANCELLED' END,
        error = 'Connection removed; a pending remote result may need checking',
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1, updatedAt = :now
        WHERE connectionId = :connectionId AND state NOT IN ('SUCCEEDED','CANCELLED')""")
    abstract suspend fun cancelConnection(connectionId: String, now: Long)

    @Query("""UPDATE transfer_tasks SET state = 'FAILED',
        error = 'Connection changed; create a new transfer after reviewing the destination',
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1, updatedAt = :now
        WHERE connectionId = :connectionId AND connectionRevision != :revision
        AND state NOT IN ('SUCCEEDED','CANCELLED')""")
    abstract suspend fun invalidateConnection(connectionId: String, revision: Long, now: Long)

    @Query("""UPDATE transfer_tasks SET state = 'CANCELLED', error = 'Backup rule removed or changed',
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1, updatedAt = :now
        WHERE backupRuleId = :ruleId AND state NOT IN ('SUCCEEDED','CANCELLED','COMMITTING','RECONCILING')""")
    abstract suspend fun cancelRule(ruleId: String, now: Long)

    @Query("""UPDATE transfer_tasks SET state = 'PAUSED', error = 'Backup rule is paused',
        leaseOwner = NULL, leaseExpiresAt = 0, leaseGeneration = leaseGeneration + 1, updatedAt = :now
        WHERE backupRuleId = :ruleId AND state NOT IN ('SUCCEEDED','CANCELLED','FAILED','PAUSED','COMMITTING','RECONCILING')""")
    abstract suspend fun pauseRule(ruleId: String, now: Long)

    @Query("""UPDATE transfer_tasks SET state = 'QUEUED', error = NULL, retryAt = 0, updatedAt = :now
        WHERE backupRuleId = :ruleId AND state = 'PAUSED' AND error = 'Backup rule is paused'
        AND leaseOwner IS NULL""")
    abstract suspend fun resumeRulePaused(ruleId: String, now: Long)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_rules ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BackupRuleEntity>>
    @Query("SELECT * FROM backup_rules WHERE id = :id")
    suspend fun get(id: String): BackupRuleEntity?
    @Query("SELECT * FROM backup_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<BackupRuleEntity>
    @Upsert suspend fun save(entity: BackupRuleEntity)
    @Query("DELETE FROM backup_rules WHERE id = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM backup_baselines WHERE ruleId = :id")
    suspend fun deleteBaselines(id: String)
    @Query("UPDATE backup_rules SET enabled = 0 WHERE connectionId = :connectionId")
    suspend fun disableConnection(connectionId: String)
    @Query("UPDATE backup_rules SET lastCheck = :now WHERE id = :id")
    suspend fun recordCheck(id: String, now: Long)
    @Query("UPDATE backup_rules SET lastSuccess = :now WHERE id = :id")
    suspend fun recordSuccess(id: String, now: Long)
    @Query("SELECT * FROM backup_baselines WHERE ruleId = :ruleId AND logicalSourceId = :logicalSourceId")
    suspend fun baseline(ruleId: String, logicalSourceId: String): BackupBaselineEntity?
    @Upsert suspend fun saveBaseline(entity: BackupBaselineEntity)
    @Query("DELETE FROM backup_baselines WHERE connectionId = :connectionId")
    suspend fun clearConnectionBaselines(connectionId: String)
    @Query("SELECT * FROM backup_scan_cursors WHERE ruleId = :id")
    suspend fun scanCursor(id: String): BackupScanCursorEntity?
    @Upsert suspend fun saveScanCursor(entity: BackupScanCursorEntity)
    @Query("DELETE FROM backup_scan_cursors WHERE ruleId = :id")
    suspend fun clearScanCursor(id: String)
}
