package space.zhuoling.fileaccess.update

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateValidationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = UpdateRepository(context)

    private fun metadata(file: File): ReleaseInfo {
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0)))
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return ReleaseInfo(info.longVersionCode, requireNotNull(info.versionName), "", "", file.length(), hash.digest().joinToString("") { "%02x".format(it) })
    }

    @Test fun fileProviderSharesOnlyUpdateDirectory() {
        val allowed = File(context.filesDir, "updates/provider-test.apk")
        assertEquals("content", FileProvider.getUriForFile(context, "${context.packageName}.updates", allowed).scheme)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "credentials/private.bin"))
        }
    }

    @Test fun rejectsDamagedAndSameVersionApk() = runBlocking<Unit> {
        val installed = File(context.applicationInfo.sourceDir)
        val release = metadata(installed)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.validate(installed, release.copy(sha256 = "0".repeat(64))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.validate(installed, release.copy(size = release.size + 1)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.validate(installed, release) } }
    }

    @Test fun acceptsNewerSameSignedApkAndRejectsMismatchedRelease() = runBlocking<Unit> {
        val fixturePath = InstrumentationRegistry.getArguments().getString("updateFixture")
        assumeTrue("Provide a newer, same-signed APK using updateFixture", fixturePath != null)
        val file = File(requireNotNull(fixturePath))
        val release = metadata(file)
        repository.validate(file, release)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.validate(file, release.copy(versionName = "99.0.0")) } }
    }
}
