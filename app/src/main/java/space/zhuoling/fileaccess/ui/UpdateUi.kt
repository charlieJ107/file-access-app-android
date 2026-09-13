package space.zhuoling.fileaccess.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.zhuoling.fileaccess.BuildConfig
import space.zhuoling.fileaccess.update.UpdateState
import space.zhuoling.fileaccess.update.UpdateViewModel

@Composable
fun UpdateSettings(state: UpdateState, model: UpdateViewModel) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("自动检查新版本", Modifier.weight(1f))
                Switch(checked = state.automatic, onCheckedChange = model::setAutomatic)
            }
            Text("打开应用时检查 GitHub 正式版本，每 24 小时最多一次。下载后由系统确认安装。", style = MaterialTheme.typography.bodySmall)
            state.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.check(manual = true) }, enabled = !state.checking && !state.downloading) {
                    Text(if (state.checking) "正在检查…" else "检查更新")
                }
                if (state.release != null) TextButton(onClick = model::show) { Text("查看更新") }
            }
        }
    }
}

@Composable
fun UpdatePrompt(model: UpdateViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.check() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) model.install(context)
        else model.report("未允许安装应用；可再次点击安装进行授权")
    }
    val release = state.release
    if (!state.showDialog || release == null) return
    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text("新版本 ${release.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("安装包约 ${release.size / (1024 * 1024)} MiB。下载可能使用移动数据。")
                if (release.notes.isNotBlank()) Text(release.notes)
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("下载中 ${(state.progress * 100).toInt()}%")
                    TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                }
                state.status?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.downloading && !state.checking, onClick = {
                if (state.apk == null) model.download()
                else if (context.packageManager.canRequestPackageInstalls()) model.install(context)
                else try {
                    model.report("请允许 FileAccess 安装应用，返回后继续安装")
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) { model.report("无法打开安装授权设置，请在系统设置中允许 FileAccess 安装应用") }
            }) { Text(if (state.apk == null) "下载更新" else "安装更新") }
        },
        dismissButton = { TextButton(onClick = model::dismiss) { Text("稍后") } },
    )
}
