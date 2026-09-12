package space.zhuoling.fileaccess.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import space.zhuoling.fileaccess.core.model.EntryRef

class BackupOperationIdTest {
    private val target = EntryRef("nas", "photos")
    @Test fun rescansHaveStableIdentityButNewContentAndTargetsDoNot() {
        fun id(generation: String = "1", ref: EntryRef = target, revision: Long = 1) =
            BackupOperationId.create("camera", "image/42", generation, ref, revision)
        assertEquals(id(), id())
        assertNotEquals(id(), id(generation = "2"))
        assertNotEquals(id(), id(ref = EntryRef("other-nas", "photos")))
        assertNotEquals(id(), id(revision = 2))
    }

    @Test fun fieldDelimitersCannotCreateAmbiguousIdentity() {
        assertNotEquals(
            BackupOperationId.create("a/b", "c", "1", target, 1),
            BackupOperationId.create("a", "b/c", "1", target, 1),
        )
    }
}
