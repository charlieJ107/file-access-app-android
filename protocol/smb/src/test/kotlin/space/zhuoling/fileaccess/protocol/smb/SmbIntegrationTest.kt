package space.zhuoling.fileaccess.protocol.smb

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.*
import org.junit.Assert.*
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.*

/** Real wire-protocol tests, opt-in through FILEACCESS_SMB_TEST_PYTHON. No real NAS is contacted. */
class SmbIntegrationTest {
    private lateinit var session: StorageSession
    private lateinit var folder: RemoteEntry
    private val mutations get() = session as MutationCapability
    private val uploads get() = session as UploadCapability

    @Before fun connect() = runBlocking {
        Assume.assumeNotNull(server)
        session = provider.connect(config(), credentials())
        folder = mutations.createDirectory(session.root.ref, UUID.randomUUID().toString())
    }

    @After fun close() { if (::session.isInitialized) session.close() }

    @Test fun authenticatesSignedListsUnicodeAndReadsARealRange() = runBlocking {
        assertEquals(TransportProtection.SIGNED, session.capabilities.transportProtection)
        assertTrue(session.capabilities.resumableUpload)
        val data = "0123456789 你好 & %20.txt".toByteArray()
        val uploaded = uploads.upload(request("相册 & 100%20.txt"), source(data), {})
        val listed = session.list(folder.ref).toList()
        assertEquals(listOf(uploaded.name), listed.map { it.name })
        val stat = session.stat(listed.single().ref)
        assertEquals(stat.revision, listed.single().revision)
        session.openRead(stat.ref, 4, stat.revision).use { assertArrayEquals(data.copyOfRange(4, data.size), it.readBytes()) }
        session.openRead(stat.ref, data.size.toLong(), stat.revision).use {
            assertEquals(0, it.read(ByteArray(0)))
            assertEquals(-1, it.read())
        }
    }

    @Test fun wrongPasswordIsAuthenticationFailure() = runBlocking {
        assertError(StorageError.AUTHENTICATION) { provider.connect(config(), credentials("wrong-password")) }
    }

    @Test fun readStreamOutlivesTheCoroutineThatOpenedIt() {
        val entry = runBlocking { uploads.upload(request("media-loader.txt"), source("media stream".toByteArray()), {}) }
        val stream = runBlocking { session.openRead(entry.ref, expectedRevision = entry.revision) }
        assertEquals(0, stream.available())
        stream.use { assertEquals("media stream", it.reader().readText()) }
        assertThrows(java.io.IOException::class.java) { stream.read() }
    }

    @Test fun sparseFileCanBeReadAtAnOffsetBeyondTwoGiB() = runBlocking {
        val ref = EntryRef("integration", SmbPaths.join(folder.ref.opaqueId, "sparse-large.bin"))
        val offset = 3L * 1024 * 1024 * 1024 + 7
        val marker = byteArrayOf(1, 2, 3, 4)
        rawWrite(ref.opaqueId, marker, offset, create = true)
        val stat = session.stat(ref)
        assertEquals(offset + marker.size, stat.size)
        session.openRead(ref, offset, stat.revision).use { assertArrayEquals(marker, it.readBytes()) }
    }

    @Test fun missingShareHasTypedFailure() = runBlocking {
        assertError(StorageError.NOT_FOUND) { provider.connect(config().copy(share = "missing-share"), credentials()) }
    }

    @Test fun encryptionRequiredDoesNotSilentlyDowngradeToSmb2() = runBlocking {
        try {
            provider.connect(config().copy(requireEncryption = true), credentials()).use {
                fail("The SMB2-only fixture must not establish an encryption-required session")
            }
        } catch (error: StorageException) {
            assertTrue(error.error == StorageError.UNSUPPORTED || error.error == StorageError.NETWORK)
        }
    }

    @Test fun uploadAndRenameNeverReplaceAnotherFile() = runBlocking {
        val first = uploads.upload(request("same.txt"), source("original".toByteArray()), {})
        val conflictingRequest = request("same.txt")
        assertError(StorageError.CONFLICT) { uploads.upload(conflictingRequest, source("replacement".toByteArray()), {}) }
        assertError(StorageError.NOT_FOUND) { session.stat(receiptRef(conflictingRequest)) }
        val second = uploads.upload(request("second.txt"), source("second".toByteArray()), {})
        assertError(StorageError.CONFLICT) { mutations.rename(second.ref, "same.txt", second.revision) }
        session.openRead(first.ref).use { assertEquals("original", it.reader().readText()) }
        session.openRead(second.ref).use { assertEquals("second", it.reader().readText()) }
    }

