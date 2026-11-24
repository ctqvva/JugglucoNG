package tk.glucodata

/**
 * What became of a history batch handed to the store.
 *
 * Being accepted is not being stored: [QUEUED] only says the batch will be
 * written. A caller that keeps track of which readings it still has to fetch
 * must wait for one of the final outcomes.
 */
enum class HistoryWriteOutcome {
    /** Accepted and waiting. The listener hears the final outcome; every other value is final. */
    QUEUED,

    /** In the database (or replaced there by a newer write of the same minute). */
    PERSISTED,

    /** Nothing to write: no usable reading, readings the user deleted, or a device that keeps no Room history. */
    SKIPPED,

    /** Not stored, and not retried any more. The readings have to be fetched again. */
    FAILED,
}

/**
 * Told the final outcome of a batch submitted with [HistoryWriteOutcome.QUEUED],
 * once, on a background thread. A process that dies first never calls it; the
 * batch itself is then written by the next process.
 */
fun interface HistoryWriteListener {
    fun onHistoryWriteSettled(outcome: HistoryWriteOutcome)
}
