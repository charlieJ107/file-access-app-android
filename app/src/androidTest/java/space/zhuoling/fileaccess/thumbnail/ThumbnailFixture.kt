package space.zhuoling.fileaccess.thumbnail

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import space.zhuoling.fileaccess.core.data.AppDatabase
import space.zhuoling.fileaccess.core.data.ConnectionRepository
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.security.CredentialStore
import space.zhuoling.fileaccess.core.storage.*
import space.zhuoling.fileaccess.core.transfer.RemoteAccess

internal class ThumbnailFixture(base: Context) {
    private val directory = File(base.cacheDir, "thumb-test-${UUID.randomUUID()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) { override fun getCacheDir(): File = directory }
    val db = Room.inMemoryDatabaseBuilder<AppDatabase>(base).setDriver(AndroidSQLiteDriver()).build()
    val connections = ConnectionRepository(db, object : CredentialStore {
        override suspend fun get(reference: String) = Credentials("fixture", charArrayOf('x'))
        override suspend fun put(reference: String, credentials: Credentials) = Unit
        override suspend fun delete(reference: String) = Unit
    })
    val files = mutableMapOf<String, ByteArray>()
    val entries = mutableMapOf<String, RemoteEntry>()
    val reads = AtomicInteger()
    val bytesRead = AtomicInteger()
    val opened = AtomicInteger()
    val streams = AtomicInteger()
    val offsets = java.util.Collections.synchronizedList(mutableListOf<Long>())
    var openDelay = 0L
    var deny = false
    private val root = RemoteEntry(EntryRef("fixture", ""), "测试媒体", true)
    val provider = object : StorageProvider {
        override val type = "fixture"
        override suspend fun connect(config: ConnectionConfig, credentials: Credentials): StorageSession = session()
    }
    private fun newRepository() = ThumbnailRepository(context, RemoteAccess(connections, ProviderRegistry(setOf(provider))), connections)
    var repository = newRepository()
        private set
    suspend fun restartRepository() { repository.shutdown(); repository = newRepository() }

    suspend fun initialize() {
        connections.save(ConnectionConfig("fixture", "Generated media", protocol = "fixture", host = "invalid.test", share = "fixture", username = "fixture"), Credentials("fixture", charArrayOf('x')))
    }

    fun put(name: String, bytes: ByteArray, revision: String = "1"): RemoteEntry {
        files[name] = bytes
        return RemoteEntry(EntryRef("fixture", name), name, false, root.ref, bytes.size.toLong(), revision = revision).also { entries[name] = it }
    }

    fun session(): StorageSession {
        opened.incrementAndGet()
        return object : StorageSession {
            private var closed = false
            override val root = this@ThumbnailFixture.root
            override val capabilities = StorageCapabilities(rangeRead = true)
            override fun list(directory: EntryRef): Flow<RemoteEntry> = entries.values.toList().asFlow()
            override suspend fun stat(ref: EntryRef): RemoteEntry = entries[ref.opaqueId] ?: root
            override suspend fun openRead(ref: EntryRef, offset: Long, expectedRevision: String?): java.io.InputStream {
                if (deny) throw StorageException(StorageError.PERMISSION, "Fixture denied")
                if (expectedRevision != null && entries[ref.opaqueId]?.revision != expectedRevision) throw StorageException(StorageError.SOURCE_CHANGED, "Fixture changed")
                if (openDelay > 0) delay(openDelay)
                reads.incrementAndGet(); offsets += offset; streams.incrementAndGet()
                return object : ByteArrayInputStream(files.getValue(ref.opaqueId).copyOfRange(offset.toInt(), files.getValue(ref.opaqueId).size)) {
                    private var done = false
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead.addAndGet(it) }
                    override fun close() { if (!done) { done = true; streams.decrementAndGet() }; super.close() }
                }
            }
            @Synchronized override fun close() { if (!closed) { closed = true; opened.decrementAndGet() } }
        }
    }

    suspend fun close() { repository.shutdown(); db.close(); directory.deleteRecursively() }

