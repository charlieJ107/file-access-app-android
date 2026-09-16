package space.zhuoling.fileaccess.preview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.thumbnail.MediaKind
import space.zhuoling.fileaccess.thumbnail.ThumbnailFixture
import space.zhuoling.fileaccess.thumbnail.mediaKind

@RunWith(AndroidJUnit4::class)
class PreviewRepositoryTest {
    private lateinit var fixture: ThumbnailFixture
    private lateinit var repository: PreviewRepository

    @Before fun setup() = runBlocking {
        fixture = ThumbnailFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.initialize()
        repository = PreviewRepository(fixture.context, RemoteAccess(fixture.connections, ProviderRegistry(setOf(fixture.provider))))
    }

    @After fun teardown() = runBlocking { fixture.close() }

    @Test fun genericMimeFallsBackToExtensionConsistentlyWithMediaQueue() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val entry = fixture.put("PICTURE.JPG", byteArrayOf(1))
            for (mime in listOf(null, "", " application/octet-stream ")) {
                assertEquals(MediaKind.IMAGE, mediaKind(entry.copy(mimeType = mime)))
                assertEquals("image/jpeg", repository.mime(entry.copy(mimeType = mime)))
            }
            assertEquals("video/mp4", repository.mime(entry.copy(mimeType = "VIDEO/MP4")))
            assertEquals(MediaKind.FILE, mediaKind(entry.copy(mimeType = "image/svg+xml")))
        } finally { Locale.setDefault(oldLocale) }
    }

    @Test fun cancellingBlockedImageLoadReleasesCacheLockStreamsAndTemporaryFile() = runBlocking {
        val oldEntry = fixture.put("old.png", ThumbnailFixture.image())
        val nextEntry = fixture.put("next.png", ThumbnailFixture.image())
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        fixture.beforeRead = { enteredRead.countDown(); releaseRead.await() }
        val oldLoad = async(Dispatchers.IO) { repository.cachedFile(oldEntry) }
        try {
            assertTrue("Image never started reading", enteredRead.await(10, TimeUnit.SECONDS))
            withTimeout(5_000) { oldLoad.cancelAndJoin() }
            assertEquals(0, fixture.streams.get())
            assertEquals(0, fixture.opened.get())
            assertFalse(fixture.context.cacheDir.walkTopDown().any { it.extension == "part" })
            fixture.beforeRead = null
            val next = withTimeout(5_000) { repository.cachedFile(nextEntry) }
            assertArrayEquals(fixture.files.getValue("next.png"), next.readBytes())
        } finally {
            releaseRead.countDown()
            oldLoad.cancelAndJoin()
            fixture.beforeRead = null
        }
    }

    @Test fun revisitingUnchangedImageReusesCacheAndChangedRevisionReadsAgain() = runBlocking {
        val first = fixture.put("photo.png", ThumbnailFixture.image(), "one")
        val cached = repository.cachedFile(first, 1)
        assertEquals(cached, repository.cachedFile(first, 1))
        assertEquals(1, fixture.reads.get())
        repository.cachedFile(first, 1, forceReload = true)
        assertEquals(2, fixture.reads.get())
        val updated = fixture.put("photo.png", byteArrayOf(5, 6, 7), "two")
        assertNotEquals(cached, repository.cachedFile(updated, 1))
        assertEquals(3, fixture.reads.get())
        assertEquals(0, fixture.opened.get())
    }

    @Test fun previewKeepsSizeAndConnectionRevisionChecks() = runBlocking {
        val entry = fixture.put("photo.png", byteArrayOf(1))
        try {
            repository.cachedFile(entry.copy(size = 128L * 1024 * 1024 + 1))
            fail("Oversized media should be rejected")
        } catch (error: StorageException) { assertEquals(StorageError.QUOTA_EXCEEDED, error.error) }
        try {
            repository.stat(entry.ref, 999)
            fail("A stale preview must not read a changed connection")
        } catch (error: StorageException) { assertEquals(StorageError.INVALID_CONFIGURATION, error.error) }
        assertEquals(0, fixture.reads.get())
        assertEquals(0, fixture.opened.get())
    }

    @Test fun truncatedImageIsNotPublishedAndCanBeRetried() = runBlocking {
        val entry = fixture.put("truncated.png", byteArrayOf(1, 2, 3))
        try {
            repository.cachedFile(entry.copy(size = 4))
            fail("Incomplete media should be rejected")
        } catch (error: StorageException) { assertEquals(StorageError.CORRUPT_DATA, error.error) }
        assertFalse(fixture.context.cacheDir.walkTopDown().any { it.isFile })
        assertArrayEquals(byteArrayOf(1, 2, 3), repository.cachedFile(entry).readBytes())
        assertEquals(0, fixture.streams.get())
    }
}
