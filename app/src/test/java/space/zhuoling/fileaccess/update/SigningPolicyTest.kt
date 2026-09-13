package space.zhuoling.fileaccess.update

import org.junit.Assert.*
import org.junit.Test

class SigningPolicyTest {
    private fun single(current: String, vararg history: String) = SigningIdentity(setOf(current), history.toList())

    @Test fun acceptsSameCurrentKeyWithOrWithoutHistory() {
        assertTrue(permitsSigningUpdate(single("B", "A", "B"), single("B")))
        assertTrue(permitsSigningUpdate(single("B"), single("B", "A", "B")))
    }

    @Test fun acceptsNativeForwardRotationAndSkippingIntermediateVersions() {
        assertTrue(permitsSigningUpdate(single("A"), single("B", "A", "B")))
        assertTrue(permitsSigningUpdate(single("A"), single("C", "A", "B", "C")))
        assertTrue(permitsSigningUpdate(single("B", "A", "B"), single("C", "A", "B", "C")))
    }

    @Test fun acceptsAuthorizedTruncatedLineageWithoutRequiringAnIdenticalPrefix() {
        assertTrue(permitsSigningUpdate(single("B", "A", "B"), single("C", "B", "C")))
        assertTrue(permitsSigningUpdate(single("B"), single("C", "A", "B", "C")))
    }

    @Test fun rejectsRollbackToAnyOlderKeyEvenThoughItIsInInstalledHistory() {
        assertFalse(permitsSigningUpdate(single("B", "A", "B"), single("A")))
        assertFalse(permitsSigningUpdate(single("C", "A", "B", "C"), single("B", "A", "B")))
    }

    @Test fun rejectsForksThatOnlyShareAnAncestor() {
        assertFalse(permitsSigningUpdate(single("B", "A", "B"), single("C", "A", "C")))
        assertFalse(permitsSigningUpdate(single("C", "A", "B", "C"), single("D", "A", "B", "D")))
    }

    @Test fun rejectsAnUnrelatedKeyAndAnUnprovenRotation() {
        assertFalse(permitsSigningUpdate(single("A"), single("B")))
        assertFalse(permitsSigningUpdate(single("A"), single("C", "B", "C")))
    }

    @Test fun multipleSignersMustMatchTheEntireSetInAnyOrder() {
        val installed = SigningIdentity(setOf("A", "B"))
        assertTrue(permitsSigningUpdate(installed, SigningIdentity(setOf("B", "A"))))
        assertFalse(permitsSigningUpdate(installed, SigningIdentity(setOf("A", "C"))))
        assertFalse(permitsSigningUpdate(installed, SigningIdentity(setOf("A", "B", "C"))))
    }

    @Test fun cannotReplaceMultipleSignersWithASingleSignerOrRotationHistory() {
        val multiple = SigningIdentity(setOf("A", "B"))
        assertFalse(permitsSigningUpdate(multiple, single("A")))
        assertFalse(permitsSigningUpdate(single("A"), multiple))
        assertFalse(permitsSigningUpdate(multiple, single("C", "A", "B", "C")))
        assertFalse(permitsSigningUpdate(single("B", "A", "B"), multiple))
    }

    @Test fun missingIdentityAndEmptyCertificatesFailClosed() {
        for (invalid in listOf(null, SigningIdentity(emptySet()), single(""))) {
            assertFalse(permitsSigningUpdate(single("A"), invalid))
            assertFalse(permitsSigningUpdate(invalid, single("A")))
        }
    }

    @Test fun malformedHistoriesCannotSupplyTrust() {
        for (invalid in listOf(single("C", "A", "B"), single("C", "A", "A", "C"),
            single("C", "A", "", "C"), SigningIdentity(setOf("A", "B"), listOf("A", "B")))) {
            assertFalse(permitsSigningUpdate(single("A"), invalid))
            assertFalse(permitsSigningUpdate(invalid, single("A")))
        }
    }
}
