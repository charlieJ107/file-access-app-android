package space.zhuoling.fileaccess.core.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferExecutionTest {
    @Test fun pausingOneTransferDoesNotStopTheNextTransferInTheBatch() = runBlocking {
        runTransferChild {
            currentCoroutineContext().cancel()
            currentCoroutineContext().ensureActive()
        }
        var nextTaskRan = false
        runTransferChild { nextTaskRan = true }
        assertTrue(nextTaskRan)
    }

    @Test fun stoppingTheSystemJobDoesNotRunItsRemainingTransfers() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var nextTaskRan = false
        val batch = launch {
            runTransferChild {
                started.complete(Unit)
                awaitCancellation()
            }
            nextTaskRan = true
        }
        started.await()
        batch.cancelAndJoin()
        assertFalse(nextTaskRan)
    }
}
