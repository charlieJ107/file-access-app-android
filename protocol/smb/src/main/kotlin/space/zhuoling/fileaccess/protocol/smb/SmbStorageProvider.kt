package space.zhuoling.fileaccess.protocol.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.AccessMask.*
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.FileAttributes.*
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateDisposition.*
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2CreateOptions.*
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMB2ShareAccess.*
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.File as SmbFile
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.*

/** SMB 2/3 only. Each connect owns its transport; credentials and handles never cross accounts. */
class SmbStorageProvider(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactoryProvider: (ConnectionConfig) -> SocketFactory = { TimeoutSocketFactory() },
) : StorageProvider {
    override val type: String = "smb"

    override suspend fun connect(config: ConnectionConfig, credentials: Credentials): StorageSession {
        val paths = SmbPaths(config)
        checkConfig(credentials.username.isNotBlank(), "An authenticated SMB account is required")
        var result: SmbStorageSession? = null
        try {
            return withContext(io) {
                runInterruptible {
                    smbCall {
                        val client = SMBClient(clientConfig(config.requireEncryption, socketFactoryProvider(config)))
                        var connection: Connection? = null
                        var session: Session? = null
                        try {
                            connection = client.connect(config.host.removeSurrounding("[", "]"), config.port)
                            val authentication = AuthenticationContext(credentials.username, credentials.password, credentials.domain)
                            try { session = connection.authenticate(authentication) } finally { authentication.password.fill('\u0000') }
                            val authenticated = checkNotNull(session)
                            if (authenticated.isGuest || authenticated.isAnonymous) throw StorageException(
                                StorageError.AUTHENTICATION, "The server returned an unauthenticated guest session",
                            )
                            val encrypted = authenticated.shouldEncryptData()
                            val protection = validateProtection(authenticated.isSigningRequired, encrypted, config.requireEncryption)
                            val share = authenticated.connectShare(config.share)
                            if (share !is DiskShare) throw StorageException(StorageError.UNSUPPORTED, "This is not a disk share")
                            if (share.treeConnect.isDfsShare) throw StorageException(StorageError.UNSUPPORTED, "DFS shares are not supported")
                            // SMBJ's default resolver follows server symlinks even with DFS disabled. Use its
                            // public LOCAL resolver on our disk view so no referral can redirect credentials or paths.
                            val boundedShare = DiskShare(share.smbPath, share.treeConnect, PathResolver.LOCAL)
                            SmbStorageSession(config, paths, client, connection, authenticated, share, boundedShare,
                                protection, io)
                                .also { result = it; it.initialize() }
                        } catch (error: Exception) {
                            runCatching { session?.close() }
                            runCatching { connection?.close(true) }
                            client.close()
                            throw error
                        }
                    }
                }
            }
        } catch (error: Exception) {
            result?.close()
            throw mapSmbError(error)
        }
    }

    internal fun clientConfig(encryption: Boolean, socketFactory: SocketFactory = TimeoutSocketFactory()): SmbConfig = SmbConfig.builder()
        .withDialects(if (encryption) listOf(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
            else listOf(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2))
        .withSigningRequired(true)
        .withSigningEnabled(true)
        .withEncryptData(encryption)
        .withDfsEnabled(false)
        .withMultiProtocolNegotiate(false)
        .withAuthenticators(NtlmAuthenticator.Factory())
        .withSocketFactory(socketFactory)
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(35, TimeUnit.SECONDS)
        .withBufferSize(BUFFER_SIZE)
        .build()
}

private const val BUFFER_SIZE = 256 * 1024
internal const val UPLOAD_CHECKPOINT_BYTES = 4L * 1024 * 1024
private val READ_SHARES = setOf(FILE_SHARE_READ)
private val NO_FOLLOW = setOf(FILE_OPEN_REPARSE_POINT)

internal fun validateProtection(signed: Boolean, encrypted: Boolean, requireEncryption: Boolean): TransportProtection {
    if (requireEncryption && !encrypted) throw StorageException(StorageError.UNSUPPORTED,
        "This connection requires SMB3 transport encryption")
    // MS-SMB2 clears Session.SigningRequired for an encrypted session: its AEAD already supplies
    // integrity. Requiring both session flags would incorrectly reject encryption-mandatory NASes.
    if (!signed && !encrypted) throw StorageException(StorageError.UNSUPPORTED,
        "The server did not establish the required SMB integrity protection")
    return if (encrypted) TransportProtection.ENCRYPTED else TransportProtection.SIGNED
}

internal class SmbStorageSession(
    private val config: ConnectionConfig,
    private val paths: SmbPaths,
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val ownedShare: DiskShare,
    private val share: DiskShare,
    protection: TransportProtection,
    private val io: CoroutineDispatcher,
) : StorageSession, UploadCapability, UploadCleanupCapability, MutationCapability {
    private val closed = AtomicBoolean(false)
    private val openStreams = ConcurrentHashMap.newKeySet<Closeable>()
    override lateinit var root: RemoteEntry
        private set

    // These flags declare implemented operations, not an ACL probe. Every operation checks server ACLs.
    override val capabilities = StorageCapabilities(upload = true, createDirectory = true,
        rename = true, delete = true, rangeRead = true, resumableUpload = true, transportProtection = protection)

    fun initialize() {
        root = statBlocking(paths.ref(paths.root))
        if (!root.isDirectory) throw StorageException(StorageError.INVALID_CONFIGURATION, "The configured root must be a directory")
    }

    override fun list(directory: EntryRef): Flow<RemoteEntry> = flow {
        val path = paths.path(directory)
        val context = currentCoroutineContext()
        try {
            guardParents(path).use {
                openDirectory(path, setOf(FILE_LIST_DIRECTORY, FILE_READ_ATTRIBUTES), setOf(FILE_ATTRIBUTE_DIRECTORY),
                    READ_SHARES, FILE_OPEN, NO_FOLLOW).use { handle ->
                    assertRegular(handle)
                    val iterator = runInterruptible { handle.iterator() }
                    while (runInterruptible { iterator.hasNext() }) {
                        context.ensureActive()
                        val item = runInterruptible { iterator.next() }
                        if (item.fileName == "." || item.fileName == "..") continue
                        if (SmbPaths.isInternalName(item.fileName)) continue
                        SmbPaths.validateName(item.fileName)
                        emit(entry(SmbPaths.join(path, item.fileName), item))
                    }
                }
            }
        } catch (error: Exception) { throw mapSmbError(error) }
    }.flowOn(io)

    override suspend fun stat(ref: EntryRef): RemoteEntry = operation { statBlocking(ref) }

    private fun statBlocking(ref: EntryRef): RemoteEntry {
        val path = paths.path(ref)
        return guardParents(path).use {
            openEntry(path, setOf(FILE_READ_ATTRIBUTES)).use { handle ->
                entry(path, assertRegular(handle))
            }
        }
    }

    override suspend fun openRead(ref: EntryRef, offset: Long, expectedRevision: String?): InputStream {
        checkConfig(offset >= 0, "The read offset cannot be negative")
        var result: InputStream? = null
        try {
            return operation {
                val path = paths.path(ref)
                guardParents(path).use {
                    val file = share.openFile(path, setOf(FILE_READ_DATA, FILE_READ_ATTRIBUTES), setOf(FILE_ATTRIBUTE_NORMAL),
                        READ_SHARES, FILE_OPEN, NO_FOLLOW)
                    try {
                        val info = assertRegular(file)
                        checkRevision(info, expectedRevision)
                        SmbInputStream(file, offset, info.standardInformation.endOfFile) { openStreams.remove(it) }
                            .also { openStreams.add(it); result = it }
                    } catch (error: Exception) { file.closeNoWait(); throw error }
                }
            }
        } catch (error: Exception) { result?.close(); throw mapSmbError(error) }
    }

    override suspend fun createDirectory(parent: EntryRef, name: String): RemoteEntry = operation {
        val target = paths.child(parent, name)
        guardParents(target).use {
            openDirectory(target, setOf(FILE_LIST_DIRECTORY, FILE_READ_ATTRIBUTES), setOf(FILE_ATTRIBUTE_DIRECTORY),
                READ_SHARES, FILE_CREATE, NO_FOLLOW).use { entry(target, assertRegular(it)) }
        }
    }

    override suspend fun rename(ref: EntryRef, name: String, expectedRevision: String?): RemoteEntry = operation {
        val source = paths.path(ref)
        val parent = paths.parent(source) ?: throw StorageException(StorageError.PERMISSION, "The connection root cannot be renamed")
        val target = paths.child(parent, name)
        if (source == target) return@operation statBlocking(ref)
        guardParents(source).use {
            openEntry(source, setOf(DELETE, FILE_READ_ATTRIBUTES)).use { file ->
                val info = assertRegular(file)
                checkRevision(info, expectedRevision)
                try { file.rename(target, false) } catch (error: Exception) {
                    if (mapSmbError(error).error == StorageError.NETWORK) outcomeUnknown()
                    throw error
                }
                entry(target, file.fileInformation)
            }
        }
    }

    override suspend fun delete(ref: EntryRef, expectedRevision: String?) = operation {
        val path = paths.path(ref)
        if (paths.parent(path) == null) throw StorageException(StorageError.PERMISSION, "The connection root cannot be deleted")
        guardParents(path).use {
            val initialRevision = openEntry(path, setOf(DELETE, FILE_READ_ATTRIBUTES)).use { handle ->
                val info = assertRegular(handle)
                checkRevision(info, expectedRevision)
                if (!info.standardInformation.isDirectory) {
                    handle.deleteOnClose()
                    return@operation
                }
                revision(info)
            }
            // Only directory deletion needs FILE_LIST_DIRECTORY; deleting a file does not require
            // reading its content. Reopen with the proper access and reject a changed directory.
            openDirectory(path, setOf(DELETE, FILE_LIST_DIRECTORY, FILE_READ_ATTRIBUTES),
                setOf(FILE_ATTRIBUTE_DIRECTORY), READ_SHARES, FILE_OPEN, NO_FOLLOW).use { directory ->
                checkRevision(assertRegular(directory), expectedRevision ?: initialRevision)
                cleanCommittedReceipts(directory, path)
                // The server rejects nonempty directories, including children added during cleanup.
                directory.deleteOnClose()
            }
        }
    }

    /** Explicit empty-directory deletion may remove only our well-formed committed bookkeeping. */
    private fun cleanCommittedReceipts(directory: Directory, path: String) {
        val candidates = directory.iterator().asSequence().filter { it.fileName != "." && it.fileName != ".." }.toList()
        if (candidates.any { SmbPaths.receiptToken(it.fileName) == null ||
                it.fileAttributes and (FILE_ATTRIBUTE_DIRECTORY.value or FILE_ATTRIBUTE_REPARSE_POINT.value) != 0L }) {
            directoryNotEmpty()
        }
        val verified = candidates.map { item ->
            val candidatePath = SmbPaths.join(path, item.fileName)
            val token = checkNotNull(SmbPaths.receiptToken(item.fileName))
            val id = share.openFile(candidatePath, setOf(FILE_READ_DATA, FILE_READ_ATTRIBUTES),
                setOf(FILE_ATTRIBUTE_NORMAL), READ_SHARES, FILE_OPEN, NO_FOLLOW).use { file ->
                val info = assertRegular(file)
                requireCommittedReceipt(file, token)
                info.internalInformation.indexNumber
            }
            Triple(candidatePath, token, id)
        }
        // Validate the entire directory first. Unknown metadata or user files leave all receipts intact.
        verified.forEach { (candidatePath, token, id) ->
            share.openFile(candidatePath, setOf(DELETE, FILE_READ_DATA, FILE_READ_ATTRIBUTES),
                setOf(FILE_ATTRIBUTE_NORMAL), emptySet(), FILE_OPEN, NO_FOLLOW).use { file ->
                if (id == 0L || assertRegular(file).internalInformation.indexNumber != id) directoryNotEmpty()
                requireCommittedReceipt(file, token)
                file.deleteOnClose()
            }
        }
    }

    private fun requireCommittedReceipt(file: SmbFile, token: String) {
        val receipt = try { readReceipt(file, token) } catch (_: StorageException) { directoryNotEmpty() }
        if (receipt.phase != UploadReceipt.Phase.COMMITTED) directoryNotEmpty()
    }

    override suspend fun upload(request: UploadRequest, source: UploadSource, onProgress: (Long) -> Unit,
        onCommit: () -> Unit): RemoteEntry {
        val context = currentCoroutineContext()
        return operation {
            val target = paths.child(request.parent, request.name)
            val token = SmbPaths.operationToken(request.operationId, config.id, target)
            val markerPath = paths.internal(request.parent, token, "receipt")
            val payloadPath = paths.internal(request.parent, token, "part")
            val sourceVersion = source.version
            val expectedLength = source.length
            checkConfig(expectedLength == null || expectedLength >= 0, "The source length cannot be negative")
            val sourceVersionHash = sourceVersion?.let { sha256(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
            fun verifySource() {
                context.ensureActive()
                if (sourceVersion != null && source.currentVersion() != sourceVersion) sourceChanged()
            }
            verifySource()
            guardParents(target).use {
                val marker = openMarker(markerPath)
                marker.first.use { receiptFile ->
                    var receipt = if (marker.second) writeReceipt(receiptFile, UploadReceipt(token, UploadReceipt.Phase.PREPARING,
                        sourceVersionHash = sourceVersionHash, sourceLength = expectedLength ?: -1))
                    else readReceipt(receiptFile, token)
                    if (receipt.sourceVersionHash != sourceVersionHash) sourceChanged()
                    if (receipt.sourceLength >= 0 && receipt.sourceLength != expectedLength) sourceChanged()
                    fun verify(file: SmbFile, saved: UploadReceipt) = verifyUpload(source, saved,
                        revision(assertRegular(file)), { buffer, offset, start, count -> file.read(buffer, offset, start, count) },
                        { writeReceipt(receiptFile, it) }, { context.ensureActive() })

                    val existing = maybeOpen(target, setOf(FILE_READ_DATA, FILE_READ_ATTRIBUTES))
                    if (existing != null) existing.use { file ->
                        if (marker.second) {
                            // We exclusively created this exact receipt and have not created a payload.
                            // A definitive name conflict can abort that bookkeeping without touching
                            // another operation's recovery state or leaving a false unfinished upload.
                            receiptFile.deleteOnClose()
                            throw StorageException(StorageError.CONFLICT, "A file already exists with this name")
                        }
                        val info = assertRegular(file)
                        if (file !is SmbFile || receipt.phase !in setOf(UploadReceipt.Phase.READY, UploadReceipt.Phase.COMMITTED, UploadReceipt.Phase.RECONCILING) ||
                            receipt.fileId == 0L || receipt.fileId != info.internalInformation.indexNumber ||
                            receipt.length != info.standardInformation.endOfFile) outcomeUnknown()
                        if (expectedLength != null && expectedLength != receipt.length) sourceChanged()
                        if (receipt.phase != UploadReceipt.Phase.RECONCILING) {
                            receipt = writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.RECONCILING,
                                verificationOffset = 0, sourceHashState = "", remoteHashState = "", verificationRevision = ""))
                        }
                        try { receipt = verify(file, receipt).first }
                        catch (error: StorageException) {
                            if (error.error == StorageError.CORRUPT_DATA) outcomeUnknown()
                            throw error
                        }
                        verifySource()
                        // The file identity survives rename and the content digest proves the original
                        // payload is still present. Same name or same length alone is never sufficient.
                        onProgress(receipt.length)
                        onCommit()
                        writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.COMMITTED))
                        return@operation entry(target, info)
                    }
                    if (receipt.phase in setOf(UploadReceipt.Phase.COMMITTED, UploadReceipt.Phase.RECONCILING)) outcomeUnknown()
                    val payloadHandle = try {
                        share.openFile(payloadPath, setOf(FILE_WRITE_DATA, FILE_READ_DATA, FILE_READ_ATTRIBUTES, DELETE),
                            setOf(FILE_ATTRIBUTE_HIDDEN), emptySet(),
                            if (receipt.phase == UploadReceipt.Phase.PREPARING) FILE_CREATE else FILE_OPEN, NO_FOLLOW)
                    } catch (error: Exception) {
                        // A missing identified payload can also mean an unacknowledged rename.
                        // Retain the operation for reconciliation instead of inventing a new payload.
                        if (receipt.phase != UploadReceipt.Phase.PREPARING && mapSmbError(error).error == StorageError.NOT_FOUND) outcomeUnknown()
                        throw error
                    }
                    payloadHandle.use { payload ->
                        val identity = assertRegular(payload).internalInformation.indexNumber
                        if (identity == 0L) outcomeUnknown()
                        if (receipt.phase == UploadReceipt.Phase.PREPARING) {
                            receipt = writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.WRITING,
                                fileId = identity, length = 0, digest = sha256(byteArrayOf())))
                        } else if (receipt.fileId != identity || payload.length < receipt.length) outcomeUnknown()
                        if (receipt.phase == UploadReceipt.Phase.READY) {
                            receipt = writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.VERIFYING,
                                verificationOffset = 0, sourceHashState = "", remoteHashState = "", verificationRevision = ""))
                        }
                        var copied = receipt.length
                        if (receipt.phase !in setOf(UploadReceipt.Phase.VERIFYING, UploadReceipt.Phase.READY)) {
                            val verified = verify(payload, receipt)
                            receipt = verified.first
                            val digest = verified.second
                            val buffer = ByteArray(BUFFER_SIZE)
                            verifySource()
                            // A write may have reached the server without its checkpoint. Discard only
                            // that unconfirmed tail on this exclusively opened, identified payload.
                            if (payload.length != copied) {
                                payload.setLength(copied)
                                payload.flush()
                                if (payload.length != copied) throw StorageException(StorageError.CORRUPT_DATA,
                                    "The server did not truncate the unconfirmed upload tail")
                            }
                            onProgress(copied)
                            source.open(copied).use { input ->
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    if (expectedLength != null && copied + count > expectedLength) sourceChanged()
                                    val written = payload.write(buffer, copied, 0, count)
                                    if (written != count.toLong()) throw StorageException(StorageError.CORRUPT_DATA, "The server accepted an incomplete write")
                                    digest.update(buffer, 0, count)
                                    copied += count
                                    if (copied - receipt.length >= UPLOAD_CHECKPOINT_BYTES) {
                                        payload.flush()
                                        receipt = writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.WRITING,
                                            length = copied, digest = digest.snapshot(), verificationOffset = 0,
                                            sourceHashState = "", remoteHashState = "", verificationRevision = ""))
                                        onProgress(copied)
                                    }
                                }
                            }
                            verifySource()
                            if (expectedLength != null && copied != expectedLength) sourceChanged()
                            payload.flush()
                            if (payload.length != copied) throw StorageException(StorageError.CORRUPT_DATA, "The uploaded file has an unexpected length")
                            // Persist the complete payload before its potentially multi-slice verification.
                            receipt = writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.VERIFYING,
                                length = copied, digest = digest.snapshot(), verificationOffset = 0,
                                sourceHashState = "", remoteHashState = "", verificationRevision = ""))
                        }
                        if (payload.length != receipt.length) throw StorageException(StorageError.CORRUPT_DATA,
                            "The uploaded file has an unexpected length")
                        if (expectedLength != null && receipt.length != expectedLength) sourceChanged()
                        receipt = verify(payload, receipt).first
                        verifySource()
                        receipt = writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.READY))
                        onProgress(copied)
                        verifySource()
                        onCommit()
                        try { payload.rename(target, false) } catch (error: Exception) {
                            if (mapSmbError(error).error == StorageError.NETWORK) outcomeUnknown()
                            throw error
                        }
                        try {
                            val info = assertRegular(payload)
                            writeReceipt(receiptFile, receipt.copy(phase = UploadReceipt.Phase.COMMITTED))
                            return@operation entry(target, info)
                        } catch (error: Exception) {
                            if (mapSmbError(error).error == StorageError.NETWORK) outcomeUnknown()
                            throw error
                        }
                    }
                }
            }
        }
    }

    override suspend fun cleanupUpload(request: UploadRequest, completed: Boolean) = operation {
        val target = paths.child(request.parent, request.name)
        val token = SmbPaths.operationToken(request.operationId, config.id, target)
        val markerPath = paths.internal(request.parent, token, "receipt")
        val payloadPath = paths.internal(request.parent, token, "part")
        guardParents(target).use {
            val marker = try {
                share.openFile(markerPath, setOf(FILE_READ_DATA, FILE_READ_ATTRIBUTES, DELETE),
                    setOf(FILE_ATTRIBUTE_NORMAL), emptySet(), FILE_OPEN, NO_FOLLOW)
            } catch (error: Exception) {
                if (mapSmbError(error).error != StorageError.NOT_FOUND) throw error
                // An absent receipt is idempotent only if no unidentifiable payload remains.
                maybeOpen(payloadPath, setOf(FILE_READ_ATTRIBUTES))?.use { outcomeUnknown() }
                return@operation
            }
            marker.use { receiptFile ->
                assertRegular(receiptFile)
                val receipt = readReceipt(receiptFile, token)
                if (completed) {
                    if (receipt.phase != UploadReceipt.Phase.COMMITTED) outcomeUnknown()
                    maybeOpen(payloadPath, setOf(FILE_READ_ATTRIBUTES))?.use { outcomeUnknown() }
                } else {
                    if (receipt.phase == UploadReceipt.Phase.COMMITTED) outcomeUnknown()
                    maybeOpen(target, setOf(FILE_READ_ATTRIBUTES))?.use { outcomeUnknown() }
                    val payload = try {
                        share.openFile(payloadPath, setOf(FILE_READ_ATTRIBUTES, DELETE),
                            setOf(FILE_ATTRIBUTE_NORMAL), emptySet(), FILE_OPEN, NO_FOLLOW)
                    } catch (error: Exception) {
                        if (mapSmbError(error).error != StorageError.NOT_FOUND) throw error
                        null
                    }
                    payload?.use {
                        if (receipt.fileId == 0L || assertRegular(it).internalInformation.indexNumber != receipt.fileId) outcomeUnknown()
                        it.deleteOnClose()
                    }
                }
                // If deletion's reply is lost, retry handles an absent payload or receipt safely.
                receiptFile.deleteOnClose()
            }
        }
    }

    private fun openMarker(path: String): Pair<SmbFile, Boolean> {
        val access = setOf(FILE_READ_DATA, FILE_WRITE_DATA, FILE_READ_ATTRIBUTES, DELETE)
        try {
            return share.openFile(path, access, setOf(FILE_ATTRIBUTE_HIDDEN), emptySet(), FILE_CREATE, NO_FOLLOW) to true
        } catch (error: Exception) {
            if (mapSmbError(error).error != StorageError.CONFLICT) throw error
            return share.openFile(path, access, setOf(FILE_ATTRIBUTE_NORMAL), emptySet(), FILE_OPEN, NO_FOLLOW).also { assertRegular(it) } to false
        }
    }

    private fun readReceipt(file: SmbFile, token: String): UploadReceipt {
        val length = file.length
        if (length !in UploadReceipt.MAX_BYTES.toLong()..UploadReceipt.JOURNAL_BYTES.toLong()) outcomeUnknown()
        val bytes = ByteArray(length.toInt())
        var position = 0
        while (position < bytes.size) {
            val count = file.read(bytes, position.toLong(), position, bytes.size - position)
            if (count <= 0) outcomeUnknown()
            position += count
        }
        return UploadReceipt.decodeJournal(bytes, token)
    }

    private fun writeReceipt(file: SmbFile, receipt: UploadReceipt): UploadReceipt {
        // The first record lives in slot zero. Migration from a v1 record writes slot one,
        // leaving the original readable even if the process dies while extending the journal.
        val next = if (file.length == 0L) receipt.copy(sequence = 0)
            else receipt.copy(sequence = Math.addExact(receipt.sequence, 1))
        val bytes = next.encode()
        if (file.write(bytes, (next.sequence % 2) * UploadReceipt.MAX_BYTES) != bytes.size.toLong()) outcomeUnknown()
        file.flush()
        return next
    }

    private fun maybeOpen(path: String, access: Set<AccessMask>): DiskEntry? = try {
        openEntry(path, access)
    } catch (error: Exception) {
        if (mapSmbError(error).error == StorageError.NOT_FOUND) null else throw error
    }

    private fun openEntry(path: String, access: Set<AccessMask>): DiskEntry =
        share.open(path, access, emptySet(), READ_SHARES, FILE_OPEN, NO_FOLLOW)

    private fun openDirectory(path: String, access: Set<AccessMask>, attributes: Set<FileAttributes>,
        sharing: Set<SMB2ShareAccess>, disposition: SMB2CreateDisposition,
        options: Set<SMB2CreateOptions>): Directory {
        // SMBJ 0.15 openDirectory() opts into lease caching. This adapter owns short-lived handles
        // explicitly, so use the generic no-lease open to keep network-loss cleanup bounded.
        val opened = share.open(path, access, attributes + FILE_ATTRIBUTE_DIRECTORY, sharing,
            disposition, options + FILE_DIRECTORY_FILE)
        if (opened is Directory) return opened
        opened.closeNoWait()
        throw StorageException(StorageError.UNSUPPORTED, "The remote entry is not a directory")
    }

    /** Hold every ancestor against replacement while opening or mutating a descendant. */
    private fun guardParents(path: String): Closeable {
        check(!closed.get()) { "The SMB session is closed" }
        val handles = mutableListOf<DiskEntry>()
        try {
            val segments = path.split('\\').dropLast(1)
            var current = ""
            // The share root is already a bounded tree connect. Each subsequent component must be
            // opened without following reparse points and remain open until the target is resolved.
            for (segment in segments) {
                current = SmbPaths.join(current, segment)
                val handle = openDirectory(current, setOf(FILE_LIST_DIRECTORY, FILE_READ_ATTRIBUTES),
                    setOf(FILE_ATTRIBUTE_DIRECTORY), READ_SHARES, FILE_OPEN, NO_FOLLOW)
                handles += handle
                assertRegular(handle)
            }
            return Closeable { handles.asReversed().forEach { runCatching { it.closeNoWait() } } }
        } catch (error: Exception) {
            handles.asReversed().forEach { runCatching { it.closeNoWait() } }
            throw error
        }
    }

    private fun assertRegular(handle: DiskEntry): FileAllInformation = handle.fileInformation.also {
        if (it.basicInformation.fileAttributes and FILE_ATTRIBUTE_REPARSE_POINT.value != 0L) throw StorageException(
            StorageError.UNSUPPORTED, "Symbolic links and reparse points are not followed",
        )
    }

    private fun checkRevision(info: FileAllInformation, expected: String?) {
        if (expected != null && expected != revision(info)) sourceChanged()
    }

    private fun entry(path: String, info: FileAllInformation): RemoteEntry = RemoteEntry(
        ref = paths.ref(path), name = if (path == paths.root) config.name else path.substringAfterLast('\\'),
        isDirectory = info.standardInformation.isDirectory, parent = paths.parent(path),
        size = if (info.standardInformation.isDirectory) null else info.standardInformation.endOfFile,
        modifiedAtEpochMillis = info.basicInformation.lastWriteTime.toEpochMillis(), revision = revision(info),
    )

    private fun entry(path: String, info: FileIdBothDirectoryInformation): RemoteEntry {
        val directory = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return RemoteEntry(paths.ref(path), info.fileName, directory, paths.parent(path),
            if (directory) null else info.endOfFile, info.lastWriteTime.toEpochMillis(),
            revision = revision(if (directory) 0 else info.endOfFile,
                info.lastWriteTime.windowsTimeStamp, info.creationTime.windowsTimeStamp))
    }

    private suspend fun <T> operation(block: () -> T): T = withContext(io) {
        runInterruptible { smbCall(block) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        openStreams.toList().forEach { runCatching { it.close() } }
        openStreams.clear()
        // Each storage session owns this connection. Force-disconnect releases every server handle
        // and makes close bounded when the network has vanished; it never deletes remote data.
        runCatching { connection.close(true) }
        client.close()
    }
}

