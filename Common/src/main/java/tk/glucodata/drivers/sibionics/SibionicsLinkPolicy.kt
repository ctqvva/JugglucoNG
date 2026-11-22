package tk.glucodata.drivers.sibionics

/** Where one Sibionics BLE session is. Owned by [SibionicsBleManager]. */
internal enum class SibionicsLinkPhase {
    IDLE,
    CONNECTING,
    DISCOVERING,
    ENABLING_NOTIFY,
    PROBING_CHINESE,
    AUTHENTICATING,
    ACTIVATING,
    SYNCING_TIME,
    REQUESTING_DATA,
    STREAMING,
    ERROR,
}

/**
 * Why an enabled driver has no session. IDLE alone used to cover all of these, so
 * the card read "Disconnected" while the driver was scanning for its sensor or about
 * to reconnect, and nothing could tell a driver that had given up from one that
 * was a second away from its next attempt.
 */
internal enum class SibionicsIdleReason {
    /** Nothing is scheduled. For an enabled driver this is a hole the watchdog closes. */
    NONE,
    RECONNECT_SCHEDULED,
    /** The driver's own scan for the saved address is running. */
    SEARCHING,
    /** No address or device yet: only the shared scanner can find it. */
    NEEDS_SCAN,
    BLUETOOTH_OFF,
}

/** What the sensor card says, in one place, for the header and the notification alike. */
internal enum class SibionicsLinkView {
    DISCONNECTED,
    BLUETOOTH_OFF,
    /** Scanning, waiting for the next attempt or opening the link: all one "Connecting…". */
    CONNECTING,
    /** Inside a connection: the phase's own step text, unchanged from before. */
    PHASE_STEP,
    WAITING_FOR_DATA,
    HISTORY,
    CONNECTED,
    FAILED,
}

/** The last thing that went wrong on the link, for the card's "Last BLE status" row. */
internal enum class SibionicsLinkNotice {
    NONE,
    /** A connect attempt ended with a GATT status code. */
    LINK_STATUS,
    /** The saved address was not advertising when we looked. */
    NOT_FOUND,
    AUTH_FAILED,
    NOT_RESPONDING,
    /** FF30/FF31/FF32 absent or service discovery failed. */
    SERVICE_MISSING,
    RESET_QUEUED,
    RESET_SENT,
}

internal enum class SibionicsWatchdogAction {
    NONE,
    /** The session stopped making progress: tear it down and connect again. */
    RECYCLE,
    /** No session and nothing scheduled to make one. */
    CONNECT,
}

internal object SibionicsLinkPolicy {
    const val WATCHDOG_INTERVAL_MS = 30_000L

    /** Connect / discover / subscribe each have a 15-20 s timer; this only backs them up. */
    const val SETUP_STALL_MS = 60_000L

    /** Probe, auth and handshake steps answer in milliseconds; the Chinese probe re-asks every 30 s. */
    const val HANDSHAKE_STALL_MS = 90_000L

    /** A live sample comes every minute; the streaming timer fires at 180 s, this a tick later. */
    const val STREAMING_STALL_MS = 210_000L

    /** A scheduled reconnect that is this far overdue was lost. */
    const val RECONNECT_OVERDUE_MS = 30_000L

    private fun isSetup(phase: SibionicsLinkPhase) =
        phase == SibionicsLinkPhase.CONNECTING ||
            phase == SibionicsLinkPhase.DISCOVERING ||
            phase == SibionicsLinkPhase.ENABLING_NOTIFY

    private fun isHandshake(phase: SibionicsLinkPhase) =
        phase == SibionicsLinkPhase.PROBING_CHINESE ||
            phase == SibionicsLinkPhase.AUTHENTICATING ||
            phase == SibionicsLinkPhase.ACTIVATING ||
            phase == SibionicsLinkPhase.SYNCING_TIME ||
            phase == SibionicsLinkPhase.REQUESTING_DATA

    /**
     * Whether a session that holds a GATT has stopped moving. A dead link that never
     * reported its disconnect used to be coalesced into forever: connectDevice() saw a
     * GATT in a non-idle phase and declined every reconnect, until the sensor was
     * removed and added again.
     */
    fun isStalled(phase: SibionicsLinkPhase, sinceProgressMs: Long): Boolean = when {
        isSetup(phase) -> sinceProgressMs >= SETUP_STALL_MS
        isHandshake(phase) -> sinceProgressMs >= HANDSHAKE_STALL_MS
        phase == SibionicsLinkPhase.STREAMING -> sinceProgressMs >= STREAMING_STALL_MS
        phase == SibionicsLinkPhase.ERROR -> sinceProgressMs >= SETUP_STALL_MS
        else -> false
    }

