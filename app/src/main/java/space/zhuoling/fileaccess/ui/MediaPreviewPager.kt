package space.zhuoling.fileaccess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.thumbnail.MediaKind
import space.zhuoling.fileaccess.thumbnail.entryKey
import space.zhuoling.fileaccess.thumbnail.mediaKind

/** A frozen queue; content decides how to display an active page without loading neighbors. */
@Composable
internal fun MediaPreviewPager(
    items: List<RemoteEntry>, initialIndex: Int, partial: Boolean,
    onSettled: (Int) -> Unit, onDownload: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (RemoteEntry, Boolean, Boolean, (Boolean) -> Unit) -> Unit,
) {
    require(items.isNotEmpty())
    // The session restores the settled file by identity. A saved numeric pager index can point
    // at a different file after directory changes, or at an unfinished swipe after rotation.
    val pager = remember(items) { PagerState(currentPage = initialIndex.coerceIn(items.indices), pageCount = { items.size }) }
    val scope = rememberCoroutineScope()
    val current = pager.settledPage.coerceIn(items.indices)
    val dragging = pager.isScrollInProgress
    val mediaQueue = remember(items) { mediaKind(items.first()) in setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO) }
    var scrubbing by remember(current) { mutableStateOf(false) }
    val latestSettled by rememberUpdatedState(onSettled)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { latestSettled(it) }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(items[current].name, style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("preview_title"))
        if (mediaQueue) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(current - 1) } },
                enabled = current > 0 && !dragging && !scrubbing, modifier = Modifier.testTag("preview_previous")) {
                Text(stringResource(R.string.preview_previous_media))
            }
            Text(stringResource(R.string.preview_media_position, current + 1, items.size), Modifier.testTag("preview_position"))
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(current + 1) } },
                enabled = current < items.lastIndex && !dragging && !scrubbing, modifier = Modifier.testTag("preview_next")) {
                Text(stringResource(R.string.preview_next_media))
            }
        }
        if (partial) Text(stringResource(R.string.preview_partial_queue),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("preview_partial"))
        TextButton(onClick = { onDownload(items[current]) }, enabled = !dragging,
            modifier = Modifier.testTag("preview_download")) { Text(stringResource(R.string.download_file)) }
        HorizontalPager(
            state = pager, key = { entryKey(items[it].ref) }, userScrollEnabled = !scrubbing && items.size > 1,
            beyondViewportPageCount = 0, modifier = Modifier.weight(1f).fillMaxWidth().testTag("preview_pager"),
        ) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                content(items[page], page == current, dragging) { scrubbing = it }
            }
        }
    }
}

@Composable
internal fun PreviewPlaceholder(entry: RemoteEntry) {
    val icon = when (mediaKind(entry)) {
        MediaKind.VIDEO -> Icons.Default.Movie
        MediaKind.AUDIO -> Icons.Default.AudioFile
        else -> Icons.Default.Image
    }
    Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
}
