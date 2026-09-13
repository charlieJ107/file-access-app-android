package space.zhuoling.fileaccess.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import space.zhuoling.fileaccess.core.data.ConnectionRepository
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.core.transfer.NetworkPolicy

sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data class Ready(val image: ThumbnailImage) : ThumbnailState
    data class Unavailable(val reason: ThumbnailFailure) : ThumbnailState
}

@Singleton
class ThumbnailRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteAccess,
    private val connections: ConnectionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val diskLock = Any()
    private val root = File(context.cacheDir, "thumbnails")
    private val networkPolicy = NetworkPolicy(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var selectedNetwork = networkPolicy.selectedNetworkHandle()
    @Volatile private var metered = readMetered(selectedNetwork)
    private val memory = object : LruCache<String, ThumbnailImage>(minOf(32 * MIB, Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ThumbnailImage): Int = value.bitmap.allocationByteCount
    }
    private data class Flight(val work: Deferred<ThumbnailState>, val ref: EntryRef, var subscribers: Int = 0)
    private val flights = mutableMapOf<String, Flight>()
    private val connectionSlots = mutableMapOf<String, Semaphore>()
    private val slots = Semaphore(2)
    private val videoSlot = Semaphore(1)
    private val videoDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val failures = LinkedHashMap<String, Pair<ThumbnailState.Unavailable, Long>>()
    private val blocked = mutableSetOf<Pair<String, Long>>()
    private val cooldowns = mutableMapOf<String, Long>()
    private val budgets = LinkedHashMap<String, ThumbnailBudget>()
    private var invalidations = 0
    private val _generation = MutableStateFlow(0L)
    val generation = _generation.asStateFlow()

    init {
        scope.launch {
            val callback = object : ConnectivityManager.NetworkCallback() {
                private fun update() {
                    val handle = networkPolicy.selectedNetworkHandle()
                    val nowMetered = readMetered(handle)
                    synchronized(lock) {
                        if (handle != selectedNetwork || nowMetered != metered) {
                            selectedNetwork = handle; metered = nowMetered
                            cooldowns.clear(); failures.clear(); _generation.value++
                        }
                    }
                }
                override fun onAvailable(network: Network) = update()
                override fun onLost(network: Network) = update()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
            }
            connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback)
            try { awaitCancellation() } finally { connectivity.unregisterNetworkCallback(callback) }
        }
        scope.launch {
            synchronized(diskLock) { root.walkTopDown().filter { it.isFile && it.extension == "part" }.forEach { it.delete() } }
            var previous: Map<String, Long>? = null
            connections.observeAll().collect { configs ->
                val current = configs.associate { it.id to it.revision }
                previous?.forEach { (id, revision) -> if (current[id] != revision) invalidateConnection(id) }
                // Remove disk namespaces belonging to deleted connections after process restart.
                synchronized(diskLock) {
                    val names = current.keys.map(::namespace).toSet()
                    root.listFiles()?.filter { it.isDirectory && it.name != "tmp" && it.name !in names }?.forEach { it.deleteRecursively() }
                }
                previous = current
            }
        }
    }

    fun budget(directoryKey: String): ThumbnailBudget = synchronized(lock) {
        budgets.getOrPut(directoryKey) { ThumbnailBudget() }.also {
            // Browsing state has a finite lifetime; active subscribers retain their budget object.
            while (budgets.size > 64) budgets.remove(budgets.keys.first())
        }
    }

    fun isMetered(): Boolean = metered

    private fun readMetered(handle: Long?): Boolean = handle == null || connectivity
        .getNetworkCapabilities(Network.fromNetworkHandle(handle))?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

    internal suspend fun shutdown() { scope.coroutineContext.job.cancelAndJoin() }

    fun observe(request: ThumbnailRequest, budget: ThumbnailBudget, allowMetered: Boolean): Flow<ThumbnailState> = flow {
        emit(ThumbnailState.Loading)
        if (mediaKind(request.entry) !in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
            emit(ThumbnailState.Unavailable(ThumbnailFailure.UNSUPPORTED)); return@flow
        }
        // Separate policy subscriptions: an automatically deferred request cannot suppress an explicit one.
        val key = request.key() + ":" + allowMetered
        val flight = synchronized(lock) {
            val existing = flights[key]?.takeIf { !it.work.isCancelled }
            if (existing != null) existing.also { it.subscribers++ }
            else if (flights.size >= 64) null
            else Flight(scope.async(start = CoroutineStart.LAZY) { load(request, budget, allowMetered) }, request.entry.ref, 1)
                .also { flights[key] = it; it.work.start() }
        }
        if (flight == null) { emit(ThumbnailState.Unavailable(ThumbnailFailure.LIMIT)); return@flow }
        try { emit(flight.work.await()) }
        finally {
            synchronized(lock) {
                flight.subscribers--
                if (flight.subscribers == 0) {
                    flight.work.cancel()
                    // Keep the slot until blocking IO/decoder has really returned.
                    flight.work.invokeOnCompletion { synchronized(lock) { if (flights[key] === flight) flights.remove(key) } }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun retry() = synchronized(lock) { failures.clear(); cooldowns.clear(); _generation.value++ }

    suspend fun clearCache() {
        synchronized(lock) { invalidations++; memory.evictAll(); failures.clear(); _generation.value++; flights.values.forEach { it.work.cancel() } }
        try { withContext(Dispatchers.IO + NonCancellable) { synchronized(diskLock) { root.listFiles()?.filter { it.name != "tmp" }?.forEach { it.deleteRecursively() } } } }
        finally { synchronized(lock) { invalidations--; _generation.value++ } }
    }

    suspend fun invalidateEntry(ref: EntryRef) {
        synchronized(lock) {
            invalidations++; memory.evictAll(); failures.clear(); _generation.value++
            flights.values.filter { it.ref == ref }.forEach { it.work.cancel() }
        }
        try { withContext(Dispatchers.IO + NonCancellable) { synchronized(diskLock) { entryDirectory(ref).deleteRecursively() } } }
        finally { synchronized(lock) { invalidations--; _generation.value++ } }
    }

    suspend fun invalidateConnection(id: String) {
        synchronized(lock) {
            invalidations++; memory.evictAll(); failures.clear(); blocked.removeAll { it.first == id }; cooldowns.remove(id)
            _generation.value++
            flights.values.filter { it.ref.connectionId == id }.forEach { it.work.cancel() }
        }
        try { withContext(Dispatchers.IO + NonCancellable) { synchronized(diskLock) { File(root, namespace(id)).deleteRecursively() } } }
        finally { synchronized(lock) { invalidations--; _generation.value++ } }
    }

    private suspend fun load(request: ThumbnailRequest, budget: ThumbnailBudget, allowMetered: Boolean): ThumbnailState {
        val key = request.key()
        val compatibleKeys = listOf(128, 256, 512).filter { it >= thumbnailEdge(request.edgePx) }.map { request.copy(edgePx = it).key() }
        val id = request.entry.ref.connectionId
        val epoch = generation.value
        suspend fun checkConfiguration() {
            currentCoroutineContext().ensureActive()
            if (connections.get(id)?.revision != request.connectionRevision || epoch != generation.value || synchronized(lock) { invalidations > 0 }) {
                throw CancellationException("Thumbnail invalidated")
            }
        }
        try {
            checkConfiguration()
            synchronized(lock) {
                if (id to request.connectionRevision in blocked) return ThumbnailState.Unavailable(ThumbnailFailure.AUTH)
                compatibleKeys.firstNotNullOfOrNull { memory.get(it) }?.let { return ThumbnailState.Ready(it) }
                failures[key]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
            }
            if (request.entry.revision != null) for (compatibleKey in compatibleKeys) readCache(request.entry.ref, compatibleKey)?.let { cached ->
                checkConfiguration()
                synchronized(lock) { if (generation.value == epoch) memory.put(compatibleKey, cached) }
                return ThumbnailState.Ready(cached)
            }
            if (budget.available() <= 0 || (mediaKind(request.entry) == MediaKind.IMAGE && (request.entry.size ?: 0) > 16 * MIB)) {
                return ThumbnailState.Unavailable(ThumbnailFailure.LIMIT)
            }
            if (!allowMetered && isMetered()) return ThumbnailState.Unavailable(ThumbnailFailure.METERED)
            if (synchronized(lock) { (cooldowns[id] ?: 0L) > System.currentTimeMillis() }) return ThumbnailState.Unavailable(ThumbnailFailure.NETWORK)
            val connectionSlot = synchronized(lock) { connectionSlots.getOrPut(id) { Semaphore(1) } }
            suspend fun generate(): ThumbnailState = slots.withPermit global@ {
                checkConfiguration()
                synchronized(lock) {
                    if (id to request.connectionRevision in blocked) return@global ThumbnailState.Unavailable(ThumbnailFailure.AUTH)
                    compatibleKeys.firstNotNullOfOrNull { memory.get(it) }?.let { return@global ThumbnailState.Ready(it) }
                    if ((cooldowns[id] ?: 0L) > System.currentTimeMillis()) return@global ThumbnailState.Unavailable(ThumbnailFailure.NETWORK)
                }
                if (!allowMetered && isMetered()) return@global ThumbnailState.Unavailable(ThumbnailFailure.METERED)
                val temporary = File(root, "tmp").apply { mkdirs() }
                if (root.usableSpace < 512 * MIB + 32 * MIB) {
                    trimCache(0)
                    if (root.usableSpace < 512 * MIB + 32 * MIB) return@global ThumbnailState.Unavailable(ThumbnailFailure.SPACE)
                }
                val image = remote.open(id, request.connectionRevision).use { session ->
                    coroutineScope {
                        // A separate coroutine closes network handles when cancellation arrives during a blocking read.
                        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { session.close() }
                        }
                        try {
                            if (mediaKind(request.entry) == MediaKind.VIDEO) {
                                withContext(videoDispatcher) { decodeThumbnail(request, session, budget, temporary) { if (!allowMetered && isMetered()) throw ThumbnailMeteredException() } }
                            } else decodeThumbnail(request, session, budget, temporary) { if (!allowMetered && isMetered()) throw ThumbnailMeteredException() }
                        } finally { closer.cancelAndJoin() }
                    }
                }
                checkConfiguration()
                if (request.entry.revision != null) writeCache(request.entry.ref, key, image, epoch)
                checkConfiguration()
                synchronized(lock) { if (generation.value == epoch) memory.put(key, image) }
                ThumbnailState.Ready(image)
            }
            // Waiting for the single video decoder must not occupy the other global IO slot.
            return connectionSlot.withPermit {
                if (mediaKind(request.entry) == MediaKind.VIDEO) videoSlot.withPermit { generate() } else generate()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val reason = when (failure) {
                is ThumbnailMeteredException -> ThumbnailFailure.METERED
                is IllegalArgumentException -> ThumbnailFailure.UNSUPPORTED
                is ThumbnailLimitException -> ThumbnailFailure.LIMIT
                is UnsupportedOperationException, is android.graphics.ImageDecoder.DecodeException -> ThumbnailFailure.UNSUPPORTED
                is StorageException -> when (failure.error) {
                    StorageError.AUTHENTICATION, StorageError.PERMISSION -> ThumbnailFailure.AUTH
                    StorageError.SOURCE_CHANGED, StorageError.NOT_FOUND, StorageError.INVALID_CONFIGURATION -> ThumbnailFailure.CHANGED
                    StorageError.UNSUPPORTED, StorageError.CORRUPT_DATA -> ThumbnailFailure.UNSUPPORTED
                    else -> ThumbnailFailure.NETWORK
                }
                else -> ThumbnailFailure.NETWORK
            }
            val result = ThumbnailState.Unavailable(reason)
            synchronized(lock) {
                failures[key] = result to (System.currentTimeMillis() + if (reason == ThumbnailFailure.NETWORK) 30_000 else 300_000)
                while (failures.size > 512) failures.remove(failures.keys.first())
                if (reason == ThumbnailFailure.NETWORK) cooldowns[id] = System.currentTimeMillis() + 30_000
                if (reason == ThumbnailFailure.AUTH) {
                    blocked.add(id to request.connectionRevision); memory.evictAll(); _generation.value++
                }
            }
            if (reason == ThumbnailFailure.AUTH) synchronized(diskLock) { File(root, namespace(id)).deleteRecursively() }
            return result
        }
    }

    private fun readCache(ref: EntryRef, key: String): ThumbnailImage? = synchronized(diskLock) {
        val file = File(entryDirectory(ref), "$key.thumb")
        if (!file.exists()) return null
        if (file.length() > 2 * MIB) { file.delete(); return null }
        if (System.currentTimeMillis() - file.lastModified() > 7 * 24 * 60 * 60 * 1000L) { file.delete(); return null }
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val duration = input.readLong().takeIf { it >= 0 }
                input.mark((2 * MIB).toInt())
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) throw java.io.IOException("Invalid dimensions")
                input.reset()
                val bitmap = BitmapFactory.decodeStream(input) ?: throw java.io.IOException("Invalid thumbnail")
                if (bitmap.width > 512 || bitmap.height > 512) { bitmap.recycle(); throw java.io.IOException("Invalid dimensions") }
                file.setLastModified(System.currentTimeMillis())
                ThumbnailImage(bitmap, duration)
            }
        } catch (_: Exception) { file.delete(); null }
    }

    private fun writeCache(ref: EntryRef, key: String, image: ThumbnailImage, epoch: Long): Unit = synchronized(diskLock) {
        if (generation.value != epoch) return
        val directory = entryDirectory(ref).apply { mkdirs() }
        val temporary = File.createTempFile("thumb-", ".part", directory)
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeLong(image.durationMillis ?: -1)
                if (!image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw java.io.IOException("Thumbnail encoding failed")
            }
            if (generation.value != epoch) return
            trimCache(128 * MIB - temporary.length())
            if (!temporary.renameTo(File(directory, "$key.thumb"))) throw java.io.IOException("Thumbnail publish failed")
        } finally { temporary.delete() }
    }

    private fun trimCache(maximum: Long) = synchronized(diskLock) {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "thumb" }.toList().sortedBy { it.lastModified() }
        var used = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count < 2048 && used <= maximum && System.currentTimeMillis() - file.lastModified() <= 7 * 24 * 60 * 60 * 1000L) continue
            val size = file.length()
            if (file.delete()) {
                used -= size; count--
                file.parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
            }
        }
    }

    private fun namespace(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun entryDirectory(ref: EntryRef): File = File(File(root, namespace(ref.connectionId)), namespace(entryKey(ref)))
}