private fun revision(info: FileAllInformation): String = revision(if (info.standardInformation.isDirectory) 0 else info.standardInformation.endOfFile,
    info.basicInformation.lastWriteTime.windowsTimeStamp, info.basicInformation.creationTime.windowsTimeStamp)

// A metadata revision is an optimistic change detector, not a cryptographic version. Some SMB
// servers return zero directory-enumeration IDs, so IDs cannot be compared consistently to stat.
private fun revision(length: Long, modified: Long, created: Long): String = "$length:$modified:$created"

private fun sourceChanged(): Nothing = throw StorageException(StorageError.SOURCE_CHANGED,
    "The source changed. Refresh the file and start a new transfer.")

private fun directoryNotEmpty(): Nothing = throw StorageException(StorageError.CONFLICT,
    "The directory contains files or unfinished recovery data and was retained")

private class SmbInputStream(
    private val file: SmbFile,
    private var position: Long,
    private val length: Long,
    private val onClose: (Closeable) -> Unit,
) : InputStream() {
    private val closed = AtomicBoolean(false)

    override fun read(): Int {
        val value = ByteArray(1)
        return if (read(value, 0, 1) < 0) -1 else value[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (offset < 0 || count < 0 || offset > buffer.size - count) throw IndexOutOfBoundsException()
        if (closed.get()) throw IOException("The remote read handle is closed")
        if (count == 0) return 0
        if (position >= length) return -1
        return smbCall {
            val requested = minOf(count.toLong(), length - position).toInt()
            val read = file.read(buffer, position, offset, requested)
            if (read <= 0) throw StorageException(StorageError.SOURCE_CHANGED, "The remote file ended before its reported length")
            position += read
            read
        }
    }

    override fun skip(count: Long): Long = minOf(count.coerceAtLeast(0), (length - position).coerceAtLeast(0)).also { position += it }
    // No content is buffered locally. Reporting the entire remote length here can make consumers
    // allocate gigabytes and incorrectly implies those bytes can be read without blocking.
    override fun available(): Int = 0

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { file.closeNoWait() } finally { onClose(this) }
        }
    }
}

private class TimeoutSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket()
    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), InetSocketAddress(local, localPort))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), InetSocketAddress(local, localPort))

    private fun connect(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket = Socket().also { socket ->
        try {
            if (local != null) socket.bind(local)
            socket.connect(remote, 15_000)
        } catch (error: Exception) { socket.close(); throw error }
    }
}
