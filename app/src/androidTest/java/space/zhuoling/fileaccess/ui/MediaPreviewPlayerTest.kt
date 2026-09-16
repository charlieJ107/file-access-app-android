package space.zhuoling.fileaccess.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.zhuoling.fileaccess.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPreviewPlayerTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun inactivePagesDoNotCreatePlayersAndDragPausesWithoutResuming() = onMain {
        val created = mutableListOf<RecordingPlayer>()
        val saved = mutableListOf<Long>()
        val holder = PreviewPlayerHolder(12_000, { RecordingPlayer(context).also { created += it } }, saved::add, { fail("Unexpected error") })
        holder.update(active = false, foreground = true, dragging = false)
        holder.update(active = true, foreground = false, dragging = false)
        assertTrue(created.isEmpty())
        holder.update(active = true, foreground = true, dragging = false)
        val first = created.single()
        assertEquals(12_000, first.position)
        assertFalse(first.wantsPlayback)
        holder.togglePlayback()
        assertTrue(first.wantsPlayback)
        first.position = 24_000
        holder.update(active = true, foreground = true, dragging = true)
        assertFalse(first.wantsPlayback)
        holder.update(active = true, foreground = true, dragging = false)
        assertFalse(first.wantsPlayback)
        assertEquals(1, created.size)
        holder.update(active = false, foreground = true, dragging = false)
        assertEquals(1, first.releases)
        assertEquals(listOf(24_000L), saved)
        holder.update(active = true, foreground = true, dragging = false)
        assertEquals(24_000, created.last().position)
        assertFalse(created.last().wantsPlayback)
        holder.release()
        holder.release()
        assertTrue(created.all { it.releases == 1 })
    }

    @Test fun preparationFailureReleasesTheCreatedPlayerAndRetryUsesANewHolder() = onMain {
        val broken = RecordingPlayer(context, failPreparation = true)
        var errors = 0
        var attempts = 0
        val holder = PreviewPlayerHolder(8_000, { attempts++; broken }, {}, { errors++ })
        holder.update(active = true, foreground = true, dragging = false)
        holder.update(active = true, foreground = true, dragging = false)
        assertEquals(1, errors)
        assertEquals(1, attempts)
        assertEquals(1, broken.releases)
        assertNull(holder.player)
        val retry = RecordingPlayer(context)
        val replacement = PreviewPlayerHolder(holder.positionMs, { retry }, {}, { errors++ })
        replacement.update(active = true, foreground = true, dragging = false)
        assertEquals(8_000, retry.position)
        assertFalse(retry.wantsPlayback)
        retry.failPlayback()
        assertEquals(2, errors)
        assertEquals(1, retry.releases)
        assertNull(replacement.player)
    }

    @Test fun backgroundAndExitReleaseAndForegroundRestoresPausedPosition() {
        val created = mutableListOf<RecordingPlayer>()
        val saved = mutableListOf<Long>()
        val visible = mutableStateOf(true)
        lateinit var owner: TestLifecycleOwner
        onMain { owner = TestLifecycleOwner().apply { registry.currentState = Lifecycle.State.STARTED } }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FileAccessTheme {
                    if (visible.value) MediaPreviewPlayerContent(
                        "video", true, false, 5_000, saved::add, { fail("Unexpected error") }, Modifier.fillMaxSize(),
                        createPlayer = { RecordingPlayer(context).also { created += it } },
                    )
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, created.size)
            created.single().position = 32_000
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(1, created.single().releases)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle {
            assertEquals(2, created.size)
            assertEquals(32_000, created.last().position)
            assertFalse(created.last().wantsPlayback)
            visible.value = false
        }
        compose.runOnIdle {
            assertTrue(created.all { it.releases == 1 })
            assertEquals(listOf(32_000L, 32_000L), saved)
        }
    }

    @Test fun replacingMediaIdentitySavesPositionToTheOutgoingFile() {
        val media = mutableStateOf("first")
        val created = mutableListOf<RecordingPlayer>()
        val saved = mutableMapOf<String, Long>()
        compose.setContent {
            FileAccessTheme {
                val selected = media.value
                MediaPreviewPlayerContent(
                    selected, true, false, 0, { saved[selected] = it }, { fail("Unexpected error") }, Modifier.fillMaxSize(),
                    createPlayer = { RecordingPlayer(context).also { created += it } },
                )
            }
        }
        compose.runOnIdle {
            created.single().position = 45_000
            media.value = "second"
        }
        compose.runOnIdle {
            assertEquals(45_000L, saved["first"])
            assertNull(saved["second"])
            assertEquals(2, created.size)
            assertEquals(1, created.first().releases)
            assertEquals(0, created.last().position)
        }
    }

    @Test fun pictureSwipeChangesPageWhilePlaybackAndSeekControlsRemainUsable() {
        val created = mutableListOf<RecordingPlayer>()
        lateinit var pager: PagerState
        compose.setContent {
            FileAccessTheme {
                pager = rememberPagerState { 2 }
                val scrubbing = remember { mutableStateOf(false) }
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !scrubbing.value) { page ->
                    if (page == 0) MediaPreviewPlayerContent(
                        "video", pager.settledPage == 0, pager.isScrollInProgress, 0, {}, { fail("Unexpected error") },
                        Modifier.fillMaxSize(), onControlScrubbingChanged = { scrubbing.value = it },
                        createPlayer = { RecordingPlayer(context).also { created += it } },
                    ) else Text("Next media", Modifier.fillMaxSize().testTag("next_media"))
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.preview_play)).performClick()
        compose.runOnIdle { assertTrue(created.single().wantsPlayback) }
        compose.onNodeWithTag("preview_media_seek").performTouchInput {
            swipe(Offset(width * .2f, centerY), Offset(width * .8f, centerY), durationMillis = 500)
        }
        compose.runOnIdle {
            assertEquals(0, pager.settledPage)
            assertTrue(created.single().position > 50_000)
            assertEquals(0, created.single().releases)
        }
        compose.onNodeWithTag("preview_media_picture").performTouchInput { swipeLeft() }
        compose.waitUntil { pager.settledPage == 1 }
        compose.runOnIdle {
            assertFalse(created.single().wantsPlayback)
            assertEquals(1, created.single().releases)
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** An actual local ExoPlayer supplies the view contracts; no data source or media is opened. */
    private class RecordingPlayer(context: Context, private val failPreparation: Boolean = false) :
        ForwardingPlayer(ExoPlayer.Builder(context).build()) {
        var position = 0L
        var wantsPlayback = false
        var releases = 0
        private val listeners = mutableSetOf<Player.Listener>()
        override fun addListener(listener: Player.Listener) { listeners += listener }
        override fun removeListener(listener: Player.Listener) { listeners -= listener }
        override fun prepare() { if (failPreparation) error("Synthetic prepare failure") }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun getCurrentPosition(): Long = position
        override fun getDuration(): Long = 100_000
        override fun isCurrentMediaItemSeekable(): Boolean = true
        override fun getPlaybackState(): Int = Player.STATE_READY
        override fun setPlayWhenReady(playWhenReady: Boolean) { wantsPlayback = playWhenReady }
        override fun getPlayWhenReady(): Boolean = wantsPlayback
        override fun play() { wantsPlayback = true }
        override fun pause() { wantsPlayback = false }
        override fun release() { releases++; super.release() }
        fun failPlayback() {
            listeners.toList().forEach { it.onPlayerError(PlaybackException("Synthetic read failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)) }
        }
    }
}
