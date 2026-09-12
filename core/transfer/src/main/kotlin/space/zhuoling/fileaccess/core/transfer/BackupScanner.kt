package space.zhuoling.fileaccess.core.transfer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import space.zhuoling.fileaccess.core.data.BackupRepository
import space.zhuoling.fileaccess.core.data.BackupRule
import space.zhuoling.fileaccess.core.data.ConnectionRepository
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.data.TransferTask
import space.zhuoling.fileaccess.core.model.TransferDirection

data class ScanResult(val taskIds: List<String>, val messages: List<String>)

/** Discovery only. Every upload remains a durable task executed by the normal transfer engine. */
@Singleton
class BackupScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: ConnectionRepository,
    private val backups: BackupRepository,
    private val transfers: TransferRepository,
    private val networkPolicy: NetworkPolicy,
    @BackupClock private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private val resolver get() = context.contentResolver

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val taskIds = linkedSetOf<String>()
            val messages = linkedSetOf<String>()
            val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MILLIS
            // Start with the rule checked least recently, so many rules can make progress.
            val rules = backups.enabledRules().sortedBy { it.lastCheck ?: Long.MIN_VALUE }
            for (rule in rules) {
                currentCoroutineContext().ensureActive()
                if (taskIds.size >= MAX_NEW_TASKS || SystemClock.elapsedRealtime() >= deadline) {
                    messages += "本轮扫描已达到批次限制，下次将继续检查。"
                    break
                }
                val connection = connections.get(rule.target.connectionId)
                if (connection == null) {
                    messages += "${rule.name}：目标连接已移除。"
                    continue
                }
                networkPolicy.waitingReason(rule.wifiOnly, rule.unmeteredOnly, rule.chargingOnly)?.let {
                    messages += "${rule.name}：$it，发现的文件会先排队。"
                }
                try {
                    val ruleDeadline = minOf(deadline,
                        SystemClock.elapsedRealtime() + SCAN_BUDGET_MILLIS / rules.size.coerceAtLeast(1))
                    val ruleTaskLimit = minOf(MAX_NEW_TASKS,
                        taskIds.size + (MAX_NEW_TASKS / rules.size.coerceAtLeast(1)).coerceAtLeast(1))
                    val complete = when (rule.sourceKind) {
                        "camera" -> scanCamera(rule, connection.revision, taskIds, messages, ruleDeadline, ruleTaskLimit)
                        "tree" -> scanTree(rule, connection.revision, taskIds, messages, ruleDeadline, ruleTaskLimit)
                        else -> false
                    }
                    if (complete) {
                        backups.clearScanCursor(rule.id)
                        backups.recordCheck(rule.id)
                    } else messages += "${rule.name}：尚未完成全部检查，已保存扫描进度。"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    messages += "${rule.name}：来源访问权限不可用，请重新授权。"
                } catch (_: Exception) {
                    // Provider messages may contain private paths or remote credentials.
                    messages += "${rule.name}：来源暂时无法完整读取，保留原文件并在下次重试。"
                }
            }
            ScanResult(taskIds.toList(), messages.toList())
        }
    }

    private suspend fun enqueue(
        rule: BackupRule, connectionRevision: Long, uri: Uri, identity: String,
        taskIds: MutableSet<String>, messages: MutableSet<String>,
    ) {
        currentCoroutineContext().ensureActive()
        val document = resolver.describe(uri)
        val baseline = backups.baseline(rule.id, identity)
        if (baseline?.sourceGeneration == document.version && baseline.remoteRef.connectionId == rule.target.connectionId) return
        if (document.size == null) {
            messages += "${rule.name}：部分文件大小未知，上传时会再次检查。"
        }
        val task = TransferTask(
            direction = TransferDirection.UPLOAD,
            remoteRef = rule.target,
            localUri = document.uri,
            name = backupFileName(document.name, identity, document.version),
            mimeType = document.mimeType,
            totalBytes = document.size,
            connectionRevision = connectionRevision,
            backupRuleId = rule.id,
            logicalSourceId = identity,
            sourceGeneration = document.version,
        )
        taskIds += transfers.enqueue(task)
    }

    private suspend fun scanCamera(
        rule: BackupRule, connectionRevision: Long, taskIds: MutableSet<String>,
        messages: MutableSet<String>, deadline: Long, maxTaskCount: Int,
    ): Boolean {
        val selected = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
        if (!images && !videos && !selected) throw SecurityException()
        if (selected && (!images || !videos)) messages += "${rule.name}：仅备份系统当前授权的照片和视频。"
        if (!selected && (!images || !videos)) messages += "${rule.name}：照片或视频权限未全部授予，仅检查可访问类别。"
        val volumes = MediaStore.getExternalVolumeNames(context).sorted()
        val streams = volumes.flatMap { volume ->
            buildList {
                if (images || selected) add(MediaStream(volume, false))
                if (videos || selected) add(MediaStream(volume, true))
            }
        }
        if (streams.isEmpty()) {
            messages += "${rule.name}：本机媒体卷暂不可用。"
            return false
        }
        val saved = readCursor(rule.id, "camera")
        val signature = streams.joinToString("|") { "${it.volume}:${it.video}" }
        var streamIndex = if (saved?.optString("streams") == signature) saved.optInt("stream", 0) else 0
        var lastId = if (saved?.optString("streams") == signature) saved.optLong("lastId", -1) else -1
        while (streamIndex < streams.size) {
            currentCoroutineContext().ensureActive()
            val stream = streams[streamIndex]
            val version = MediaStore.getVersion(context, stream.volume) ?: "unknown"
            if (saved != null && saved.optInt("stream", -1) == streamIndex &&
                saved.optString("mediaVersion", version) != version) lastId = -1
            val collection = if (stream.video) MediaStore.Video.Media.getContentUri(stream.volume)
                else MediaStore.Images.Media.getContentUri(stream.volume)
            fun cursorPayload() = JSONObject().put("kind", "camera").put("version", 1)
                .put("streams", signature).put("stream", streamIndex).put("lastId", lastId)
                .put("mediaVersion", version).toString()
            val selection = "${MediaStore.MediaColumns._ID} > ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND " +
                "${MediaStore.MediaColumns.DATE_MODIFIED} <= ?"
            val args = arrayOf(lastId.toString(), "DCIM/Camera/%",
                ((clock.millis() - STABLE_AGE_MILLIS) / 1000).toString())
            val cursor = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                selection, args, "${MediaStore.MediaColumns._ID} ASC")
                ?: throw java.io.IOException("Media provider unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (taskIds.size >= maxTaskCount || SystemClock.elapsedRealtime() >= deadline) {
                        backups.saveScanCursor(rule.id, cursorPayload())
                        return false
                    }
                    val id = it.getLong(0)
                    val uri = ContentUris.withAppendedId(collection, id)
                    // The media DB version namespaces IDs after a media database rebuild.
                    enqueue(rule, connectionRevision, uri, "$uri#media-version=$version", taskIds, messages)
                    lastId = id
                    backups.saveScanCursor(rule.id, cursorPayload())
                }
            }
            streamIndex++
            lastId = -1
            backups.saveScanCursor(rule.id, cursorPayload())
        }
        return true
    }

    private suspend fun scanTree(
        rule: BackupRule, connectionRevision: Long, taskIds: MutableSet<String>,
        messages: MutableSet<String>, deadline: Long, maxTaskCount: Int,
    ): Boolean {
        val tree = Uri.parse(rule.sourceTreeUri)
        if (!DocumentsContract.isTreeUri(tree) || resolver.persistedUriPermissions.none {
                it.uri == tree && it.isReadPermission
            }) throw SecurityException()
        val saved = readCursor(rule.id, "tree")?.takeIf { it.optString("style") == "depth-first" }
        val queue = ArrayDeque<DirectoryCursor>()
        val savedDirectories = saved?.optJSONArray("directories")
        if (savedDirectories != null) {
            for (index in 0 until savedDirectories.length().coerceAtMost(MAX_DIRECTORY_DEPTH)) {
                val item = savedDirectories.getJSONObject(index)
                queue += DirectoryCursor(item.getString("id"), item.optInt("offset", 0).coerceAtLeast(0))
            }
        }
        if (queue.isEmpty()) queue += DirectoryCursor(DocumentsContract.getTreeDocumentId(tree), 0)
        suspend fun checkpoint() {
            val directories = JSONArray()
            queue.forEach { directories.put(JSONObject().put("id", it.id).put("offset", it.offset)) }
            backups.saveScanCursor(rule.id, JSONObject().put("kind", "tree").put("version", 1)
                .put("style", "depth-first").put("directories", directories).toString())
        }
        var visitedRows = 0
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.last()
            var descended = false
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS)
            val cursor = resolver.query(children, projection, null, null, null)
                ?: throw java.io.IOException("Document provider unavailable")
            cursor.use {
                // SAF offers no universal change cursor. Persisted offsets are only discovery
                // hints; every completed pass resets them and no missing row causes deletion.
                it.moveToPosition(directory.offset - 1)
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (taskIds.size >= maxTaskCount || visitedRows >= MAX_ROWS ||
                        SystemClock.elapsedRealtime() >= deadline) {
                        checkpoint()
                        return false
                    }
                    visitedRows++
                    val id = it.getString(0)
                    val mime = it.getString(1)
                    val modified = if (it.isNull(2)) null else it.getLong(2)
                    val flags = if (it.isNull(3)) 0 else it.getInt(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (queue.none { parent -> parent.id == id }) {
                            if (queue.size >= MAX_DIRECTORY_DEPTH) {
                                messages += "${rule.name}：目录层级过深，请选择更具体的备份文件夹。"
                                checkpoint()
                                return false
                            }
                            directory.offset = it.position + 1
                            queue += DirectoryCursor(id, 0)
                            descended = true
                            checkpoint()
                            break
                        }
                    } else if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                        messages += "${rule.name}：跳过需要转换格式的虚拟文件。"
                    } else if (modified == null || modified <= 0 ||
                        modified < clock.millis() - STABLE_AGE_MILLIS) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        enqueue(rule, connectionRevision, uri, uri.toString(), taskIds, messages)
                        if (modified == null || modified <= 0) {
                            messages += "${rule.name}：部分来源没有修改时间，无法保证识别同大小的内容变化。"
                        }
                    }
                    directory.offset = it.position + 1
                    if (visitedRows % 25 == 0) checkpoint()
                }
                if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    messages += "${rule.name}：来源仍在载入，保留进度并等待下次完整检查。"
                    checkpoint()
                    return false
                }
            }
            if (!descended) queue.removeLast()
            checkpoint()
        }
        return true
    }

    private suspend fun readCursor(id: String, kind: String): JSONObject? {
        val serialized = backups.scanCursor(id) ?: return null
        return runCatching { JSONObject(serialized) }.getOrNull()?.takeIf {
            it.optInt("version") == 1 && it.optString("kind") == kind
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private data class MediaStream(val volume: String, val video: Boolean)
    private data class DirectoryCursor(val id: String, var offset: Int)

    private companion object {
        const val SCAN_BUDGET_MILLIS = 20_000L
        const val STABLE_AGE_MILLIS = 60_000L
        const val MAX_NEW_TASKS = 200
        const val MAX_ROWS = 5000
        const val MAX_DIRECTORY_DEPTH = 256
    }
}

/** Stable non-overwriting names preserve new source generations without overwriting older backups. */
internal fun backupFileName(original: String, identity: String, generation: String): String {
    val normalized = original.map { char ->
        if (char.code < 32 || char in "\\/:*?\"<>|") '_' else char
    }.joinToString("").trim().trimEnd('.').ifBlank { "file" }
    val extensionAt = normalized.lastIndexOf('.').takeIf { it > 0 && normalized.length - it <= 12 }
    val rawStem = extensionAt?.let { normalized.take(it) } ?: normalized
    val stem = buildString {
        var bytes = 0
        var index = 0
        while (index < rawStem.length) {
            val point = rawStem.codePointAt(index)
            val next = String(Character.toChars(point))
            val byteCount = next.toByteArray(Charsets.UTF_8).size
            if (bytes + byteCount > 180) break
            append(next)
            bytes += byteCount
            index += Character.charCount(point)
        }
    }
    val extension = extensionAt?.let { normalized.substring(it) } ?: ""
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "${identity.length}:$identity${generation.length}:$generation".toByteArray(Charsets.UTF_8),
    ).take(8).joinToString("") { "%02x".format(it) }
    return "$stem-$digest$extension"
}
