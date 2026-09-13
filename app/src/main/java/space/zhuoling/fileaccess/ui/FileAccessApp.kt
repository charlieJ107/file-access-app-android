package space.zhuoling.fileaccess.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import space.zhuoling.fileaccess.MainViewModel
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.preview.PreviewRepository

@Serializable private data object Spaces : NavKey
@Serializable private data object Transfers : NavKey
@Serializable private data object Backups : NavKey
@Serializable private data object Settings : NavKey
@Serializable private data class Browse(val connectionId: String, val opaqueId: String = "", val name: String = "") : NavKey
@Serializable private data class Preview(val connectionId: String, val opaqueId: String) : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileAccessApp(model: MainViewModel, previewRepository: PreviewRepository, remote: RemoteAccess,
    updateModel: space.zhuoling.fileaccess.update.UpdateViewModel) {
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    UpdatePrompt(updateModel)
    val context = LocalContext.current
    val resources = LocalResources.current
    val stack = rememberNavBackStack(Spaces)
    val current = stack.lastOrNull() ?: Spaces
    val snackbar = remember { SnackbarHostState() }
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val connections by model.connections.collectAsStateWithLifecycle()
    val tasks by model.transfers.collectAsStateWithLifecycle()
    val rules by model.rules.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    var editConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val editConnection = connections.firstOrNull { it.id == editConnectionId }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var removeConnection by remember { mutableStateOf<ConnectionConfig?>(null) }
    var pendingDownload by rememberSaveable(stateSaver = PendingEntrySaver) { mutableStateOf<RemoteEntry?>(null) }
    var uploadConnection by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadDirectory by rememberSaveable { mutableStateOf("") }
    var showRule by rememberSaveable { mutableStateOf(false) }
    var treeUri by rememberSaveable { mutableStateOf("") }
    var pendingConnection by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }

    fun persist(uri: Uri, flags: Int) {
        try { context.contentResolver.takePersistableUriPermission(uri, flags) }
        catch (_: SecurityException) { model.notify(resources.getString(R.string.permission_not_persisted)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (pendingConnection != null) {
            val id = pendingConnection
            pendingConnection = null
            if (granted["android.permission.ACCESS_LOCAL_NETWORK"] == true && id != null) stack.add(Browse(id))
            else model.notify(resources.getString(R.string.lan_permission_needed))
        } else if (granted["android.permission.ACCESS_LOCAL_NETWORK"] == false) model.notify(resources.getString(R.string.lan_permission_needed))
        else if (granted.values.none { it }) model.notify(resources.getString(R.string.media_permission_needed))
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { persist(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val connection = uploadConnection
        if (uris.isNotEmpty() && connection != null) { requestNotifications(); model.upload(uris, EntryRef(connection, uploadDirectory)) }
        uploadConnection = null
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) {
            persist(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            requestNotifications()
            model.download(entry, uri)
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); treeUri = uri.toString() }
    }
    fun openConnection(config: ConnectionConfig) {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            pendingConnection = config.id
            permission.launch(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"))
        } else stack.add(Browse(config.id, name = config.name))
    }
    fun download(entry: RemoteEntry) { pendingDownload = entry; downloadPicker.launch(entry.name) }
    val rootDestination = when (current) { Transfers -> Transfers; Backups -> Backups; Settings -> Settings; else -> Spaces }
    val navigation = listOf(
        Triple(Spaces, R.string.spaces, Icons.Default.FolderOpen),
        Triple(Transfers, R.string.transfers, Icons.Default.SwapVert),
        Triple(Backups, R.string.backups, Icons.Default.CloudUpload),
        Triple(Settings, R.string.settings, Icons.Default.Settings),
    )
    fun navigate(key: NavKey) { stack.clear(); stack.add(key) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (expanded) NavigationRail(Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(24.dp))
                navigation.forEach { (key, label, icon) ->
                    NavigationRailItem(selected = rootDestination == key, onClick = { navigate(key) },
                        icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(when (current) {
                                is Browse -> connections.firstOrNull { it.id == current.connectionId }?.name ?: stringResource(R.string.files)
                                is Preview -> stringResource(R.string.preview)
                                else -> stringResource(navigation.first { it.first == rootDestination }.second)
                            }, maxLines = 1)
                        },
                        navigationIcon = { if (stack.size > 1) IconButton(onClick = { stack.removeLastOrNull() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                        actions = {
                            if (current == Spaces) IconButton(onClick = { editConnectionId = null; showEditor = true }) { Icon(Icons.Default.Add, stringResource(R.string.add_connection)) }
                            if (current is Browse) IconButton(onClick = { model.refresh() }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                        },
                    )
                },
                bottomBar = {
                    if (!expanded) NavigationBar {
                        navigation.forEach { (key, label, icon) ->
                            NavigationBarItem(selected = rootDestination == key, onClick = { navigate(key) },
                                icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    NavDisplay(
                        backStack = stack,
                        onBack = { if (stack.size > 1) stack.removeLastOrNull() },
                        modifier = Modifier.weight(1f),
                        entryProvider = entryProvider {
                            entry<Spaces> {
                                SpacesScreen(connections, onOpen = ::openConnection,
                                    onAdd = { editConnectionId = null; showEditor = true },
                                    onEdit = { editConnectionId = it.id; showEditor = true }, onRemove = { removeConnection = it })
                            }
                            entry<Browse> { key ->
                                LaunchedEffect(key) { model.loadDirectory(key.connectionId, key.opaqueId) }
                                val state by model.browser.collectAsStateWithLifecycle()
                                val displayed = if (state.requestedRef == EntryRef(key.connectionId, key.opaqueId)) state else space.zhuoling.fileaccess.BrowserState(loading = true)
                                BrowserScreen(displayed, busy,
                                    onOpen = { if (it.isDirectory) stack.add(Browse(it.ref.connectionId, it.ref.opaqueId, it.name)) else stack.add(Preview(it.ref.connectionId, it.ref.opaqueId)) },
                                    onUpload = {
                                        state.directory?.ref?.let { uploadConnection = it.connectionId; uploadDirectory = it.opaqueId; uploadPicker.launch(arrayOf("*/*")) }
                                    }, onCreateDirectory = model::createDirectory,
                                    onRename = model::rename, onDelete = model::delete, onDownload = ::download,
                                    onBackup = { treeUri = ""; showRule = true }, onRetry = model::refresh,
                                    directoryKey = space.zhuoling.fileaccess.thumbnail.entryKey(EntryRef(key.connectionId, key.opaqueId)),
                                    viewMode = settings.browserViewMode, onViewModeChange = model::setBrowserViewMode,
                                    thumbnails = model.thumbnails, unmeteredOnly = settings.mediaThumbnailsUnmeteredOnly)
                            }
                            entry<Preview> { key ->
                                PreviewScreen(EntryRef(key.connectionId, key.opaqueId), previewRepository, remote, ::download)
                            }
                            entry<Transfers> { TransfersScreen(tasks, model::pauseTransfer, model::resumeTransfer, model::cancelTransfer) }
                            entry<Backups> { BackupScreen(rules, tasks, model::toggleRule, model::deleteRule, { requestNotifications(); model.backupNow() }, { navigate(Spaces) }) }
                            entry<Settings> {
                                SettingsScreen(settings.mediaThumbnailsUnmeteredOnly, model::setThumbnailNetworkPolicy, model::clearThumbnails) {
                                    UpdateSettings(updateState, updateModel)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    if (showEditor && (editConnectionId == null || editConnection != null)) ConnectionDialog(editConnection, busy, { showEditor = false }, { config, password, test ->
        if (Build.VERSION.SDK_INT >= 37 && test && ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            permission.launch(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"))
            model.notify(resources.getString(R.string.test_after_permission))
        } else model.saveConnection(config, password, test) { showEditor = false }
    })
    removeConnection?.let { config ->
        AlertDialog(onDismissRequest = { removeConnection = null }, title = { Text(stringResource(R.string.remove_connection)) },
            text = { Text(stringResource(R.string.remove_connection_detail, config.name)) },
            confirmButton = { TextButton(onClick = { model.removeConnection(config.id); removeConnection = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removeConnection = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (showRule) BackupRuleDialog(treeUri, { treePicker.launch(null) }, { showRule = false }, busy = busy) { name, kind, wifi, unmetered, charging ->
        val mediaGranted = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (kind == "camera" && !mediaGranted) {
            permission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            model.notify(resources.getString(R.string.enable_after_permission))
        } else {
            requestNotifications()
            model.saveRule(name, kind, treeUri, wifi, unmetered, charging) { showRule = false }
        }
    }
}

private val PendingEntrySaver = listSaver<RemoteEntry?, String>(
    save = { entry -> entry?.let { listOf(it.ref.connectionId, it.ref.opaqueId, it.name, it.size?.toString().orEmpty(), it.revision.orEmpty(), it.mimeType.orEmpty()) } ?: emptyList() },
    restore = { values -> if (values.size != 6) null else RemoteEntry(EntryRef(values[0], values[1]), values[2], false,
        size = values[3].toLongOrNull(), revision = values[4].ifEmpty { null }, mimeType = values[5].ifEmpty { null }) },
)