    @Test fun sameOperationRetryReturnsOriginalWithoutDuplicateVisibleFiles() = runBlocking {
        val request = request("idempotent.txt")
        val bytes = "one operation".toByteArray()
        val first = uploads.upload(request, source(bytes), {})
        val second = uploads.upload(request, source(bytes), {})
        assertEquals(first.ref, second.ref)
        assertEquals(listOf("idempotent.txt"), session.list(folder.ref).toList().map { it.name })
    }

    @Test fun retryAfterSourceChangeNeverAcceptsAnEarlierPayload() = runBlocking {
        val request = request("versioned.txt")
        uploads.upload(request, source("original".toByteArray(), "v1"), {})
        assertError(StorageError.SOURCE_CHANGED) { uploads.upload(request, source("different".toByteArray(), "v2"), {}) }
    }

    @Test fun staleRevisionBlocksReadRenameAndDelete() = runBlocking {
        val entry = uploads.upload(request("stale.txt"), source("payload".toByteArray()), {})
        assertError(StorageError.SOURCE_CHANGED) { session.openRead(entry.ref, expectedRevision = "stale").close() }
        assertError(StorageError.SOURCE_CHANGED) { mutations.rename(entry.ref, "renamed.txt", "stale") }
        assertError(StorageError.SOURCE_CHANGED) { mutations.delete(entry.ref, "stale") }
        assertEquals(entry.ref, session.stat(entry.ref).ref)
    }

    @Test fun deletesOnlyFilesAndEmptyDirectories() = runBlocking {
        val empty = mutations.createDirectory(folder.ref, "empty")
        mutations.delete(empty.ref)
        assertError(StorageError.NOT_FOUND) { session.stat(empty.ref) }
        val file = uploads.upload(request("keep.txt"), source("keep".toByteArray()), {})
        assertError(StorageError.CONFLICT) { mutations.delete(folder.ref) }
        assertEquals(file.ref, session.stat(file.ref).ref)
        mutations.delete(file.ref)
        assertError(StorageError.NOT_FOUND) { session.stat(file.ref) }
        assertTrue(session.list(folder.ref).toList().isEmpty())
        mutations.delete(folder.ref)
        assertError(StorageError.NOT_FOUND) { session.stat(folder.ref) }
    }

    @Test fun corruptedReceiptIsRetainedWhenDeletingALogicallyEmptyDirectory() = runBlocking {
        val request = request("deleted.txt")
        val file = uploads.upload(request, source("content".toByteArray()), {})
        mutations.delete(file.ref)
        val receipt = receiptRef(request)
        rawWrite(receipt.opaqueId, ByteArray(UploadReceipt.JOURNAL_BYTES) { 'x'.code.toByte() })
        assertTrue(session.list(folder.ref).toList().isEmpty())
        assertError(StorageError.CONFLICT) { mutations.delete(folder.ref) }
        assertEquals(receipt, session.stat(receipt).ref)
    }

    @Test fun unfinishedReceiptIsRetainedWhenDeletingALogicallyEmptyDirectory() = runBlocking {
        val request = request("interrupted.txt")
        try { uploads.upload(request, source("payload".toByteArray()), {}, { throw CancellationException() }) }
        catch (_: CancellationException) { }
        assertTrue(session.list(folder.ref).toList().isEmpty())
        assertError(StorageError.CONFLICT) { mutations.delete(folder.ref) }
        assertEquals(receiptRef(request), session.stat(receiptRef(request)).ref)
    }

    @Test fun nonemptyDirectoryDoesNotLoseCommittedReceiptsOnFailedDelete() = runBlocking {
        val request = request("kept.txt")
        val file = uploads.upload(request, source("keep".toByteArray()), {})
        assertError(StorageError.CONFLICT) { mutations.delete(folder.ref) }
        assertEquals(file.ref, session.stat(file.ref).ref)
        assertEquals(receiptRef(request), session.stat(receiptRef(request)).ref)
    }

