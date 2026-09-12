package space.zhuoling.fileaccess.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import space.zhuoling.fileaccess.BrowserState
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.data.BackupRule
import space.zhuoling.fileaccess.core.data.TransferTask
import space.zhuoling.fileaccess.core.model.*

private val PagePadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Composable
private fun Page(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1100.dp).fillMaxSize(), content = content)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, detail: String, action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(24.dp).size(40.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun SpacesScreen(connections: List<ConnectionConfig>, onOpen: (ConnectionConfig) -> Unit,
    onAdd: () -> Unit, onEdit: (ConnectionConfig) -> Unit, onRemove: (ConnectionConfig) -> Unit) = Page {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Storage, null, Modifier.size(28.dp))
                    Text(stringResource(R.string.screen_spaces_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.screen_spaces_detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (connections.isEmpty()) item {
            EmptyState(Icons.Default.CreateNewFolder, stringResource(R.string.screen_no_connections), stringResource(R.string.screen_no_connections_detail)) {
                Button(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.screen_add_smb)) }
            }
        }
        items(connections, key = { it.id }) { connection ->
            var menu by remember { mutableStateOf(false) }
            OutlinedCard(onClick = { onOpen(connection) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(connection.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${connection.host}:${connection.port} / ${connection.share}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (connection.requireEncryption) R.string.screen_smb_encrypted else R.string.screen_smb_signed), style = MaterialTheme.typography.labelMedium)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.screen_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_edit)) }, onClick = { menu = false; onEdit(connection) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.screen_remove_connection)) }, onClick = { menu = false; onRemove(connection) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionDialog(existing: ConnectionConfig?, busy: Boolean, onDismiss: () -> Unit,
    onSave: (ConnectionConfig, String, Boolean) -> Unit) {
    val id = rememberSaveable(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable(id) { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable(id) { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable(id) { mutableStateOf((existing?.port ?: 445).toString()) }
    var share by rememberSaveable(id) { mutableStateOf(existing?.share ?: "") }
    var root by rememberSaveable(id) { mutableStateOf(existing?.rootPath ?: "") }
    var username by rememberSaveable(id) { mutableStateOf(existing?.username ?: "") }
    var domain by rememberSaveable(id) { mutableStateOf(existing?.domain ?: "") }
    // Passwords deliberately never enter saved state or navigation arguments.
    var password by remember(id) { mutableStateOf("") }
    var visible by remember(id) { mutableStateOf(false) }
    var encryption by rememberSaveable(id) { mutableStateOf(existing?.requireEncryption ?: false) }
    val valid = name.isNotBlank() && host.isNotBlank() && !host.contains(Regex("[\\s/\\\\]")) &&
        share.isNotBlank() && !share.contains(Regex("[/\\\\]")) && (port.toIntOrNull() ?: 0) in 1..65535
    fun submit(test: Boolean) {
        if (!busy && valid) onSave(ConnectionConfig(id, name.trim(), host = host.trim(), port = port.toInt(),
            share = share.trim(), rootPath = root.trim(), username = username, domain = domain,
            requireEncryption = encryption, revision = existing?.revision ?: 1), password, test)
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (existing == null) R.string.screen_add_smb else R.string.screen_edit_connection)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(name, { name = it }, R.string.screen_connection_name, enabled = !busy)
                FormField(host, { host = it }, R.string.screen_host, enabled = !busy)
                Text(stringResource(R.string.screen_host_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(share, { share = it }, R.string.screen_share, Modifier.weight(1f), !busy)
                    FormField(port, { port = it }, R.string.screen_port, Modifier.width(100.dp), !busy, KeyboardType.Number)
                }
                FormField(root, { root = it }, R.string.screen_root_optional, enabled = !busy)
                FormField(username, { username = it }, R.string.screen_username, enabled = !busy)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(stringResource(if (existing == null) R.string.screen_password else R.string.screen_password_preserve)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.screen_toggle_password))
                    } })
                FormField(domain, { domain = it }, R.string.screen_domain_optional, enabled = !busy)
                SettingToggle(stringResource(R.string.screen_require_encryption), encryption, { encryption = it }, !busy)
                Text(stringResource(R.string.screen_smb_security_detail), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { submit(true) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (busy) R.string.screen_working else R.string.screen_test_connection))
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit(false) }, enabled = valid && !busy) { Text(stringResource(R.string.screen_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.screen_cancel)) } })
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(stringResource(label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}

@Composable
fun BrowserScreen(state: BrowserState, busy: Boolean, onOpen: (RemoteEntry) -> Unit, onUpload: () -> Unit,
    onCreateDirectory: (String) -> Unit, onRename: (RemoteEntry, String) -> Unit, onDelete: (List<RemoteEntry>) -> Unit,
    onDownload: (RemoteEntry) -> Unit, onBackup: () -> Unit, onRetry: () -> Unit) = Page {
    var query by rememberSaveable(state.directory?.ref) { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var selection by remember(state.directory?.ref) { mutableStateOf(setOf<EntryRef>()) }
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
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)) {
            state.error?.let { error -> item {
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(error)
                        TextButton(onClick = onRetry, enabled = !state.loading) { Text(stringResource(R.string.screen_retry)) }
                    }
                }
            } }
            if (!state.loading && state.error == null && entries.isEmpty()) item {
                EmptyState(Icons.Default.FolderOpen,
                    stringResource(if (query.isBlank()) R.string.screen_empty_folder else R.string.screen_no_results),
                    stringResource(if (query.isBlank()) R.string.screen_empty_folder_detail else R.string.screen_no_results_detail))
            }
            items(entries, key = { "${it.ref.connectionId}:${it.ref.opaqueId}" }) { entry ->
                var menu by remember { mutableStateOf(false) }
                fun toggle() { selection = if (entry.ref in selection) selection - entry.ref else selection + entry.ref }
                ListItem(
                    modifier = Modifier.combinedClickable(onClick = { if (selection.isEmpty()) onOpen(entry) else toggle() }, onLongClick = { toggle() }),
                    headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(entryDetail(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        if (selection.isNotEmpty()) Checkbox(entry.ref in selection, { toggle() })
                        else Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        if (selection.isEmpty()) Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, stringResource(R.string.screen_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                if (!entry.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.screen_download)) }, onClick = { menu = false; onDownload(entry) })
                                if (state.capabilities.rename) DropdownMenuItem(text = { Text(stringResource(R.string.screen_rename)) }, onClick = { menu = false; rename = entry })
                                if (state.capabilities.delete) DropdownMenuItem(text = { Text(stringResource(R.string.screen_delete)) }, onClick = { menu = false; deleting = listOf(entry) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.screen_select)) }, onClick = { menu = false; toggle() })
                            }
                        }
                    })
            }
            if (!state.loading && !state.complete && state.entries.isNotEmpty()) item {
                Text(stringResource(R.string.screen_partial_listing), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
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

@Composable
private fun NameDialog(title: String, initial: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\:*?\"<>|" || it.code < 32 } && !name.endsWith('.') && !name.endsWith(' ')
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { FormField(name, { name = it }, R.string.screen_file_name) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid && !busy) { Text(stringResource(R.string.screen_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_cancel)) } })
}

