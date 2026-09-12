package space.zhuoling.fileaccess.core.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import space.zhuoling.fileaccess.core.model.EntryRef

/** Stable across rescans/process restarts; framing prevents ambiguous concatenation. */
object BackupOperationId {
    fun create(
        ruleId: String, logicalSourceId: String, sourceGeneration: String,
        target: EntryRef, connectionRevision: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("backup-v1", ruleId, logicalSourceId, sourceGeneration,
            target.connectionId, target.opaqueId, connectionRevision.toString()).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
