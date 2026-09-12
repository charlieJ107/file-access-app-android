package space.zhuoling.fileaccess.core.transfer

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.model.TransferState

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val scanner: BackupScanner,
    private val tasks: TransferRepository,
    private val engine: TransferEngine,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            tasks.recoverExpired()
            scanner.scan()
            var unfinished = false
            // Automatic one-shot SMB uploads must fit an interruptible time slice. Large files
            // remain visible and require a user-initiated long job until byte-resume is added.
            val finished = withTimeoutOrNull(7 * 60_000L) {
                for (id in tasks.runnableIds(limit = 1000)) {
                    val task = tasks.get(id) ?: continue
                    if (task.backupRuleId == null) continue
                    val totalBytes = task.totalBytes
                    if (totalBytes == null || totalBytes > 256L * 1024 * 1024) {
                        tasks.setWaiting(id, "大文件需要在应用内点击立即备份；当前上传会从头重传")
                        continue
                    }
                    engine.execute(id)
                    if (tasks.get(id)?.state in setOf(TransferState.WAITING, TransferState.RECONCILING)) unfinished = true
                }
                true
            } ?: false
            if ((!finished || unfinished) && runAttemptCount < 5) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
}

@HiltWorker
class RecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val tasks: TransferRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { tasks.recoverExpired(); return Result.success() }
}
