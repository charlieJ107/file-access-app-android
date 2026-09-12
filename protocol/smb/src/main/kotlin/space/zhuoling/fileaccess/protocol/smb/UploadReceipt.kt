package space.zhuoling.fileaccess.protocol.smb

/** A bounded, versioned sidecar. Its name identifies the operation; it never contains a password. */
internal data class UploadReceipt(
    val token: String,
    val phase: Phase,
    val fileId: Long = 0,
    val length: Long = 0,
    val digest: String = "",
    val sourceVersionHash: String = "",
) {
    enum class Phase { PREPARING, WRITING, READY, COMMITTED }

    fun encode(): ByteArray {
        val body = listOf("FILEACCESS_UPLOAD_1", token, phase.name, fileId.toString(),
            length.toString(), digest, sourceVersionHash).joinToString("\n")
        return (body + "\n" + sha256(body.toByteArray(Charsets.UTF_8))).padEnd(MAX_BYTES, ' ').toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_BYTES = 1024
        fun decode(bytes: ByteArray, expectedToken: String): UploadReceipt {
            if (bytes.size != MAX_BYTES) outcomeUnknown()
            val fields = bytes.toString(Charsets.UTF_8).trimEnd(' ').split('\n')
            if (fields.size != 8 || fields[0] != "FILEACCESS_UPLOAD_1" || fields[1] != expectedToken ||
                !expectedToken.matches(Regex("[0-9a-f]{64}"))) outcomeUnknown()
            if (sha256(fields.take(7).joinToString("\n").toByteArray(Charsets.UTF_8)) != fields[7]) outcomeUnknown()
            val phase = Phase.entries.firstOrNull { it.name == fields[2] } ?: outcomeUnknown()
            val fileId = fields[3].toLongOrNull() ?: outcomeUnknown()
            val length = fields[4].toLongOrNull()?.takeIf { it >= 0 } ?: outcomeUnknown()
            fun hash(value: String) = value.isEmpty() || value.matches(Regex("[0-9a-f]{64}"))
            if (!hash(fields[5]) || !hash(fields[6])) outcomeUnknown()
            if (phase in setOf(Phase.READY, Phase.COMMITTED) && fields[5].isEmpty()) outcomeUnknown()
            return UploadReceipt(expectedToken, phase, fileId, length, fields[5], fields[6])
        }
    }
}
