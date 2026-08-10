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
    /** What this device is meant to do with a sensor. */
    enum class Intent {
        /** Read it whenever the peer is not. */
        TAKE,

        /**
         * This device is the user's assigned owner. Keep or acquire the sensor
         * even while a stale peer report still says the other device has it.
         * The peer's matching [YIELD] intent makes the two decisions converge.
         */
        PREFER,

        /**
         * Stand aside so the peer can connect, but only until a deadline.
         *
         * Sensors that serve one client at a time cannot be handed over any
         * other way: the peer can never get a reading — and so can never claim
         * ownership — while this device holds the connection. The deadline is
         * what stops that becoming "nobody reads it": once it passes without the
         * peer taking over, this device goes back to reading.
         */
        YIELD,

        /** Never read it; the user assigned it elsewhere. */
        NEVER,
    }

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
        intent: Intent,
        localHasConnection: Boolean,
        localLastReadingMs: Long,
        peer: PeerReport?,
        nowMs: Long,
        peerSilentAfterMs: Long,
        yieldUntilMs: Long = 0L,
    ): Boolean {
        // The user's choice comes first: never grab a sensor we were told to
        // leave alone.
        if (intent == Intent.NEVER) return false

        // Handing over: let go long enough for the peer to actually connect.
        // Bounded, so a peer that cannot take it does not leave the sensor unread.
        if (intent == Intent.YIELD && nowMs < yieldUntilMs) return false

        // Nothing heard, or nothing heard recently enough to trust: read. The
        // peer may be out of range, powered off, or its app killed, and none of
        // those are distinguishable from here.
        val peerIsCurrent = peer != null && nowMs - peer.receivedAtMs <= peerSilentAfterMs
        if (!peerIsCurrent) return true

        // The peer is there but not reading it either.
        if (!peer!!.owns) return true

        // The assigned watch must not tear down a proven live GATT because the
        // phone's last pre-release announcement has the same reading timestamp.
        // That exact race caused the first hardware handoff to collapse.
        if (intent == Intent.PREFER) return true

        // The user assigned this sensor to the peer. A local reconnect at the
        // same minute must not win it back on the phone tie-breaker; that was
        // the ownership flap seen on hardware. A current peer ownership proof
        // keeps the assignment until the peer drops or goes silent.
        if (intent == Intent.YIELD) return false

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
