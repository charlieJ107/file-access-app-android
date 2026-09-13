package space.zhuoling.fileaccess.thumbnail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.*
import space.zhuoling.fileaccess.protocol.smb.SmbStorageProvider

@RunWith(AndroidJUnit4::class)
class ThumbnailSmbTest {
    @Test fun generatedImageAndVideoDecodeThroughRealSmbRangeReads() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("smbTestPort")?.toIntOrNull()
        assumeTrue("Requires a dedicated isolated SMB fixture", port != null)
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = ConnectionConfig("media-test", "Isolated media", host = "10.0.2.2", port = port!!,
            share = "test", username = "fileaccess-test")
        withContext(Dispatchers.IO) {
            Credentials(config.username, args.getString("smbTestPassword")!!.toCharArray()).use { credentials ->
                SmbStorageProvider().connect(config, credentials).use { session ->
                    val mutation = session as MutationCapability
                    val directory = mutation.createDirectory(session.root.ref, "media-${UUID.randomUUID()}")
                    val uploaded = mutableListOf<RemoteEntry>()
                    try {
                        val samples = listOf("image.png" to ThumbnailFixture.image(),
                            "tail-index.mp4" to ThumbnailFixture.padBeforeIndex(ThumbnailFixture.video(context.cacheDir)))
                        for ((name, bytes) in samples) {
                            val entry = (session as UploadCapability).upload(UploadRequest(UUID.randomUUID().toString(), directory.ref, name),
                                object : UploadSource { override val length = bytes.size.toLong(); override fun open() = ByteArrayInputStream(bytes) }, {})
                            uploaded += entry
                            var readBytes = 0L
                            val offsets = mutableListOf<Long>()
                            val monitored = object : StorageSession by session {
                                override suspend fun openRead(ref: EntryRef, offset: Long, expectedRevision: String?): InputStream {
                                    assertEquals(entry.revision, expectedRevision)
                                    offsets += offset
                                    val stream = session.openRead(ref, offset, expectedRevision)
                                    return object : FilterInputStream(stream) {
                                        override fun read(buffer: ByteArray, start: Int, length: Int): Int = super.read(buffer, start, length)
                                            .also { if (it > 0) readBytes += it }
                                    }
                                }
                            }
                            val started = android.os.SystemClock.elapsedRealtime()
                            val image = try {
                                decodeThumbnail(ThumbnailRequest(entry, 1, 128, 0), monitored, ThumbnailBudget(), context.cacheDir)
                            } catch (failure: Exception) {
                                throw AssertionError("Generated $name: bytes=$readBytes, offsets=$offsets", failure)
                            }
                            assertTrue(image.bitmap.width <= 128 && image.bitmap.height <= 128)
                            if (name.endsWith("mp4")) {
                                assertTrue((image.durationMillis ?: 0) > 0)
                                assertTrue("Index must be read by offset beyond the padding", offsets.any { it >= 12 * MIB })
                                assertTrue("Thumbnail must not download the whole video", readBytes < bytes.size)
                            }
                            assertTrue(readBytes <= 8 * MIB)
                            assertTrue(offsets.isNotEmpty())
                            android.util.Log.i("MediaFixture", "generated=$name, sourceBytes=${bytes.size}, readBytes=$readBytes, elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}, offsets=$offsets")
                            image.bitmap.recycle()
                        }
                    } finally {
                        uploaded.forEach { mutation.delete(it.ref) }
                        mutation.delete(directory.ref)
                    }
                }
            }
        }
    }
}
