package space.zhuoling.fileaccess.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.BrowserState
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageCapabilities

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptySpacesOffersConnectionCreation() {
        var added = false
        compose.setContent { FileAccessTheme { SpacesScreen(emptyList(), {}, { added = true }, {}, {}) } }
        compose.onNodeWithText("添加 SMB 连接").performClick()
        compose.runOnIdle { assertTrue(added) }
    }

    @Test fun incompleteConnectionCannotBeSaved() {
        compose.setContent { FileAccessTheme { ConnectionDialog(null, false, {}) { _, _, _ -> error("Invalid form submitted") } } }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("连接名称").performTextInput("Home NAS")
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }

    @Test fun existingPasswordIsNotLoadedIntoEditorAndBusyBlocksSave() {
        val config = ConnectionConfig("fixture", "Home NAS", host = "127.0.0.1", share = "test")
        var saved = false
        compose.setContent { FileAccessTheme { ConnectionDialog(config, true, {}) { _, _, _ -> saved = true } } }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("密码（留空不变）").assertExists()
        compose.runOnIdle { assertFalse(saved) }
    }

    @Test fun remoteDeleteRequiresExplicitConfirmation() {
        val root = RemoteEntry(EntryRef("fixture", ""), "test", true)
        val file = RemoteEntry(EntryRef("fixture", "example.txt"), "example.txt", false, root.ref, size = 12)
        var deleted = emptyList<RemoteEntry>()
        compose.setContent { FileAccessTheme {
            BrowserScreen(BrowserState(root, listOf(file), StorageCapabilities(delete = true), complete = true), false,
                {}, {}, {}, { _, _ -> }, { deleted = it }, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("删除这 1 项？").assertExists()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(listOf(file), deleted) }
    }
}
