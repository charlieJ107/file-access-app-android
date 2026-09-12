package space.zhuoling.fileaccess.core.data

import space.zhuoling.fileaccess.core.model.TransferState

/** A committed/unknown operation is reconciled before it can be retried or discarded. */
object TransferTransitions {
    val active = setOf(
        TransferState.PREPARING, TransferState.RUNNING, TransferState.VERIFYING,
        TransferState.COMMITTING, TransferState.RECONCILING,
    )

    fun allows(from: TransferState, to: TransferState): Boolean {
        if (from == to) return true
        return to in when (from) {
            TransferState.QUEUED -> setOf(TransferState.PREPARING, TransferState.WAITING,
                TransferState.PAUSED, TransferState.CANCELLED, TransferState.FAILED)
            TransferState.PREPARING -> setOf(TransferState.RUNNING, TransferState.RECONCILING,
                TransferState.WAITING, TransferState.PAUSED, TransferState.CANCELLED, TransferState.FAILED)
            TransferState.RUNNING -> setOf(TransferState.VERIFYING, TransferState.COMMITTING,
                TransferState.RECONCILING, TransferState.WAITING, TransferState.PAUSED,
                TransferState.CANCELLED, TransferState.FAILED)
            TransferState.VERIFYING -> setOf(TransferState.COMMITTING, TransferState.SUCCEEDED,
                TransferState.RECONCILING, TransferState.WAITING, TransferState.PAUSED, TransferState.FAILED)
            TransferState.COMMITTING -> setOf(TransferState.SUCCEEDED, TransferState.RECONCILING,
                TransferState.FAILED)
            TransferState.RECONCILING -> setOf(TransferState.RUNNING, TransferState.VERIFYING,
                TransferState.COMMITTING, TransferState.SUCCEEDED, TransferState.WAITING, TransferState.FAILED)
            TransferState.WAITING, TransferState.PAUSED, TransferState.FAILED ->
                setOf(TransferState.QUEUED, TransferState.CANCELLED)
            TransferState.SUCCEEDED, TransferState.CANCELLED -> emptySet()
        }
    }
}
