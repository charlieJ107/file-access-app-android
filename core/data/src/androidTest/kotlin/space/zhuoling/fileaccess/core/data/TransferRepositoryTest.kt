package space.zhuoling.fileaccess.core.data

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.data.db.ConnectionEntity
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.TransferDirection
import space.zhuoling.fileaccess.core.model.TransferState

@RunWith(AndroidJUnit4::class)
class TransferRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: TransferRepository
    private val target = EntryRef("nas", "photos")

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder<AppDatabase>(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
            .setDriver(AndroidSQLiteDriver()).build()
        repository = TransferRepository(database)
        database.connections().save(ConnectionEntity.from(
            ConnectionConfig("nas", "Test NAS", host = "nas", share = "test"), null,
        ))
    }

    @After fun close() { database.close() }

    private fun task() = TransferTask(
        direction = TransferDirection.UPLOAD, remoteRef = target, localUri = "content://test/image/1",
        name = "photo.jpg", totalBytes = 20,
    )

    @Test fun simultaneousSchedulersHaveExactlyOneLeaseOwner() = runBlocking {
        val id = repository.enqueue(task())
        val leases = coroutineScope {
            (1..16).map { owner -> async { repository.claim(id, "owner-$owner", now = 100) } }.awaitAll()
        }.filterNotNull()
        assertEquals(1, leases.size)
        assertTrue(repository.owns(leases.single(), now = 101))
    }

    @Test fun expiredOwnerCannotPublishProgressOrCompleteAfterRecovery() = runBlocking {
        val id = repository.enqueue(task())
        val oldLease = requireNotNull(repository.claim(id, "old", now = 100, leaseMillis = 10))
        assertEquals(1, repository.recoverExpired(now = 111))
        assertEquals(TransferState.RECONCILING, repository.get(id)?.state)
        val newLease = requireNotNull(repository.claim(id, "new", now = 112))
        assertFalse(repository.progress(oldLease, 20, now = 113))
        assertFalse(repository.transition(oldLease, TransferState.SUCCEEDED, now = 113))
        assertTrue(repository.owns(newLease, now = 113))
    }

    @Test fun rescanDeduplicatesTheSameLogicalSourceGeneration() = runBlocking {
        BackupRepository(database).save(BackupRule(id = "rule", name = "Camera", sourceTreeUri = "content://test/tree", target = target))
        val source = task().copy(backupRuleId = "rule", logicalSourceId = "image/1", sourceGeneration = "v1")
        val first = repository.enqueue(source)
        val duplicate = repository.enqueue(source.copy(id = "different-id", operationId = "different-op"))
        assertEquals(first, duplicate)
        val secondGeneration = repository.enqueue(source.copy(id = "new-generation", sourceGeneration = "v2"))
        assertFalse(first == secondGeneration)
    }

    @Test fun failedVerificationCannotAdvanceBackupBaseline() = runBlocking {
        val backups = BackupRepository(database)
        backups.save(BackupRule(id = "rule", name = "Camera", sourceTreeUri = "content://test/tree", target = target))
        val id = repository.enqueue(task().copy(backupRuleId = "rule", logicalSourceId = "image/1", sourceGeneration = "v1"))
        val lease = requireNotNull(repository.claim(id, "test", now = 100))
        assertTrue(repository.transition(lease, TransferState.RUNNING, now = 101))
        val remote = RemoteEntry(EntryRef("nas", "photos/photo.jpg"), "photo.jpg", false, size = 20)
        assertFalse(repository.complete(lease, remote, now = 102))
        assertNull(backups.baseline("rule", "image/1"))
        assertTrue(repository.transition(lease, TransferState.VERIFYING, now = 103))
        assertTrue(repository.complete(lease, remote, now = 104))
        assertEquals(TransferState.SUCCEEDED, repository.get(id)?.state)
        assertNotNull(backups.baseline("rule", "image/1"))
    }

    @Test fun reconcileReleaseDropsOwnershipAndHonorsRetryTime() = runBlocking {
        val id = repository.enqueue(task())
        val lease = requireNotNull(repository.claim(id, "old", now = 100))
        assertTrue(repository.releaseForReconciliation(lease, "Unknown commit", now = 101, retryAt = 200))
        assertFalse(repository.owns(lease, now = 102))
        assertFalse(repository.releaseForReconciliation(lease, "Stale owner", now = 102))
        assertNull(repository.claim(id, "new", now = 199))
        assertNotNull(repository.claim(id, "new", now = 200))
    }

    @Test fun explicitRestartResetsProgressButOrdinaryUpdatesCannotRegress() = runBlocking {
        val id = repository.enqueue(task())
        val lease = requireNotNull(repository.claim(id, "worker", now = 100))
        assertTrue(repository.progress(lease, 10, now = 101))
        assertFalse(repository.progress(lease, 0, now = 102))
        assertTrue(repository.resetProgress(lease, now = 103))
        assertEquals(0L, repository.get(id)?.confirmedBytes)
        repository.pause(id)
        assertFalse(repository.resetProgress(lease, now = 104))
        assertFalse(repository.releaseForReconciliation(lease, "Stale owner", now = 104))
    }

    @Test fun scanningCursorIsPersistedAndRemovedWhenItsSourceChanges() = runBlocking {
        val backups = BackupRepository(database)
        val rule = BackupRule(id = "rule", name = "Camera", sourceTreeUri = "content://test/tree1", target = target)
        backups.save(rule)
        backups.saveScanCursor(rule.id, "cursor-one")
        assertEquals("cursor-one", backups.scanCursor(rule.id))
        backups.save(rule.copy(sourceTreeUri = "content://test/tree2"))
        assertNull(backups.scanCursor(rule.id))
    }

    @Test fun reenabledRuleDoesNotResumeAnIndividuallyPausedTask() = runBlocking {
        val backups = BackupRepository(database)
        val rule = BackupRule(id = "rule", name = "Camera", sourceTreeUri = "content://test/tree", target = target)
        backups.save(rule)
        val first = repository.enqueue(task().copy(backupRuleId = rule.id, logicalSourceId = "one", sourceGeneration = "v1"))
        val second = repository.enqueue(task().copy(backupRuleId = rule.id, logicalSourceId = "two", sourceGeneration = "v1"))
        repository.pause(first)
        backups.save(rule.copy(enabled = false))
        backups.save(rule.copy(enabled = true))
        assertEquals(TransferState.PAUSED, repository.get(first)?.state)
        assertEquals(TransferState.QUEUED, repository.get(second)?.state)
    }

    @Test fun resumedDownloadCannotReuseItsCheckpointForAnotherSourceVersion() = runBlocking {
        val id = repository.enqueue(task().copy(direction = TransferDirection.DOWNLOAD))
        val lease = requireNotNull(repository.claim(id, "worker", now = 100))
        assertTrue(repository.pinSourceRevision(lease, "revision-one", now = 101))
        assertFalse(repository.pinSourceRevision(lease, "revision-two", now = 102))
        assertEquals("revision-one", repository.get(id)?.sourceRevision)
        repository.pause(id)
        assertFalse(repository.pinSourceRevision(lease, "revision-one", now = 103))
    }

    @Test fun oldCommitCannotAdvanceBaselineAfterTheBackupDestinationChanges() = runBlocking {
        val backups = BackupRepository(database)
        val rule = BackupRule(id = "rule", name = "Camera", sourceTreeUri = "content://test/tree", target = target)
        backups.save(rule)
        val id = repository.enqueue(task().copy(backupRuleId = "rule", logicalSourceId = "image/1", sourceGeneration = "v1"))
        val lease = requireNotNull(repository.claim(id, "worker", now = 100))
        assertTrue(repository.transition(lease, TransferState.RUNNING, now = 101))
        assertTrue(repository.transition(lease, TransferState.COMMITTING, now = 102))
        backups.save(rule.copy(target = EntryRef("nas", "other-photos")))
        assertTrue(repository.complete(lease,
            RemoteEntry(EntryRef("nas", "photos/photo.jpg"), "photo.jpg", false, size = 20), now = 103))
        assertEquals(TransferState.SUCCEEDED, repository.get(id)?.state)
        assertEquals(null, backups.baseline("rule", "image/1"))
    }
}
