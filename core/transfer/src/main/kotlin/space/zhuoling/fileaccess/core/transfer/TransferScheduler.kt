package space.zhuoling.fileaccess.core.transfer

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.PersistableBundle
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.model.TransferState

@Singleton
class TransferScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: TransferRepository,
    private val engine: TransferEngine,
    private val scanner: BackupScanner,
) {
    private val work get() = WorkManager.getInstance(context)

    suspend fun initialize() {
        tasks.recoverExpired()
        work.enqueueUniquePeriodicWork("backup-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        // Reclaim an execution killed shortly before opening the app, after its heartbeat expires.
        work.enqueueUniqueWork("recover-transfers", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecoveryWorker>().setInitialDelay(65, TimeUnit.SECONDS).build())
    }

    fun scheduleBackupScan() {
        work.enqueueUniqueWork("backup-scan", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    /** Must be called as a consequence of the user's current, visible action. */
    suspend fun startUserInitiated(ids: List<String>) {
        if (ids.isEmpty()) return
        val eligible = ids.distinct().filter { tasks.get(it)?.state in setOf(TransferState.QUEUED, TransferState.WAITING, TransferState.RECONCILING) }
        if (eligible.isEmpty()) return
        // A current user action may retry immediately while preserving unknown-commit state.
        eligible.forEach { tasks.makeEligibleNow(it) }
        // Namespace prevents collisions with WorkManager's JobScheduler IDs.
        val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace("fileaccess-user-transfers")
        for (batch in eligible.chunked(100)) {
            val jobId = UUID.randomUUID().hashCode() and Int.MAX_VALUE
            val extras = PersistableBundle().apply { putStringArray("task_ids", batch.toTypedArray()) }
            // No INTERNET/VALIDATED capability: a NAS can be reachable on an offline LAN.
            val info = JobInfo.Builder(jobId, ComponentName(context, UserTransferService::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build())
                .setRequiresStorageNotLow(true)
                .setExtras(extras)
                .build()
            val result = try { scheduler.schedule(info) }
                catch (_: IllegalStateException) { JobScheduler.RESULT_FAILURE }
                catch (_: SecurityException) { JobScheduler.RESULT_FAILURE }
            if (result != JobScheduler.RESULT_SUCCESS) {
                batch.forEach { tasks.setWaiting(it, "系统尚未启动任务；请在应用内点击继续") }
            }
        }
    }

    suspend fun scanAndStartUserInitiated(): ScanResult {
        val result = scanner.scan()
        val ids = tasks.runnableIds(limit = 1000).filter { tasks.get(it)?.backupRuleId != null }
        startUserInitiated(ids)
        return result
    }

    suspend fun pause(id: String) { if (tasks.pause(id)) engine.stop(id) }
    suspend fun cancel(id: String) {
        if (tasks.cancel(id)) {
            engine.stop(id)
            scheduleBackupScan()
            // Only discard this app's private staging data; the chosen destination may exist.
            runCatching { UUID.fromString(id) }.getOrNull()?.let {
                java.io.File(context.noBackupFilesDir, "transfers/$id.part").delete()
            }
        }
    }
}
