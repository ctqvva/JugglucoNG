package tk.glucodata

/**
 * Who reads a given sensor: this device, or the one at the other end of the
 * Data Layer.
 *
 * Handing a sensor over is not a switch, it is an agreement between two devices
 * that can each vanish without warning. The rules below are written so that the
 * failure modes land on the safe side: when anything is unclear both devices
 * read the sensor, which wastes a connection attempt, rather than neither
 * reading it, which loses glucose data.
 */
object SensorOwnershipPolicy {
    /** What the other device last told us about a sensor. */
    data class PeerReport(
        /** The peer says it holds a live connection and is getting readings. */
        val owns: Boolean,
        /** Peer's newest accepted reading for this sensor, its clock. */
        val lastReadingMs: Long,
        /** When we received this, our clock. Never compared with peer times. */
        val receivedAtMs: Long,
    )

    /**
     * Whether this device should be reading [serial] itself.
     *
     * @param localAllowed the user's intent — a device told not to take the
     *   sensor never takes it, however quiet the other side goes.
     * @param localHasConnection this process holds a live GATT and has accepted
     *   a reading from it.
     * @param peerSilentAfterMs how long without a report before the peer counts
     *   as gone.
     */
    fun shouldReadLocally(
        isPhone: Boolean,
        localAllowed: Boolean,
        localHasConnection: Boolean,
        localLastReadingMs: Long,
        peer: PeerReport?,
        nowMs: Long,
        peerSilentAfterMs: Long,
    ): Boolean {
        // The user's choice comes first: never grab a sensor we were told to
        // leave alone.
        if (!localAllowed) return false

        // Nothing heard, or nothing heard recently enough to trust: read. The
        // peer may be out of range, powered off, or its app killed, and none of
        // those are distinguishable from here.
        val peerIsCurrent = peer != null && nowMs - peer.receivedAtMs <= peerSilentAfterMs
        if (!peerIsCurrent) return true

        // The peer is there but not reading it either.
        if (!peer!!.owns) return true

        // The peer is reading it and we are not: leave it to them.
        if (!localHasConnection) return false

        // Both of us hold the sensor. Whoever has the fresher reading keeps it;
        // an exact tie goes to the phone, so the two devices always reach the
        // same answer and cannot trade it back and forth.
        if (localLastReadingMs != peer.lastReadingMs) {
            return localLastReadingMs > peer.lastReadingMs
        }
        return isPhone
    }
}
