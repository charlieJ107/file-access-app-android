package space.zhuoling.fileaccess.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.BrowserState
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.thumbnail.ThumbnailFixture

@RunWith(AndroidJUnit4::class)
class MediaBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val root = RemoteEntry(EntryRef("fixture", ""), "测试媒体", true)
    private val file = RemoteEntry(EntryRef("fixture", "photo.png"), "photo.png", false, root.ref, 1024)

    @Test fun switchingLayoutPreservesFilterSelectionAndDeleteConfirmation() {
        var deleted = emptyList<RemoteEntry>()
        compose.setContent { FileAccessTheme {
            BrowserScreen(BrowserState(root, listOf(file, file.copy(ref = EntryRef("fixture", "other"), name = "other.txt")),
                StorageCapabilities(delete = true), complete = true), false,
                {}, {}, {}, { _, _ -> }, { deleted = it }, {}, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performTextInput("photo")
        compose.onNodeWithText("photo.png").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("切换为网格").performClick()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
        compose.onNodeWithText("other.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription("删除").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("删除这 1 项？").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(listOf(file), deleted) }
    }

    @Test fun gridAndListOpenTheSameEntryAndShowPartialListingFailure() {
        val opened = mutableListOf<RemoteEntry>()
        compose.setContent { FileAccessTheme {
            BrowserScreen(BrowserState(root, listOf(file), error = "网络中断，目录未加载完整"), false,
                { opened += it }, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("photo.png").performClick()
        compose.onNodeWithContentDescription("切换为网格").performClick()
        compose.onNodeWithText("photo.png").performClick()
        compose.onNodeWithText("网络中断，目录未加载完整").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(file, file), opened) }
    }

    @Test fun tenThousandEntriesStayLazyAndLayoutSwitchPreservesVisibleAnchor() {
        val entries = (0 until 10_000).map { index -> file.copy(ref = EntryRef("fixture", "$index"), name = "file_${index.toString().padStart(5, '0')}.png") }
        compose.setContent { FileAccessTheme {
            BrowserScreen(BrowserState(root, entries, complete = true), false, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5_001)
        compose.onNodeWithText("file_05000.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换为网格").performClick()
        compose.onNodeWithText("file_05000.png").assertIsDisplayed()
        assertTrue("Only the viewport should be composed", compose.onAllNodes(hasText("file_", substring = true)).fetchSemanticsNodes().size < 100)
        compose.onNodeWithContentDescription("切换为列表").performClick()
        compose.onNodeWithText("file_05000.png").assertIsDisplayed()
    }

    @Test fun generatedMediaProducesReviewableGridAndListScreenshots() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fixture = ThumbnailFixture(context)
        try {
            fixture.initialize()
            val colors = listOf(0xff3896b2.toInt(), 0xffba764a.toInt(), 0xff748f55.toInt(), 0xffbd6688.toInt(), 0xff635fad.toInt(), 0xffe3b65a.toInt())
            val entries = listOf(root.copy(ref = EntryRef("fixture", "travel"), name = "旅行")) + colors.mapIndexed { index, color ->
                fixture.put("照片_${index + 1}.png", ThumbnailFixture.image(color = color))
            }
            compose.setContent { FileAccessTheme {
                BrowserScreen(BrowserState(root, entries, StorageCapabilities(rename = true, delete = true), complete = true, connectionRevision = 1),
                    false, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, viewMode = "grid", thumbnails = fixture.repository, unmeteredOnly = false)
            } }
            compose.waitUntil(timeoutMillis = 20_000) { fixture.reads.get() >= 3 && fixture.opened.get() == 0 }
            compose.waitForIdle()
            val directory = context.getExternalFilesDir("media-validation")!!
            directory.mkdirs()
            File(directory, "grid.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("切换为列表").performClick()
            compose.waitForIdle()
            File(directory, "list.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { fixture.close() }
    }
}
