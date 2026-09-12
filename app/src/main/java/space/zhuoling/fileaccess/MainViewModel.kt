package space.zhuoling.fileaccess

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.core.data.BackupRepository
import space.zhuoling.fileaccess.core.data.BackupRule
import space.zhuoling.fileaccess.core.data.ConnectionRepository
import space.zhuoling.fileaccess.core.data.TransferRepository
import space.zhuoling.fileaccess.core.data.TransferTask
import space.zhuoling.fileaccess.core.model.*
import space.zhuoling.fileaccess.core.storage.MutationCapability
import space.zhuoling.fileaccess.core.storage.ProviderRegistry
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.core.transfer.TransferScheduler
import space.zhuoling.fileaccess.core.transfer.describe

data class BrowserState(
    val directory: RemoteEntry? = null,
    val entries: List<RemoteEntry> = emptyList(),
    val capabilities: StorageCapabilities = StorageCapabilities(),
    val loading: Boolean = false,
    val error: String? = null,
    val complete: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val transferRepository: TransferRepository,
    private val backupRepository: BackupRepository,
    private val registry: ProviderRegistry,
    private val remote: RemoteAccess,
    private val scheduler: TransferScheduler,
) : ViewModel() {
    val connections = connectionRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transfers = transferRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rules = backupRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _browser = MutableStateFlow(BrowserState())
    val browser = _browser.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var listing: Job? = null
    private var requestedDirectory: EntryRef? = null

    init {
        viewModelScope.launch {
            try { scheduler.initialize() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.userMessage() }
        }
    }

    fun clearMessage() { _message.value = null }
    fun notify(message: String) { _message.value = message }

    fun loadDirectory(connectionId: String, opaqueId: String = "") {
        requestedDirectory = EntryRef(connectionId, opaqueId)
        listing?.cancel()
        _browser.value = BrowserState(loading = true)
        listing = viewModelScope.launch {
            try {
                remote.withSession(connectionId) { session ->
                    val directory = if (opaqueId.isEmpty()) session.root else session.stat(EntryRef(connectionId, opaqueId))
                    currentCoroutineContext().ensureActive()
                    _browser.update { it.copy(directory = directory, capabilities = session.capabilities) }
                    val entries = mutableListOf<RemoteEntry>()
                    session.list(directory.ref).collect { entry ->
                        currentCoroutineContext().ensureActive()
                        entries.add(entry)
                        if (entries.size % 100 == 0) _browser.update { it.copy(entries = entries.toList()) }
                    }
                    currentCoroutineContext().ensureActive()
                    _browser.update { it.copy(entries = entries.toList(), loading = false, complete = true) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _browser.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    fun saveConnection(config: ConnectionConfig, password: String, testOnly: Boolean, completed: () -> Unit) = action {
        require(config.name.isNotBlank()) { "请输入连接名称" }
        require(config.host.isNotBlank() && config.share.isNotBlank()) { "请输入主机与共享名称" }
        val credentials = if (password.isEmpty() && connectionRepository.get(config.id) != null) {
            val saved = connectionRepository.credentials(config.id)
                ?: throw StorageException(StorageError.AUTHENTICATION, "Credentials unavailable")
            saved.use { Credentials(config.username, it.password.copyOf(), config.domain) }
        } else Credentials(config.username, password.toCharArray(), config.domain)
        credentials.use {
            if (testOnly) {
                withContext(Dispatchers.IO) { registry.provider("smb").connect(config, it).use { session -> session.stat(session.root.ref) } }
                _message.value = "可以读取此共享目录"
            } else {
                connectionRepository.save(config, it)
                completed()
                _message.value = "连接已保存"
            }
        }
    }

    fun removeConnection(id: String) = action {
        transferRepository.observeAll().first().filter { it.remoteRef.connectionId == id }.forEach { scheduler.cancel(it.id) }
        connectionRepository.delete(id)
        _message.value = "已移除此连接，远端文件保留"
    }

    fun createDirectory(name: String) = action {
        val current = _browser.value.directory ?: return@action
        remote.withSession(current.ref.connectionId) { session ->
            val mutations = session as? MutationCapability ?: throw StorageException(StorageError.UNSUPPORTED, "Unsupported")
            mutations.createDirectory(current.ref, name)
        }
        loadDirectory(current.ref.connectionId, current.ref.opaqueId)
    }

    fun rename(entry: RemoteEntry, name: String) = action {
        remote.withSession(entry.ref.connectionId) { session ->
            (session as? MutationCapability)?.rename(entry.ref, name, entry.revision)
                ?: throw StorageException(StorageError.UNSUPPORTED, "Unsupported")
        }
        refresh()
    }

    fun delete(entries: List<RemoteEntry>) = action {
        var succeeded = 0
        val errors = mutableListOf<String>()
        for (entry in entries) {
            try {
                remote.withSession(entry.ref.connectionId) { session ->
                    val mutations = session as? MutationCapability ?: throw StorageException(StorageError.UNSUPPORTED, "Unsupported")
                    mutations.delete(entry.ref, entry.revision)
                }
                succeeded++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { errors.add("${entry.name}：${error.userMessage()}") }
        }
        refresh()
        _message.value = if (errors.isEmpty()) "已删除 $succeeded 项" else "已删除 $succeeded 项；${errors.first()}"
    }

    fun refresh() { requestedDirectory?.let { loadDirectory(it.connectionId, it.opaqueId) } }

    fun upload(uris: List<Uri>, target: EntryRef) = action {
        val config = connectionRepository.get(target.connectionId)
            ?: throw StorageException(StorageError.INVALID_CONFIGURATION, "Connection was removed")
        val ids = withContext(Dispatchers.IO) {
            uris.map { uri ->
                val item = context.contentResolver.describe(uri)
                transferRepository.enqueue(TransferTask(
                    direction = TransferDirection.UPLOAD, remoteRef = target, localUri = uri.toString(),
                    name = item.name, mimeType = item.mimeType, totalBytes = item.size,
                    connectionRevision = config.revision, sourceGeneration = item.version,
                ))
            }
        }
        scheduler.startUserInitiated(ids)
        _message.value = "已加入 ${ids.size} 个上传任务"
    }

    fun download(entry: RemoteEntry, destination: Uri) = action {
        val config = connectionRepository.get(entry.ref.connectionId) ?: return@action
        val id = transferRepository.enqueue(TransferTask(
            direction = TransferDirection.DOWNLOAD, remoteRef = entry.ref, localUri = destination.toString(),
            name = entry.name, mimeType = entry.mimeType, totalBytes = entry.size,
            sourceRevision = entry.revision, connectionRevision = config.revision,
        ))
        scheduler.startUserInitiated(listOf(id))
        _message.value = "已加入下载队列"
    }

    fun pauseTransfer(id: String) = action { scheduler.pause(id) }
    fun resumeTransfer(id: String) = action { transferRepository.resume(id); scheduler.startUserInitiated(listOf(id)) }
    fun cancelTransfer(id: String) = action { scheduler.cancel(id) }

    fun saveRule(name: String, sourceKind: String, sourceTreeUri: String, wifiOnly: Boolean, unmetered: Boolean, charging: Boolean, completed: () -> Unit) = action {
        val target = _browser.value.directory ?: throw IllegalArgumentException("请等待目标目录加载完成")
        val rule = BackupRule(
            id = UUID.randomUUID().toString(), name = name, sourceKind = sourceKind, sourceTreeUri = sourceTreeUri,
            target = target.ref, wifiOnly = wifiOnly, unmeteredOnly = unmetered, chargingOnly = charging,
        )
        backupRepository.save(rule)
        scheduler.scheduleBackupScan()
        _message.value = "备份规则已启用，本机删除不会删除远端副本"
        completed()
    }

    fun toggleRule(rule: BackupRule) = action { backupRepository.save(rule.copy(enabled = !rule.enabled)); scheduler.scheduleBackupScan() }
    fun deleteRule(rule: BackupRule) = action { backupRepository.delete(rule.id); _message.value = "规则已移除，远端副本保留" }
    fun backupNow() = action {
        val result = scheduler.scanAndStartUserInitiated()
        _message.value = result.messages.firstOrNull() ?: "扫描完成，新增 ${result.taskIds.size} 个备份任务"
    }

    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _message.value = error.userMessage() }
            finally { _busy.value = false }
        }
    }
}

fun Throwable.userMessage(): String = when (this) {
    is StorageException -> when (error) {
        StorageError.AUTHENTICATION -> "登录失败，请检查用户名、密码和域"
        StorageError.PERMISSION -> "没有访问权限，请检查共享权限和系统授权"
        StorageError.NOT_FOUND -> "文件或共享不存在，请刷新后重试"
        StorageError.CONFLICT -> "目标已存在，或文件夹非空；请另取名称，已有文件不会被覆盖"
        StorageError.NETWORK -> "NAS 暂时不可达，请检查网络、主机和端口"
        StorageError.UNSUPPORTED -> "此服务不支持该操作"
        StorageError.SOURCE_CHANGED -> "源文件已变化，请重新选择文件"
        StorageError.CORRUPT_DATA -> "文件校验未通过，未标记为完成"
        StorageError.OUTCOME_UNKNOWN -> "正在确认远端结果，请勿重复创建任务"
        StorageError.INVALID_CONFIGURATION -> "连接配置无效或已变化，请检查设置"
        StorageError.QUOTA_EXCEEDED -> "可用存储空间不足，或文件超过预览限制"
    }
    is SecurityException -> "访问授权已失效，请重新授权"
    is space.zhuoling.fileaccess.core.security.CredentialUnavailableException -> "已保存的凭据不可用，请重新输入连接密码"
    is IllegalArgumentException -> message ?: "请检查输入内容"
    else -> "操作未完成，请检查连接后重试"
}
