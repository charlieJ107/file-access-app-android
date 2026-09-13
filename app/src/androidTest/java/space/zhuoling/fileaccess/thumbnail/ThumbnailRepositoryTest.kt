package space.zhuoling.fileaccess.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: ThumbnailFixture
    @Before fun setup() = runBlocking { fixture = ThumbnailFixture(context); fixture.initialize() }
    @After fun teardown() = runBlocking { fixture.close() }
    private suspend fun load(request: ThumbnailRequest, budget: ThumbnailBudget = ThumbnailBudget()) =
        withTimeout(20_000) { fixture.repository.observe(request, budget, true).first { it !is ThumbnailState.Loading } }

    @Test fun imageIsDownsampledAndCacheAvoidsRemoteReads() = runBlocking {
        val entry = fixture.put("photo.png", ThumbnailFixture.image())
        val request = ThumbnailRequest(entry, 1, 128, 0)
        val first = load(request) as ThumbnailState.Ready
        assertEquals(128, first.image.bitmap.width)
        assertEquals(64, first.image.bitmap.height)
        val reads = fixture.reads.get()
        assertTrue(load(request) is ThumbnailState.Ready)
        assertEquals(reads, fixture.reads.get())
        assertEquals(0, fixture.opened.get()); assertEquals(0, fixture.streams.get())
    }

    @Test fun allEightExifOrientationsAreAppliedAndAlphaIsPreserved() = runBlocking {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 80) original.setPixel(x, y, colors[(if (y >= 20) 2 else 0) + if (x >= 40) 1 else 0])
        val jpeg = ByteArrayOutputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 100, it); it.toByteArray() }
        original.recycle()
        val expected = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        for (orientation in 1..8) {
            val exif = ByteBuffer.allocate(36).put(byteArrayOf(-1, -31, 0, 34)).put("Exif\u0000\u0000".toByteArray())
                .order(ByteOrder.LITTLE_ENDIAN).putShort(0x4949).putShort(42).putInt(8).putShort(1)
                .putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0).array()
            val entry = fixture.put("orientation-$orientation.jpg", jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size))
            val bitmap = (load(ThumbnailRequest(entry, 1, 128, 0)) as ThumbnailState.Ready).image.bitmap
            assertEquals(if (orientation >= 5) 40 else 80, bitmap.width)
            assertEquals(if (orientation >= 5) 80 else 40, bitmap.height)
            val actual = listOf(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4), bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4), bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4))
            actual.forEachIndexed { index, color ->
                val target = colors[expected[orientation - 1][index]]
                assertTrue("Orientation $orientation corner $index", kotlin.math.abs(Color.red(color) - Color.red(target)) < 10 &&
                    kotlin.math.abs(Color.green(color) - Color.green(target)) < 10 && kotlin.math.abs(Color.blue(color) - Color.blue(target)) < 10)
            }
        }
        val transparent = fixture.put("alpha.png", ThumbnailFixture.image(color = Color.TRANSPARENT))
        assertEquals(0, Color.alpha((load(ThumbnailRequest(transparent, 1, 128, 0)) as ThumbnailState.Ready).image.bitmap.getPixel(0, 0)))
    }

    @Test fun simultaneousSubscriptionsShareWorkAndOneCancellationDoesNotCancelOther() = runBlocking {
        val request = ThumbnailRequest(fixture.put("shared.png", ThumbnailFixture.image()), 1, 128, 0)
        fixture.openDelay = 300
        val first = async { load(request) }
        val second = async { load(request) }
        delay(100); first.cancelAndJoin()
        assertTrue(second.await() is ThumbnailState.Ready)
        assertEquals(1, fixture.reads.get())
        assertEquals(0, fixture.opened.get())
    }

    @Test fun diskCacheSurvivesRepositoryRestartAndCorruptionRegenerates() = runBlocking {
        val request = ThumbnailRequest(fixture.put("disk.png", ThumbnailFixture.image()), 1, 128, 0)
        assertTrue(load(request) is ThumbnailState.Ready)
        fixture.restartRepository()
        assertTrue(load(request) is ThumbnailState.Ready)
        assertEquals(1, fixture.reads.get())
        val cache = fixture.context.cacheDir.walkTopDown().first { it.extension == "thumb" }
        cache.writeBytes(byteArrayOf(0, 1, 2))
        fixture.restartRepository()
        assertTrue(load(request) is ThumbnailState.Ready)
        assertEquals(2, fixture.reads.get())
    }

    @Test fun permissionFailureBlocksQueuedWorkAndRemovesCachedThumbnails() = runBlocking {
        val cached = ThumbnailRequest(fixture.put("cached.png", ThumbnailFixture.image()), 1, 128, 0)
        assertTrue(load(cached) is ThumbnailState.Ready)
        val denied = ThumbnailRequest(fixture.put("denied.png", ThumbnailFixture.image()), 1, 128, 0)
        fixture.deny = true
        assertEquals(ThumbnailState.Unavailable(ThumbnailFailure.AUTH), load(denied))
        assertEquals(ThumbnailState.Unavailable(ThumbnailFailure.AUTH), load(cached))
        assertFalse(fixture.context.cacheDir.walkTopDown().any { it.extension == "thumb" })
    }

    @Test fun cancellingLastSubscriberClosesSessionAndDoesNotPublish() = runBlocking {
        val request = ThumbnailRequest(fixture.put("cancel.png", ThumbnailFixture.image()), 1, 128, 0)
        fixture.openDelay = 5_000
        val pending = launch { load(request) }
        delay(200); pending.cancelAndJoin()
        withTimeout(5_000) { while (fixture.opened.get() != 0) delay(10) }
        fixture.openDelay = 0
        assertTrue(load(request) is ThumbnailState.Ready)
        assertEquals(1, fixture.reads.get())
    }

    @Test fun clearingWhileGeneratingPreventsLatePublication() = runBlocking {
        val request = ThumbnailRequest(fixture.put("clearing.png", ThumbnailFixture.image()), 1, 128, 0)
        fixture.openDelay = 5_000
        val pending = async { load(request) }
        withTimeout(5_000) { while (fixture.opened.get() == 0) delay(10) }
        fixture.repository.clearCache()
        try { pending.await(); fail("Invalidated work published a result") } catch (_: CancellationException) { }
        assertFalse(fixture.context.cacheDir.walkTopDown().any { it.extension == "thumb" })
        fixture.openDelay = 0
        assertTrue(load(request) is ThumbnailState.Ready)
    }

    @Test fun knownOversizedImageDoesNotOpenRemoteSession() = runBlocking {
        val entry = fixture.put("oversized.png", ThumbnailFixture.image()).copy(size = 17 * MIB)
        assertEquals(ThumbnailState.Unavailable(ThumbnailFailure.LIMIT), load(ThumbnailRequest(entry, 1, 128, 0)))
        assertEquals(0, fixture.reads.get()); assertEquals(0, fixture.opened.get())
    }

    @Test fun limitedAndCorruptImagesDegradeWithoutRetainingTemporaryFiles() = runBlocking {
        val entry = fixture.put("limited.png", ThumbnailFixture.image())
        assertEquals(ThumbnailState.Unavailable(ThumbnailFailure.LIMIT), load(ThumbnailRequest(entry, 1, 128, 0), ThumbnailBudget(2)))
        val corrupt = fixture.put("corrupt.png", byteArrayOf(1, 2, 3))
        assertTrue(load(ThumbnailRequest(corrupt, 1, 128, 0)) is ThumbnailState.Unavailable)
        assertFalse(fixture.context.cacheDir.walkTopDown().any { it.extension == "part" })
        assertEquals(0, fixture.opened.get()); assertEquals(0, fixture.streams.get())
    }

    @Test fun clearingCacheForcesRegenerationAndConfigurationMismatchCannotHitMemory() = runBlocking {
        val entry = fixture.put("version.png", ThumbnailFixture.image())
        val request = ThumbnailRequest(entry, 1, 128, 0)
        assertTrue(load(request) is ThumbnailState.Ready)
        fixture.repository.clearCache()
        assertTrue(load(request.copy(refreshEpoch = fixture.repository.generation.value)) is ThumbnailState.Ready)
        assertEquals(2, fixture.reads.get())
        val config = fixture.connections.get("fixture")!!
        fixture.connections.save(config.copy(share = "changed"))
        try { load(request); fail("Old configuration served a thumbnail") } catch (_: CancellationException) { }
    }

    @Test fun generatedH264VideoProducesScaledFrameAndClosesRandomAccessSource() = runBlocking {
        val bytes = ThumbnailFixture.video(fixture.context.cacheDir)
        val entry = fixture.put("sample.mp4", bytes)
        val state = load(ThumbnailRequest(entry, 1, 128, 0))
        assertTrue("Expected video frame, got $state", state is ThumbnailState.Ready)
        val image = (state as ThumbnailState.Ready).image
        assertTrue(image.bitmap.width <= 128 && image.bitmap.height <= 128)
        assertTrue((image.durationMillis ?: 0) > 0)
        assertTrue(fixture.bytesRead.get() <= 8 * MIB)
        assertEquals(0, fixture.streams.get()); assertEquals(0, fixture.opened.get())
    }

    @Test fun headIndexedPortraitVideoHasUprightDimensions() = runBlocking {
        val video = ThumbnailFixture.fastStart(ThumbnailFixture.video(fixture.context.cacheDir, rotation = 90))
        val entry = fixture.put("portrait.mp4", video)
        val result = load(ThumbnailRequest(entry, 1, 128, 0)) as ThumbnailState.Ready
        assertTrue("Portrait orientation must be applied exactly once", result.image.bitmap.height > result.image.bitmap.width)
        assertTrue(result.image.bitmap.height <= 128)
    }

    @Test fun paddedTailIndexUsesRandomReadsWithoutDownloadingPadding() = runBlocking {
        val video = ThumbnailFixture.padBeforeIndex(ThumbnailFixture.video(fixture.context.cacheDir))
        val entry = fixture.put("padded.mp4", video)
        val result = load(ThumbnailRequest(entry, 1, 128, 0))
        assertTrue("Result $result, offsets=${fixture.offsets}, bytes=${fixture.bytesRead}", result is ThumbnailState.Ready)
        assertTrue(fixture.bytesRead.get() < video.size)
    }
}
