package space.zhuoling.fileaccess.thumbnail

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.storage.StorageSession

data class ThumbnailImage(val bitmap: Bitmap, val durationMillis: Long? = null)

internal suspend fun decodeThumbnail(
    request: ThumbnailRequest, session: StorageSession, budget: ThumbnailBudget, temporaryDirectory: File,
    checkNetwork: () -> Unit = {},
): ThumbnailImage {
    val entry = request.entry
    val length = entry.size
    val context = currentCoroutineContext()
    val edge = thumbnailEdge(request.edgePx)
    if (mediaKind(entry) == MediaKind.VIDEO) {
        if (!session.capabilities.rangeRead || length == null || length <= 0 || entry.revision == null) {
            throw UnsupportedOperationException()
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val reader = ThumbnailReader(length, budget, checkActive = {
            context.ensureActive()
            checkNetwork()
            if (SystemClock.elapsedRealtime() > deadline) throw ThumbnailLimitException()
        }) { offset -> runBlocking { session.openRead(entry.ref, offset, entry.revision) } }
        val readFailure = AtomicReference<Exception?>(null)
        val source = object : MediaDataSource() {
            override fun getSize(): Long = length
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                // Some extractors expect a full request except at EOF. Coalesce SMB short reads
                // and cached fragments rather than exposing an arbitrary cache boundary to them.
                require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
                if (size == 0) return 0
                if (position >= length) return -1
                val wanted = minOf(size.toLong(), length - position).toInt()
                var total = 0
                while (total < wanted) {
                    val count = try { reader.readAt(position + total, buffer, offset + total, wanted - total) }
                    catch (failure: Exception) { readFailure.compareAndSet(null, failure); throw failure }
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("Empty media read")
                    total += count
                }
                return if (total == 0) -1 else total
            }
            override fun close() = reader.close()
        }
        source.use {
            MediaMetadataRetriever().use { retriever ->
                try { retriever.setDataSource(source) }
                catch (failure: Exception) { throw readFailure.get() ?: failure }
                context.ensureActive()
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 }
                val timeUs = minOf(1_000L, (duration ?: 0) / 2) * 1_000
                val bitmap = try { retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge) }
                catch (failure: Exception) { throw readFailure.get() ?: failure }
                if (bitmap == null) throw readFailure.get() ?: UnsupportedOperationException()
                try {
                    context.ensureActive()
                    checkRevision(request, session)
                    return ThumbnailImage(bitmap, duration)
                } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            }
        }
    }
    if (length != null && length > 16 * MIB) throw ThumbnailLimitException()
    temporaryDirectory.mkdirs()
    val temporary = File.createTempFile("source-", ".part", temporaryDirectory)
    try {
        session.openRead(entry.ref, expectedRevision = entry.revision).use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    context.ensureActive()
                    checkNetwork()
                    val reserved = budget.reserve(minOf(buffer.size.toLong(), 16 * MIB + 1 - count).toInt())
                    var actual = 0
                    val read = try { input.read(buffer, 0, reserved).also { actual = it.coerceAtLeast(0) } }
                    finally { budget.refund(reserved - actual) }
                    if (read < 0) break
                    if (read == 0) throw java.io.IOException("Empty remote read")
                    count += read
                    if (count > 16 * MIB) throw ThumbnailLimitException()
                    output.write(buffer, 0, read)
                    if (length != null && count == length) break
                }
                if (length != null && count != length) throw java.io.IOException("Source length changed")
            }
        }
        checkRevision(request, session)
        context.ensureActive()
        // ImageDecoder applies embedded orientation and returns only a static bitmap, including GIF.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(temporary)) { decoder, info, _ ->
            val factor = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }
        try { context.ensureActive(); return ThumbnailImage(bitmap) }
        catch (failure: Throwable) { bitmap.recycle(); throw failure }
    } finally { temporary.delete() }
}

private suspend fun checkRevision(request: ThumbnailRequest, session: StorageSession) {
    if (request.entry.revision != null && session.stat(request.entry.ref).revision != request.entry.revision) {
        throw StorageException(StorageError.SOURCE_CHANGED, "Thumbnail source changed")
    }
}
