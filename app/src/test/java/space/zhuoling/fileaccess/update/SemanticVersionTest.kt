package space.zhuoling.fileaccess.update

import org.junit.Assert.*
import org.junit.Test

class SemanticVersionTest {
    @Test fun followsSemverSpecificationOrdering() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.9.0", "1.10.0", "2.0.0")
        versions.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", SemanticVersion.parse(a) < SemanticVersion.parse(b)) }
        assertEquals(0, SemanticVersion.parse("1.0.0+123").compareTo(SemanticVersion.parse("1.0.0+456")))
        assertTrue(SemanticVersion.parse("1.0.0-1") < SemanticVersion.parse("1.0.0--1"))
    }
    @Test fun rejectsInvalidVersions() {
        listOf("1.0", "v1.0.0", "01.0.0", "1.0.0-01", "1.0.0-", "1.0.0+", "1.0.0-alpha..1", " 1.0.0").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { SemanticVersion.parse(it) }
        }
    }
    @Test fun largeNumericIdentifiersDoNotOverflow() {
        assertTrue(SemanticVersion.parse("999999999999999999999.0.0") > SemanticVersion.parse("2.0.0"))
    }
}
