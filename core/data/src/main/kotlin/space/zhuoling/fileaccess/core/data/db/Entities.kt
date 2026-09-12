package space.zhuoling.fileaccess.core.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import space.zhuoling.fileaccess.core.data.BackupBaseline
import space.zhuoling.fileaccess.core.data.BackupRule
import space.zhuoling.fileaccess.core.data.TransferTask
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.TransferDirection
import space.zhuoling.fileaccess.core.model.TransferState

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val share: String,
    val rootPath: String,
    val username: String,
    val domain: String,
    val requireEncryption: Boolean,
    val revision: Long,
    val credentialRef: String?,
) {
    fun config() = ConnectionConfig(
        id, name, protocol, host, port, share, rootPath, username, domain, requireEncryption, revision,
    )
    companion object {
        fun from(config: ConnectionConfig, credentialRef: String?) = ConnectionEntity(
            config.id, config.name, config.protocol, config.host, config.port, config.share,
            config.rootPath, config.username, config.domain, config.requireEncryption,
            config.revision, credentialRef,
        )
    }
}

@Entity(
    tableName = "transfer_tasks",
    indices = [Index(value = ["operationId"], unique = true), Index(value = ["state", "retryAt"]),
        Index(value = ["connectionId"]), Index(value = ["backupRuleId", "logicalSourceId"])],
)
data class TransferTaskEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val direction: String,
    val connectionId: String,
    val remoteOpaqueId: String,
    val localUri: String,
    val name: String,
    val mimeType: String?,
    val totalBytes: Long?,
    val confirmedBytes: Long,
    val connectionRevision: Long,
    val sourceRevision: String?,
    val state: String,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val attempt: Int,
    val backupRuleId: String?,
    val logicalSourceId: String?,
    val sourceGeneration: String?,
    val leaseOwner: String? = null,
    val leaseGeneration: Long = 0,
    val leaseExpiresAt: Long = 0,
    val retryAt: Long = 0,
) {
    fun model() = TransferTask(
        id, operationId, TransferDirection.valueOf(direction), EntryRef(connectionId, remoteOpaqueId),
        localUri, name, mimeType, totalBytes, confirmedBytes, connectionRevision, sourceRevision,
        TransferState.valueOf(state), error, createdAt, updatedAt, attempt, backupRuleId,
        logicalSourceId, sourceGeneration,
    )
    companion object {
        fun from(task: TransferTask) = TransferTaskEntity(
            task.id, task.operationId, task.direction.name, task.remoteRef.connectionId,
            task.remoteRef.opaqueId, task.localUri, task.name, task.mimeType, task.totalBytes,
            task.confirmedBytes, task.connectionRevision, task.sourceRevision, task.state.name,
            task.error, task.createdAt, task.updatedAt, task.attempt, task.backupRuleId,
            task.logicalSourceId, task.sourceGeneration,
        )
    }
}

@Entity(tableName = "backup_rules", indices = [Index("connectionId")])
data class BackupRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceTreeUri: String,
    val sourceKind: String,
    val connectionId: String,
    val remoteOpaqueId: String,
    val wifiOnly: Boolean,
    val unmeteredOnly: Boolean,
    val chargingOnly: Boolean,
    val enabled: Boolean,
    val lastCheck: Long?,
    val lastSuccess: Long?,
) {
    fun model() = BackupRule(
        id, name, sourceTreeUri, sourceKind, EntryRef(connectionId, remoteOpaqueId),
        wifiOnly, unmeteredOnly, chargingOnly, enabled, lastCheck, lastSuccess,
    )
    companion object {
        fun from(rule: BackupRule) = BackupRuleEntity(
            rule.id, rule.name, rule.sourceTreeUri, rule.sourceKind,
            rule.target.connectionId, rule.target.opaqueId, rule.wifiOnly, rule.unmeteredOnly,
            rule.chargingOnly, rule.enabled, rule.lastCheck, rule.lastSuccess,
        )
    }
}

@Entity(tableName = "backup_baselines", primaryKeys = ["ruleId", "logicalSourceId"])
data class BackupBaselineEntity(
    val ruleId: String,
    val logicalSourceId: String,
    val sourceGeneration: String,
    val connectionId: String,
    val remoteOpaqueId: String,
    val remoteRevision: String?,
    val verifiedBytes: Long?,
    val verifiedAt: Long,
    val sourceCreatedAt: Long = 0,
) {
    fun model() = BackupBaseline(
        ruleId, logicalSourceId, sourceGeneration, EntryRef(connectionId, remoteOpaqueId),
        remoteRevision, verifiedBytes, verifiedAt,
    )
}

/** Private discovery continuation; resuming may repeat rows but never infers deletions. */
@Entity(tableName = "backup_scan_cursors")
data class BackupScanCursorEntity(
    @PrimaryKey val ruleId: String,
    val payload: String,
    val updatedAt: Long,
)
