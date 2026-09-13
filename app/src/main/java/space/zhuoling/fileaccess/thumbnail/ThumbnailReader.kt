package space.zhuoling.fileaccess.thumbnail

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Platform-independent random reader. Every seek opens at its actual Long offset. */
internal class ThumbnailReader(
    private val length: Long,
    private val budget: ThumbnailBudget,
    private val maximumBytes: Long = 8 * MIB,
    private val maxSeeks: Int = 32,
    private val cacheMaximumBytes: Int = (2 * MIB).toInt(),
    private val checkActive: () -> Unit,
    private val open: (Long) -> InputStream,
) : Closeable {
    @Volatile private var stream: InputStream? = null
    private var position = -1L
    private var consumed = 0L
    private var seeks = 0
    private val cached = LinkedHashMap<Long, ByteArray>()
    private var cachedBytes = 0
    private val closed = AtomicBoolean(false)

    @Synchronized fun readAt(offset: Long, buffer: ByteArray, start: Int, size: Int): Int {
        require(offset >= 0 && start >= 0 && size >= 0 && start <= buffer.size - size)
        check(!closed.get()) { "Reader closed" }
        checkActive()
        if (size == 0) return 0
        if (offset >= length) return -1
        cached.entries.lastOrNull { offset >= it.key && offset - it.key < it.value.size }?.let { hit ->
            val from = (offset - hit.key).toInt()
            val count = minOf(size, hit.value.size - from)
            hit.value.copyInto(buffer, start, from, from + count)
            return count
        }
        if (consumed >= maximumBytes) throw ThumbnailLimitException()
        if (position != offset || stream == null) {
            if (++seeks > maxSeeks) throw ThumbnailLimitException()
            stream?.close()
            stream = null
            stream = open(offset)
            if (closed.get()) { stream?.close(); throw java.io.IOException("Reader closed") }
            position = offset
        }
        val wanted = minOf(size.toLong(), length - offset, maximumBytes - consumed, 256 * 1024L).toInt()
        val reserved = budget.reserve(wanted)
        var count = 0
        try {
            val read = stream!!.read(buffer, start, reserved)
            count = read.coerceAtLeast(0)
            consumed += count
            position += count
            if (count > 0 && cacheMaximumBytes > 0) {
                cached.remove(offset)?.let { cachedBytes -= it.size }
                cached[offset] = buffer.copyOfRange(start, start + count)
                cachedBytes += count
                while (cachedBytes > cacheMaximumBytes || cached.size > 32) {
                    cachedBytes -= cached.remove(cached.keys.first())!!.size
                }
            }
            checkActive()
            return read
        } finally { budget.refund(reserved - count) }
    }

    /** Do not wait for a blocked read's monitor before closing its socket-backed stream. */
    override fun close() { if (closed.compareAndSet(false, true)) stream?.close() }
}
