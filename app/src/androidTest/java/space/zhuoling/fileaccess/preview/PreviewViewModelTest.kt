package space.zhuoling.fileaccess.preview

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.Credentials
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.storage.StorageProvider
import space.zhuoling.fileaccess.core.storage.StorageSession
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.thumbnail.ThumbnailFixture

@RunWith(AndroidJUnit4::class)
class PreviewViewModelTest {
    private lateinit var fixture: ThumbnailFixture
    private val models = mutableListOf<PreviewViewModel>()
    private var revision = 0L

    @Before fun setup() = runBlocking {
        fixture = ThumbnailFixture(ApplicationProvider.getApplicationContext())
        fixture.initialize()
        revision = requireNotNull(fixture.connections.get("fixture")).revision
    }

    @After fun tearDown() = runBlocking {
        onMain { models.forEach { it.close() } }
        withTimeout(5_000) { while (fixture.opened.get() != 0) delay(10) }
        fixture.close()
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun model(
        savedState: SavedStateHandle = SavedStateHandle(),
        wrap: (StorageSession) -> StorageSession = { it },
    ): PreviewViewModel {
        val provider = object : StorageProvider {
            override val type = "fixture"
            override suspend fun connect(config: ConnectionConfig, credentials: Credentials): StorageSession {
                val base = fixture.session()
                val strict = object : StorageSession by base {
                    override suspend fun stat(ref: EntryRef): RemoteEntry =
                        if (ref.opaqueId.isEmpty()) root else fixture.entries[ref.opaqueId]
                            ?: throw StorageException(StorageError.NOT_FOUND, "Fixture file removed")
                }
                return wrap(strict)
            }
        }
        return onMain {
            PreviewViewModel(savedState, RemoteAccess(fixture.connections, ProviderRegistry(setOf(provider))))
                .also { models.add(it) }
        }
    }

    private fun start(model: PreviewViewModel, selected: RemoteEntry, entries: List<RemoteEntry>,
        query: String = "", sort: Int = 0, partial: Boolean = false) = onMain {
        model.start(EntryRef("fixture", ""), selected, entries, query, sort, partial, revision)
    }

    private suspend fun awaitRestored(model: PreviewViewModel, route: PreviewSessionRequest): PreviewSessionState =
        withTimeout(5_000) { model.state.first { it.sessionId == route.sessionId && !it.restoring } }

    @Test fun savedStateRebuildsTheQueueAndRestoresTheLastSettledFile() = runBlocking {
        val first = fixture.put("trip-a.jpg", ByteArray(5))
        val initial = fixture.put("trip-b.mp4", ByteArray(20))
        val third = fixture.put("trip-c.jpg", ByteArray(10))
        fixture.put("excluded.jpg", ByteArray(100))
        val handle = SavedStateHandle()
        val original = model(handle)
        val route = start(original, initial, listOf(initial, third, first), query = "trip", sort = 2)
        onMain { original.select(2) }
        val saved = onMain { handle.keys().associateWith { key -> handle.get<Any?>(key) } }
        assertEquals(1, saved.size)
        assertTrue(saved.values.single() is String)
        onMain { original.close() }
        val newer = fixture.put("trip-new.jpg", ByteArray(30))
        val restored = model(SavedStateHandle(saved))
        onMain { restored.open(route) }
        val state = awaitRestored(restored, route)
        assertEquals(listOf(newer, initial, third, first), state.items)
        assertEquals(first.ref, state.current?.ref)
        assertEquals(3, state.currentIndex)
        assertFalse(state.partial)
        assertNull(state.error)
    }

    @Test fun retainedSessionIgnoresDirectoryChangesAndStopsAtBothBoundaries() {
        val first = fixture.put("a.jpg", byteArrayOf(1))
        val second = fixture.put("b.mp4", byteArrayOf(2))
        val visible = mutableListOf(first, second)
        val model = model()
        val route = start(model, first, visible, partial = true)
        visible.clear()
        fixture.put("new.jpg", byteArrayOf(3))
        onMain { model.open(route); model.select(-1) }
        assertEquals(first, model.state.value.current)
        onMain { model.select(1); model.select(2) }
        assertEquals(second, model.state.value.current)
        assertEquals(listOf(first, second), model.state.value.items)
        assertTrue(model.state.value.partial)
        assertEquals(0, fixture.opened.get())
    }

    @Test fun failedListingKeepsTheCurrentFileAndRetryReconstructsTheFullQueue() = runBlocking {
        val first = fixture.put("a.jpg", byteArrayOf(1))
        val second = fixture.put("b.jpg", byteArrayOf(2))
        val selected = fixture.put("c.mp4", byteArrayOf(3))
        val route = PreviewSessionRequest("partial", "fixture", "", selected.ref.opaqueId, "", 0,
            PreviewGroup.VISUAL, revision)
        val fail = AtomicBoolean(true)
        val model = model(wrap = { base ->
            object : StorageSession by base {
                override fun list(directory: EntryRef): Flow<RemoteEntry> = flow {
                    emit(first)
                    if (fail.get()) throw StorageException(StorageError.NETWORK, "Interrupted listing")
                    emit(second)
                    emit(selected)
                }
            }
        })
        onMain { model.open(route) }
        val partial = awaitRestored(model, route)
        assertEquals(listOf(first, selected), partial.items)
        assertEquals(selected.ref, partial.current?.ref)
        assertTrue(partial.partial)
        assertNotNull(partial.error)
        fail.set(false)
        onMain { model.retry() }
        val complete = awaitRestored(model, route)
        assertEquals(listOf(first, second, selected), complete.items)
        assertEquals(selected.ref, complete.current?.ref)
        assertFalse(complete.partial)
        assertNull(complete.error)
    }

    @Test fun changedConnectionCannotRestoreOldRemoteIdentities() = runBlocking {
        val selected = fixture.put("a.jpg", byteArrayOf(1))
        val original = model()
        val route = start(original, selected, listOf(selected))
        val config = requireNotNull(fixture.connections.get("fixture"))
        fixture.connections.save(config.copy(host = "changed.invalid.test"), Credentials("fixture", charArrayOf('x')))
        val restored = model()
        onMain { restored.open(route) }
        val state = awaitRestored(restored, route)
        assertTrue(state.items.isEmpty())
        assertNotNull(state.error)
        assertEquals(0, fixture.opened.get())
    }

    @Test fun removedCurrentFileReportsFailureInsteadOfOpeningANeighbour() = runBlocking {
        val selected = fixture.put("a.jpg", byteArrayOf(1))
        val neighbour = fixture.put("b.jpg", byteArrayOf(2))
        val original = model()
        val route = start(original, selected, listOf(selected, neighbour))
        fixture.entries.remove(selected.ref.opaqueId)
        val restored = model()
        onMain { restored.open(route) }
        val state = awaitRestored(restored, route)
        assertTrue(state.items.isEmpty())
        assertNull(state.current)
        assertNotNull(state.error)
    }

    @Test fun playbackProgressSurvivesRetainedOpenAndSeparatesFileVersions() {
        val selected = fixture.put("movie.mp4", byteArrayOf(1), revision = "first")
        val model = model()
        val route = start(model, selected, listOf(selected))
        onMain { model.savePosition(selected, 4_000); model.open(route) }
        assertEquals(4_000L, model.position(selected))
        assertEquals(0L, model.position(selected.copy(revision = "replacement")))
        onMain { model.close() }
        assertEquals(0L, model.position(selected))
    }
}
