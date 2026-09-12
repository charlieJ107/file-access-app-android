package space.zhuoling.fileaccess.core.transfer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import space.zhuoling.fileaccess.core.data.BackupRepository
import space.zhuoling.fileaccess.core.data.TransferLease
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.data.TransferTask
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.UploadCapability
import space.zhuoling.fileaccess.core.storage.UploadRequest

@Singleton
class TransferEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: TransferRepository,
    private val backups: BackupRepository,
    private val remote: RemoteAccess,
    private val network: NetworkPolicy,
) {
    private val concurrent = Semaphore(2)
    private val connections = ConcurrentHashMap<String, Semaphore>()
    private val active = ConcurrentHashMap<String, Job>()

    fun stop(id: String) { active[id]?.cancel(CancellationException("Task interrupted")) }

    suspend fun execute(id: String, onProgress: (Long, Long?) -> Unit = { _, _ -> }) {
        val task = tasks.get(id) ?: return
        concurrent.withPermit {
            connections.getOrPut(task.remoteRef.connectionId) { Semaphore(1) }.withPermit {
                runTransferChild { executeClaimed(id, onProgress) }
            }
        }
    }

    private suspend fun executeClaimed(id: String, onProgress: (Long, Long?) -> Unit) = coroutineScope {
        val lease = tasks.claim(id, UUID.randomUUID().toString()) ?: return@coroutineScope
        val task = lease.task
        val executionJob = currentCoroutineContext().job
        active[id] = executionJob
        val heartbeat = launch {
            while (isActive) {
                delay(15_000)
                if (!tasks.renew(lease)) { executionJob.cancel(CancellationException("Task ownership changed")); break }
            }
        }
        try {
            val rule = task.backupRuleId?.let { backups.get(it) }
            if (task.backupRuleId != null && (rule == null || !rule.enabled)) {
                tasks.transition(lease, TransferState.WAITING, "备份规则已暂停或移除")
                return@coroutineScope
            }
            val waiting = network.waitingReason(rule?.wifiOnly ?: false, rule?.unmeteredOnly ?: false, rule?.chargingOnly ?: false)
            if (waiting != null) {
                tasks.transition(lease, TransferState.WAITING, waiting, retryAt = System.currentTimeMillis() + 30_000)
                return@coroutineScope
            }
            withContext(Dispatchers.IO) {
                val connectedNetwork = network.selectedNetworkHandle()
                remote.open(task.remoteRef.connectionId, task.connectionRevision).use { session ->
                    requireTransition(lease, TransferState.RUNNING)
                    val lastUpdate = AtomicLong(0)
                    var lastAcknowledged = task.confirmedBytes
                    fun checkRunning(force: Boolean = false) {
                        executionJob.ensureActive()
                        val now = System.currentTimeMillis()
                        if (force || now - lastUpdate.get() > 400) {
                            if (!runBlocking { tasks.owns(lease) }) throw CancellationException("Task ownership changed")
                            val condition = network.waitingReason(rule?.wifiOnly ?: false, rule?.unmeteredOnly ?: false, rule?.chargingOnly ?: false)
                            if (condition != null) throw WaitForConditions(condition)
                            if (network.selectedNetworkHandle() != connectedNetwork) {
                                throw WaitForConditions("网络已切换，等待重新连接 NAS")
                            }
                        }
                    }
                    fun progress(bytes: Long, total: Long?) {
                        checkRunning()
                        onProgress(bytes, total)
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate.get() >= 400 || bytes == total) {
                            if (!runBlocking { tasks.progress(lease, maxOf(bytes, lastAcknowledged), total) }) {
                                throw CancellationException("Task ownership changed")
                            }
                            lastAcknowledged = maxOf(lastAcknowledged, bytes)
                            lastUpdate.set(now)
                        }
                    }
                    if (task.direction == TransferDirection.UPLOAD) {
                        val uploader = session as? UploadCapability
                            ?: throw StorageException(StorageError.UNSUPPORTED, "Upload is not supported")
                        val document = context.contentResolver.describe(Uri.parse(task.localUri))
                        verifyCameraVersion(task)
                        if (task.sourceGeneration != null && document.version != task.sourceGeneration) {
                            throw StorageException(StorageError.SOURCE_CHANGED, "Local source changed")
                        }
                        // SMB's initial writer restarts the current payload and reconciles its receipt.
                        // The UI explicitly describes restart semantics; no byte-resume is advertised.
                        if (!tasks.resetProgress(lease)) throw CancellationException("Task ownership changed")
                        lastAcknowledged = 0
                        val source = ContentUriSource(context.contentResolver, document) { checkRunning() }
                        val result = uploader.upload(
                            UploadRequest(task.operationId, task.remoteRef, task.name), source,
                            onProgress = { progress(it, document.size) },
                            onCommit = {
                                checkRunning(force = true)
                                verifyCameraVersion(task)
                                runBlocking {
                                    requireTransition(lease, TransferState.VERIFYING)
                                    requireTransition(lease, TransferState.COMMITTING)
                                }
                            },
                        )
                        withContext(NonCancellable) {
                            tasks.progress(lease, result.size ?: document.size ?: lastAcknowledged, result.size ?: document.size)
                            tasks.complete(lease, result)
                        }
                    } else {
                        val current = session.stat(task.remoteRef)
                        if (task.sourceRevision != null && current.revision != task.sourceRevision) {
                            throw StorageException(StorageError.SOURCE_CHANGED, "Remote source changed")
                        }
                        current.revision?.let {
                            if (!tasks.pinSourceRevision(lease, it)) throw CancellationException("Task ownership changed")
                        }
                        val directory = File(context.noBackupFilesDir, "transfers").apply { mkdirs() }
                        // Only app-generated task UUIDs address these private files.
                        UUID.fromString(task.id)
                        val file = File(directory, "${task.id}.part")
                        // Only fsynced and durably recorded bytes form a resumable checkpoint.
                        var offset = if (session.capabilities.rangeRead && current.revision != null)
                            minOf(file.length(), task.confirmedBytes) else 0L
                        if (offset > (current.size ?: Long.MAX_VALUE)) offset = 0
                        val remaining = (current.size ?: 128L * 1024 * 1024) - offset
                        if (directory.usableSpace < remaining + 512L * 1024 * 1024) {
                            throw StorageException(StorageError.QUOTA_EXCEEDED, "Insufficient space to stage download")
                        }
                        java.io.RandomAccessFile(file, "rw").use { it.setLength(offset) }
                        if (offset == 0L || offset != task.confirmedBytes) {
                            if (!tasks.resetProgress(lease)) throw CancellationException("Task ownership changed")
                            if (offset > 0 && !tasks.progress(lease, offset, current.size)) {
                                throw CancellationException("Task ownership changed")
                            }
                            lastAcknowledged = offset
                        }
                        var count = offset
                        var lastDurable = offset
                        session.openRead(task.remoteRef, offset, current.revision).use { input ->
                            file.outputStreamAppend().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkRunning()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    count += read
                                    if (count - lastDurable >= 4L * 1024 * 1024 &&
                                        directory.usableSpace < 512L * 1024 * 1024) {
                                        throw StorageException(StorageError.QUOTA_EXCEEDED, "Insufficient download staging space")
                                    }
                                    onProgress(count, current.size)
                                    if (count - lastDurable >= 4L * 1024 * 1024) {
                                        output.fd.sync()
                                        progress(count, current.size)
                                        lastDurable = count
                                    }
                                }
                                output.fd.sync()
                                progress(count, current.size ?: count)
                            }
                        }
                        requireTransition(lease, TransferState.VERIFYING)
                        if (current.size != null && count != current.size) throw StorageException(StorageError.CORRUPT_DATA, "Download length mismatch")
                        if (current.revision != null && session.stat(task.remoteRef).revision != current.revision) {
                            throw StorageException(StorageError.SOURCE_CHANGED, "Remote source changed during download")
                        }
                        checkRunning(force = true)
                        requireTransition(lease, TransferState.COMMITTING)
                        // ACTION_CREATE_DOCUMENT gives a new destination. A stopped publication is
                        // replayed from the staged file; never treat its partial contents as success.
                        context.contentResolver.openOutputStream(Uri.parse(task.localUri), "wt")?.use { output ->
                            file.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    executionJob.ensureActive()
                                    if (!tasks.owns(lease)) throw CancellationException("Task ownership changed")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
                            }
                        } ?: throw SecurityException("Destination is unavailable")
                        withContext(NonCancellable) {
                            tasks.progress(lease, count, current.size ?: count)
                            if (tasks.complete(lease, current)) file.delete()
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val current = tasks.get(id)
                if (current?.state in setOf(TransferState.COMMITTING, TransferState.RECONCILING)) {
                    tasks.releaseForReconciliation(lease, "传输已中断；继续时将核对已有结果")
                } else tasks.transition(lease, TransferState.WAITING,
                    "传输已中断；继续时将核对已有结果", retryAt = System.currentTimeMillis() + 30_000)
            }
            throw cancelled
        } catch (wait: WaitForConditions) {
            if (tasks.get(id)?.state in setOf(TransferState.COMMITTING, TransferState.RECONCILING)) {
                tasks.releaseForReconciliation(lease, wait.message)
            } else tasks.transition(lease, TransferState.WAITING, wait.message, retryAt = System.currentTimeMillis() + 30_000)
        } catch (error: Exception) {
            val current = tasks.get(id)
            val ambiguous = current?.state == TransferState.COMMITTING || (error as? StorageException)?.error == StorageError.OUTCOME_UNKNOWN
            val attempt = current?.attempt ?: task.attempt
            val temporary = (error as? StorageException)?.error == StorageError.NETWORK && attempt < 5
            val state = when { ambiguous && attempt < 5 -> TransferState.RECONCILING; temporary -> TransferState.WAITING; else -> TransferState.FAILED }
            val retryAt = System.currentTimeMillis() + retryDelay(attempt)
            if (state == TransferState.RECONCILING) {
                tasks.releaseForReconciliation(lease, failureMessage(error), retryAt = retryAt)
            } else tasks.transition(lease, state, failureMessage(error), retryAt = if (temporary) retryAt else 0)
        } finally {
            heartbeat.cancel()
            active.remove(id, executionJob)
        }
    }

    private suspend fun requireTransition(lease: TransferLease, target: TransferState) {
        if (!tasks.transition(lease, target)) throw CancellationException("Task ownership changed")
    }

    private fun verifyCameraVersion(task: TransferTask) {
        val identity = task.logicalSourceId ?: return
        val uri = Uri.parse(task.localUri)
        if (uri.authority != MediaStore.AUTHORITY || !identity.contains("#media-version=")) return
        val expected = identity.substringAfterLast("#media-version=")
        val current = MediaStore.getVersion(context, MediaStore.getVolumeName(uri)) ?: "unknown"
        if (expected != current) throw StorageException(StorageError.SOURCE_CHANGED, "Media database changed")
    }

    companion object {
        fun retryDelay(attempt: Int): Long = minOf(6L * 60 * 60 * 1000, 30_000L * (1L shl attempt.coerceIn(0, 10)))
    }
}

