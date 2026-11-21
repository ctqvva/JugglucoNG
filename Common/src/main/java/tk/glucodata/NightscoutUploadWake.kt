package tk.glucodata

/**
 * Bridges managed-driver native poll writes to the Nightscout uploader.
 */
object NightscoutUploadWake {
    private const val TAG = "NightscoutWake"

    /**
     * A journal entry was written, changed or deleted.
     *
     * Cheap enough to call per row: the uploader collapses everything raised before its next
     * pass into one, and one pass sends the whole backlog. Silent when treatments are not
     * being sent, so nothing wakes a network thread for a setting that is off.
     *
     * A follower has no uploader thread at all (Follow and Upload are exclusive), so when it
     * sends its entries they go through their own queue instead.
     */
    @JvmStatic
    fun afterJournalChange() {
        if (followerSendsTreatments()) {
            JournalTreatmentUploadAccess.sendAsFollower()
            return
        }
        if (!runCatching { Natives.getpostTreatments() }.getOrDefault(false)) return
        runCatching {
            Natives.waketreatments()
        }.onFailure {
            Log.stack(TAG, "journal wake failed", it)
        }
    }

    /**
     * The journal was switched back on. Passes that ran while it was off sent and imported
     * nothing and cleared their retry, so ask for one now. Unlike [afterJournalChange] this
     * also covers receive-only: it runs once per switch, so it cannot feed the receive loop.
     */
    @JvmStatic
    fun afterJournalEnabled() {
        if (followerSendsTreatments()) {
            JournalTreatmentUploadAccess.sendAsFollower()
            return
        }
        val wanted = runCatching {
            Natives.getpostTreatments() || JournalTreatmentUploadAccess.getReceiveTreatments()
        }.getOrDefault(false)
        if (!wanted) return
        runCatching {
            Natives.waketreatments()
        }.onFailure {
            Log.stack(TAG, "journal enable wake failed", it)
        }
    }

    /**
     * Follow mode with sending on. Not while the native uploader is also on (the legacy
     * screen can leave both set): that one already sends, and two senders would race.
     */
    @JvmStatic
    fun followerSendsTreatments(): Boolean =
        runCatching {
            !Natives.getuseuploader() &&
                tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry.sendsTreatments(Applic.app)
        }.getOrDefault(false)

    @JvmStatic
    fun afterLiveNativeWrite(source: String, timestampMillis: Long) = wakeGlucose(source, timestampMillis)

    /** Wake once after writing a recovered or corrected batch, even if no live frame follows. */
    fun afterHistoryNativeWrite(source: String, timestampMillis: Long) = wakeGlucose("$source backfill", timestampMillis)

    private fun wakeGlucose(source: String, timestampMillis: Long) {
        if (timestampMillis <= 0L || !Natives.getuseuploader()) return
        runCatching {
            Natives.wakeNightscoutForLiveReading(source, timestampMillis)
        }.onFailure {
            Log.stack(TAG, "wake failed source=$source timestamp=$timestampMillis", it)
        }
    }
}
