package space.zhuoling.fileaccess.thumbnail

import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry

internal const val MIB = 1024L * 1024
enum class MediaKind { IMAGE, VIDEO, AUDIO, FILE, DIRECTORY }

fun mediaKind(entry: RemoteEntry): MediaKind {
    if (entry.isDirectory) return MediaKind.DIRECTORY
    val mime = entry.mimeType?.lowercase(Locale.ROOT)
    if (mime != null && mime != "application/octet-stream") return when {
        mime.startsWith("image/") && mime != "image/svg+xml" -> MediaKind.IMAGE
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.FILE
    }
    return when (entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "gif", "heif", "heic", "avif", "bmp" -> MediaKind.IMAGE
        "mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi" -> MediaKind.VIDEO
        "mp3", "m4a", "wav", "flac", "ogg", "opus", "aac" -> MediaKind.AUDIO
        else -> MediaKind.FILE
    }
}

fun entryKey(ref: EntryRef): String = "${ref.connectionId.length}:${ref.connectionId}${ref.opaqueId}"
fun thumbnailEdge(pixels: Int): Int = when { pixels <= 128 -> 128; pixels <= 256 -> 256; else -> 512 }

data class ThumbnailRequest(val entry: RemoteEntry, val connectionRevision: Long, val edgePx: Int, val refreshEpoch: Long) {
    internal fun key(): String {
        val values = listOf("thumb-v1", entry.ref.connectionId, connectionRevision.toString(), entry.ref.opaqueId,
            entry.revision, entry.size?.toString(), entry.modifiedAtEpochMillis?.toString(), mediaKind(entry).name,
            thumbnailEdge(edgePx).toString(), "sync-1s-fit-oriented", if (entry.revision == null) refreshEpoch.toString() else null)
        val encoded = values.joinToString("") { value -> if (value == null) "-1:" else "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

enum class ThumbnailFailure { UNSUPPORTED, LIMIT, NETWORK, AUTH, SPACE, CHANGED, METERED }
internal class ThumbnailMeteredException : IOException("Automatic thumbnail network policy changed")
internal class ThumbnailLimitException : IOException("Thumbnail read budget exceeded")

/** Reservations prevent concurrent consumers from overspending a directory allowance. */
class ThumbnailBudget(initialBytes: Long = 64 * MIB) {
    private var remaining = initialBytes
    @Synchronized fun extend() { remaining = (remaining + 64 * MIB).coerceAtMost(1024 * MIB) }
    @Synchronized internal fun reserve(wanted: Int): Int {
        if (remaining <= 0) throw ThumbnailLimitException()
        return minOf(wanted.toLong(), remaining).toInt().also { remaining -= it }
    }
    @Synchronized internal fun refund(bytes: Int) { remaining += bytes }
    @Synchronized fun available(): Long = remaining
}
