package space.zhuoling.fileaccess.preview

import android.content.Context
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.transfer.RemoteAccess

@Singleton
class PreviewRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteAccess,
) {
    private val cacheMutex = Mutex()
    fun mime(entry: RemoteEntry): String = entry.mimeType
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

    suspend fun text(entry: RemoteEntry): Pair<String, Boolean> = remote.withSession(entry.ref.connectionId) { session ->
        session.openRead(entry.ref, expectedRevision = entry.revision).use { input ->
            val bytes = input.readNBytes(1_048_577)
            bytes.copyOf(minOf(bytes.size, 1_048_576)).toString(Charsets.UTF_8) to (bytes.size > 1_048_576)
        }
    }

    suspend fun cachedFile(entry: RemoteEntry): File = cacheMutex.withLock { withContext(Dispatchers.IO) {
        val maximum = 128L * 1024 * 1024
        if ((entry.size ?: 0L) > maximum) throw StorageException(StorageError.QUOTA_EXCEEDED, "文件较大，请下载后打开")
        val directory = File(context.cacheDir, "previews").apply { mkdirs() }
        if (directory.usableSpace < maximum + 512L * 1024 * 1024) {
            throw StorageException(StorageError.QUOTA_EXCEEDED, "预览需要更多本机空间")
        }
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${entry.ref.connectionId}\u0000${entry.ref.opaqueId}\u0000${entry.revision}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.${entry.name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12)}")
        if (entry.revision != null && file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        val candidates = directory.listFiles().orEmpty().filter { it != file }.sortedBy { it.lastModified() }
        var used = candidates.sumOf { it.length() }
        val incoming = entry.size?.coerceAtLeast(0) ?: maximum
        for (old in candidates) {
            val expired = System.currentTimeMillis() - old.lastModified() > 24 * 60 * 60 * 1000L
            if (expired || used + incoming > 256L * 1024 * 1024) {
                val bytes = old.length()
                if (old.delete()) used -= bytes
            }
        }
        val temporary = File.createTempFile("preview-", ".part", directory)
        try {
            remote.withSession(entry.ref.connectionId) { session ->
                session.openRead(entry.ref, expectedRevision = entry.revision).use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > maximum) throw StorageException(StorageError.QUOTA_EXCEEDED, "文件较大，请下载后打开")
                            output.write(buffer, 0, read)
                        }
                        if (entry.size != null && count != entry.size) {
                            throw StorageException(StorageError.CORRUPT_DATA, "读取长度与文件大小不一致")
                        }
                    }
                }
                if (entry.revision != null && session.stat(entry.ref).revision != entry.revision) {
                    throw StorageException(StorageError.SOURCE_CHANGED, "预览期间文件已变化")
                }
            }
            if (!temporary.renameTo(file)) throw java.io.IOException("Cannot publish preview")
            directory.listFiles()?.filter { it != file && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                ?.forEach { it.delete() }
            file
        } finally { temporary.delete() }
    } }
}
