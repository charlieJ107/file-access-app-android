package space.zhuoling.fileaccess.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.BuildConfig

@Singleton
class UpdateRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = preferences.getBoolean("automatic", true)
        set(value) { preferences.edit().putBoolean("automatic", value).apply() }
    fun shouldCheck(now: Long) = automaticCheckDue(automatic, preferences.getLong("last_attempt", 0), now)
    fun recordAttempt(now: Long) { preferences.edit().putLong("last_attempt", now).apply() }

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/${BuildConfig.RELEASE_REPOSITORY}/releases/latest", api = true)
        try {
            when (connection.responseCode) {
                404 -> error("暂无可访问的正式版本；请确认仓库已公开并发布 Release")
                403, 429 -> error("GitHub 请求受限，请稍后重试")
                200 -> Unit
                else -> error("检查更新失败（HTTP ${connection.responseCode}）")
            }
            val bytes = connection.inputStream.use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "发布信息过大" }
            parseRelease(bytes.toString(Charsets.UTF_8), BuildConfig.RELEASE_REPOSITORY, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        } finally { connection.disconnect() }
    }

    suspend fun download(release: ReleaseInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(directory, "fileaccess-${release.versionCode}.apk")
        if (target.isFile) {
            try { validate(target, release); return@withContext target }
            catch (_: IllegalArgumentException) { target.delete() }
        }
        directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
        val partial = File(directory, "download.part")
        try {
            val connection = open(release.url)
            try {
                require(connection.responseCode == 200) { "下载安装包失败（HTTP ${connection.responseCode}）" }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= release.size && total <= MAX_APK_BYTES) { "APK 大小与发布信息不符" }
                            output.write(buffer, 0, count)
                            progress(total.toFloat() / release.size)
                        }
                    }
                }
            } finally { connection.disconnect() }
            validate(partial, release)
            check(partial.renameTo(target)) { "无法保存安装包，请检查可用空间" }
            target
        } finally { partial.delete() }
    }

    suspend fun validate(file: File, release: ReleaseInfo) = withContext(Dispatchers.IO) {
        require(file.length() == release.size) { "APK 文件不完整，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "APK 校验失败，请重新下载" }
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = requireNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags)) { "无法读取 APK" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.longVersionCode == release.versionCode &&
            archive.longVersionCode > installed.longVersionCode && archive.versionName == release.versionName) { "APK 包名或版本与发布信息不符" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "新版本不支持当前 Android 系统" }
        // GET_SIGNING_CERTIFICATES verifies the APK and its native proof-of-rotation.
        // Trust only a successor authorized by the currently installed key, not any old key.
        require(permitsSigningUpdate(installed.signingInfo.verifiedSigningIdentity(), archive.signingInfo.verifiedSigningIdentity())) {
            "安装包签名不受当前版本信任；需要相同签名或由当前签名授权的向前轮换，不能退回旧签名"
        }
    }

    private fun open(url: String, api: Boolean = false): HttpURLConnection {
        var next = URI(url)
        repeat(6) {
            require(next.scheme == "https" && next.userInfo == null && next.port == -1 &&
                next.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "下载重定向来源无效" }
            val connection = next.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FileAccess/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
            if (api) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                next = next.resolve(requireNotNull(connection.getHeaderField("Location")))
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("下载重定向次数过多")
    }
}