@Composable
fun TransfersScreen(tasks: List<TransferTask>, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit) = Page {
    var completed by rememberSaveable { mutableStateOf(false) }
    val terminal = setOf(TransferState.SUCCEEDED, TransferState.CANCELLED)
    val filtered = tasks.filter { (it.state in terminal) == completed }.sortedByDescending { it.createdAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!completed, { completed = false }, label = { Text(stringResource(R.string.screen_active_tasks)) })
            FilterChip(completed, { completed = true }, label = { Text(stringResource(R.string.screen_finished_tasks)) })
        } }
        if (filtered.isEmpty()) item {
            EmptyState(Icons.Default.SwapVert, stringResource(R.string.screen_no_tasks), stringResource(R.string.screen_no_tasks_detail))
        }
        items(filtered, key = { it.id }) { task ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (task.direction == TransferDirection.UPLOAD) Icons.Default.Upload else Icons.Default.Download, null)
                        Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(transferStateLabel(task.state), style = MaterialTheme.typography.labelMedium)
                    }
                    val total = task.totalBytes
                    if (task.state !in terminal) {
                        if (total != null && total > 0) LinearProgressIndicator(progress = { (task.confirmedBytes.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else if (task.state in setOf(TransferState.RUNNING, TransferState.PREPARING, TransferState.VERIFYING, TransferState.COMMITTING, TransferState.RECONCILING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text("${bytes(task.confirmedBytes)} / ${total?.let(::bytes) ?: stringResource(R.string.screen_unknown_size)}", style = MaterialTheme.typography.bodySmall)
                    task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (task.state !in terminal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (task.state in setOf(TransferState.PAUSED, TransferState.FAILED, TransferState.WAITING)) TextButton(onClick = { onResume(task.id) }) { Text(stringResource(R.string.screen_resume)) }
                        else if (task.state in setOf(TransferState.QUEUED, TransferState.PREPARING, TransferState.RUNNING)) TextButton(onClick = { onPause(task.id) }) { Text(stringResource(R.string.screen_pause)) }
                        TextButton(onClick = { onCancel(task.id) }) { Text(stringResource(R.string.screen_cancel_task)) }
                    }
                }
            }
        }
    }
}

@Composable
fun BackupScreen(rules: List<BackupRule>, tasks: List<TransferTask>, onToggle: (BackupRule) -> Unit,
    onDelete: (BackupRule) -> Unit, onRun: () -> Unit, onBrowse: () -> Unit) = Page {
    var deleting by remember { mutableStateOf<BackupRule?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.screen_backup_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = rules.any { it.enabled }) { Icon(Icons.Default.Sync, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.screen_backup_now)) }
            OutlinedButton(onClick = onBrowse) { Text(stringResource(R.string.screen_add_rule)) }
        } }
        if (rules.isEmpty()) item {
            EmptyState(Icons.Default.CloudUpload, stringResource(R.string.screen_no_rules), stringResource(R.string.screen_no_rules_detail))
        }
        items(rules, key = { it.id }) { rule ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(rule.enabled, { onToggle(rule) })
                    }
                    Text(stringResource(if (rule.sourceKind == "camera") R.string.screen_camera else R.string.screen_selected_folder), style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(
                        if (rule.wifiOnly) stringResource(R.string.screen_wifi_only) else null,
                        if (rule.unmeteredOnly) stringResource(R.string.screen_unmetered_only) else null,
                        if (rule.chargingOnly) stringResource(R.string.screen_charging_only) else null
                    ).joinToString(" · ").ifBlank { stringResource(R.string.screen_any_network) }, style = MaterialTheme.typography.bodySmall)
                    val succeeded = tasks.count { it.backupRuleId == rule.id && it.state == TransferState.SUCCEEDED }
                    val pending = tasks.count { it.backupRuleId == rule.id && it.state !in setOf(TransferState.SUCCEEDED, TransferState.CANCELLED) }
                    Text(stringResource(R.string.screen_backup_counts, succeeded, pending), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.screen_last_scan, rule.lastCheck?.let(::date) ?: stringResource(R.string.screen_never)), style = MaterialTheme.typography.bodySmall)
                    rule.lastSuccess?.let { Text(stringResource(R.string.screen_last_success, date(it)), style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { deleting = rule }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.screen_remove_rule)) }
                }
            }
        }
    }
    deleting?.let { rule -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.screen_remove_rule)) },
        text = { Text(stringResource(R.string.screen_remove_rule_detail, rule.name)) },
        confirmButton = { TextButton(onClick = { onDelete(rule); deleting = null }) { Text(stringResource(R.string.screen_confirm)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.screen_cancel)) } }) }
}

