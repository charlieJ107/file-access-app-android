package space.zhuoling.fileaccess.preview

import java.util.Locale
import kotlinx.serialization.Serializable
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.thumbnail.MediaKind
import space.zhuoling.fileaccess.thumbnail.mediaKind

@Serializable
enum class PreviewGroup { VISUAL, AUDIO, SINGLE }

/** Only the recovery recipe belongs in saved state; directory contents stay in memory. */
@Serializable
data class PreviewSessionRequest(
    val sessionId: String,
    val connectionId: String,
    val directoryId: String,
    val currentId: String,
    val query: String,
    val sort: Int,
    val group: PreviewGroup,
    val connectionRevision: Long?,
) {
    val currentRef: EntryRef get() = EntryRef(connectionId, currentId)
}

data class PreviewSessionState(
    val sessionId: String = "",
    val items: List<RemoteEntry> = emptyList(),
    val currentIndex: Int = 0,
    val partial: Boolean = false,
    val restoring: Boolean = false,
    val error: String? = null,
    val connectionRevision: Long? = null,
) {
    val current: RemoteEntry? get() = items.getOrNull(currentIndex)
}

fun previewGroup(entry: RemoteEntry): PreviewGroup = when (mediaKind(entry)) {
    MediaKind.IMAGE, MediaKind.VIDEO -> PreviewGroup.VISUAL
    MediaKind.AUDIO -> PreviewGroup.AUDIO
    else -> PreviewGroup.SINGLE
}

/** Shared by the browser and process restoration, including deterministic sort ties. */
fun sortAndFilterEntries(entries: List<RemoteEntry>, query: String, sort: Int): List<RemoteEntry> {
    val order = when (sort) {
        1 -> compareByDescending<RemoteEntry> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
        2 -> compareByDescending<RemoteEntry> { it.size ?: -1L }
        else -> compareBy { it.name.lowercase(Locale.ROOT) }
    }
    return entries.filter { it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.then(order).thenBy { it.name }
            .thenBy { it.ref.connectionId }.thenBy { it.ref.opaqueId })
}

/** Preserve the supplied visible order and identify files by ref, never by display name. */
internal fun previewQueue(
    entries: List<RemoteEntry>, selected: EntryRef, group: PreviewGroup,
): List<RemoteEntry> = entries.filter { entry ->
    !entry.isDirectory && entry.ref.connectionId == selected.connectionId &&
        if (group == PreviewGroup.SINGLE) entry.ref == selected else previewGroup(entry) == group
}.distinctBy { it.ref }

internal fun previewSessionState(
    request: PreviewSessionRequest, visibleEntries: List<RemoteEntry>, partial: Boolean,
): PreviewSessionState {
    val items = previewQueue(visibleEntries, request.currentRef, request.group)
    val index = items.indexOfFirst { it.ref == request.currentRef }
    if (index < 0) throw StorageException(StorageError.NOT_FOUND, "Selected file is no longer in the filtered directory")
    return PreviewSessionState(request.sessionId, items, index, partial, connectionRevision = request.connectionRevision)
}

/** A replaced remote file must not inherit the previous file's playback offset. */
internal class PreviewPositions {
    private data class Version(val ref: EntryRef, val revision: String?, val size: Long?, val modifiedAt: Long?)
    private val values = mutableMapOf<Version, Long>()
    private fun version(entry: RemoteEntry) = Version(entry.ref, entry.revision, entry.size, entry.modifiedAtEpochMillis)
    fun get(entry: RemoteEntry): Long = values[version(entry)] ?: 0L
    fun save(entry: RemoteEntry, positionMillis: Long) { values[version(entry)] = positionMillis.coerceAtLeast(0) }
    fun clear() { values.clear() }
}
