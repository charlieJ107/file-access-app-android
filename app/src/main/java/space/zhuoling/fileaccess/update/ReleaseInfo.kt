package space.zhuoling.fileaccess.update

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GithubAsset>,
)

@Serializable
internal data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
    val state: String,
)

data class ReleaseInfo(val versionCode: Long, val versionName: String, val notes: String,
    val url: String, val size: Long, val sha256: String)

internal const val MAX_APK_BYTES = 256L * 1024 * 1024
private val json = Json { ignoreUnknownKeys = true }

internal fun parseRelease(body: String, repository: String, installedVersion: String, installedCode: Long): ReleaseInfo? {
    val release = json.decodeFromString<GithubRelease>(body)
    if (release.draft || release.prerelease) return null
    require(release.tag.startsWith('v')) { "发布版本标签必须以 v 开头" }
    val versionName = release.tag.removePrefix("v")
    val version = SemanticVersion.parse(versionName)
    if (version.prerelease.isNotEmpty() || version <= SemanticVersion.parse(installedVersion)) return null
    val candidates = release.assets.mapNotNull { asset ->
        Regex("fileaccess-([1-9][0-9]*)\\.apk").matchEntire(asset.name)?.let { match ->
            asset to requireNotNull(match.groupValues[1].toLongOrNull()) { "发布版本号无效" }
        }
    }
    require(candidates.size == 1) { "Release 必须包含一个正式 APK" }
    val (asset, code) = candidates.single()
    require(code > installedCode) { "新版本的 Android versionCode 未递增，请联系发布者" }
    require(asset.state == "uploaded" && asset.size in 1..MAX_APK_BYTES) { "APK 尚未上传完成或大小超出限制" }
    val uri = URI(asset.url)
    require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath == "/$repository/releases/download/${release.tag}/${asset.name}") { "APK 下载来源无效" }
    val digest = requireNotNull(asset.digest?.removePrefix("sha256:")?.takeIf {
        asset.digest.startsWith("sha256:") && Regex("[a-fA-F0-9]{64}").matches(it)
    }) { "Release 缺少 SHA-256 校验信息" }
    return ReleaseInfo(code, release.tag.removePrefix("v"), release.body.orEmpty().take(8_000), asset.url, asset.size, digest.lowercase())
}

internal fun automaticCheckDue(enabled: Boolean, lastAttempt: Long, now: Long): Boolean =
    enabled && (lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= 24 * 60 * 60 * 1000L)
