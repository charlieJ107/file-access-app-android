package space.zhuoling.fileaccess.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.preview.PreviewRepository
import space.zhuoling.fileaccess.preview.PreviewViewModel
import space.zhuoling.fileaccess.thumbnail.ThumbnailFixture

@RunWith(AndroidJUnit4::class)
class PreviewPagerTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val entries = (0..2).map { RemoteEntry(EntryRef("fixture", "album/$it"), "photo_$it.png", false) }

    @Test fun swipesAndButtonsFollowQueueOrderStopAtEdgesAndDownloadCurrentIdentity() {
        val downloads = mutableListOf<RemoteEntry>()
        val selections = mutableListOf<Int>()
        compose.setContent { FileAccessTheme {
            MediaPreviewPager(entries, 1, partial = true, selections::add, downloads::add) { item, active, _, _ ->
                if (active) Text("Content: ${item.name}")
            }
        } }
        compose.onNodeWithTag("preview_position").assertTextEquals("2 / 3")
        compose.onNodeWithTag("preview_partial").assertIsDisplayed()
        compose.onNodeWithTag("preview_download").performClick()
        compose.onNodeWithTag("preview_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("preview_position").assertTextEquals("3 / 3")
        compose.onNodeWithTag("preview_next").assertIsNotEnabled()
        compose.onNodeWithTag("preview_download").performClick()
        compose.onNodeWithTag("preview_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("preview_position").assertTextEquals("3 / 3")
        compose.onNodeWithTag("preview_previous").performClick()
        compose.onNodeWithTag("preview_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("preview_position").assertTextEquals("1 / 3")
        compose.onNodeWithTag("preview_previous").assertIsNotEnabled()
        compose.onNodeWithTag("preview_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("preview_download").performClick()
        compose.runOnIdle {
            assertEquals(listOf(entries[1], entries[2], entries[0]), downloads)
            assertEquals(listOf(1, 2, 1, 0), selections)
        }
    }

    @Test fun downloadIsDisabledDuringDragAndTitleChangesOnlyWhenSettled() {
        compose.setContent { FileAccessTheme {
            MediaPreviewPager(entries, 0, false, {}, {}) { item, active, _, _ -> if (active) Text(item.ref.opaqueId) }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("preview_pager").performTouchInput {
            down(center)
            moveTo(center.copy(x = width * .15f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("preview_download").assertIsNotEnabled()
        compose.onNodeWithTag("preview_title").assertTextEquals("photo_0.png")
        compose.onNodeWithTag("preview_pager").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("preview_download").assertIsEnabled()
    }

    @Test fun savedPagerRestoresCurrentItemAndOnlyOnePageOwnsActiveContent() {
        val restoration = StateRestorationTester(compose)
        val active = mutableSetOf<EntryRef>()
        val queue = mutableStateOf(entries)
        restoration.setContent { FileAccessTheme {
            var selectedId by rememberSaveable { mutableStateOf(entries[0].ref.opaqueId) }
            MediaPreviewPager(queue.value, queue.value.indexOfFirst { it.ref.opaqueId == selectedId }, false,
                { selectedId = queue.value[it].ref.opaqueId }, {}) { item, isActive, _, _ ->
                if (isActive) {
                    DisposableEffect(item.ref) { active += item.ref; onDispose { active -= item.ref } }
                    Text(item.name)
                }
            }
        } }
        repeat(2) { compose.onNodeWithTag("preview_next").performClick() }
        compose.onNodeWithTag("preview_position").assertTextEquals("3 / 3")
        compose.runOnIdle { assertEquals(setOf(entries[2].ref), active) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("preview_position").assertTextEquals("3 / 3")
        compose.runOnIdle { assertEquals(setOf(entries[2].ref), active) }
        // Reconstructed directories can change order. The selected identity remains authoritative.
        compose.runOnIdle { queue.value = listOf(entries[2], entries[0], entries[1]) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("preview_position").assertTextEquals("1 / 3")
        compose.onNodeWithTag("preview_title").assertTextEquals("photo_2.png")
        compose.runOnIdle { assertEquals(setOf(entries[2].ref), active) }
    }

    @Test fun decodingFailureCanBeSwipedPastAndRetriedAfterRemoteReplacement() = runBlocking<Unit> {
        val fixture = ThumbnailFixture(context)
        val visible = mutableStateOf(true)
        try {
            fixture.initialize()
            val broken = fixture.put("broken.png", "Invalid image".toByteArray())
            val other = fixture.put("landscape.png", ThumbnailFixture.image(color = 0xff477c89.toInt()))
            val remote = RemoteAccess(fixture.connections, ProviderRegistry(setOf(fixture.provider)))
            val repository = PreviewRepository(fixture.context, remote)
            val model = PreviewViewModel(SavedStateHandle(), remote)
            model.start(EntryRef("fixture", ""), broken, listOf(broken, other), "", 0, false, 1)
            val downloads = mutableListOf<RemoteEntry>()
            compose.setContent { FileAccessTheme { if (visible.value) PreviewScreen(model, repository, remote, downloads::add) } }
            val retry = context.getString(R.string.preview_retry)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("preview_pager").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) { fixture.reads.get() == 2 && fixture.opened.get() == 0 }
            compose.onNodeWithTag("preview_title").assertTextEquals("landscape.png")
            compose.onNodeWithTag("preview_download").performClick()
            compose.runOnIdle { assertEquals(other.ref, downloads.single().ref) }
            compose.onNodeWithTag("preview_pager").performTouchInput { swipeRight() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
            // Retrying must bypass the broken disk cache even if the server's revision is unchanged.
            val replacement = fixture.put("broken.png", ThumbnailFixture.image(color = 0xff426882.toInt()), revision = "1")
            compose.onNodeWithText(retry).performClick()
            compose.waitUntil(10_000) { fixture.reads.get() == 3 && fixture.opened.get() == 0 }
            compose.onNodeWithText(retry).assertDoesNotExist()
            compose.onNodeWithTag("preview_download").performClick()
            compose.runOnIdle { assertEquals(replacement, downloads.last()) }
            val directory = context.getExternalFilesDir("media-validation")!!.apply { mkdirs() }
            File(directory, "preview-swipe.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    @Test fun rapidPagingCancelsPendingReadsAndDoesNotPrefetchNeighbors() = runBlocking<Unit> {
        val fixture = ThumbnailFixture(context)
        val visible = mutableStateOf(true)
        try {
            fixture.initialize()
            val queue = (0..2).map { fixture.put("page_$it.png", ThumbnailFixture.image()) }
            val remote = RemoteAccess(fixture.connections, ProviderRegistry(setOf(fixture.provider)))
            val repository = PreviewRepository(fixture.context, remote)
            val model = PreviewViewModel(SavedStateHandle(), remote)
            model.start(EntryRef("fixture", ""), queue[0], queue, "", 0, false, 1)
            fixture.openDelay = 60_000
            compose.setContent { FileAccessTheme { if (visible.value) PreviewScreen(model, repository, remote, {}) } }
            compose.waitUntil(10_000) { fixture.opened.get() > 0 && File(fixture.context.cacheDir, "previews").listFiles().orEmpty().any { it.extension == "part" } }
            compose.onNodeWithTag("preview_pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("preview_title").assertTextEquals("page_1.png")
            compose.waitUntil(10_000) { fixture.opened.get() > 0 && File(fixture.context.cacheDir, "previews").listFiles().orEmpty().any { it.extension == "part" } }
            fixture.openDelay = 0
            compose.onNodeWithTag("preview_pager").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) { fixture.reads.get() == 1 && fixture.opened.get() == 0 }
            compose.onNodeWithTag("preview_title").assertTextEquals("page_2.png")
            compose.onNodeWithContentDescription("page_2.png").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, fixture.streams.get())
                assertEquals(1, fixture.reads.get())
                assertTrue(File(fixture.context.cacheDir, "previews").listFiles().orEmpty().none { it.extension == "part" })
            }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }
}
