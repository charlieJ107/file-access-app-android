package space.zhuoling.fileaccess.update

/** Certificate encodings obtained from PackageManager after APK signature verification. */
internal data class SigningIdentity(
    val currentSigners: Set<String>,
    /** Oldest to current, including the current signer; empty when no history is available. */
    val history: List<String> = emptyList(),
)

/**
 * Allows the installed key to authorize its successor, never the reverse relationship.
 * A common historical ancestor alone does not authorize an update between two branches.
 * Android's installer remains responsible for checking the lineage's capability flags.
 */
internal fun permitsSigningUpdate(installed: SigningIdentity?, candidate: SigningIdentity?): Boolean {
    if (installed == null || candidate == null || !installed.isConsistent() || !candidate.isConsistent()) return false
    if (installed.currentSigners == candidate.currentSigners) return true
    // Multiple APK signers are an indivisible identity and cannot use signing-key rotation.
    if (installed.currentSigners.size != 1 || candidate.currentSigners.size != 1) return false
    return installed.currentSigners.single() in candidate.history.dropLast(1)
}

private fun SigningIdentity.isConsistent(): Boolean {
    if (currentSigners.isEmpty() || currentSigners.any(String::isBlank)) return false
    if (currentSigners.size > 1) return history.isEmpty()
    return history.isEmpty() || (history.last() == currentSigners.single() &&
        history.none(String::isBlank) && history.distinct().size == history.size)
}
