package space.zhuoling.fileaccess

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.data.*
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.security.CredentialStore
import space.zhuoling.fileaccess.core.security.KeystoreCredentialStore
import space.zhuoling.fileaccess.core.storage.MutationCapability
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.transfer.*
import space.zhuoling.fileaccess.protocol.smb.SmbStorageProvider
import space.zhuoling.fileaccess.preview.RemoteMediaDataSource
import space.zhuoling.fileaccess.preview.PreviewRepository
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in real-wire tests: connect only to a separately provisioned emulator-host fixture. */
@RunWith(AndroidJUnit4::class)
class TransferIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var tasks: TransferRepository
    private lateinit var backups: BackupRepository
    private lateinit var remote: RemoteAccess
    private lateinit var engine: TransferEngine
    private lateinit var root: RemoteEntry
    private val localUris = mutableListOf<Uri>()
    private val runId = UUID.randomUUID().toString()

    @Before fun setup() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("smbTestPort")?.toIntOrNull()
        assumeTrue("Start isolated fixture and pass smbTestPort to opt in", port != null)
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        tasks = TransferRepository(db)
        backups = BackupRepository(db)
        val credentials = object : CredentialStore {
            private val entries = mutableMapOf<String, Triple<String, CharArray, String>>()
            override suspend fun get(reference: String) = entries[reference]?.let { Credentials(it.first, it.second.copyOf(), it.third) }
            override suspend fun put(reference: String, credentials: Credentials) {
                entries[reference] = Triple(credentials.username, credentials.password.copyOf(), credentials.domain)
            }
            override suspend fun delete(reference: String) { entries.remove(reference)?.second?.fill('\u0000') }
        }
        val connections = ConnectionRepository(db, credentials)
        val config = ConnectionConfig("test", "Isolated fixture", host = "10.0.2.2", port = port!!,
            share = args.getString("smbTestShare") ?: "TEST", username = args.getString("smbTestUser") ?: "tester")
        Credentials(config.username, (args.getString("smbTestPassword") ?: "test-password").toCharArray()).use { connections.save(config, it) }
        remote = RemoteAccess(connections, ProviderRegistry(setOf(SmbStorageProvider())))
        engine = TransferEngine(context, tasks, backups, remote, NetworkPolicy(context))
        root = remote.withSession("test") { session -> (session as MutationCapability).createDirectory(session.root.ref, "android-$runId") }
    }

    @After fun cleanup() = runBlocking {
        localUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        if (::root.isInitialized) remote.withSession("test") { session ->
            val mutations = session as MutationCapability
            session.list(root.ref).toList().forEach { mutations.delete(it.ref) }
            try { mutations.delete(root.ref) }
            catch (error: StorageException) {
                // A refused conflicting operation can leave an unfinished receipt. The adapter
                // correctly retains it; the fixture owner removes its entire random share later.
                if (error.error != StorageError.CONFLICT) throw error
            }
        }
        if (::db.isInitialized) db.close()
    }

    private fun document(bytes: ByteArray, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FileAccessTests-$runId")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        localUris += uri
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    private suspend fun enqueue(uri: Uri, name: String = "payload.bin", rule: BackupRule? = null): String {
        val source = context.contentResolver.describe(uri)
        return tasks.enqueue(TransferTask(direction = TransferDirection.UPLOAD, remoteRef = root.ref,
            localUri = uri.toString(), name = name, totalBytes = source.size, sourceGeneration = source.version,
            backupRuleId = rule?.id, logicalSourceId = if (rule != null) uri.toString() else null))
    }

    @Test fun uploadAndDownloadPreserveBytesAndTaskCompletion() = runBlocking {
        val bytes = ByteArray(512 * 1024 + 19) { (it * 31).toByte() }
        val id = enqueue(document(bytes, "source.bin"))
        engine.execute(id)
        assertEquals(tasks.get(id)?.error, TransferState.SUCCEEDED, tasks.get(id)?.state)
        assertEquals(bytes.size.toLong(), tasks.get(id)?.confirmedBytes)
        val entry = remote.withSession("test") { it.list(root.ref).toList().single() }
        val destination = document(byteArrayOf(), "download.bin")
        val downloadId = tasks.enqueue(TransferTask(direction = TransferDirection.DOWNLOAD, remoteRef = entry.ref,
            localUri = destination.toString(), name = entry.name, totalBytes = entry.size, sourceRevision = entry.revision))
        engine.execute(downloadId)
        assertEquals(tasks.get(downloadId)?.error, TransferState.SUCCEEDED, tasks.get(downloadId)?.state)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(destination)!!.use { it.readBytes() })
    }

    @Test fun changedLocalSourceCannotUploadUnderOldGeneration() = runBlocking {
        val source = document(byteArrayOf(1, 2, 3), "source.bin")
        val id = enqueue(source)
        context.contentResolver.openOutputStream(source, "wt")!!.use { it.write(ByteArray(2048)) }
        engine.execute(id)
        assertEquals(TransferState.FAILED, tasks.get(id)?.state)
        assertTrue(remote.withSession("test") { it.list(root.ref).toList().isEmpty() })
    }

    @Test fun conflictingUploadNeverOverwritesExistingFile() = runBlocking {
        val original = byteArrayOf(1, 3, 5, 7)
        val first = enqueue(document(original, "first.bin"))
        engine.execute(first)
        assertEquals(tasks.get(first)?.error, TransferState.SUCCEEDED, tasks.get(first)?.state)
        val second = enqueue(document(ByteArray(512) { 42 }, "second.bin"))
        engine.execute(second)
        assertEquals(TransferState.FAILED, tasks.get(second)?.state)
        remote.withSession("test") { session ->
            val entry = session.list(root.ref).toList().single()
            assertArrayEquals(original, session.openRead(entry.ref).use { it.readBytes() })
        }
    }

    @Test fun verifiedBackupCreatesBaselineAndDeduplicatesRequeue() = runBlocking {
        val source = document(ByteArray(4099) { it.toByte() }, "backup.bin")
        val rule = BackupRule(name = "Test backup", sourceTreeUri = "content://test/tree", target = root.ref,
            wifiOnly = false, unmeteredOnly = false)
        backups.save(rule)
        val id = enqueue(source, rule = rule)
        assertNull(backups.baseline(rule.id, source.toString()))
        engine.execute(id)
        assertEquals(tasks.get(id)?.error, TransferState.SUCCEEDED, tasks.get(id)?.state)
        assertNotNull(backups.baseline(rule.id, source.toString()))
        assertEquals(id, enqueue(source, rule = rule))
        assertEquals(1, tasks.observeAll().first().size)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun previewsReadUtf8AndMediaRangesAcrossIndependentOpens() = runBlocking {
        val bytes = "FileAccess 预览验证\n第二行\n".repeat(200).toByteArray()
        val id = enqueue(document(bytes, "preview-source.bin"), "preview.txt")
        engine.execute(id)
        assertEquals(tasks.get(id)?.error, TransferState.SUCCEEDED, tasks.get(id)?.state)
        val entry = remote.withSession("test") { it.list(root.ref).toList().single() }
        val preview = PreviewRepository(context, remote).text(entry)
        assertEquals(bytes.toString(Charsets.UTF_8), preview.first)
        assertFalse(preview.second)
        val data = RemoteMediaDataSource(entry, remote)
        try {
            for (offset in listOf(19, bytes.size - 37, 0)) {
                val spec = DataSpec.Builder().setUri(Uri.parse("fileaccess://preview/sample"))
                    .setPosition(offset.toLong()).setLength(32).build()
                assertEquals(32, data.open(spec))
                val actual = ByteArray(32)
                var count = 0
                while (count < actual.size) {
                    val read = data.read(actual, count, actual.size - count)
                    assertTrue(read > 0)
                    count += read
                }
                assertArrayEquals(bytes.copyOfRange(offset, offset + 32), actual)
                assertEquals(-1, data.read(ByteArray(1), 0, 1))
                data.close()
            }
        } finally { data.close() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun media3PreparesAnActualWavOverSmb() = runBlocking {
        val pcmBytes = 8_000 * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmBytes)
            put(ByteArray(pcmBytes))
        }.array()
        val id = enqueue(document(wav, "audio-source.bin"), "preview.wav")
        engine.execute(id)
        assertEquals(tasks.get(id)?.error, TransferState.SUCCEEDED, tasks.get(id)?.state)
        val entry = remote.withSession("test") { it.list(root.ref).toList().single() }
        val ready = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
                DataSource.Factory { RemoteMediaDataSource(entry, remote) },
            )).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }
                override fun onPlayerError(failure: PlaybackException) { error.set(failure); ready.countDown() }
            })
            player.setMediaItem(MediaItem.fromUri("fileaccess://preview/preview.wav"))
            player.prepare()
        }
        try {
            assertTrue("Media3 did not become ready", ready.await(20, TimeUnit.SECONDS))
            assertNull(error.get()?.errorCodeName, error.get())
            instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, player.playbackState) }
        } finally { instrumentation.runOnMainSync { player.release() } }
    }

    @Test fun visibleUserActionRunsTheManifestJobServiceWithHiltDependencies() = runBlocking {
        // This test intentionally uses the application's real DB/Keystore. JobService executes
        // in the app's dependency graph, so an in-memory repository would conceal wiring errors.
        val actualDb = AppDatabase.create(context)
        val actualConnections = ConnectionRepository(actualDb, KeystoreCredentialStore(context))
        val actualTasks = TransferRepository(actualDb)
        val actualBackups = BackupRepository(actualDb)
        val args = InstrumentationRegistry.getArguments()
        val config = ConnectionConfig("job-test-$runId", "Temporary UIDT fixture", host = "10.0.2.2",
            port = args.getString("smbTestPort")!!.toInt(), share = args.getString("smbTestShare") ?: "TEST",
            username = args.getString("smbTestUser") ?: "tester")
        val policy = NetworkPolicy(context)
        val actualRemote = RemoteAccess(actualConnections, ProviderRegistry(setOf(SmbStorageProvider())))
        val scheduler = TransferScheduler(context, actualTasks,
            TransferEngine(context, actualTasks, actualBackups, actualRemote, policy),
            BackupScanner(context, actualConnections, actualBackups, actualTasks, policy))
        var id: String? = null
        try {
            Credentials(config.username, (args.getString("smbTestPassword") ?: "test-password").toCharArray()).use { actualConnections.save(config, it) }
            val uri = document(ByteArray(16_385) { it.toByte() }, "job-source.bin")
            val source = context.contentResolver.describe(uri)
            val taskId = actualTasks.enqueue(TransferTask(direction = TransferDirection.UPLOAD,
                remoteRef = root.ref.copy(connectionId = config.id), localUri = uri.toString(), name = "job.bin",
                totalBytes = source.size, sourceGeneration = source.version))
            id = taskId
            ActivityScenario.launch(MainActivity::class.java).use {
                scheduler.startUserInitiated(listOf(taskId))
                withTimeout(30_000) {
                    while (actualTasks.get(taskId)?.state !in setOf(TransferState.SUCCEEDED, TransferState.FAILED)) {
                        delay(200)
                    }
                }
                assertEquals(actualTasks.get(taskId)?.error, TransferState.SUCCEEDED, actualTasks.get(taskId)?.state)
            }
        } finally {
            id?.let { scheduler.cancel(it) }
            actualConnections.delete(config.id)
            actualDb.close()
        }
    }
}
