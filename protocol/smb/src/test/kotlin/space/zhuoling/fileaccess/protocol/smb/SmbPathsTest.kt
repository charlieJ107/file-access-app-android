package space.zhuoling.fileaccess.protocol.smb

import org.junit.Assert.*
import org.junit.Test
import space.zhuoling.fileaccess.core.model.*

class SmbPathsTest {
    private fun config(root: String = "Photos") = ConnectionConfig("a", "NAS", host = "192.168.1.2", share = "data", rootPath = root)

    @Test fun preservesUnicodeAndLiteralPercentSequences() {
        val paths = SmbPaths(config())
        assertEquals("Photos\\假期 & 100%20.jpg", paths.child(paths.ref("Photos"), "假期 & 100%20.jpg"))
    }

    @Test fun normalizesOnlyConfigurationSeparators() {
        val paths = SmbPaths(config("Pictures/2026"))
        assertEquals("Pictures\\2026", paths.root)
        assertInvalid { paths.path(EntryRef("a", "Pictures/2026")) }
    }

    @Test fun rejectsTraversalAndRootPrefixConfusion() {
        val paths = SmbPaths(config())
        listOf("Photos\\..\\secret", "Photos2\\secret", "Photos\\.\\file", "\\Photos", "Photos\\\\file").forEach {
            assertInvalid { paths.path(EntryRef("a", it)) }
        }
    }

    @Test fun rejectsReferencesFromAnotherAccount() {
        assertInvalid { SmbPaths(config()).path(EntryRef("b", "Photos")) }
    }

    @Test fun rejectsWindowsAlternateStreamsAndPathSeparators() {
        listOf("a:b", "a/b", "a\\b", "..", ".", "", "a*", "a?", "a\u0000b", "a\r\nb", "a.", "a ").forEach {
            assertInvalid { SmbPaths.validateName(it) }
        }
    }

    @Test fun rootCannotBeEscapedByAbsoluteOrUrlPaths() {
        listOf("/Photos", "C:\\Photos", "Photos/../private", "smb://server/data", "Photos/").forEach {
            assertInvalid { SmbPaths(config(it)) }
        }
    }

    @Test fun validatesHostPortAndShareWithoutConnecting() {
        listOf("smb://server", "server/share", "user@server", "server\n").forEach {
            assertInvalid { SmbPaths(config().copy(host = it)) }
        }
        assertInvalid { SmbPaths(config().copy(port = 0)) }
        assertInvalid { SmbPaths(config().copy(share = "data/subdirectory")) }
    }

    @Test fun uploadInternalNamesCannotBeChosenByTheUser() {
        val paths = SmbPaths(config())
        assertInvalid { paths.child(paths.ref("Photos"), ".FileAccess-upload-user.part") }
    }

    @Test fun operationKeysIsolateConnectionTargetAndId() {
        val key = SmbPaths.operationToken("job1", "a", "Photos\\same.txt")
        assertEquals(key, SmbPaths.operationToken("job1", "a", "Photos\\same.txt"))
        assertEquals(64, key.length)
        assertNotEquals(key, SmbPaths.operationToken("job1", "b", "Photos\\same.txt"))
        assertNotEquals(key, SmbPaths.operationToken("job1", "a", "Photos\\other.txt"))
        assertNotEquals(key, SmbPaths.operationToken("job2", "a", "Photos\\same.txt"))
    }

    @Test fun childDepthIsValidatedBeforeAnyMutation() {
        val deep = List(64) { "a" }.joinToString("\\")
        val paths = SmbPaths(config(deep))
        assertInvalid { paths.child(paths.ref(deep), "too-deep.txt") }
        assertInvalid { paths.internal(paths.ref(deep), "a".repeat(64), "receipt") }
    }

    private fun assertInvalid(block: () -> Unit) {
        val error = assertThrows(StorageException::class.java, block)
        assertEquals(StorageError.INVALID_CONFIGURATION, error.error)
    }
}
