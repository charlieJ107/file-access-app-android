package space.zhuoling.fileaccess.protocol.smb

import java.security.MessageDigest
import space.zhuoling.fileaccess.core.model.ConnectionConfig
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException

/** SMB names are not URLs. Percent sequences and Unicode are preserved byte for byte. */
internal class SmbPaths(private val config: ConnectionConfig) {
    val root: String = canonicalPath(config.rootPath)

    init {
        checkConfig(config.protocol == "smb", "Unsupported connection protocol")
        checkConfig(config.id.isNotBlank(), "A connection identifier is required")
        checkConfig(config.host.isNotBlank() && config.host.length <= 253 &&
            config.host.none { it.isWhitespace() || it in "/\\@?#" || it.code < 32 },
            "Enter a hostname or IP address without a URL or share path")
        checkConfig(config.port in 1..65535, "The port must be between 1 and 65535")
        validateName(config.share)
    }

    fun path(ref: EntryRef): String {
        checkConfig(ref.connectionId == config.id, "The entry belongs to another connection")
        val path = canonicalPath(ref.opaqueId)
        checkConfig(path == ref.opaqueId, "Invalid remote entry reference")
        checkConfig(root.isEmpty() || path == root || path.startsWith("$root\\"),
            "The entry is outside the configured root")
        return path
    }

    fun ref(path: String): EntryRef = EntryRef(config.id, path).also { path(it) }

    fun child(parent: EntryRef, name: String): String {
        validateName(name)
        checkConfig(!name.startsWith(INTERNAL_PREFIX, ignoreCase = true),
            "This name is reserved for upload recovery")
        return canonicalPath(join(path(parent), name))
    }

    fun parent(path: String): EntryRef? = if (path == root) null else ref(path.substringBeforeLast('\\', ""))

    fun internal(parent: EntryRef, token: String, suffix: String): String =
        canonicalPath(join(path(parent), "$INTERNAL_PREFIX$token.$suffix"))

    companion object {
        const val INTERNAL_PREFIX = ".fileaccess-upload-"
        private val receiptPattern = Regex("\\.fileaccess-upload-([0-9a-f]{64})\\.receipt")
        private val internalPattern = Regex("\\.fileaccess-upload-[0-9a-f]{64}\\.(receipt|part)")

        fun receiptToken(name: String): String? = receiptPattern.matchEntire(name)?.groupValues?.get(1)
        fun isInternalName(name: String): Boolean = internalPattern.matches(name)

        fun canonicalPath(input: String): String {
            checkConfig(input.length <= 4096, "The remote path is too long")
            val path = input.replace('/', '\\')
            if (path.isEmpty()) return path
            val segments = path.split('\\')
            checkConfig(segments.size <= 64, "The remote path is too deeply nested")
            segments.forEach(::validateName)
            return path
        }

        fun validateName(name: String) {
            checkConfig(name.isNotEmpty() && name.length <= 255 && name != "." && name != ".." &&
                !name.endsWith('.') && !name.endsWith(' ') &&
                name.none { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" },
                "Use one valid SMB name without separators, traversal, or alternate data streams")
        }

        fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent\\$name"

        fun operationToken(requestId: String, connectionId: String, target: String): String {
            checkConfig(requestId.isNotBlank() && requestId.length <= 256, "Invalid upload operation identifier")
            return sha256("$connectionId\u0000$requestId\u0000$target".toByteArray(Charsets.UTF_8))
        }
    }
}

internal fun checkConfig(condition: Boolean, message: String) {
    if (!condition) throw StorageException(StorageError.INVALID_CONFIGURATION, message)
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
