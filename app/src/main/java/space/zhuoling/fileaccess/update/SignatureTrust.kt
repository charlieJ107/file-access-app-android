package space.zhuoling.fileaccess.update

import android.content.pm.SigningInfo

/** Only pass SigningInfo returned by PackageManager, never metadata supplied by a release. */
internal fun SigningInfo?.verifiedSigningIdentity(): SigningIdentity? {
    if (this == null) return null
    val signers = apkContentsSigners?.map { it.toCharsString() }.orEmpty()
    if (signers.isEmpty() || signers.distinct().size != signers.size || hasMultipleSigners() != (signers.size > 1)) return null
    val history = if (hasMultipleSigners()) emptyList() else signingCertificateHistory?.map { it.toCharsString() }.orEmpty()
    return SigningIdentity(signers.toSet(), history)
}
