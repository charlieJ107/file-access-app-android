package space.zhuoling.fileaccess.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.zhuoling.fileaccess.core.model.TransferState

class TransferTransitionsTest {
    @Test fun completionRequiresVerificationOrCommit() {
        assertFalse(TransferTransitions.allows(TransferState.RUNNING, TransferState.SUCCEEDED))
        assertTrue(TransferTransitions.allows(TransferState.VERIFYING, TransferState.SUCCEEDED))
        assertTrue(TransferTransitions.allows(TransferState.COMMITTING, TransferState.SUCCEEDED))
    }
    @Test fun cancelCannotClaimToUndoAnUnknownCommit() {
        assertFalse(TransferTransitions.allows(TransferState.COMMITTING, TransferState.CANCELLED))
        assertFalse(TransferTransitions.allows(TransferState.RECONCILING, TransferState.QUEUED))
        assertTrue(TransferTransitions.allows(TransferState.COMMITTING, TransferState.RECONCILING))
    }
    @Test fun completedAndCancelledTasksCannotRestart() {
        assertFalse(TransferTransitions.allows(TransferState.SUCCEEDED, TransferState.QUEUED))
        assertFalse(TransferTransitions.allows(TransferState.CANCELLED, TransferState.RUNNING))
        assertTrue(TransferTransitions.allows(TransferState.PAUSED, TransferState.QUEUED))
    }
}
