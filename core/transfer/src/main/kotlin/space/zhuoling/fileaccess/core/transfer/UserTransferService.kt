package space.zhuoling.fileaccess.core.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.*
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.model.TransferState

@AndroidEntryPoint
class UserTransferService : JobService() {
    @Inject lateinit var engine: TransferEngine
    @Inject lateinit var tasks: TransferRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val ids = params.extras.getStringArray("task_ids")?.toList().orEmpty()
        if (ids.isEmpty()) return false
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "文件传输", NotificationManager.IMPORTANCE_LOW))
        fun notification(progress: Long = 0, total: Long? = null, finished: Int = 0): android.app.Notification {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val pause = Intent(this, TransferActionReceiver::class.java)
                .setAction("space.zhuoling.fileaccess.PAUSE").putExtra("task_ids", ids.toTypedArray())
            val pending = PendingIntent.getBroadcast(this, params.jobId, pause, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("FileAccess · 文件传输")
                .setContentText("已完成 $finished / ${ids.size} 项")
                .setOnlyAlertOnce(true).setOngoing(true)
                .setProgress(100, if (total != null && total > 0) ((progress.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0, total == null)
                .setContentIntent(launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
                .addAction(android.R.drawable.ic_media_pause, "暂停", pending)
                .build()
        }
        setNotification(params, params.jobId, notification(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        jobs[params.jobId] = scope.launch {
            var completed = 0
            var reschedule = false
            var lastNotification = 0L
            try {
                tasks.recoverExpired()
                for (id in ids) {
                    engine.execute(id) { bytes, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotification >= 500) {
                            setNotification(params, params.jobId, notification(bytes, total, completed), JOB_END_NOTIFICATION_POLICY_REMOVE)
                            lastNotification = now
                        }
                    }
                    val task = tasks.get(id)
                    if (task?.state == TransferState.SUCCEEDED) completed++
                    if (task?.state in setOf(TransferState.WAITING, TransferState.RECONCILING)) reschedule = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reschedule = true }
            finally {
                if (isActive) jobFinished(params, reschedule)
                jobs.remove(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            // A system Task Manager stop is also a user pause, including for automatic rules.
            val ids = params.extras.getStringArray("task_ids").orEmpty()
            scope.launch { ids.forEach { tasks.pause(it) } }
        }
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { private const val CHANNEL = "file-transfers" }
}

@AndroidEntryPoint
class TransferActionReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: TransferScheduler
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "space.zhuoling.fileaccess.PAUSE") return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { intent.getStringArrayExtra("task_ids")?.forEach { scheduler.pause(it) } }
            finally { pending.finish() }
        }
    }
}
