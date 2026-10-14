package space.zhuoling.fileaccess.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.userMessage

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val remote: RemoteAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(PreviewSessionState())
    val state = _state.asStateFlow()
    private var request: PreviewSessionRequest? = null
    private var restoration: Job? = null
    private val positions = PreviewPositions()

    fun start(
        directory: EntryRef, selected: RemoteEntry, visibleEntries: List<RemoteEntry>,
        query: String, sort: Int, partial: Boolean, connectionRevision: Long?,
    ): PreviewSessionRequest {
        val next = PreviewSessionRequest(UUID.randomUUID().toString(), directory.connectionId, directory.opaqueId,
            selected.ref.opaqueId, query, sort, previewGroup(selected), connectionRevision)
        val snapshot = previewSessionState(next, visibleEntries, partial)
        restoration?.cancel()
        positions.clear()
        request = next
        persist(next)
        _state.value = snapshot
        return next
    }

    fun open(route: PreviewSessionRequest) {
        if (request?.sessionId == route.sessionId) return
        // The route retains its original item. Saved state tracks the last settled page.
        val restored = savedState.get<String>(SAVED_REQUEST)?.let { value ->
            runCatching { Json.decodeFromString<PreviewSessionRequest>(value) }.getOrNull()
        }?.takeIf { it.sessionId == route.sessionId } ?: route
        request = restored
        positions.clear()
        restore(restored)
    }

    fun select(index: Int) {
        val current = _state.value
        val entry = current.items.getOrNull(index) ?: return
        if (current.restoring || index == current.currentIndex) return
        _state.value = current.copy(currentIndex = index)
        request?.copy(currentId = entry.ref.opaqueId)?.let { request = it; persist(it) }
    }

    fun position(entry: RemoteEntry): Long = positions.get(entry)

    fun savePosition(entry: RemoteEntry, positionMillis: Long) {
        if (_state.value.items.any { it.ref == entry.ref }) positions.save(entry, positionMillis)
    }

    fun retry() { request?.let(::restore) }

    fun close() {
        restoration?.cancel()
        request = null
        positions.clear()
        savedState.remove<String>(SAVED_REQUEST)
        _state.value = PreviewSessionState()
    }

    private fun persist(value: PreviewSessionRequest) {
        savedState[SAVED_REQUEST] = Json.encodeToString(value)
    }

    private fun restore(value: PreviewSessionRequest) {
        restoration?.cancel()
        persist(value)
        _state.value = PreviewSessionState(sessionId = value.sessionId, restoring = true,
            connectionRevision = value.connectionRevision)
        restoration = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    remote.open(value.connectionId, value.connectionRevision).use { session ->
                        // Confirm identity before reconstructing the queue; never silently select a neighbour.
                        val selected = session.stat(value.currentRef)
                        if (value.group == PreviewGroup.SINGLE) {
                            previewSessionState(value, listOf(selected), partial = false)
                        } else {
                            val directory = if (value.directoryId.isEmpty()) session.root.ref
                                else EntryRef(value.connectionId, value.directoryId)
                            val entries = mutableListOf<RemoteEntry>()
                            var listingFailure: Exception? = null
                            try {
                                session.list(directory).collect { entry ->
                                    currentCoroutineContext().ensureActive()
                                    entries.add(entry)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { listingFailure = error }
                            // A failed listing may not have reached the selected file yet.
                            if (listingFailure != null && entries.none { it.ref == selected.ref }) entries.add(selected)
                            previewSessionState(value, sortAndFilterEntries(entries, value.query, value.sort),
                                partial = listingFailure != null).copy(error = listingFailure?.userMessage())
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                _state.value = restored
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.value = PreviewSessionState(sessionId = value.sessionId, error = error.userMessage(),
                    connectionRevision = value.connectionRevision)
            }
        }
    }

    private companion object { const val SAVED_REQUEST = "preview.request" }
}
