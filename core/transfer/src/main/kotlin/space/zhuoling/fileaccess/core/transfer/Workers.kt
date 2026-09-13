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
            // SMB checkpoints survive each bounded WorkManager execution, including large videos.
            val finished = withTimeoutOrNull(7 * 60_000L) {
                for (id in tasks.runnableIds(limit = 1000)) {
                    val task = tasks.get(id) ?: continue
                    if (task.backupRuleId == null) continue
                    engine.executeAutomatic(id)
                    if (tasks.get(id)?.state in setOf(TransferState.WAITING, TransferState.RECONCILING)) unfinished = true
                }
                true
            } ?: false
            withTimeoutOrNull(30_000L) { engine.cleanupUploads() }
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
