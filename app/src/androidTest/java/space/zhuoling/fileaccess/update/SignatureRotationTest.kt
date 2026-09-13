package space.zhuoling.fileaccess.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real v3/v3.1 APKs made by scripts/test-signing-rotation.py with disposable test keys. */
@RunWith(AndroidJUnit4::class)
class SignatureRotationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    private fun fixture(argument: String): PackageInfo {
        val path = InstrumentationRegistry.getArguments().getString(argument)
        assumeTrue("Provide $argument from the isolated signing-rotation fixture script", path != null)
        val file = File(requireNotNull(path))
        assertTrue("Fixture must be readable by the test app: $argument", file.isFile && file.canRead())
        // This verifies the complete APK and its native proof-of-rotation, not supplied metadata.
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) {
            "Android rejected the signature or package of fixture $argument"
        }
        assertTrue("Fixtures must exercise APK v3/v3.1 verification", requireNotNull(info.signingInfo).schemeVersion >= 3)
        return info
    }

    private fun identities(argument: String): Pair<SigningIdentity, SigningIdentity> {
        val base = fixture("rotationBaseApk")
        val candidate = fixture(argument)
        assertEquals(base.packageName, candidate.packageName)
        assertTrue("Every candidate, including rejected keys, must have a newer Android version", candidate.longVersionCode > base.longVersionCode)
        assertTrue("Every candidate must have a newer SemVer", SemanticVersion.parse(requireNotNull(candidate.versionName)) >
            SemanticVersion.parse(requireNotNull(base.versionName)))
        val installed = requireNotNull(base.signingInfo.verifiedSigningIdentity())
        val update = requireNotNull(candidate.signingInfo.verifiedSigningIdentity())
        assertEquals("Base fixture must be A -> B", 2, installed.history.size)
        assertEquals(setOf(installed.history.last()), installed.currentSigners)
        return installed to update
    }

    @Test fun acceptsSameCurrentSigningKey() {
        val (installed, candidate) = identities("rotationSameApk")
        assertEquals(installed.currentSigners, candidate.currentSigners)
        assertTrue(permitsSigningUpdate(installed, candidate))
    }

    @Test fun acceptsVerifiedForwardRotation() {
        val (installed, candidate) = identities("rotationForwardApk")
        assertEquals("Forward fixture must be A -> B -> C", 3, candidate.history.size)
        assertNotEquals(installed.currentSigners, candidate.currentSigners)
        assertTrue(permitsSigningUpdate(installed, candidate))
    }

    @Test fun acceptsVerifiedForwardRotationWithTruncatedHistory() {
        val (installed, candidate) = identities("rotationForwardTruncatedApk")
        assertEquals("Truncated fixture must be B -> C", 2, candidate.history.size)
        assertEquals(installed.currentSigners.single(), candidate.history.first())
        assertNotEquals(installed.currentSigners, candidate.currentSigners)
        assertTrue(permitsSigningUpdate(installed, candidate))
    }

    @Test fun rejectsAnOlderSigningKeyEvenWithAHigherVersion() {
        val (installed, candidate) = identities("rotationRollbackApk")
        assertTrue(candidate.currentSigners.single() in installed.history.dropLast(1))
        assertFalse(permitsSigningUpdate(installed, candidate))
    }

    @Test fun rejectsAVerifiedForkThatOnlySharesAnAncestor() {
        val (installed, candidate) = identities("rotationForkApk")
        assertEquals(installed.history.first(), candidate.history.first())
        assertFalse(installed.currentSigners.single() in candidate.history)
        assertFalse(permitsSigningUpdate(installed, candidate))
    }

    @Test fun rejectsAnUnrelatedSigningKey() {
        val (installed, candidate) = identities("rotationUnrelatedApk")
        assertTrue(candidate.history.none { it in installed.history })
        assertFalse(permitsSigningUpdate(installed, candidate))
    }
}
