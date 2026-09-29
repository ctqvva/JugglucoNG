package tk.glucodata.drivers.aidex

/**
 * Decisions of the AiDex setup wizard's connecting step. Free of Android types so the JVM tests
 * can pin them; the wizard feeds in what it polls from the driver and the Bluetooth adapter.
 */
object AiDexSetupPolicy {

    enum class ConnectingState {
        /** Link, discovery or handshake still under way. */
        CONNECTING,
        /** Android is pairing: the user has to confirm the system pairing request. */
        AWAITING_PAIRING_CONFIRMATION,
        /** The driver gave the link up (broadcast-only) without a session; the user may retry. */
        NOT_CONNECTED,
        /** Handshake done and the driver is streaming from this sensor. */
        READY,
        /** No session before the deadline. */
        TIMED_OUT,
        /**
         * Out of time with the link down, on a sensor this setup added that stays registered
         * anyway (see [decideConnectingState]), so the setup ends as added. The link may come back on its
         * own, or stay down (a driver held in broadcast-only) until the user reconnects from the card.
         */
        KEPT,
    }

    /**
     * [sessionEstablished] means the driver streams from this sensor, which it only reaches after
     * a key exchange whose BOND data decrypted and passed its CRC — the handshake is proven, and
     * a new sensor has no glucose to wait for until its warm-up is over.
     *
     * A pairing in progress holds off [deadlineMs]: the rollback behind a timeout would tear the
     * link down under the SMP exchange the user is confirming. Android ends a pairing within
     * about 30 s, and [hardDeadlineMs] bounds the wait even if the bond state never leaves
     * BONDING. A driver that gave up waits for the user until [notConnectedDeadlineMs]: the
     * screen says why and offers a retry, and once that runs out the setup ends like any other
     * timeout — a new sensor is rolled back before the driver's own retry (ten minutes on) can
     * raise another pairing prompt nobody is there to answer.
     *
     * A timeout on a sensor this setup added that [sensorStays] registered — the rollback keeps it
     * for a stored PAIR key or a completed key exchange — is [ConnectingState.KEPT], not a
     * failure: telling the user the pairing failed while the sensor stays would be wrong. A
     * configured sensor's failed re-setup is still [ConnectingState.TIMED_OUT] (it is not removed).
     */
    fun decideConnectingState(
        sessionEstablished: Boolean,
        pairingInProgress: Boolean,
        driverGaveUp: Boolean,
        sensorStays: Boolean,
        nowMs: Long,
        deadlineMs: Long,
        hardDeadlineMs: Long,
        notConnectedDeadlineMs: Long,
    ): ConnectingState = when {
        sessionEstablished -> ConnectingState.READY
        pairingInProgress && nowMs < hardDeadlineMs -> ConnectingState.AWAITING_PAIRING_CONFIRMATION
        driverGaveUp && nowMs < notConnectedDeadlineMs -> ConnectingState.NOT_CONNECTED
        !driverGaveUp && nowMs < deadlineMs -> ConnectingState.CONNECTING
        sensorStays -> ConnectingState.KEPT
        else -> ConnectingState.TIMED_OUT
    }

    /** [notConnectedMs] is 0 while the driver has not given up. */
    data class Deadlines(val softMs: Long, val hardMs: Long, val notConnectedMs: Long = 0L)

    fun initialDeadlines(nowMs: Long, sessionTimeoutMs: Long, hardTimeoutMs: Long): Deadlines =
        Deadlines(softMs = nowMs + sessionTimeoutMs, hardMs = nowMs + hardTimeoutMs)

    /**
     * The deadlines for the next poll.
     *
     * While the driver has given up, the connect deadlines are not restarted: the soft one no
     * longer applies (it only moves for a pairing, below), and the hard one, which still ends a
     * pairing seen meanwhile, never moves.
     * The screen waits for the user, bounded by [notConnectedLimitMs] from the first poll that saw
     * the driver give up (not moved by later polls). When the driver tries again on its own (its
     * timed exit from broadcast-only), that attempt gets full connect deadlines from now, whether
     * or not Android is already pairing for it: measured from the old start it would time out at
     * once and roll the sensor back in the middle of its own connect. A Retry does not come
     * through here; it starts a new wait with fresh deadlines.
     *
     * While Android pairs, the soft deadline is pushed to at least [graceMs] past [nowMs]: a
     * pairing confirmed late still needs the handshake after it, and a pairing that fails shows
     * up as broadcast-only only a few seconds after the bond drops; a timeout in either gap would
     * roll the sensor back. Never past the hard deadline.
     */
    fun nextDeadlines(
        current: Deadlines,
        nowMs: Long,
        pairingInProgress: Boolean,
        driverGaveUp: Boolean,
        sessionTimeoutMs: Long,
        hardTimeoutMs: Long,
        graceMs: Long,
        notConnectedLimitMs: Long,
    ): Deadlines {
        val notConnectedMs = when {
            !driverGaveUp -> 0L
            current.notConnectedMs > 0L -> current.notConnectedMs
            else -> nowMs + notConnectedLimitMs
        }
        // notConnectedMs is only ever set on a poll that saw the driver give up.
        val driverTriesAgain = !driverGaveUp && current.notConnectedMs > 0L
        val base = if (driverTriesAgain) initialDeadlines(nowMs, sessionTimeoutMs, hardTimeoutMs) else current
        val softMs = if (pairingInProgress) minOf(base.hardMs, maxOf(base.softMs, nowMs + graceMs)) else base.softMs
        return base.copy(softMs = softMs, notConnectedMs = notConnectedMs)
    }

    enum class NotConnectedReason { PAIRING_NOT_CONFIRMED, CONNECT_FAILED }

    /**
     * Why the driver gave up, for the screen text. Android pairing on this attempt that never
     * reached BONDED was not confirmed; anything else — no pairing needed, or a confirmed pairing
     * whose handshake then failed — is a failed connect.
     */
    fun notConnectedReason(sawPairing: Boolean, sawBonded: Boolean): NotConnectedReason =
        if (sawPairing && !sawBonded) NotConnectedReason.PAIRING_NOT_CONFIRMED else NotConnectedReason.CONNECT_FAILED

    /**
     * Whether abandoning setup may remove the sensor again. One whose handshake has finished at
     * least once — a stored PAIR credential, or a key exchange this driver completed even if the
     * link dropped since — stays: removing it throws away a pairing the sensor keeps on its own
     * side. A momentary "connected" is not the test: the link can blink as the user taps Back.
     */
    @JvmStatic
    fun mayRollBack(vendorPaired: Boolean, handshakeCompleted: Boolean): Boolean =
        !vendorPaired && !handshakeCompleted
}
