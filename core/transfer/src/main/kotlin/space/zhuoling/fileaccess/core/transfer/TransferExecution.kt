package space.zhuoling.fileaccess.core.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A user can pause one child while the batch continues; cancelling the batch still stops it. */
internal suspend fun runTransferChild(block: suspend CoroutineScope.() -> Unit) {
    try { coroutineScope(block) }
    catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
}
