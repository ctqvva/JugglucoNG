package tk.glucodata

/**
 * Bridges managed-driver live writes to the native Nightscout uploader.
 *
 * Call this only after the matching point has been written to native poll storage. History
 * backfills deliberately do not wake the uploader; the next live point or explicit full sync
 * handles them without producing one network wake per historical row.
 */
object NightscoutUploadWake {
    private const val TAG = "NightscoutWake"

    /**
     * A journal entry was written, changed or deleted.
     *
     * Cheap enough to call per row: the uploader collapses everything raised before its next
     * pass into one, and one pass sends the whole backlog. Silent when treatments are not
     * being sent, so nothing wakes a network thread for a setting that is off.
     */
    @JvmStatic
    fun afterJournalChange() {
        if (!runCatching { Natives.getpostTreatments() }.getOrDefault(false)) return
        runCatching {
            Natives.waketreatments()
        }.onFailure {
            Log.stack(TAG, "journal wake failed", it)
        }
    }

    @JvmStatic
    fun afterLiveNativeWrite(source: String, timestampMillis: Long) {
        if (timestampMillis <= 0L || !Natives.getuseuploader()) return
        runCatching {
            Natives.wakeNightscoutForLiveReading(source, timestampMillis)
        }.onFailure {
            Log.stack(TAG, "wake failed source=$source timestamp=$timestampMillis", it)
        }
    }
}
