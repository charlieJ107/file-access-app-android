package space.zhuoling.fileaccess.core.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FilterInputStream
import java.io.InputStream
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.storage.UploadSource

data class LocalDocument(val uri: String, val name: String, val size: Long?, val mimeType: String?, val version: String)

/** Always query a URI through its provider; a content URI is not a filesystem path. */
fun ContentResolver.describe(uri: Uri): LocalDocument {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "A user-granted content URI is required" }
    var name = "file"
    var size: Long? = null
    var modified: Long? = null
    var generation: Long? = null
    query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                if (!cursor.isNull(it)) name = cursor.getString(it)
            }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                if (!cursor.isNull(it)) size = cursor.getLong(it).takeIf { bytes -> bytes >= 0 }
            }
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let {
                if (!cursor.isNull(it)) modified = cursor.getLong(it)
            }
            if (modified == null) cursor.getColumnIndex("date_modified").takeIf { it >= 0 }?.let {
                if (!cursor.isNull(it)) modified = cursor.getLong(it)
            }
            cursor.getColumnIndex("generation_modified").takeIf { it >= 0 }?.let {
                if (!cursor.isNull(it)) generation = cursor.getLong(it)
            }
        } else throw StorageException(StorageError.NOT_FOUND, "Source is no longer available")
    } ?: throw StorageException(StorageError.PERMISSION, "Source cannot be read")
    return LocalDocument(uri.toString(), name, size, getType(uri),
        "${size ?: -1}:${modified ?: -1}:${generation ?: -1}")
}

class ContentUriSource(
    private val resolver: ContentResolver,
    private val document: LocalDocument,
    private val checkRunning: () -> Unit = {},
) : UploadSource {
    override val length = document.size
    override val version = document.version
    override fun currentVersion(): String = resolver.describe(Uri.parse(document.uri)).version

    override fun open(): InputStream {
        checkRunning()
        if (currentVersion() != version) throw StorageException(StorageError.SOURCE_CHANGED, "Source changed")
        val stream = resolver.openInputStream(Uri.parse(document.uri))
            ?: throw StorageException(StorageError.PERMISSION, "Source cannot be opened")
        return object : FilterInputStream(stream) {
            override fun read(): Int { checkRunning(); return super.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkRunning()
                return `in`.read(buffer, offset, length)
            }
        }
    }
}