@Composable
fun BackupRuleDialog(treeUri: String, onPickTree: () -> Unit, onDismiss: () -> Unit, busy: Boolean = false,
    onSave: (String, String, Boolean, Boolean, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var camera by rememberSaveable { mutableStateOf(true) }
    var wifi by rememberSaveable { mutableStateOf(true) }
    var unmetered by rememberSaveable { mutableStateOf(true) }
    var charging by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.screen_backup_here)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(name, { name = it }, R.string.screen_rule_name, enabled = !busy)
                Text(stringResource(R.string.screen_backup_destination), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(camera, { camera = true }, enabled = !busy, label = { Text(stringResource(R.string.screen_camera)) })
                    FilterChip(!camera, { camera = false }, enabled = !busy, label = { Text(stringResource(R.string.screen_selected_folder)) })
                }
                if (!camera) OutlinedButton(onClick = onPickTree, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (treeUri.isBlank()) R.string.screen_choose_folder else R.string.screen_folder_chosen))
                }
                if (camera) Text(stringResource(R.string.screen_camera_permission_detail), style = MaterialTheme.typography.bodySmall)
                SettingToggle(stringResource(R.string.screen_wifi_only), wifi, { wifi = it }, !busy)
                SettingToggle(stringResource(R.string.screen_unmetered_only), unmetered, { unmetered = it }, !busy)
                SettingToggle(stringResource(R.string.screen_charging_only), charging, { charging = it }, !busy)
                Text(stringResource(R.string.screen_backup_intro), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { onSave(name.trim(), if (camera) "camera" else "tree", wifi, unmetered, charging) },
            enabled = !busy && name.isNotBlank() && (camera || treeUri.isNotBlank())) { Text(stringResource(R.string.screen_enable_rule)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.screen_cancel)) } })
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun SettingsScreen() = Page {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("FileAccess", style = MaterialTheme.typography.headlineMedium) }
        item { Text(stringResource(R.string.screen_app_version), style = MaterialTheme.typography.bodyMedium) }
        item { SettingsInfo(Icons.Default.Palette, stringResource(R.string.screen_appearance), stringResource(R.string.screen_appearance_detail)) }
        item { SettingsInfo(Icons.Default.Lock, stringResource(R.string.screen_privacy), stringResource(R.string.screen_privacy_detail)) }
        item { SettingsInfo(Icons.Default.CloudUpload, stringResource(R.string.screen_backup_policy), stringResource(R.string.screen_backup_intro)) }
        item { SettingsInfo(Icons.Default.Info, stringResource(R.string.screen_initial_scope), stringResource(R.string.screen_initial_scope_detail)) }
    }
}

