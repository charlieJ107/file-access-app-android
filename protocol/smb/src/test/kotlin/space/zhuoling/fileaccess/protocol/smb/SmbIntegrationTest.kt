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
        assertFalse(session.capabilities.resumableUpload)
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
        rawWrite(receipt.opaqueId, ByteArray(UploadReceipt.MAX_BYTES) { 'x'.code.toByte() })
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
        val receipt = session.openRead(receiptRef).use { UploadReceipt.decode(it.readBytes(), token) }
        // Reproduce the durable state after rename succeeded but before COMMITTED was recorded.
        rawWrite(receiptRef.opaqueId, receipt.copy(phase = UploadReceipt.Phase.READY).encode())
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
