package space.zhuoling.fileaccess.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateState(
    val automatic: Boolean = true, val checking: Boolean = false,
    val release: ReleaseInfo? = null, val showDialog: Boolean = false,
    val downloading: Boolean = false, val progress: Float = 0f, val apk: File? = null,
    val status: String? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(private val repository: UpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateState(automatic = repository.automatic))
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun setAutomatic(enabled: Boolean) {
        repository.automatic = enabled
        mutableState.update { it.copy(automatic = enabled) }
        if (enabled) check()
    }

    fun check(manual: Boolean = false) {
        if (operation?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && !repository.shouldCheck(now)) return
        repository.recordAttempt(now)
        mutableState.update { it.copy(checking = true, status = null) }
        operation = viewModelScope.launch {
            try {
                val release = repository.latest()
                mutableState.update { it.copy(release = release, apk = null, showDialog = release != null,
                    status = if (release == null) "已是最新正式版本" else "发现新版本 ${release.versionName}") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error.message ?: "检查更新失败，请检查网络后重试") }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun show() { mutableState.update { it.copy(showDialog = true) } }
    fun dismiss() { mutableState.update { it.copy(showDialog = false) } }
    fun report(message: String) { mutableState.update { it.copy(status = message) } }
    fun cancelDownload() { operation?.cancel() }

    fun download() {
        if (operation?.isActive == true) return
        val release = state.value.release ?: return
        mutableState.update { it.copy(downloading = true, progress = 0f, status = null) }
        operation = viewModelScope.launch {
            try {
                val apk = repository.download(release) { value -> mutableState.update { it.copy(progress = value) } }
                mutableState.update { it.copy(apk = apk, status = "下载完成，点击安装以继续") }
            } catch (cancelled: CancellationException) {
                report("下载已取消，可重新下载")
                throw cancelled
            } catch (error: Exception) { report(error.message ?: "下载失败，请检查网络和可用空间") }
            finally { mutableState.update { it.copy(downloading = false) } }
        }
    }

    fun install(context: Context) {
        if (operation?.isActive == true) return
        val release = state.value.release ?: return
        val apk = state.value.apk ?: return
        operation = viewModelScope.launch {
            try {
                repository.validate(apk, release)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(apk = null) }
                report(error.message ?: "无法打开系统安装器，请重新下载后重试")
            }
        }
    }
}
