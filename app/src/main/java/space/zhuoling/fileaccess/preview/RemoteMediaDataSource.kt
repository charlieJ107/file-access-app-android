package space.zhuoling.fileaccess.preview

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.storage.StorageSession
import space.zhuoling.fileaccess.core.transfer.RemoteAccess

/** Media3 invokes this on its loader thread. Each seek checks the revision and holds a read handle. */
@UnstableApi
class RemoteMediaDataSource(private val entry: RemoteEntry, private val remote: RemoteAccess) : BaseDataSource(true) {
    private var session: StorageSession? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            session = runBlocking { remote.open(entry.ref.connectionId) }
            input = runBlocking { session!!.openRead(entry.ref, dataSpec.position, entry.revision) }
            val available = entry.size?.let { it - dataSpec.position }
            if (available != null && available < 0) throw EOFException("Position exceeds file length")
            remaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> available?.let { minOf(it, dataSpec.length) } ?: dataSpec.length
                available != null -> available
                else -> C.LENGTH_UNSET.toLong()
            }
            uri = dataSpec.uri
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val read = (input ?: throw java.io.IOException("Stream is closed")).read(buffer, offset, wanted)
        if (read < 0) {
            if (remaining > 0) throw EOFException("Remote stream ended early")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining > 0) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { input?.close() }
        finally {
            input = null
            try { session?.close() }
            finally {
                session = null
                uri = null
                if (started) { started = false; transferEnded() }
            }
        }
    }
}
