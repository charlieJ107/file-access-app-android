package space.zhuoling.fileaccess.thumbnail

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry

class ThumbnailPolicyTest {
    private val entry = RemoteEntry(EntryRef("connection", "picture"), "PICTURE.JPG", false, size = 12, revision = "v1")

    @Test fun keysIsolateConnectionFileVersionConfigurationAndSize() {
        val original = ThumbnailRequest(entry, 1, 128, 1)
        val variants = listOf(original, original.copy(connectionRevision = 2), original.copy(edgePx = 512),
            original.copy(entry = entry.copy(ref = EntryRef("other", "picture"))), original.copy(entry = entry.copy(revision = "v2")))
        assertEquals(variants.size, variants.map { it.key() }.toSet().size)
        assertEquals(original.key(), original.copy(refreshEpoch = 2).key())
        assertNotEquals(original.copy(entry = entry.copy(revision = null)).key(), original.copy(entry = entry.copy(revision = null), refreshEpoch = 2).key())
        assertNotEquals(entryKey(EntryRef("a:b", "c")), entryKey(EntryRef("a", "b:c")))
    }

    @Test fun mediaClassificationUsesMimeThenLocaleIndependentExtension() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(MediaKind.IMAGE, mediaKind(entry))
            assertEquals(MediaKind.VIDEO, mediaKind(entry.copy(name = "FILM.MP4", mimeType = "application/octet-stream")))
            assertEquals(MediaKind.AUDIO, mediaKind(entry.copy(mimeType = "audio/flac")))
            assertEquals(MediaKind.DIRECTORY, mediaKind(entry.copy(isDirectory = true)))
            assertEquals(MediaKind.FILE, mediaKind(entry.copy(mimeType = "image/svg+xml")))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun randomReadSeeksDirectlyPastTwoGiBAndClosesHandles() {
        val positions = mutableListOf<Long>()
        var closes = 0
        val reader = ThumbnailReader(4L * 1024 * MIB, ThumbnailBudget(), checkActive = {}, open = { offset ->
            positions += offset
            object : ByteArrayInputStream(ByteArray(100) { 42 }) {
                override fun close() { closes++; super.close() }
            }
        })
        reader.use {
            assertEquals(8, it.readAt(3L * 1024 * MIB, ByteArray(8), 0, 8))
            assertEquals(8, it.readAt(3L * 1024 * MIB + 8, ByteArray(8), 0, 8))
            assertEquals(4, it.readAt(0, ByteArray(4), 0, 4))
            assertEquals(-1, it.readAt(4L * 1024 * MIB, ByteArray(4), 0, 4))
            assertEquals(0, it.readAt(0, ByteArray(0), 0, 0))
        }
        assertEquals(listOf(3L * 1024 * MIB, 0), positions)
        assertEquals(2, closes)
    }

    @Test fun shortReadsRefundReservationButRepeatedRemoteReadsConsumeBudget() {
        val budget = ThumbnailBudget(6)
        ThumbnailReader(100, budget, cacheMaximumBytes = 0, checkActive = {}, open = { ByteArrayInputStream(byteArrayOf(1, 2)) }).use {
            assertEquals(2, it.readAt(0, ByteArray(5), 0, 5))
            assertEquals(4L, budget.available())
            assertEquals(2, it.readAt(0, ByteArray(5), 0, 5))
            assertEquals(2, it.readAt(0, ByteArray(5), 0, 5))
            assertThrows(ThumbnailLimitException::class.java) { it.readAt(0, ByteArray(5), 0, 5) }
        }
    }

    @Test fun overlappingCachedReadsDoNotSpendNetworkBudgetOrOpenAnotherStream() {
        val budget = ThumbnailBudget(20)
        var opens = 0
        ThumbnailReader(100, budget, checkActive = {}, open = { opens++; ByteArrayInputStream(ByteArray(100) { it.toByte() }) }).use {
            it.readAt(0, ByteArray(10), 0, 10)
            val target = ByteArray(4)
            assertEquals(4, it.readAt(3, target, 0, 4))
            assertArrayEquals(byteArrayOf(3, 4, 5, 6), target)
            assertEquals(10L, budget.available())
            assertEquals(1, opens)
        }
    }

    @Test fun failedReadsRefundBudgetAndSeekLimitPreventsUnboundedWork() {
        val budget = ThumbnailBudget(100)
        ThumbnailReader(100, budget, maxSeeks = 1, checkActive = {}, open = {
            object : ByteArrayInputStream(byteArrayOf(1)) { override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException() }
        }).use {
            assertThrows(IOException::class.java) { it.readAt(0, ByteArray(5), 0, 5) }
            assertEquals(100L, budget.available())
            assertThrows(ThumbnailLimitException::class.java) { it.readAt(5, ByteArray(5), 0, 5) }
        }
    }

    @Test fun singleSourceLimitCannotBeBypassedByExtendingDirectoryBudget() {
        val budget = ThumbnailBudget(2)
        ThumbnailReader(100, budget, maximumBytes = 3, checkActive = {}, open = { ByteArrayInputStream(ByteArray(100)) }).use {
            assertEquals(2, it.readAt(0, ByteArray(10), 0, 10))
            budget.extend()
            assertEquals(1, it.readAt(2, ByteArray(10), 0, 10))
            assertThrows(ThumbnailLimitException::class.java) { it.readAt(3, ByteArray(10), 0, 10) }
        }
    }

    @Test fun closingReaderTwiceClosesTheUnderlyingHandleOnce() {
        var closes = 0
        val reader = ThumbnailReader(10, ThumbnailBudget(), checkActive = {}, open = {
            object : ByteArrayInputStream(ByteArray(10)) { override fun close() { closes++ } }
        })
        reader.readAt(0, ByteArray(1), 0, 1)
        reader.close(); reader.close()
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { reader.readAt(0, ByteArray(1), 0, 1) }
    }
}
