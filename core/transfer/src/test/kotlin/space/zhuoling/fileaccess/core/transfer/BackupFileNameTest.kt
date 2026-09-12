package space.zhuoling.fileaccess.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileNameTest {
    @Test fun rescansKeepANameWhileChangedOriginalsGetAnotherName() {
        assertEquals(backupFileName("photo.jpg", "content://photo/1", "v1"),
            backupFileName("photo.jpg", "content://photo/1", "v1"))
        assertNotEquals(backupFileName("photo.jpg", "content://photo/1", "v1"),
            backupFileName("photo.jpg", "content://photo/1", "v2"))
        assertNotEquals(backupFileName("photo.jpg", "content://photo/1", "v1"),
            backupFileName("photo.jpg", "content://photo/2", "v1"))
    }

    @Test fun hostileNamesCannotBecomePathsAndExtensionIsPreserved() {
        val name = backupFileName("../../a\\b:photo?.jpg", "source", "v1")
        assertFalse(name.any { it in "/\\:*?\"<>|" })
        assertTrue(name.endsWith(".jpg"))
    }

    @Test fun longUnicodeNamesStayWithinCommonNasByteLimitsWithoutSplittingSurrogates() {
        val name = backupFileName("📷照片".repeat(200) + ".jpg", "source", "v1")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(name, name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertTrue(name.endsWith(".jpg"))
    }
}