    @Test fun durableReadyStateAfterLostCommitAcknowledgementReconcilesByIdentityAndHash() = runBlocking {
        val request = request("lost-response.txt")
        val source = source("committed payload".toByteArray())
        val committed = uploads.upload(request, source, {})
        val receiptRef = receiptRef(request)
        val token = checkNotNull(SmbPaths.receiptToken(receiptRef.opaqueId.substringAfterLast('\\')))
        val receipt = session.openRead(receiptRef).use { UploadReceipt.decodeJournal(it.readBytes(), token) }
        // Reproduce the durable state after rename succeeded but before COMMITTED was recorded.
        rawWrite(receiptRef.opaqueId, receipt.copy(phase = UploadReceipt.Phase.READY, sequence = receipt.sequence + 1).encode())
        var commitCalled = false
        val reconciled = uploads.upload(request, source, {}, { commitCalled = true })
        assertTrue(commitCalled)
        assertEquals(committed.ref, reconciled.ref)
    }

    @Test fun sameLengthRemoteContentEditCannotBeMistakenForSuccessfulRetry() = runBlocking {
        val request = request("changed-remote.txt")
        val file = uploads.upload(request, source("original".toByteArray()), {})
        rawWrite(file.ref.opaqueId, "modified".toByteArray())
        assertError(StorageError.OUTCOME_UNKNOWN) { uploads.upload(request, source("original".toByteArray()), {}) }
        session.openRead(file.ref).use { assertEquals("modified", it.reader().readText()) }
    }

    @Test fun rejectsCrossConnectionAndRootMutation() = runBlocking {
        assertError(StorageError.INVALID_CONFIGURATION) { session.stat(EntryRef("other-account", folder.ref.opaqueId)) }
        assertError(StorageError.PERMISSION) { mutations.rename(session.root.ref, "moved") }
        assertError(StorageError.PERMISSION) { mutations.delete(session.root.ref) }
    }

    @Test fun knownLengthMismatchDoesNotPublishPartialFile() = runBlocking {
        val badSource = object : UploadSource {
            override val length = 100L
            override fun open() = ByteArrayInputStream("short".toByteArray())
        }
        assertError(StorageError.SOURCE_CHANGED) { uploads.upload(request("partial.txt"), badSource, {}) }
        assertTrue(session.list(folder.ref).toList().isEmpty())
    }

    @Test fun sourceChangingDuringTransferDoesNotCommit() = runBlocking {
        var version = "before"
        val source = object : UploadSource {
            override val length = 7L
            override val version = "before"
            override fun currentVersion() = version
            override fun open(): InputStream = ByteArrayInputStream("payload".toByteArray())
        }
        assertError(StorageError.SOURCE_CHANGED) {
            uploads.upload(request("changed.txt"), source, { version = "after" })
        }
        assertTrue(session.list(folder.ref).toList().isEmpty())
    }

