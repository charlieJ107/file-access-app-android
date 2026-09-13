package space.zhuoling.fileaccess.update

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseInfoTest {
    private val repository = "owner/repo"
    private fun release(tag: String = "v1.10.0", code: Long = 12, draft: Boolean = false,
        prerelease: Boolean = false, url: String? = null, digest: String? = "sha256:${"ab".repeat(32)}",
        size: Long = 100, duplicate: Boolean = false): String = buildJsonObject {
        put("tag_name", tag); put("draft", draft); put("prerelease", prerelease)
        put("body", JsonNull)
        putJsonArray("assets") {
            repeat(if (duplicate) 2 else 1) {
                add(buildJsonObject {
                    put("name", "fileaccess-$code.apk"); put("size", size); put("state", "uploaded")
                    put("browser_download_url", url ?: "https://github.com/$repository/releases/download/$tag/fileaccess-$code.apk")
                    put("digest", digest?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
    }.toString()
    private fun parse(body: String) = parseRelease(body, repository, "1.9.0", 10)

    @Test fun numericSemverFindsUpdate() {
        val result = requireNotNull(parse(release()))
        assertEquals("1.10.0", result.versionName)
        assertEquals(12L, result.versionCode)
        assertEquals("", result.notes)
    }
    @Test fun ignoresOlderEqualDraftAndPrerelease() {
        assertNull(parse(release(tag = "v1.8.0", code = 100)))
        assertNull(parse(release(tag = "v1.9.0", code = 100)))
        assertNull(parse(release(tag = "v1.9.0+build.100", code = 100)))
        assertNull(parse(release(draft = true)))
        assertNull(parse(release(prerelease = true)))
        assertNull(parse(release(tag = "v2.0.0-rc.1")))
    }
    @Test fun androidCodeCannotOverrideSemverOrPreventInstallSilently() {
        assertThrows(IllegalArgumentException::class.java) { parse(release(code = 10)) }
    }
    @Test fun rejectsUntrustedOrAmbiguousAssets() {
        for (url in listOf("http://github.com/owner/repo/a.apk", "https://evil.example/a.apk",
            "https://github.com/other/repo/releases/download/v1.10.0/fileaccess-12.apk",
            "https://github.com/owner/repo/releases/download/v1.10.0/fileaccess-12.apk?redirect=1",
            "https://user@github.com/owner/repo/releases/download/v1.10.0/fileaccess-12.apk")) {
            assertThrows(IllegalArgumentException::class.java) { parse(release(url = url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(release(duplicate = true)) }
        assertThrows(IllegalArgumentException::class.java) { parse(release(digest = null)) }
        assertThrows(IllegalArgumentException::class.java) { parse(release(digest = "sha1:123")) }
        assertThrows(IllegalArgumentException::class.java) { parse(release(size = 0)) }
        assertThrows(IllegalArgumentException::class.java) { parse(release(size = MAX_APK_BYTES + 1)) }
    }
    @Test fun throttlesAutomaticChecksAndHandlesClockChanges() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(automaticCheckDue(true, 0, day))
        assertFalse(automaticCheckDue(false, 0, day))
        assertFalse(automaticCheckDue(true, day, 2 * day - 1))
        assertTrue(automaticCheckDue(true, day, 2 * day))
        assertTrue(automaticCheckDue(true, day, day - 1))
    }
}
