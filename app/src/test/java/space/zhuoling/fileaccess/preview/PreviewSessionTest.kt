package space.zhuoling.fileaccess.preview

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException

class PreviewSessionTest {
    private fun entry(id: String, name: String, size: Long? = null, modified: Long? = null) =
        RemoteEntry(EntryRef("connection", id), name, false, size = size, modifiedAtEpochMillis = modified)
    private fun request(selected: RemoteEntry, query: String = "", sort: Int = 0) =
        PreviewSessionRequest("session", selected.ref.connectionId, "directory", selected.ref.opaqueId,
            query, sort, previewGroup(selected), 7)

    @Test fun visualQueueRetainsVisibleSortAndUsesRefsForDuplicateNames() {
        val first = entry("one", "same.jpg", size = 10)
        val second = entry("two", "same.jpg", size = 50)
        val video = entry("three", "video.mp4", size = 30)
        val visible = sortAndFilterEntries(listOf(first, second, video, entry("audio", "song.mp3"),
            entry("folder", "folder").copy(isDirectory = true), entry("text", "note.txt")), "", 2)
        val snapshot = previewSessionState(request(second), visible, partial = false)
        assertEquals(listOf(second, video, first), snapshot.items)
        assertEquals(second, snapshot.current)
        assertEquals(0, snapshot.currentIndex)
        assertEquals(7L, snapshot.connectionRevision)
    }

    @Test fun queuesSeparateAudioAndSingleDocumentsAndExcludeOtherConnections() {
        val audio = entry("audio", "song.mp3")
        val audio2 = entry("audio2", "podcast.bin").copy(mimeType = "audio/ogg")
        val pdf = entry("pdf", "paper.pdf")
        val all = listOf(audio, entry("image", "photo.jpg"), audio2, pdf,
            audio.copy(ref = EntryRef("another", "audio")), entry("text", "notes.txt"))
        assertEquals(listOf(audio, audio2), previewSessionState(request(audio), all, false).items)
        assertEquals(listOf(pdf), previewSessionState(request(pdf), all, false).items)
    }

    @Test fun openSnapshotRemainsFixedWhileDirectoryLoadsMoreEntries() {
        val first = entry("first", "trip-1.jpg")
        val selected = entry("selected", "trip-2.mp4")
        val listing = mutableListOf(first, selected)
        val snapshot = previewSessionState(request(selected), listing, partial = true)
        listing.clear()
        listing.add(entry("next", "trip-3.jpg"))
        assertEquals(listOf(first, selected), snapshot.items)
        assertEquals(1, snapshot.currentIndex)
        assertTrue(snapshot.partial)
    }

    @Test fun recoveryReappliesSearchAndDateOrderThenLocatesCurrentIdentity() {
        val first = entry("first", "Trip.jpg", modified = 10)
        val selected = entry("selected", "trip.mp4", modified = 30)
        val later = entry("new", "TRIP.jpg", modified = 50)
        val recipe = request(selected, query = "tRiP", sort = 1)
        val decoded = Json.decodeFromString<PreviewSessionRequest>(Json.encodeToString(recipe))
        val recovered = previewSessionState(decoded, sortAndFilterEntries(
            listOf(first, entry("excluded", "holiday.jpg", modified = 100), later, selected), decoded.query, decoded.sort), false)
        assertEquals(listOf(later, selected, first), recovered.items)
        assertEquals(1, recovered.currentIndex)
        assertEquals(recipe, decoded)
    }

    @Test fun missingCurrentNeverSilentlySelectsItsNeighbour() {
        val selected = entry("selected", "photo.jpg")
        val error = assertThrows(StorageException::class.java) {
            previewSessionState(request(selected), listOf(entry("neighbour", "photo.jpg")), false)
        }
        assertEquals(StorageError.NOT_FOUND, error.error)
    }

    @Test fun duplicateListingRowsDoNotCreateDuplicatePagerKeys() {
        val selected = entry("selected", "photo.jpg")
        assertEquals(listOf(selected), previewSessionState(request(selected), listOf(selected, selected), false).items)
    }

    @Test fun equalNamesDatesAndSizesRestoreInTheSameOrderAcrossWireListings() {
        val first = entry("a", "same.jpg", size = 1, modified = 2)
        val second = entry("b", "same.jpg", size = 1, modified = 2)
        for (sort in 0..2) {
            assertEquals(listOf(first, second), sortAndFilterEntries(listOf(second, first), "", sort))
            assertEquals(listOf(first, second), sortAndFilterEntries(listOf(first, second), "", sort))
        }
    }

    @Test fun playbackPositionsSurviveRevisitButNotRemoteReplacement() {
        val video = entry("video", "video.mp4", 100, 20).copy(revision = "v1")
        val positions = PreviewPositions()
        positions.save(video, 12_500)
        assertEquals(12_500L, positions.get(video))
        assertEquals(0L, positions.get(video.copy(revision = "v2")))
        assertEquals(0L, positions.get(video.copy(size = 200)))
        assertEquals(0L, positions.get(video.copy(modifiedAtEpochMillis = 40)))
        positions.save(video, -1)
        assertEquals(0L, positions.get(video))
        positions.save(video, 12_500)
        positions.clear()
        assertEquals(0L, positions.get(video))
    }
}