    @Test fun cancellationRetainsNoVisiblePartialAndSameOperationCanRestart() = runBlocking {
        val request = request("cancelled.txt")
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        try {
            uploads.upload(request, source(bytes), { throw CancellationException("cancel test transfer") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(session.list(folder.ref).toList().isEmpty())
        val retried = uploads.upload(request, source(bytes), {})
        session.openRead(retried.ref).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun failedCommitLeaseNeverPublishesAndReadyReceiptCanRetry() = runBlocking {
        val request = request("lease.txt")
        val source = source("lease guarded".toByteArray())
        try {
            uploads.upload(request, source, {}, { throw CancellationException("lease lost") })
            fail("Lost lease must stop commit")
        } catch (_: CancellationException) { }
        assertTrue(session.list(folder.ref).toList().isEmpty())
        var commitCalled = false
        uploads.upload(request, source, {}, { commitCalled = true })
        assertTrue(commitCalled)
        assertEquals(listOf("lease.txt"), session.list(folder.ref).toList().map { it.name })
    }

    @Test fun sourceWithoutVersionIsRecheckedBeforeCommit() = runBlocking {
        var opens = 0
        val source = object : UploadSource {
            override val length = 4L
            override fun open(): InputStream = ByteArrayInputStream((if (++opens == 1) "abcd" else "efgh").toByteArray())
        }
        assertError(StorageError.SOURCE_CHANGED) { uploads.upload(request("unversioned.txt"), source, {}) }
        assertTrue(session.list(folder.ref).toList().isEmpty())
    }

    private fun request(name: String) = UploadRequest(UUID.randomUUID().toString(), folder.ref, name)

    @Test fun missingIdentifiedPayloadRequiresReconciliationAndIsNeverRecreated() = runBlocking {
        val request = request("missing-payload.bin")
        val bytes = ByteArray((UPLOAD_CHECKPOINT_BYTES + 73).toInt())
        interruptAt(request, source(bytes), UPLOAD_CHECKPOINT_BYTES)
        mutations.delete(partRef(request))
        assertError(StorageError.OUTCOME_UNKNOWN) { uploads.upload(request, source(bytes), {}) }
        assertError(StorageError.NOT_FOUND) { session.stat(partRef(request)) }
        assertEquals(UPLOAD_CHECKPOINT_BYTES, readReceipt(request).length)
    }

    @Test fun finalVerificationResumesAfterWorkerStopsWithoutReuploadingPayload() = runBlocking {
        val request = request("verification-slices.bin")
        val bytes = ByteArray((3 * UPLOAD_CHECKPOINT_BYTES + 73).toInt()) { (it % 251).toByte() }
        var opens = 0
        val interrupted = object : UploadSource {
            override val length = bytes.size.toLong()
            override val version = "stable"
            override fun open(): InputStream {
                val checking = ++opens == 2
                return object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                        if (checking && pos >= UPLOAD_CHECKPOINT_BYTES) throw CancellationException("verification time slice ended")
                        return super.read(buffer, offset, count)
                    }
                }
            }
        }
        try { uploads.upload(request, interrupted, {}); fail("Expected verification interruption") }
        catch (_: CancellationException) { }
        assertEquals(UploadReceipt.Phase.VERIFYING, readReceipt(request).phase)
        assertEquals(UPLOAD_CHECKPOINT_BYTES, readReceipt(request).verificationOffset)
        session.close()
        session = provider.connect(config(), credentials())
        val offsets = mutableListOf<Long>()
        val resumed = object : UploadSource {
            override val length = bytes.size.toLong()
            override val version = "stable"
            override fun open() = ByteArrayInputStream(bytes)
            override fun open(offset: Long): InputStream { offsets += offset; return super.open(offset) }
        }
        val progress = mutableListOf<Long>()
        val file = uploads.upload(request, resumed, { progress += it })
        assertEquals(UPLOAD_CHECKPOINT_BYTES, offsets.first())
        assertEquals(bytes.size.toLong(), progress.first())
        session.openRead(file.ref).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun actualProcessDeathKeepsDurableCheckpointAcrossIndependentJvms() = runBlocking {
        val request = request("process-death.bin")
        val classpath = requireNotNull(System.getProperty("fileaccess.test.classpath"))
        assertTrue("Test runtime must expose its classpath for the crash client", classpath.isNotEmpty())
        for (boundary in listOf(UPLOAD_CHECKPOINT_BYTES, 2 * UPLOAD_CHECKPOINT_BYTES)) {
            val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp", classpath, SmbCrashClient::class.java.name, port.toString(), password,
                folder.ref.opaqueId, request.operationId, boundary.toString()).redirectErrorStream(true).start()
            try {
                assertTrue("Crash client did not finish", child.waitFor(90, TimeUnit.SECONDS))
                assertEquals(child.inputStream.bufferedReader().readText(), 91, child.exitValue())
            } finally { if (child.isAlive) child.destroyForcibly() }
            assertEquals(boundary, readReceipt(request).length)
        }
        val bytes = ByteArray((3 * UPLOAD_CHECKPOINT_BYTES + 71).toInt()) { (it % 251).toByte() }
        var initial = -1L
        val file = uploads.upload(request, source(bytes), { if (initial < 0) initial = it })
        assertEquals(2 * UPLOAD_CHECKPOINT_BYTES, initial)
        session.openRead(file.ref).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun serverQuotaAndPermissionFailuresKeepCheckpointForRetry() = runBlocking {
        val bytes = ByteArray((2 * UPLOAD_CHECKPOINT_BYTES + 71).toInt()) { (it % 241).toByte() }
        for ((fault, expected) in listOf("quota" to StorageError.QUOTA_EXCEEDED, "permission" to StorageError.PERMISSION)) {
            val request = request("$fault.bin")
            rawWrite(SmbPaths.join(folder.ref.opaqueId, ".fixture-fault-$fault"), byteArrayOf(1), create = true)
            assertError(expected) { uploads.upload(request, source(bytes), {}) }
            assertEquals(UPLOAD_CHECKPOINT_BYTES, readReceipt(request).length)
            val progress = mutableListOf<Long>()
            val file = uploads.upload(request, source(bytes), { progress += it })
            assertEquals(UPLOAD_CHECKPOINT_BYTES, progress.first())
            session.openRead(file.ref).use { assertArrayEquals(bytes, it.readBytes()) }
        }
    }

    @Test fun repeatedTransportLossResumesConfirmedOffsetsAndNeverDuplicates() = runBlocking {
        val request = request("resume.bin")
        val bytes = ByteArray((3 * UPLOAD_CHECKPOINT_BYTES + 71).toInt()) { (it % 251).toByte() }
        for (checkpoint in 1..3) {
            val boundary = checkpoint * UPLOAD_CHECKPOINT_BYTES
            try {
                uploads.upload(request, source(bytes), { if (it >= boundary) { session.close(); throw CancellationException() } })
                fail("Expected interruption")
            } catch (_: CancellationException) { }
            session = provider.connect(config(), credentials())
            assertEquals(boundary, readReceipt(request).length)
            assertTrue(session.list(folder.ref).toList().isEmpty())
        }
        val progress = mutableListOf<Long>()
        val file = uploads.upload(request, source(bytes), { progress += it })
        assertEquals(3 * UPLOAD_CHECKPOINT_BYTES, progress.first())
        session.openRead(file.ref).use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(file.ref, uploads.upload(request, source(bytes), {}).ref)
        assertEquals(1, session.list(folder.ref).toList().size)
    }

    @Test fun unconfirmedTailIsDiscardedAndTornJournalFallsBackToPreviousCheckpoint() = runBlocking {
        val request = request("torn.bin")
        val bytes = ByteArray((3 * UPLOAD_CHECKPOINT_BYTES + 17).toInt()) { (it % 239).toByte() }
        interruptAt(request, source(bytes), 2 * UPLOAD_CHECKPOINT_BYTES)
        val saved = readReceipt(request)
        rawWrite(receiptRef(request).opaqueId, ByteArray(UploadReceipt.MAX_BYTES) { 0x7f },
            (saved.sequence % 2) * UploadReceipt.MAX_BYTES)
        rawWrite(partRef(request).opaqueId, "unconfirmed junk".toByteArray(), 2 * UPLOAD_CHECKPOINT_BYTES)
        val progress = mutableListOf<Long>()
        val file = uploads.upload(request, source(bytes), { progress += it })
        assertEquals(UPLOAD_CHECKPOINT_BYTES, progress.first())
        session.openRead(file.ref).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun changedPrefixIsRejectedEvenWhenSourceMetadataIsUnchanged() = runBlocking {
        val request = request("source-prefix.bin")
        val bytes = ByteArray((UPLOAD_CHECKPOINT_BYTES + 100).toInt()) { 1 }
        interruptAt(request, source(bytes), UPLOAD_CHECKPOINT_BYTES)
        bytes[0] = 2
        assertError(StorageError.SOURCE_CHANGED) { uploads.upload(request, source(bytes), {}) }
        assertTrue(session.list(folder.ref).toList().isEmpty())
    }

    @Test fun remotePrefixCorruptionIsRejectedAndRetained() = runBlocking {
        val request = request("remote-prefix.bin")
        val bytes = ByteArray((UPLOAD_CHECKPOINT_BYTES + 100).toInt()) { 1 }
        interruptAt(request, source(bytes), UPLOAD_CHECKPOINT_BYTES)
        rawWrite(partRef(request).opaqueId, byteArrayOf(2))
        assertError(StorageError.CORRUPT_DATA) { uploads.upload(request, source(bytes), {}) }
        assertEquals(UPLOAD_CHECKPOINT_BYTES, session.stat(partRef(request)).size)
    }

    @Test fun cancelledPayloadCleanupIsIdempotentAndNeverDeletesPublishedFiles() = runBlocking {
        val request = request("abort.bin")
        val bytes = ByteArray((UPLOAD_CHECKPOINT_BYTES + 100).toInt())
        interruptAt(request, source(bytes), UPLOAD_CHECKPOINT_BYTES)
        val cleaner = session as UploadCleanupCapability
        cleaner.cleanupUpload(request, completed = false)
        cleaner.cleanupUpload(request, completed = false)
        assertError(StorageError.NOT_FOUND) { session.stat(partRef(request)) }
        assertError(StorageError.NOT_FOUND) { session.stat(receiptRef(request)) }
        val committedRequest = request("keep-published.bin")
        val file = uploads.upload(committedRequest, source(byteArrayOf(1, 2)), {})
        assertError(StorageError.OUTCOME_UNKNOWN) { cleaner.cleanupUpload(committedRequest, completed = false) }
        cleaner.cleanupUpload(committedRequest, completed = true)
        cleaner.cleanupUpload(committedRequest, completed = true)
        session.openRead(file.ref).use { assertArrayEquals(byteArrayOf(1, 2), it.readBytes()) }
    }

    @Test fun cancelledCleanupRetainsUnidentifiedReplacement() = runBlocking {
        val request = request("replaced-part.bin")
        val bytes = ByteArray((UPLOAD_CHECKPOINT_BYTES + 100).toInt())
        interruptAt(request, source(bytes), UPLOAD_CHECKPOINT_BYTES)
        val part = partRef(request)
        mutations.rename(part, "original-part.bin") // Keep original inode allocated.
        rawWrite(part.opaqueId, byteArrayOf(7), create = true)
        assertError(StorageError.OUTCOME_UNKNOWN) {
            (session as UploadCleanupCapability).cleanupUpload(request, completed = false)
        }
        session.openRead(part).use { assertArrayEquals(byteArrayOf(7), it.readBytes()) }
    }

    @Test fun multiGiBVideoSurvivesThreeFreshConnections() = runBlocking {
        Assume.assumeTrue(System.getenv("FILEACCESS_SMB_LARGE_TEST") == "1")
        val request = request("large-video.bin")
        val length = 3L * 1024 * 1024 * 1024 + 73
        val source = object : UploadSource {
            override val length = length
            override val version = "generated-video-v1"
            override fun open(): InputStream = object : InputStream() {
                var position = 0L
                override fun read(): Int = if (position >= length) -1 else ((position++ % 251).toInt())
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    if (count == 0) return 0
                    if (position >= length) return -1
                    val size = minOf(count.toLong(), length - position).toInt()
                    for (i in 0 until size) bytes[offset + i] = ((position + i) % 251).toByte()
                    position += size
                    return size
                }
            }
        }
        for (boundary in listOf(512L * 1024 * 1024, 1536L * 1024 * 1024, 2560L * 1024 * 1024)) {
            interruptAt(request, source, boundary)
            session.close()
            session = provider.connect(config(), credentials())
            assertEquals(boundary, readReceipt(request).length)
        }
        var first = -1L
        val file = uploads.upload(request, source, { if (first < 0) first = it })
        assertEquals(2560L * 1024 * 1024, first)
        assertEquals(length, file.size)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
        val actual = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        source.open().use { input -> while (true) { val count = input.read(buffer); if (count < 0) break; expected.update(buffer, 0, count) } }
        session.openRead(file.ref).use { input -> while (true) { val count = input.read(buffer); if (count < 0) break; actual.update(buffer, 0, count) } }
        assertArrayEquals(expected.digest(), actual.digest())
        assertEquals(file.ref, uploads.upload(request, source, {}).ref)
        assertEquals(1, session.list(folder.ref).toList().size)
    }

    private suspend fun interruptAt(request: UploadRequest, source: UploadSource, offset: Long) {
        try {
            uploads.upload(request, source, { if (it >= offset) throw CancellationException("simulated process interruption") })
            fail("Expected interruption")
        } catch (_: CancellationException) { }
    }

    private suspend fun readReceipt(request: UploadRequest): UploadReceipt {
        val ref = receiptRef(request)
        val token = checkNotNull(SmbPaths.receiptToken(ref.opaqueId.substringAfterLast('\\')))
        return session.openRead(ref).use { UploadReceipt.decodeJournal(it.readBytes(), token) }
    }

    private fun partRef(request: UploadRequest) = receiptRef(request).let {
        it.copy(opaqueId = it.opaqueId.removeSuffix(".receipt") + ".part")
    }
    private fun receiptRef(request: UploadRequest): EntryRef {
        val target = SmbPaths.join(request.parent.opaqueId, request.name)
        val token = SmbPaths.operationToken(request.operationId, "integration", target)
        return EntryRef("integration", SmbPaths.join(request.parent.opaqueId, "${SmbPaths.INTERNAL_PREFIX}$token.receipt"))
    }

    /** Direct fixture mutation simulates an interrupted writer or another client, not adapter behavior. */
    private fun rawWrite(path: String, bytes: ByteArray, offset: Long = 0, create: Boolean = false) {
        com.hierynomus.smbj.SMBClient(provider.clientConfig(false)).use { client ->
            client.connect("127.0.0.1", port).use { connection ->
                connection.authenticate(com.hierynomus.smbj.auth.AuthenticationContext("fileaccess-test", password.toCharArray(), "")).use { authenticated ->
                    (authenticated.connectShare("test") as com.hierynomus.smbj.share.DiskShare).use { share ->
                        share.openFile(path, setOf(com.hierynomus.msdtyp.AccessMask.FILE_WRITE_DATA),
                            setOf(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL), emptySet(),
                            if (create) com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE else com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { file ->
                            if (create && offset > 0) file.setLength(offset + bytes.size)
                            assertEquals(bytes.size.toLong(), file.write(bytes, offset))
                            file.flush()
                        }
                    }
                }
            }
        }
    }
    private fun source(bytes: ByteArray, sourceVersion: String? = "stable"): UploadSource = object : UploadSource {
        override val length = bytes.size.toLong()
        override val version = sourceVersion
        override fun open() = ByteArrayInputStream(bytes)
    }

    private suspend fun assertError(expected: StorageError, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") } catch (error: StorageException) { assertEquals(expected, error.error) }
    }

    companion object {
        private val provider = SmbStorageProvider()
        private var server: Process? = null
        private lateinit var rootDirectory: File
        private lateinit var password: String
        private var port = 0
        private fun config() = ConnectionConfig("integration", "Isolated test SMB", host = "127.0.0.1", port = port, share = "test")
        private fun credentials(value: String = password) = Credentials("fileaccess-test", value.toCharArray())

        @JvmStatic @BeforeClass fun startServer() {
            val python = System.getenv("FILEACCESS_SMB_TEST_PYTHON") ?: return
            rootDirectory = Files.createTempDirectory("fileaccess-smb-test-").toFile()
            password = UUID.randomUUID().toString()
            port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
            val fixture = File("src/test/fixtures/smb_server.py").absolutePath
            val distro = System.getenv("FILEACCESS_SMB_TEST_WSL_DISTRO")
            val command = if (distro != null) {
                val normalized = fixture.replace('\\', '/')
                val linuxPath = "/mnt/${normalized[0].lowercaseChar()}${normalized.substring(2)}"
                listOf("wsl.exe", "-d", distro, "--exec", python, linuxPath, "--port", port.toString(), "--password", password)
            } else listOf(python, fixture, "--root", rootDirectory.absolutePath, "--port", port.toString(), "--password", password)
            server = ProcessBuilder(command).redirectErrorStream(true).start()
            val reader = server!!.inputStream.bufferedReader()
            val ready = reader.readLine()
            assertEquals("Fixture failed to start: $ready", "READY", ready.orEmpty())
            Thread({ reader.use { while (it.readLine() != null) { /* Drain fixture diagnostics to avoid pipe backpressure. */ } } },
                "smb-fixture-output").apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
                    break
                } catch (error: java.io.IOException) {
                    if (System.nanoTime() >= deadline) throw error
                    Thread.sleep(100)
                }
            }
        }

        @JvmStatic @AfterClass fun stopServer() {
            server?.outputStream?.close()
            if (server?.waitFor(5, TimeUnit.SECONDS) == false) server?.destroyForcibly()
            // Only our own randomly allocated test directory is removed. Never remove a configured share.
            if (::rootDirectory.isInitialized && rootDirectory.canonicalFile.parentFile == File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile &&
                rootDirectory.name.startsWith("fileaccess-smb-test-")) rootDirectory.deleteRecursively()
        }
    }
}
