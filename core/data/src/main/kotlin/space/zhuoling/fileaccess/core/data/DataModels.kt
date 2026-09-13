package space.zhuoling.fileaccess.core.data

import java.util.UUID
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.TransferDirection
import space.zhuoling.fileaccess.core.model.TransferState

data class TransferTask(
    val id: String = UUID.randomUUID().toString(),
    val operationId: String = id,
    val direction: TransferDirection,
    /** Upload target directory, or download source file. */
    val remoteRef: EntryRef,
    /** Persisted SAF/MediaStore URI; never an inferred filesystem path. */
    val localUri: String,
    val name: String,
    val mimeType: String? = null,
    val totalBytes: Long? = null,
    val confirmedBytes: Long = 0,
    val connectionRevision: Long = 1,
    val sourceRevision: String? = null,
    val state: TransferState = TransferState.QUEUED,
    /** Only sanitized, user-facing messages may be persisted. */
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val attempt: Int = 0,
    val backupRuleId: String? = null,
    val logicalSourceId: String? = null,
    val sourceGeneration: String? = null,
)

data class TransferLease(val task: TransferTask, val owner: String, val generation: Long)

data class BackupRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceTreeUri: String = "",
    val sourceKind: String = "tree",
    val target: EntryRef,
    val wifiOnly: Boolean = true,
    val unmeteredOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val enabled: Boolean = true,
    val lastCheck: Long? = null,
    val lastSuccess: Long? = null,
)

data class BackupBaseline(
    val ruleId: String,
    val logicalSourceId: String,
    val sourceGeneration: String,
    val remoteRef: EntryRef,
    val remoteRevision: String?,
    val verifiedBytes: Long?,
    val verifiedAt: Long,
)

data class AppSettings(
    val theme: String = "system",
    val showHiddenFiles: Boolean = false,
    val showFileNamesInNotifications: Boolean = false,
    val browserViewMode: String = "list",
    val mediaThumbnailsUnmeteredOnly: Boolean = true,
)
