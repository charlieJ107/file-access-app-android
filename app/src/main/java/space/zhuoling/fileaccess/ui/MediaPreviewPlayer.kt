package space.zhuoling.fileaccess.ui

import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.preview.RemoteMediaDataSource

/** The pager's settled page owns this player; adjacent pages must remain placeholders. */
@OptIn(UnstableApi::class)
@Composable
internal fun MediaPreviewPlayer(
    entry: RemoteEntry,
    remote: RemoteAccess,
    active: Boolean,
    isPagerDragging: Boolean,
    initialPositionMs: Long,
    onPositionSaved: (Long) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
    expectedConfigRevision: Long? = null,
    onControlScrubbingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val playerKey = Triple(entry.ref, entry.revision, expectedConfigRevision)
    MediaPreviewPlayerContent(
        playerKey, active, isPagerDragging, initialPositionMs, onPositionSaved, onError,
        modifier, onControlScrubbingChanged,
        createPlayer = {
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
                DataSource.Factory { RemoteMediaDataSource(entry, remote, expectedConfigRevision) },
            )).build().also {
                try { it.setMediaItem(MediaItem.fromUri("fileaccess://preview/${Uri.encode(entry.name)}")) }
                catch (failure: Exception) { it.release(); throw failure }
            }
        },
    )
}

/** A factory seam keeps lifecycle and gesture tests independent of accounts and remote storage. */
@OptIn(UnstableApi::class)
@Composable
internal fun MediaPreviewPlayerContent(
    playerKey: Any,
    active: Boolean,
    isPagerDragging: Boolean,
    initialPositionMs: Long,
    onPositionSaved: (Long) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
    onControlScrubbingChanged: (Boolean) -> Unit = {},
    createPlayer: () -> Player,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // These callbacks belong to this media identity. Updating them across a key change would
    // save the outgoing player's position into the newly selected file during disposal.
    val currentActive by rememberUpdatedState(active)
    val currentDragging by rememberUpdatedState(isPagerDragging)
    val holder = remember(playerKey) {
        PreviewPlayerHolder(initialPositionMs, createPlayer, onPositionSaved, onError)
    }
    DisposableEffect(holder, lifecycle) {
        fun update() = holder.update(currentActive, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), currentDragging)
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            holder.release()
        }
    }
    SideEffect { holder.update(active, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), isPagerDragging) }
    val player = holder.player
    LaunchedEffect(holder, player) {
        while (player != null) {
            holder.refresh()
            delay(250)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Native playback controls consume picture drags. Keep the native view display-only and
        // use Compose controls below it, so a picture swipe reaches the enclosing Compose pager.
        AndroidView(
            factory = { PictureOnlyPlayerView(it) },
            update = { it.player = player; it.keepScreenOn = holder.playing },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("preview_media_picture"),
        )
        if (player != null) {
            PlaybackControls(holder, enabled = !isPagerDragging, onControlScrubbingChanged)
        }
    }
}

@Composable
private fun PlaybackControls(holder: PreviewPlayerHolder, enabled: Boolean, onScrubbingChanged: (Boolean) -> Unit) {
    var scrubPosition by remember(holder) { mutableStateOf<Long?>(null) }
    val notifyScrubbing by rememberUpdatedState(onScrubbingChanged)
    val interactions = remember(holder) { MutableInteractionSource() }
    DisposableEffect(holder) { onDispose { notifyScrubbing(false) } }
    LaunchedEffect(interactions) {
        interactions.interactions.collect {
            if (it is DragInteraction.Cancel) {
                scrubPosition = null
                notifyScrubbing(false)
            }
        }
    }
    val duration = holder.durationMs
    val position = (scrubPosition ?: holder.positionMs).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
    val seekDescription = stringResource(R.string.preview_seek)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { holder.togglePlayback() }, enabled = enabled) {
            Icon(
                if (holder.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (holder.playing) R.string.preview_pause else R.string.preview_play),
            )
        }
        Text(formatPlaybackTime(position))
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = {
                if (scrubPosition == null) notifyScrubbing(true)
                scrubPosition = (it * duration).toLong().coerceIn(0, duration)
            },
            onValueChangeFinished = {
                scrubPosition?.let(holder::seekTo)
                scrubPosition = null
                notifyScrubbing(false)
            },
            enabled = enabled && duration > 0 && holder.seekable,
            interactionSource = interactions,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                .semantics { contentDescription = seekDescription }.testTag("preview_media_seek"),
        )
        Text(if (duration > 0) formatPlaybackTime(duration) else "--:--")
    }
}

/** All methods and callbacks run on the player's main application thread. */
internal class PreviewPlayerHolder(
    initialPositionMs: Long,
    private val createPlayer: () -> Player,
    private val onPositionSaved: (Long) -> Unit,
    private val onError: () -> Unit,
) {
    var player by mutableStateOf<Player?>(null)
        private set
    var positionMs by mutableLongStateOf(initialPositionMs.coerceAtLeast(0))
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var seekable by mutableStateOf(false)
        private set
    private var failed = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
        override fun onPlayerError(error: PlaybackException) {
            failed = true
            release()
            onError()
        }
    }

    fun update(active: Boolean, foreground: Boolean, dragging: Boolean) {
        if (!active || !foreground) {
            release()
            return
        }
        if (player == null && !failed) {
            try {
                val created = createPlayer()
                player = created
                created.addListener(listener)
                created.playWhenReady = false
                created.seekTo(positionMs)
                created.prepare()
            } catch (_: Exception) {
                failed = true
                release()
                onError()
            }
        }
        if (dragging) player?.pause()
        refresh()
    }

    fun refresh() {
        val current = player ?: return
        positionMs = current.currentPosition.coerceAtLeast(0)
        durationMs = current.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        playing = current.playWhenReady && current.playbackState != Player.STATE_ENDED
        seekable = current.isCurrentMediaItemSeekable
    }

    fun togglePlayback() {
        val current = player ?: return
        if (current.playWhenReady) current.pause() else {
            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
            current.play()
        }
        refresh()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
        refresh()
    }

    fun release() {
        val previous = player ?: return
        positionMs = previous.currentPosition.coerceAtLeast(0)
        player = null
        playing = false
        seekable = false
        previous.removeListener(listener)
        try {
            previous.pause()
            onPositionSaved(positionMs)
        } finally { previous.release() }
    }
}

@OptIn(UnstableApi::class)
private class PictureOnlyPlayerView(context: Context) : PlayerView(context) {
    init { useController = false; isClickable = false; isFocusable = false }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}
