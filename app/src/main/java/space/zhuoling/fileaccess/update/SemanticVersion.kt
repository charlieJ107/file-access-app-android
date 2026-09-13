package space.zhuoling.fileaccess.update

import java.math.BigInteger

/** SemVer 2.0.0 precedence; build metadata never affects ordering. */
internal data class SemanticVersion(
    val major: BigInteger, val minor: BigInteger, val patch: BigInteger,
    val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
            left.compareTo(right).let { if (it != 0) return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when { prerelease == other.prerelease -> 0; prerelease.isEmpty() -> 1; else -> -1 }
        }
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val order = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (order != 0) return order
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(value: String): SemanticVersion {
            val match = requireNotNull(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?").matchEntire(value)) { "版本不符合 Semantic Versioning" }
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            require(pre.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) { "预发布版本数字不能带前导零" }
            return SemanticVersion(match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(), match.groupValues[3].toBigInteger(), pre)
        }
    }
}