private fun File.outputStreamAppend() = java.io.FileOutputStream(this, true)
private class WaitForConditions(message: String) : Exception(message)

internal fun failureMessage(error: Throwable): String = when (error) {
    is SecurityException -> "本机访问授权已失效，请重新授权"
    is space.zhuoling.fileaccess.core.security.CredentialUnavailableException -> "已保存的凭据不可用，请重新登录此连接"
    is StorageException -> when (error.error) {
        StorageError.AUTHENTICATION -> "登录失败，请更新连接凭据"
        StorageError.PERMISSION -> "共享或文件访问权限不足"
        StorageError.NETWORK -> "NAS 暂时不可达；继续时会核对结果，上传可能需要从头重传"
        StorageError.CONFLICT -> "目标存在同名文件，未覆盖；请另取名称后重新上传"
        StorageError.SOURCE_CHANGED -> "源文件已变化，请重新创建任务"
        StorageError.OUTCOME_UNKNOWN -> "远端提交结果待确认，请继续同一任务进行核对"
        StorageError.CORRUPT_DATA -> "内容校验未通过，未标记完成"
        StorageError.QUOTA_EXCEEDED -> "可用存储空间不足"
        StorageError.INVALID_CONFIGURATION -> "连接配置已变化，请重新创建任务"
        StorageError.NOT_FOUND -> "源文件或目标目录不存在"
        StorageError.UNSUPPORTED -> "此服务不支持此操作"
    }
    else -> "传输未完成，请检查连接和授权后重试"
}
