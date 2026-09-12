package space.zhuoling.fileaccess.core.data

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.zhuoling.fileaccess.core.data.db.BackupRuleEntity
import space.zhuoling.fileaccess.core.data.db.BackupScanCursorEntity

class BackupRepository(private val database: AppDatabase) {
    private val dao get() = database.backups()
    fun observeAll(): Flow<List<BackupRule>> = dao.observeAll().map { rows -> rows.map { it.model() } }
    suspend fun get(id: String): BackupRule? = dao.get(id)?.model()
    suspend fun enabledRules(): List<BackupRule> = dao.enabledRules().map { it.model() }

    suspend fun save(rule: BackupRule) = database.withWriteTransaction {
        require(rule.id.isNotBlank() && rule.name.isNotBlank())
        require(rule.sourceKind in setOf("tree", "camera"))
        if (rule.sourceKind == "tree") require(rule.sourceTreeUri.startsWith("content://"))
        require(database.connections().get(rule.target.connectionId) != null) { "Connection no longer exists" }
        val old = dao.get(rule.id)?.model()
        if (old != null && (old.target != rule.target || old.sourceKind != rule.sourceKind ||
                old.sourceTreeUri != rule.sourceTreeUri)) {
            dao.deleteBaselines(rule.id)
            dao.clearScanCursor(rule.id)
            database.transfers().cancelRule(rule.id, System.currentTimeMillis())
        }
        if (!rule.enabled) database.transfers().pauseRule(rule.id, System.currentTimeMillis())
        else if (old?.enabled == false) database.transfers().resumeRulePaused(rule.id, System.currentTimeMillis())
        dao.save(BackupRuleEntity.from(rule))
    }

    suspend fun delete(id: String) = database.withWriteTransaction {
        database.transfers().cancelRule(id, System.currentTimeMillis())
        dao.deleteBaselines(id)
        dao.clearScanCursor(id)
        dao.delete(id)
    }

    suspend fun recordCheck(id: String, now: Long = System.currentTimeMillis()) = dao.recordCheck(id, now)
    suspend fun baseline(ruleId: String, logicalSourceId: String): BackupBaseline? =
        dao.baseline(ruleId, logicalSourceId)?.model()

    suspend fun scanCursor(id: String): String? = dao.scanCursor(id)?.payload
    suspend fun saveScanCursor(id: String, payload: String) = database.withWriteTransaction {
        require(payload.length <= 256 * 1024) { "Discovery cursor is too large" }
        if (dao.get(id) != null) dao.saveScanCursor(BackupScanCursorEntity(id, payload, System.currentTimeMillis()))
    }
    suspend fun clearScanCursor(id: String) = dao.clearScanCursor(id)
}