    fun watchdogAction(
        phase: SibionicsLinkPhase,
        idleReason: SibionicsIdleReason,
        sinceProgressMs: Long,
        reconnectOverdueMs: Long,
        recoveryActive: Boolean,
        restoring: Boolean,
        bluetoothOn: Boolean,
    ): SibionicsWatchdogAction {
        if (!bluetoothOn) return SibionicsWatchdogAction.NONE
        if (phase != SibionicsLinkPhase.IDLE) {
            return if (isStalled(phase, sinceProgressMs)) SibionicsWatchdogAction.RECYCLE
            else SibionicsWatchdogAction.NONE
        }
        if (recoveryActive || restoring) return SibionicsWatchdogAction.NONE
        return when (idleReason) {
            SibionicsIdleReason.RECONNECT_SCHEDULED ->
                if (reconnectOverdueMs >= RECONNECT_OVERDUE_MS) SibionicsWatchdogAction.CONNECT
                else SibionicsWatchdogAction.NONE
            // Recovery finished without saying so, an idle driver nobody woke, an
            // address the shared scanner may have filled in since, or Bluetooth back.
            SibionicsIdleReason.SEARCHING,
            SibionicsIdleReason.NONE,
            SibionicsIdleReason.NEEDS_SCAN,
            SibionicsIdleReason.BLUETOOTH_OFF,
            -> SibionicsWatchdogAction.CONNECT
        }
    }

    /**
     * @param inHistoryTransfer a backlog download is under way; the sensor drops the link
     *   after every page, so the reconnect gaps between pages keep showing the progress.
     */
    fun view(
        phase: SibionicsLinkPhase,
        idleReason: SibionicsIdleReason,
        paused: Boolean,
        bluetoothOn: Boolean,
        restoring: Boolean,
        inHistoryTransfer: Boolean,
        historyShowable: Boolean,
        hasLiveReading: Boolean,
        hasAnyReading: Boolean,
    ): SibionicsLinkView {
        if (paused) return SibionicsLinkView.DISCONNECTED
        if (!bluetoothOn) return SibionicsLinkView.BLUETOOTH_OFF
        val history = inHistoryTransfer && historyShowable
        return when (phase) {
            SibionicsLinkPhase.IDLE -> when {
                restoring -> SibionicsLinkView.CONNECTING
                // Bluetooth is back; the adapter broadcast or the watchdog connects next.
                idleReason == SibionicsIdleReason.BLUETOOTH_OFF -> SibionicsLinkView.CONNECTING
                // Scan, wait and attempt flip every few seconds; to the user it is one
                // connect that has not landed yet, and the out-of-reach chip takes over
                // once it has gone on too long.
                idleReason == SibionicsIdleReason.SEARCHING ||
                    idleReason == SibionicsIdleReason.NEEDS_SCAN -> SibionicsLinkView.CONNECTING
                idleReason == SibionicsIdleReason.RECONNECT_SCHEDULED ->
                    if (history) SibionicsLinkView.HISTORY else SibionicsLinkView.CONNECTING
                else -> SibionicsLinkView.DISCONNECTED
            }
            SibionicsLinkPhase.CONNECTING,
            SibionicsLinkPhase.DISCOVERING,
            SibionicsLinkPhase.ENABLING_NOTIFY,
            SibionicsLinkPhase.PROBING_CHINESE,
            SibionicsLinkPhase.AUTHENTICATING,
            SibionicsLinkPhase.ACTIVATING,
            SibionicsLinkPhase.SYNCING_TIME,
            SibionicsLinkPhase.REQUESTING_DATA,
            -> SibionicsLinkView.PHASE_STEP
            SibionicsLinkPhase.STREAMING -> when {
                hasLiveReading -> SibionicsLinkView.CONNECTED
                historyShowable -> SibionicsLinkView.HISTORY
                hasAnyReading -> SibionicsLinkView.CONNECTED
                else -> SibionicsLinkView.WAITING_FOR_DATA
            }
            SibionicsLinkPhase.ERROR -> SibionicsLinkView.FAILED
        }
    }

    /** Notices that a successful connection answers; the rest wait for a live reading. */
    fun isTransportNotice(notice: SibionicsLinkNotice): Boolean =
        notice == SibionicsLinkNotice.LINK_STATUS || notice == SibionicsLinkNotice.NOT_FOUND
}
