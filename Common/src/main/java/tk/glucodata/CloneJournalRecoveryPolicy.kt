package tk.glucodata

/** Revision rules shared by durable journal recovery tombstones and their tests. */
internal object CloneJournalRecoveryPolicy {
    fun deletionBlocksIncomingEntry(deletedAt: Long, entryUpdatedAt: Long): Boolean {
        require(deletedAt > 0L && entryUpdatedAt > 0L) {
            "Invalid Clone journal recovery revision"
        }
        return deletedAt >= entryUpdatedAt
    }
}
