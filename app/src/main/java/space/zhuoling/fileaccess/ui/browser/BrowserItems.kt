package space.zhuoling.fileaccess.ui.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.thumbnail.*

@Composable
internal fun BrowserItems(
    entries: List<RemoteEntry>, grid: Boolean, resetKey: String, modifier: Modifier = Modifier,
    header: @Composable () -> Unit, footer: @Composable () -> Unit,
    content: @Composable (RemoteEntry, Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var previousGrid by rememberSaveable { mutableStateOf(grid) }
    var previousReset by rememberSaveable { mutableStateOf(resetKey) }
    var anchor by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep an item identity while a refresh temporarily empties the directory snapshot.
    LaunchedEffect(grid, resetKey, entries.isEmpty()) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (previousReset != resetKey) {
            listState.scrollToItem(0); gridState.scrollToItem(0); anchor = null
        } else if (previousGrid != grid || anchor != null) {
            val target = anchor?.let { key -> entries.indexOfFirst { entryKey(it.ref) == key }.takeIf { it >= 0 } } ?: 0
            if (grid) gridState.scrollToItem(target + 1) else listState.scrollToItem(target + 1)
        }
        previousGrid = grid; previousReset = resetKey
    }
    LaunchedEffect(grid, entries) {
        if (entries.isNotEmpty()) snapshotFlow {
            if (grid) gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && it.key != "header" }?.key as? String
            else listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && it.key != "header" }?.key as? String
        }.collect { if (it != null && it != "footer") anchor = it }
    }
    val visible by remember(grid, entries) { derivedStateOf {
        if (grid) {
            val items = gridState.layoutInfo.visibleItemsInfo
            val keys = items.map { it.key }.toSet()
            val columns = (items.maxOfOrNull { it.column } ?: 0) + 1
            val last = items.filter { it.key != "header" && it.key != "footer" }.maxOfOrNull { it.index }
            if (gridState.isScrollInProgress || last == null) keys
            else keys + entries.drop(last).take(columns).map { entryKey(it.ref) }
        } else {
            val items = listState.layoutInfo.visibleItemsInfo
            val keys = items.map { it.key }.toSet()
            val last = items.filter { it.key != "header" && it.key != "footer" }.maxOfOrNull { it.index }
            if (listState.isScrollInProgress || last == null) keys
            else keys + entries.drop(last).take(1).map { entryKey(it.ref) }
        }
    } }
    if (grid) {
        val minimum = if (LocalDensity.current.fontScale > 1.3f) 136.dp else 104.dp
        LazyVerticalGrid(columns = GridCells.Adaptive(minimum), state = gridState, modifier = modifier,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            items(entries, key = { entryKey(it.ref) }, contentType = { "file" }) { content(it, entryKey(it.ref) in visible) }
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    } else LazyColumn(modifier, state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "header") { header() }
        items(entries, key = { entryKey(it.ref) }, contentType = { "file" }) { content(it, entryKey(it.ref) in visible) }
        item(key = "footer") { footer() }
    }
}

@Composable
internal fun FileBrowserItem(
    entry: RemoteEntry, grid: Boolean, selected: Boolean, selecting: Boolean,
    detail: String, onOpen: () -> Unit, onToggle: () -> Unit,
    thumbnail: @Composable (Modifier) -> Unit, menu: @Composable () -> Unit,
) {
    val interaction = Modifier.semantics { this.selected = selected }
        .combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onToggle,
            onLongClickLabel = stringResource(R.string.screen_select))
    if (grid) {
        OutlinedCard(modifier = interaction.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                thumbnail(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopEnd)) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                        if (selecting) Checkbox(selected, { onToggle() }) else menu()
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(entry.name, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else ListItem(modifier = interaction,
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Box(Modifier.size(48.dp)) {
            thumbnail(Modifier.fillMaxSize())
            if (selecting) Checkbox(selected, { onToggle() }, Modifier.align(Alignment.Center))
        } }, trailingContent = { if (!selecting) menu() })
}

@Composable
internal fun RemoteThumbnail(
    entry: RemoteEntry, revision: Long?, epoch: Long, visible: Boolean, repository: ThumbnailRepository?,
    budget: ThumbnailBudget?, allowMetered: Boolean, modifier: Modifier = Modifier,
    onFailure: (ThumbnailFailure) -> Unit,
) {
    val kind = remember(entry) { mediaKind(entry) }
    val icon = when (kind) {
        MediaKind.DIRECTORY -> Icons.Default.Folder
        MediaKind.IMAGE -> Icons.Default.Image
        MediaKind.VIDEO -> Icons.Default.Movie
        MediaKind.AUDIO -> Icons.Default.AudioFile
        MediaKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val edge = thumbnailEdge(with(LocalDensity.current) { maxWidth.roundToPx() })
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val latestFailure by rememberUpdatedState(onFailure)
        val state by produceState<ThumbnailState>(ThumbnailState.Loading, entry, revision, epoch, visible, edge, allowMetered) {
            value = ThumbnailState.Loading
            if (visible && revision != null && repository != null && budget != null && kind in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    repository.observe(ThumbnailRequest(entry, revision, edge, epoch), budget, allowMetered).collectLatest {
                        value = it
                        if (it is ThumbnailState.Unavailable) latestFailure(it.reason)
                    }
                }
            }
        }
        val unavailable = stringResource(R.string.media_unavailable)
        val image = (state as? ThumbnailState.Ready)?.image
        if (image != null) Image(image.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(icon, null, Modifier.size(32.dp).then(if (state is ThumbnailState.Unavailable) Modifier.semantics {
            stateDescription = unavailable
        } else Modifier), tint = MaterialTheme.colorScheme.primary)
        if (kind == MediaKind.VIDEO && image != null) {
            Surface(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.media_video), Modifier.size(14.dp))
                    image.durationMillis?.let { duration ->
                        val seconds = duration / 1000
                        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