@Composable
private fun SettingsInfo(icon: ImageVector, title: String, detail: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, leadingContent = { Icon(icon, null) })
    }
}

@Composable
private fun entryDetail(entry: RemoteEntry): String = listOfNotNull(
    if (entry.isDirectory) stringResource(R.string.screen_folder) else entry.size?.let(::bytes) ?: stringResource(R.string.screen_unknown_size),
    entry.modifiedAtEpochMillis?.let(::date),
).joinToString(" · ")

private fun date(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

private fun bytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble() / 1024
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return String.format(Locale.getDefault(), "%.1f %s", amount, units[unit])
}

@Composable
private fun transferStateLabel(state: TransferState): String = stringResource(when (state) {
    TransferState.QUEUED -> R.string.screen_state_queued
    TransferState.PREPARING -> R.string.screen_state_preparing
    TransferState.RUNNING -> R.string.screen_state_running
    TransferState.VERIFYING -> R.string.screen_state_verifying
    TransferState.COMMITTING -> R.string.screen_state_committing
    TransferState.RECONCILING -> R.string.screen_state_reconciling
    TransferState.WAITING -> R.string.screen_state_waiting
    TransferState.PAUSED -> R.string.screen_state_paused
    TransferState.SUCCEEDED -> R.string.screen_state_succeeded
    TransferState.FAILED -> R.string.screen_state_failed
    TransferState.CANCELLED -> R.string.screen_state_cancelled
})
