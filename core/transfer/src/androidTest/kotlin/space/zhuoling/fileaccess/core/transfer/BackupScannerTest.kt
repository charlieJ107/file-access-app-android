package space.zhuoling.fileaccess.core.transfer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.data.AppDatabase
import space.zhuoling.fileaccess.core.data.BackupRepository
import space.zhuoling.fileaccess.core.data.BackupRule
import space.zhuoling.fileaccess.core.data.ConnectionRepository
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.data.db.ConnectionEntity
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.Credentials
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.security.CredentialStore

@RunWith(AndroidJUnit4::class)
class BackupScannerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ownedUris = mutableListOf<Uri>()
    private lateinit var database: AppDatabase
    private lateinit var backups: BackupRepository
    private lateinit var tasks: TransferRepository
    private lateinit var connections: ConnectionRepository
    private val now = Instant.now()
    private val jpeg = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output) } finally { bitmap.recycle() }
        output.toByteArray()
    }

    @Before fun setup() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        database = Room.inMemoryDatabaseBuilder<AppDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        backups = BackupRepository(database)
        tasks = TransferRepository(database)
        connections = ConnectionRepository(database, object : CredentialStore {
            override suspend fun get(reference: String): Credentials? = null
            override suspend fun put(reference: String, credentials: Credentials) = Unit
            override suspend fun delete(reference: String) = Unit
        })
        database.connections().save(ConnectionEntity.from(
            ConnectionConfig("nas", "Test NAS", host = "unused", share = "test"), null,
        ))
        backups.save(BackupRule(id = "camera", name = "Test camera", sourceKind = "camera",
            target = EntryRef("nas", "photos"), wifiOnly = false, unmeteredOnly = false))
    }

    @After fun cleanup() {
        // Only URIs inserted by this test are removed. No shared-folder deletion or broad query.
        ownedUris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
        database.close()
    }

    @Test fun limitedPermissionScansStableCameraMediaAndDeduplicatesASecondPass() = runBlocking {
        val ready = createImage()
        createImage(pending = true)
        createImage(directory = "Pictures/FileAccessTests/")
        // Real MediaStore metadata, with an injected wall clock for the one-minute settling policy.
        val youngScan = scanner(now).scan()
        assertTrue(youngScan.taskIds.isEmpty())
        val first = scanner(now.plusSeconds(180)).scan()
        assertEquals(1, first.taskIds.size)
        assertEquals(ready.toString(), tasks.get(first.taskIds.single())?.localUri)
        assertTrue(first.messages.any { it.contains("仅备份系统当前授权") })
        val second = scanner(now.plusSeconds(180)).scan()
        assertEquals(first.taskIds, second.taskIds)
        assertEquals(1, tasks.observeAll().first().size)
        assertTrue(tasks.get(first.taskIds.single())?.logicalSourceId?.contains("#media-version=") == true)
    }

    @Test fun discoveryContinuesBeyondTheTwoHundredTaskBatchBoundary() = runBlocking {
        repeat(201) { createImage() }
        val scanner = scanner(Instant.now().plusSeconds(180))
        val first = scanner.scan()
        assertTrue(first.taskIds.isNotEmpty())
        assertTrue(first.taskIds.size <= 200)
        assertFalse(backups.scanCursor("camera").isNullOrBlank())
        repeat(10) {
            if (tasks.observeAll().first().size < 201) scanner.scan()
        }
        val all = tasks.observeAll().first()
        assertEquals(201, all.size)
        assertEquals(201, all.map { it.operationId }.distinct().size)
        assertEquals(ownedUris.map(Uri::toString).toSet(), all.map { it.localUri }.toSet())
    }

    private fun scanner(instant: Instant) = BackupScanner(
        context, connections, backups, tasks, NetworkPolicy(context), Clock.fixed(instant, ZoneOffset.UTC),
    )

    private fun createImage(directory: String = "DCIM/Camera/", pending: Boolean = false): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FileAccessScannerTest-${UUID.randomUUID()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        ownedUris += uri
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(jpeg) }
        if (!pending) context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }
}
