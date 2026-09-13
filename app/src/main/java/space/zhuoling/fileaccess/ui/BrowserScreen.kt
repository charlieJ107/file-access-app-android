package space.zhuoling.fileaccess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import space.zhuoling.fileaccess.BrowserState
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.*

import androidx.compose.runtime.saveable.listSaver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.zhuoling.fileaccess.thumbnail.*
import space.zhuoling.fileaccess.ui.browser.*
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(state: BrowserState, busy: Boolean, onOpen: (RemoteEntry) -> Unit, onUpload: () -> Unit,
    onCreateDirectory: (String) -> Unit, onRename: (RemoteEntry, String) -> Unit, onDelete: (List<RemoteEntry>) -> Unit,
    onDownload: (RemoteEntry) -> Unit, onBackup: () -> Unit, onRetry: () -> Unit,
    directoryKey: String = state.directory?.ref?.let(::entryKey) ?: "",
    viewMode: String = "list", onViewModeChange: (String) -> Unit = {},
    thumbnails: ThumbnailRepository? = null, unmeteredOnly: Boolean = true,
) = Page {
    var modeOverride by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = (modeOverride ?: viewMode) == "grid"
    LaunchedEffect(viewMode) { modeOverride = null }
    var allowMeteredOnce by rememberSaveable(directoryKey) { mutableStateOf(false) }
    var failure by remember(directoryKey) { mutableStateOf<ThumbnailFailure?>(null) }
    val budget = remember(directoryKey, thumbnails) { thumbnails?.budget(directoryKey) }
    val scope = rememberCoroutineScope()
    val generation by (thumbnails?.generation?.collectAsStateWithLifecycle() ?: remember { mutableLongStateOf(0L) })
    var query by rememberSaveable(directoryKey) { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var selection by rememberSaveable(directoryKey, stateSaver = listSaver<Set<EntryRef>, String>(
        save = { entries -> entries.flatMap { listOf(it.connectionId, it.opaqueId) } },
        restore = { parts -> parts.chunked(2).map { EntryRef(it[0], it[1]) }.toSet() },
    )) { mutableStateOf(setOf<EntryRef>()) }
    LaunchedEffect(state.complete, state.entries) {
        if (state.complete) selection = selection.intersect(state.entries.map { it.ref }.toSet())
    }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    val entries = remember(state.entries, query, sort) {
        val filtered = state.entries.filter { it.name.contains(query, ignoreCase = true) }
        val order = when (sort) {
            1 -> compareByDescending<RemoteEntry> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
            2 -> compareByDescending<RemoteEntry> { it.size ?: -1 }
            else -> compareBy { it.name.lowercase(Locale.ROOT) }
        }
        filtered.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.then(order).thenBy { it.name })
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.directory?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.screen_share_root),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text(stringResource(R.string.screen_search_folder)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
                IconButton(onClick = {
                    modeOverride = if (grid) "list" else "grid"
                    onViewModeChange(modeOverride!!)
                }) { Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    stringResource(if (grid) R.string.media_switch_list else R.string.media_switch_grid)) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.screen_sort)) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf(R.string.screen_sort_name, R.string.screen_sort_date, R.string.screen_sort_size).forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { sort = index; sortMenu = false })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selection.isNotEmpty()) {
                    Text(stringResource(R.string.screen_selected, selection.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { deleting = state.entries.filter { it.ref in selection } }, enabled = state.capabilities.delete && !busy) {
                        Icon(Icons.Default.Delete, stringResource(R.string.screen_delete))
                    }
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, stringResource(R.string.screen_clear_selection)) }
                } else {
                    Text(stringResource(R.string.screen_item_count, entries.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = onUpload, enabled = state.directory != null && state.capabilities.upload && !busy) { Icon(Icons.Default.Upload, stringResource(R.string.screen_upload)) }
                    IconButton(onClick = { newFolder = true }, enabled = state.directory != null && state.capabilities.createDirectory && !busy) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.screen_new_folder)) }
                    IconButton(onClick = onBackup, enabled = state.directory != null && state.capabilities.upload && !busy) { Icon(Icons.Default.AddToPhotos, stringResource(R.string.screen_backup_here)) }
                }
            }
            if (state.capabilities.transportProtection != TransportProtection.UNKNOWN) Text(
                stringResource(if (state.capabilities.transportProtection == TransportProtection.ENCRYPTED) R.string.screen_transport_encrypted else R.string.screen_transport_signed),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (thumbnails != null && failure != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (failure != null) Text(stringResource(when (failure) {
                ThumbnailFailure.METERED -> R.string.media_metered
                ThumbnailFailure.AUTH -> R.string.media_auth
                else -> R.string.media_paused
            }), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            else Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                allowMeteredOnce = true; budget?.extend(); failure = null; thumbnails.retry()
            }) { Text(stringResource(if (failure == ThumbnailFailure.METERED) R.string.media_load else R.string.media_continue)) }
        }
        BrowserItems(entries, grid, "$query:$sort", Modifier.weight(1f), header = {
            state.error?.let { error ->
                Surface(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(error)
                        TextButton(onClick = onRetry, enabled = !state.loading) { Text(stringResource(R.string.screen_retry)) }
                    }
                }
            }
            if (!state.loading && state.error == null && entries.isEmpty()) {
                EmptyState(Icons.Default.FolderOpen,
                    stringResource(if (query.isBlank()) R.string.screen_empty_folder else R.string.screen_no_results),
                    stringResource(if (query.isBlank()) R.string.screen_empty_folder_detail else R.string.screen_no_results_detail))
            }
        }, footer = {
            if (!state.loading && !state.complete && state.entries.isNotEmpty()) {
                Text(stringResource(R.string.screen_partial_listing), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }) { entry, visible ->
            var menu by remember { mutableStateOf(false) }
            fun toggle() { selection = if (entry.ref in selection) selection - entry.ref else selection + entry.ref }
            FileBrowserItem(entry, grid, entry.ref in selection, selection.isNotEmpty(), entryDetail(entry),
                onOpen = { onOpen(entry) }, onToggle = ::toggle,
                thumbnail = { modifier ->
                    key(generation, state.connectionRevision) {
                        RemoteThumbnail(entry, state.connectionRevision, state.listingEpoch xor (generation shl 32), visible, thumbnails, budget,
                            !unmeteredOnly || allowMeteredOnce, modifier) { reason ->
                            if (reason !in setOf(ThumbnailFailure.UNSUPPORTED, ThumbnailFailure.CHANGED)) failure = reason
                        }
                    }
                }, menu = {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, stringResource(R.string.screen_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            if (!entry.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.screen_download)) }, onClick = { menu = false; onDownload(entry) })
                            if (thumbnails != null && mediaKind(entry) in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.media_reload)) }, onClick = {
                                    menu = false
                                    scope.launch { thumbnails.invalidateEntry(entry.ref) }
                                })
                            }
                            if (state.capabilities.rename) DropdownMenuItem(text = { Text(stringResource(R.string.screen_rename)) }, onClick = { menu = false; rename = entry })
                            if (state.capabilities.delete) DropdownMenuItem(text = { Text(stringResource(R.string.screen_delete)) }, onClick = { menu = false; deleting = listOf(entry) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_select)) }, onClick = { menu = false; toggle() })
                        }
                    }
                })
        }
    }
    if (newFolder) NameDialog(stringResource(R.string.screen_new_folder), "", busy, { newFolder = false }) { onCreateDirectory(it); newFolder = false }
    rename?.let { entry -> NameDialog(stringResource(R.string.screen_rename), entry.name, busy, { rename = null }) { onRename(entry, it); rename = null } }
    deleting?.let { targets ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.screen_delete_confirm, targets.size)) },
            text = { Text(targets.take(3).joinToString("\n") { it.name } + "\n\n" + stringResource(R.string.screen_delete_detail)) },
            confirmButton = { TextButton(onClick = { onDelete(targets); deleting = null; selection = emptySet() }, enabled = !busy) { Text(stringResource(R.string.screen_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.screen_cancel)) } })
    }
}
