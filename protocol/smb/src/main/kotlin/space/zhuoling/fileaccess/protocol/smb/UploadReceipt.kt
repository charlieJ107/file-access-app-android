package space.zhuoling.fileaccess.protocol.smb

/** A bounded, versioned sidecar. Its name identifies the operation; it never contains a password. */
internal data class UploadReceipt(
    val token: String,
    val phase: Phase,
    val fileId: Long = 0,
    val length: Long = 0,
    val digest: String = "",
    val sourceVersionHash: String = "",
    val sourceLength: Long = -1,
    val sequence: Long = 0,
    val verificationOffset: Long = 0,
    val sourceHashState: String = "",
    val remoteHashState: String = "",
    val verificationRevision: String = "",
) {
    enum class Phase { PREPARING, WRITING, CHECKING, CHECKED, VERIFYING, READY, COMMITTED, RECONCILING }

    fun encode(): ByteArray {
        val body = listOf("FILEACCESS_UPLOAD_2", token, phase.name, fileId.toString(),
            length.toString(), digest, sourceVersionHash, sourceLength.toString(), sequence.toString(),
            verificationOffset.toString(), sourceHashState, remoteHashState, verificationRevision).joinToString("\n")
        check(body.length + 65 <= MAX_BYTES)
        return (body + "\n" + sha256(body.toByteArray(Charsets.UTF_8))).padEnd(MAX_BYTES, ' ').toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_BYTES = 1024
        const val JOURNAL_BYTES = 2 * MAX_BYTES

        /** Alternate independently checksummed slots; an interrupted update preserves its predecessor. */
        fun decodeJournal(bytes: ByteArray, expectedToken: String): UploadReceipt {
            if (bytes.size !in MAX_BYTES..JOURNAL_BYTES) outcomeUnknown()
            val records = (0..1).mapNotNull { slot ->
                val end = (slot + 1) * MAX_BYTES
                if (end > bytes.size) null else try {
                    decode(bytes.copyOfRange(slot * MAX_BYTES, end), expectedToken)
                } catch (_: space.zhuoling.fileaccess.core.model.StorageException) { null }
            }
            return records.maxByOrNull { it.sequence } ?: outcomeUnknown()
        }

        fun decode(bytes: ByteArray, expectedToken: String): UploadReceipt {
            if (bytes.size != MAX_BYTES) outcomeUnknown()
            val fields = bytes.toString(Charsets.UTF_8).trimEnd(' ').split('\n')
            val legacy = fields.firstOrNull() == "FILEACCESS_UPLOAD_1"
            if (fields.size != (if (legacy) 8 else 14) ||
                fields[0] !in setOf("FILEACCESS_UPLOAD_1", "FILEACCESS_UPLOAD_2") || fields[1] != expectedToken ||
                !expectedToken.matches(Regex("[0-9a-f]{64}"))) outcomeUnknown()
            if (sha256(fields.dropLast(1).joinToString("\n").toByteArray(Charsets.UTF_8)) != fields.last()) outcomeUnknown()
            val phase = Phase.entries.firstOrNull { it.name == fields[2] } ?: outcomeUnknown()
            val fileId = fields[3].toLongOrNull() ?: outcomeUnknown()
            val length = fields[4].toLongOrNull()?.takeIf { it >= 0 } ?: outcomeUnknown()
            fun hash(value: String) = value.isEmpty() || value.matches(Regex("[0-9a-f]{64}"))
            if (!hash(fields[5]) || !hash(fields[6])) outcomeUnknown()
            if (phase in setOf(Phase.READY, Phase.COMMITTED, Phase.RECONCILING, Phase.VERIFYING) && fields[5].isEmpty()) outcomeUnknown()
            val sourceLength = if (legacy) -1 else fields[7].toLongOrNull()?.takeIf { it >= -1 } ?: outcomeUnknown()
            val sequence = if (legacy) 0 else fields[8].toLongOrNull()?.takeIf { it >= 0 } ?: outcomeUnknown()
            if (sourceLength >= 0 && length > sourceLength) outcomeUnknown()
            if (length > 0 && fields[5].isEmpty()) outcomeUnknown()
            val verified = if (legacy) 0 else fields[9].toLongOrNull()?.takeIf { it in 0..length } ?: outcomeUnknown()
            if (!legacy && verified > 0 && (fields[10].isEmpty() || fields[11].isEmpty() || fields[12].isEmpty())) outcomeUnknown()
            return UploadReceipt(expectedToken, phase, fileId, length, fields[5], fields[6], sourceLength, sequence,
                verified, if (legacy) "" else fields[10], if (legacy) "" else fields[11], if (legacy) "" else fields[12])
        }
    }
}
