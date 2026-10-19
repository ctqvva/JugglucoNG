package tk.glucodata.data.calibration

import org.json.JSONObject

/**
 * Decides whether a calibration profile arriving over the QR-mirror link may replace the
 * local one.
 *
 * A mirror import is destructive: it deletes the target sensor's calibrations before
 * inserting the peer's. A phone used purely as an NFC reader has no calibrations of its
 * own, so applying its profile unconditionally erased the calibration on the phone the
 * user actually calibrates. Every profile therefore carries a revision, and only a
 * strictly newer one wins.
 */
object MirrorCalibrationProfilePolicy {

    /**
     * Newest edit the incoming profile represents. Peers running a build from before
     * revisions existed send no `revision` field, so their newest calibration timestamp
     * stands in for it — that keeps a populated legacy profile from losing to an empty
     * modern one and vice versa.
     */
    fun incomingRevision(root: JSONObject): Long {
        var newest = root.optLong("revision", 0L)
        val array = root.optJSONArray("calibrations")
        if (array != null) {
            for (i in 0 until array.length()) {
                val timestamp = array.optJSONObject(i)?.optLong("timestamp", 0L) ?: 0L
                if (timestamp > newest) newest = timestamp
            }
        }
        return newest
    }

    /**
     * How recent the local profile is. The stored stamp never sits below the newest
     * calibration row, so profiles written before revisions existed still compare
     * sensibly.
     */
    fun localRevision(storedRevision: Long, newestCalibrationTimestamp: Long): Long =
        maxOf(storedRevision, newestCalibrationTimestamp)

    /** Stamp claiming the local profile is now the newest one. */
    fun nextRevision(now: Long, localRevision: Long): Long = maxOf(now, localRevision + 1)

    fun shouldApply(incomingRevision: Long, localRevision: Long): Boolean =
        incomingRevision > localRevision

    /**
     * Equal revisions mean both sides already agree; only a strictly older peer needs our
     * profile offered back to it.
     */
    fun shouldOfferLocalBack(incomingRevision: Long, localRevision: Long): Boolean =
        incomingRevision < localRevision
}