    companion object {
        fun fastStart(video: ByteArray): ByteArray {
            val source = ByteBuffer.wrap(video)
            var offset = 0
            while (offset + 8 <= video.size) {
                val size = source.getInt(offset)
                require(size >= 8)
                if (String(video, offset + 4, 4, Charsets.US_ASCII) == "moov") {
                    val moov = video.copyOfRange(offset, offset + size)
                    val buffer = ByteBuffer.wrap(moov)
                    fun adjust(start: Int, end: Int) {
                        var child = start
                        while (child + 8 <= end) {
                            val length = buffer.getInt(child)
                            require(length >= 8 && child + length <= end)
                            when (String(moov, child + 4, 4, Charsets.US_ASCII)) {
                                "moov", "trak", "mdia", "minf", "stbl" -> adjust(child + 8, child + length)
                                "stco" -> repeat(buffer.getInt(child + 12)) { index ->
                                    val position = child + 16 + index * 4
                                    buffer.putInt(position, buffer.getInt(position) + size)
                                }
                                "co64" -> repeat(buffer.getInt(child + 12)) { index ->
                                    val position = child + 16 + index * 8
                                    buffer.putLong(position, buffer.getLong(position) + size)
                                }
                            }
                            child += length
                        }
                    }
                    adjust(0, moov.size)
                    val prefix = source.getInt(0)
                    val placeholder = ByteArray(size)
                    ByteBuffer.wrap(placeholder).putInt(size).put("free".toByteArray(Charsets.US_ASCII))
                    return video.copyOfRange(0, prefix) + moov + video.copyOfRange(prefix, offset) + placeholder + video.copyOfRange(offset + size, video.size)
                }
                offset += size
            }
            error("MP4 index not found")
        }

        fun padBeforeIndex(video: ByteArray): ByteArray {
            var offset = 0
            while (offset + 8 <= video.size) {
                val size = ByteBuffer.wrap(video, offset, 4).int
                require(size >= 8)
                if (String(video, offset + 4, 4, Charsets.US_ASCII) == "moov") {
                    val padding = ByteArray((12 * MIB).toInt())
                    ByteBuffer.wrap(padding).putInt(padding.size).put("free".toByteArray(Charsets.US_ASCII))
                    val moov = video.copyOfRange(offset, offset + size)
                    val placeholder = ByteArray(size)
                    ByteBuffer.wrap(placeholder).putInt(size).put("free".toByteArray(Charsets.US_ASCII))
                    // Preserve all original media offsets by replacing the old index with a free atom.
                    return video.copyOfRange(0, offset) + placeholder + video.copyOfRange(offset + size, video.size) + padding + moov
                }
                offset += size
            }
            error("MP4 index not found")
        }

        fun image(width: Int = 800, height: Int = 400, color: Int = Color.rgb(30, 120, 200)): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            bitmap.recycle()
            return bytes
        }

        /** Generated H.264 sample; helpers normalize index placement without changing sample offsets. */
        fun video(directory: File, rotation: Int = 0): ByteArray {
            val file = File.createTempFile("video-", ".mp4", directory)
            val codec = MediaCodec.createEncoderByType("video/avc")
            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            var muxing = false
            try {
                val format = MediaFormat.createVideoFormat("video/avc", 160, 120).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 5)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                var sent = 0
                var track = -1
                var ended = false
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 20_000_000_000L
                while (!ended && System.nanoTime() < deadline) {
                    if (sent <= 5) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            if (sent < 5) {
                                buffer.put(ByteArray(160 * 120) { (60 + sent * 20).toByte() })
                                buffer.put(ByteArray(160 * 120 / 2) { 128.toByte() })
                                codec.queueInputBuffer(index, 0, 160 * 120 * 3 / 2, sent * 200_000L, 0)
                            } else codec.queueInputBuffer(index, 0, 0, 1_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sent++
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                    } else if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                check(ended) { "Fixture encoder timed out" }
                codec.stop(); muxer.stop(); muxing = false
                return file.readBytes()
            } finally {
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                muxer.release(); file.delete()
            }
        }
    }
}
