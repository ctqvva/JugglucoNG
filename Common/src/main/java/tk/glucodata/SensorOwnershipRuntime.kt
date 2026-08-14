package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class SensorHandoffUiState {
    NONE,
    HANDING_TO_WATCH,
    STREAMING_FROM_WATCH,
}

internal fun resolveSensorHandoffUiState(
    companionEnabled: Boolean,
    releasedLocally: Boolean,
    peerOwns: Boolean,
    peerReportFresh: Boolean,
): SensorHandoffUiState = when {
    !companionEnabled -> SensorHandoffUiState.NONE
    !releasedLocally -> SensorHandoffUiState.NONE
    peerOwns && peerReportFresh -> SensorHandoffUiState.STREAMING_FROM_WATCH
    else -> SensorHandoffUiState.HANDING_TO_WATCH
}

internal fun resolveSensorOwnershipIntent(
    isWearable: Boolean,
    companionEnabled: Boolean,
    directRequested: Boolean,
    assignedToWatch: Boolean,
): SensorOwnershipPolicy.Intent = when {
    isWearable && directRequested -> SensorOwnershipPolicy.Intent.PREFER
    isWearable -> SensorOwnershipPolicy.Intent.TAKE
    companionEnabled && assignedToWatch -> SensorOwnershipPolicy.Intent.YIELD
    else -> SensorOwnershipPolicy.Intent.TAKE
}

/**
 * Whether the other device should be treated as gone rather than merely quiet.
 *
 * Only automatic switching acts on this: it is the one mode where the peer
 * leaving has to be noticed within a minute rather than within the silence
 * timeout, because nobody is coming to press a button.
 *
 * @param undiscoverableForMs how long capability discovery has reported no peer,
 *   or a negative value while it is finding one.
 */
internal fun resolvePeerGone(
    autoSwitchEnabled: Boolean,
    lastAnnouncementDelivered: Boolean,
    undiscoverableForMs: Long,
    graceMs: Long,
): Boolean {
    if (!autoSwitchEnabled) return false
    if (!lastAnnouncementDelivered) return true
    return undiscoverableForMs >= 0L && undiscoverableForMs >= graceMs
}

/** The state of one sensor's handover window; see [resolveYieldWindow]. */
internal data class SensorYieldWindow(
    /** When the current handover attempt began, or null to forget it. */
    val startedAtMs: Long?,
    /** Stay off the sensor until this time; 0 means read it. */
    val deadlineMs: Long,
    /** `%s` stands for the sensor. Null when nothing changed worth saying. */
    val logMessage: String? = null,
)

/**
 * Until when this device stays off a sensor it has assigned to the peer.
 *
 * A sensor that serves one client at a time cannot be handed over any other
 * way: the peer can never get a reading — and so can never claim ownership —
 * while this device holds the connection. So the handover is a bounded window
 * of standing aside, retried periodically if the peer does not take it.
 *
 * [peerGone] short-circuits all of that. Yielding to a device that is not there
 * only loses readings, and it is what let an assigned-but-absent watch keep the
 * phone off its own sensor.
 */
internal fun resolveYieldWindow(
    intent: SensorOwnershipPolicy.Intent,
    peerOwns: Boolean,
    peerGone: Boolean,
    startedAtMs: Long?,
    nowMs: Long,
    windowMs: Long,
    retryIntervalMs: Long,
): SensorYieldWindow {
    if (intent != SensorOwnershipPolicy.Intent.YIELD || peerGone) {
        return SensorYieldWindow(startedAtMs = null, deadlineMs = 0L)
    }
    // The peer has it: no window needed, the normal rules apply. Record when
    // that was, rather than forgetting the handover happened — otherwise the
    // moment the peer loses the sensor looks like a fresh handover and opens
    // a new window, so a watch dropping its connection put the phone into a
    // blackout instead of straight back onto the sensor.
    if (peerOwns) return SensorYieldWindow(startedAtMs = nowMs, deadlineMs = 0L)
    if (startedAtMs == null) {
        return SensorYieldWindow(
            startedAtMs = nowMs,
            deadlineMs = nowMs + windowMs,
            logMessage = "handing %s to the watch: standing down for ${windowMs / 1000}s",
        )
    }
    if (nowMs < startedAtMs + windowMs) {
        return SensorYieldWindow(startedAtMs = startedAtMs, deadlineMs = startedAtMs + windowMs)
    }
    if (nowMs >= startedAtMs + retryIntervalMs) {
        return SensorYieldWindow(
            startedAtMs = nowMs,
            deadlineMs = nowMs + windowMs,
            logMessage = "offering %s to the watch again",
        )
    }
    // Window spent and the peer did not take it: read it ourselves again.
    return SensorYieldWindow(startedAtMs = startedAtMs, deadlineMs = 0L)
}

/**
 * Keeps exactly one device reading each sensor, and hands it over when that
 * device stops being able to.
 *
 * Both ends run this. Each announces, once a minute and whenever something
 * changes, whether it currently holds a sensor and how recent its newest reading
 * is; each then applies [SensorOwnershipPolicy] and either keeps its connection
 * or stands down. Releasing is per sensor — pausing that sensor's driver, the
 * same way the phone's own pause button does — because tearing down Bluetooth
 * wholesale would drop every other sensor with it.
 *
 * Nothing here is destructive: a released sensor keeps its record, its history
 * and its calibration, and resuming is a reconnect.
 */
object SensorOwnershipRuntime {
    private const val LOG_ID = "SensorOwnership"

    /** How often each device says what it is holding. */
    private const val ANNOUNCE_INTERVAL_MS = 60_000L

    /**
     * How often an unchanged ownership state is repeated. Long, because every
     * one of these wakes the peer out of doze, and a change is sent at once.
     */
    private const val ANNOUNCE_HEARTBEAT_MS = 15L * 60_000L

    /** Three missed announcements before the peer counts as gone. */
    // Must outlast the heartbeat, or a quiet-but-present peer looks gone.
    private const val PEER_SILENT_AFTER_MS = 2L * ANNOUNCE_HEARTBEAT_MS + 60_000L

    /**
     * Heartbeat used while automatic switching is on.
     *
     * Half an hour of silence is a reasonable price for battery when ownership
     * only ever changes because the user asked it to. It is not when the point
     * of the setting is that the other device takes over on its own: a watch
     * left at home would keep the phone off its sensor for the whole timeout.
     * The user opted into the extra traffic by turning the switch on.
     */
    private const val AUTO_SWITCH_HEARTBEAT_MS = 5L * 60_000L
    private const val AUTO_SWITCH_PEER_SILENT_AFTER_MS = 2L * AUTO_SWITCH_HEARTBEAT_MS + 60_000L

    /** How long discovery must find no peer before that counts as it leaving. */
    private const val PEER_UNDISCOVERABLE_GRACE_MS = 30_000L

    /**
     * How long the phone lets go for, when the user hands a sensor to the watch.
     *
     * This window is the one time neither device is reading, so it is kept as
     * short as a handover allows. It was briefly widened to six minutes after a
     * watch took three and a half to connect; that doubled the outage whenever
     * the watch could not take the sensor at all, which is the more common case
     * and the more costly one. A handover that needs longer than this should be
     * fixed by having the watch say it is actively scanning, not by making the
     * gap bigger.
     */
    private const val YIELD_WINDOW_MS = 3L * 60L * 1000L

    /** After a failed handover, how long before offering the watch another go. */
    private const val YIELD_RETRY_INTERVAL_MS = 15L * 60L * 1000L

    /** Reconnect delay when taking a sensor back, in ms. */
    private const val RESUME_DELAY_MS = 200L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SensorOwnership").apply { isDaemon = true }
    }

    private val peerReports = ConcurrentHashMap<String, SensorOwnershipPolicy.PeerReport>()
    /** Serials as the peer spells them, so we can answer about sensors we lack. */
    private val peerSerials = ConcurrentHashMap<String, String>()
    private val localReadings = ConcurrentHashMap<String, Long>()
    private val releaseState = SensorOwnershipReleaseState(::key)
    private val yieldStartedAt = ConcurrentHashMap<String, Long>()

    /**
     * Whether the last announcement we tried to deliver actually reached the
     * peer. Only sampled while automatic switching is on, because it is the only
     * mode that needs to notice the peer leaving faster than the silence
     * timeout — and because learning it costs a blocking send.
     *
     * On its own this was far too coarse: announcements are only sent when
     * ownership changes or the heartbeat falls due, so a watch that switched off
     * while the phone was already standing down produced no send to fail, and
     * the phone sat released for the whole handover window with capability
     * discovery reporting no watch at all. [MessageSender.peerUnreachable] is
     * the signal that actually notices; this one only corroborates it.
     */
    @Volatile private var peerDeliverable = true

    /** When discovery first came back empty; 0 while the peer is being found. */
    @Volatile private var peerUndiscoverableSince = 0L

    @Volatile private var started = false

    private fun key(serial: String): String =
        (runCatching { SensorIdentity.canonicalSensorId(serial) }.getOrNull() ?: serial).lowercase()

    /** Starts announcing and reconciling. Safe to call repeatedly. */
    @JvmStatic
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        executor.scheduleWithFixedDelay(
            { runCatching { announceAndReconcile() }.onFailure { Log.stack(LOG_ID, "tick", it) } },
            5_000L,
            ANNOUNCE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        if (!Applic.isWearable && !MessageSender.outgoingAllowed()) {
            executor.execute {
                runCatching { recoverDisabledCompanionRouting() }
                    .onFailure { Log.stack(LOG_ID, "recover disabled companion routing", it) }
            }
        }
        Log.i(LOG_ID, "sensor ownership arbitration started")
    }

    /** A local driver accepted a reading — the evidence that we hold a sensor. */
    @JvmStatic
    fun noteLocalReading(serial: String?, timestampMs: Long) {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        val stamp = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
        val previous = localReadings.put(key(target), stamp)
        // First reading after a gap changes what we would announce, so say so now
        // rather than at the next tick.
        if (previous == null) executor.execute { runCatching { announceAndReconcile() } }
    }

    /**
     * Whether this device is the one reading [serial] right now. Readings that
     * arrive over the Data Layer for a sensor we hold ourselves are dropped, so
     * the two devices cannot write over each other during a changeover.
     */
    @JvmStatic
    fun readsLocally(serial: String?): Boolean {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return false
        if (releaseState.isReleased(target)) return false
        return holdsLiveConnection(target)
    }

    /**
     * True when this device deliberately let a sensor go to the other one.
     *
     * Distinct from simply having no connection: a driver that briefly drops out
     * is still the authority on its own last reading, whereas one we stood down
     * from is holding a value that stopped being current the moment we released.
     */
    @JvmStatic
    fun hasStoodDown(serial: String?): Boolean {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return false
        return releaseState.isReleased(target)
    }

    /** Hard gate consulted at every route to connectGatt while the peer owns it. */
    @JvmStatic
    fun blocksLocalConnection(serial: String?): Boolean {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return false
        return releaseState.isReleased(target)
    }

    /** Phone UI state for the deliberate gap and the subsequent watch-owned stream. */
    @JvmStatic
    fun handoffUiState(serial: String?): SensorHandoffUiState {
        if (Applic.isWearable) return SensorHandoffUiState.NONE
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) }
            ?: return SensorHandoffUiState.NONE
        val companionEnabled = MessageSender.outgoingAllowed()
        if (!companionEnabled) return SensorHandoffUiState.NONE
        val peer = peerReportFor(target)
        val now = System.currentTimeMillis()
        val autoSwitch = autoSwitchEnabled()
        // A watch that discovery can no longer find is not streaming to us,
        // whatever its last report said; saying otherwise is what "it kept
        // trying to receive data from the watch" looked like on screen.
        val peerGone = peerGone(autoSwitch)
        val silentAfter = if (autoSwitch) AUTO_SWITCH_PEER_SILENT_AFTER_MS else PEER_SILENT_AFTER_MS
        return resolveSensorHandoffUiState(
            companionEnabled = companionEnabled,
            releasedLocally = releaseState.isReleased(target),
            peerOwns = peer?.owns == true,
            peerReportFresh = !peerGone && peer != null && now - peer.receivedAtMs <= silentAfter,
        )
    }

    /**
     * The global companion switch is an ownership boundary, not only a transport
     * preference. When it goes off, stale routing and peer reports must not keep
     * the phone released from its sensor.
     */
    @JvmStatic
    fun onCompanionEnabledChanged(enabled: Boolean) {
        if (Applic.isWearable) return
        executor.execute {
            runCatching {
                if (!enabled) {
                    peerReports.clear()
                    peerSerials.clear()
                    yieldStartedAt.clear()
                    resumeReleasedSensors("WearOS companion disabled")
                }
                reconcile()
                UiRefreshBus.requestStatusRefresh()
            }.onFailure { Log.stack(LOG_ID, "companion enabled=$enabled", it) }
        }
    }

    /** User explicitly revoked a handoff from the sensor card. */
    @JvmStatic
    fun requestPhoneOwnership(serial: String?) {
        if (Applic.isWearable) return
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        executor.execute {
            runCatching {
                forgetPeerReport(target)
                yieldStartedAt.remove(key(target))
                if (releaseState.isReleased(target)) {
                    resume(target, "requested on phone")
                } else if (!holdsLiveConnection(target)) {
                    findGatt(target)?.let { gatt ->
                        gatt.setPause(false)
                        gatt.connectDevice(RESUME_DELAY_MS)
                    }
                }
                UiRefreshBus.requestStatusRefresh()
            }.onFailure { Log.stack(LOG_ID, "requestPhoneOwnership($target)", it) }
        }
    }

    /** The peer told us what it is holding. */
    @JvmStatic
    fun onPeerReport(data: ByteArray?) {
        if (!Applic.isWearable && !MessageSender.outgoingAllowed()) return
        val report = decode(data) ?: return
        // Hearing from the peer is proof it is there, whatever the last send said.
        peerDeliverable = true
        peerSerials[key(report.first)] = report.first
        peerReports[key(report.first)] = SensorOwnershipPolicy.PeerReport(
            owns = report.second,
            lastReadingMs = report.third,
            receivedAtMs = System.currentTimeMillis(),
        )
        if (Log.doLog) {
            Log.i(LOG_ID, "peer holds ${report.first}=${report.second} newest=${report.third}")
        }
        executor.execute { runCatching { reconcile() }.onFailure { Log.stack(LOG_ID, "reconcile", it) } }
    }

    /**
     * The peer's report for a sensor, matched by identity rather than by an
     * exact key.
     *
     * A device spells a sensor differently depending on what it knows about it —
     * the watch answered about "6CA04230E260" until it took the sensor, then
     * switched to the short alias "230E260". Keyed lookups missed from that
     * moment on, so the phone never heard that the handover had succeeded, gave
     * up and took the sensor back.
     */
    private fun peerReportFor(serial: String): SensorOwnershipPolicy.PeerReport? {
        peerReports[key(serial)]?.let { return it }
        return peerSerials.entries
            .asSequence()
            .filter { (_, spelling) -> SensorIdentity.matches(spelling, serial) }
            .mapNotNull { (id, _) -> peerReports[id] }
            // Several spellings of one sensor: trust the most recent word.
            .maxByOrNull { it.receivedAtMs }
    }

    private fun announceAndReconcile() {
        announce()
        reconcile()
    }

    /**
     * Announces ownership — but only when it has something new to say.
     *
     * This used to send a message per sensor every minute, from both devices,
     * for as long as either was running. Each one wakes the peer's
     * WearableListenerService, so a paired phone was pulled out of doze around
     * the clock whether or not anything had changed. Nothing in the protocol
     * needed that: the arbitration reacts to changes, and the peer-silence
     * timeout only needs a heartbeat slow enough not to matter.
     *
     * A change is sent immediately; an unchanged state is repeated at
     * [ANNOUNCE_HEARTBEAT_MS] so a peer that missed one still converges.
     */
    private fun announce() {
        if (!MessageSender.outgoingAllowed()) return
        val now = System.currentTimeMillis()
        val autoSwitch = autoSwitchEnabled()
        val heartbeat = if (autoSwitch) AUTO_SWITCH_HEARTBEAT_MS else ANNOUNCE_HEARTBEAT_MS
        // At most one blocking send per pass: an unreachable peer costs the send
        // its full timeout, and doing that once per sensor would starve the tick
        // this runs on.
        var probed = false
        sensors().forEach { serial ->
            val owns = holdsLiveConnection(serial)
            val newest = localReadings[key(serial)] ?: 0L
            val id = key(serial)
            // The reading time changes every minute by nature, so it is not part
            // of what counts as a change; only ownership is.
            val previous = lastAnnounced[id]
            val dueForHeartbeat = now - (lastAnnouncedAt[id] ?: 0L) >= heartbeat
            if (previous == owns && !dueForHeartbeat) return@forEach
            val payload = encode(serial, owns, newest)
            if (autoSwitch && !probed) {
                probed = true
                val delivered = MessageSender.sendSyncMessageAwait(
                    MessageSender.SENSOR_OWNERSHIP_PATH,
                    payload,
                )
                if (delivered != peerDeliverable) {
                    Log.i(LOG_ID, "peer ${if (delivered) "reachable again" else "unreachable"}")
                    peerDeliverable = delivered
                }
            } else {
                MessageSender.sendSyncMessage(MessageSender.SENSOR_OWNERSHIP_PATH, payload)
            }
            lastAnnounced[id] = owns
            lastAnnouncedAt[id] = now
        }
    }

    private fun autoSwitchEnabled(): Boolean =
        runCatching { AutoSensorSwitch.isEnabled() }.getOrDefault(false)

    /**
     * How long discovery has reported no peer, or -1 while it is finding one.
     *
     * The grace period applied to this keeps a blip in capability discovery from
     * being read as the other device leaving: acting on it hands the sensor
     * over, and an assigned owner coming straight back opens a fresh stand-down
     * window. Half a minute is far below the timeouts this replaces and far
     * above any momentary gap.
     */
    private fun undiscoverableForMs(): Long {
        val unreachable = runCatching { MessageSender.peerUnreachable() }.getOrDefault(false)
        if (!unreachable) {
            peerUndiscoverableSince = 0L
            return -1L
        }
        val since = peerUndiscoverableSince
        if (since == 0L) {
            peerUndiscoverableSince = System.currentTimeMillis()
            return 0L
        }
        return System.currentTimeMillis() - since
    }

    private fun peerGone(autoSwitch: Boolean): Boolean = resolvePeerGone(
        autoSwitchEnabled = autoSwitch,
        lastAnnouncementDelivered = peerDeliverable,
        undiscoverableForMs = undiscoverableForMs(),
        graceMs = PEER_UNDISCOVERABLE_GRACE_MS,
    )

    /**
     * Capability discovery found, or stopped finding, the other device. This is
     * the fastest honest word we get on it leaving — the phone otherwise sat
     * released from a sensor for the whole handover window while discovery had
     * been reporting no watch at all for a minute.
     */
    @JvmStatic
    fun onPeerReachabilityChanged(reachable: Boolean) {
        if (!started) return
        Log.i(LOG_ID, "peer ${if (reachable) "discovered" else "no longer discoverable"}")
        if (reachable) {
            peerDeliverable = true
            peerUndiscoverableSince = 0L
        } else if (peerUndiscoverableSince == 0L) {
            peerUndiscoverableSince = System.currentTimeMillis()
        }
        executor.execute {
            runCatching { reconcile() }.onFailure { Log.stack(LOG_ID, "reachability reconcile", it) }
        }
        if (reachable) return
        // Reconcile again once the grace has run out; nothing else is due to
        // happen until the minute tick, and the point is not to wait for it.
        runCatching {
            executor.schedule(
                { runCatching { reconcile() }.onFailure { Log.stack(LOG_ID, "grace reconcile", it) } },
                PEER_UNDISCOVERABLE_GRACE_MS + 1_000L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** What was last put on the wire per sensor, so a repeat stays silent. */
    private val lastAnnounced = ConcurrentHashMap<String, Boolean>()
    private val lastAnnouncedAt = ConcurrentHashMap<String, Long>()

    private fun reconcile() {
        val now = System.currentTimeMillis()
        val companionEnabled = Applic.isWearable || MessageSender.outgoingAllowed()
        val autoSwitch = autoSwitchEnabled()
        // Automatic switching is the one mode that acts on the peer being gone
        // rather than merely quiet, so an undeliverable announcement retires its
        // report at once instead of waiting out the silence timeout.
        val peerGone = peerGone(autoSwitch)
        val peerSilentAfterMs =
            if (autoSwitch) AUTO_SWITCH_PEER_SILENT_AFTER_MS else PEER_SILENT_AFTER_MS
        sensors().forEach { serial ->
            val id = key(serial)
            val peer = if (companionEnabled && !peerGone) peerReportFor(serial) else null
            val intent = intentFor(serial, companionEnabled)
            val shouldRead = SensorOwnershipPolicy.shouldReadLocally(
                isPhone = !Applic.isWearable,
                intent = intent,
                localHasConnection = holdsLiveConnection(serial),
                localLastReadingMs = localReadings[id] ?: 0L,
                peer = peer,
                nowMs = now,
                peerSilentAfterMs = peerSilentAfterMs,
                yieldUntilMs = yieldDeadline(id, intent, peer, now, peerGone),
            )
            val released = releaseState.isReleased(serial)
            when {
                !shouldRead && !released -> release(serial)
                shouldRead && released -> resume(serial)
                // Should be reading it, never stood down from it, and has no
                // connection. Reconnecting only ever happened on the way back
                // from a release, so a device that was armed while the sensor
                // was simply not its own sat here doing nothing at all.
                shouldRead && autoSwitch && !holdsLiveConnection(serial) -> nudge(serial)
            }
        }
    }

    /**
     * What this device should do with a sensor.
     *
     * The watch always takes what it can: with no assignment it only ends up
     * reading a sensor the phone has stopped reading, which is the automatic
     * "whichever device can reach it" behaviour. The phone stands aside only
     * where the user has actually assigned the sensor to the watch.
     */
    private fun intentFor(serial: String, companionEnabled: Boolean): SensorOwnershipPolicy.Intent =
        resolveSensorOwnershipIntent(
            isWearable = Applic.isWearable,
            companionEnabled = companionEnabled,
            directRequested = Applic.isWearable && WearSensorClaim.isDirectRequested(),
            assignedToWatch = !Applic.isWearable && assignedToWatch(serial),
        )

    /**
     * Until when this device stays off the sensor.
     *
     * The window opens when the handover starts and closes once it expires; if
     * the watch took the sensor in the meantime the ordinary rules keep the phone
     * off anyway. A handover that failed is retried periodically, so a watch that
     * was out of range gets another chance without the user touching anything.
     */
    private fun yieldDeadline(
        id: String,
        intent: SensorOwnershipPolicy.Intent,
        peer: SensorOwnershipPolicy.PeerReport?,
        now: Long,
        peerGone: Boolean = false,
    ): Long {
        val window = resolveYieldWindow(
            intent = intent,
            peerOwns = peer?.owns == true,
            peerGone = peerGone,
            startedAtMs = yieldStartedAt[id],
            nowMs = now,
            windowMs = YIELD_WINDOW_MS,
            retryIntervalMs = YIELD_RETRY_INTERVAL_MS,
        )
        if (window.startedAtMs == null) yieldStartedAt.remove(id) else yieldStartedAt[id] = window.startedAtMs
        window.logMessage?.let { Log.i(LOG_ID, it.replace("%s", id)) }
        return window.deadlineMs
    }

    /** Whether the user has assigned the sensor to the watch, read from prefs. */
    private fun assignedToWatch(serial: String): Boolean = runCatching {
        val prefs = Applic.app
            ?.getSharedPreferences("wear_routing_request", android.content.Context.MODE_PRIVATE)
            ?: return@runCatching false
        val requestedNodes = prefs.all
            .filter { (prefKey, value) -> prefKey.startsWith("direct.") && value == true }
            .keys
            .map { it.removePrefix("direct.") }
        requestedNodes.any { nodeId ->
            val assigned = prefs.getString("sensor.$nodeId", null)
            if (!assigned.isNullOrBlank()) {
                SensorIdentity.matches(assigned, serial)
            } else {
                // Absence-only migration for requests saved by older builds:
                // they meant the sensor selected when direct mode was enabled,
                // not every sensor on the phone.
                SensorIdentity.matches(SensorIdentity.resolveMainSensor(), serial)
            }
        }
    }.getOrDefault(false)

    /**
     * Upgrade recovery for builds that switched Wear off but left a direct
     * assignment and a paused driver behind. A stale direct request is the
     * evidence that this pause belonged to handoff; ordinary user-paused
     * sensors are left alone.
     */
    private fun recoverDisabledCompanionRouting() {
        if (Applic.isWearable || MessageSender.outgoingAllowed()) return
        val context = Applic.app ?: return
        val prefs = context.getSharedPreferences(
            "wear_routing_request",
            android.content.Context.MODE_PRIVATE,
        )
        val directNodes = prefs.all
            .filter { (prefKey, value) -> prefKey.startsWith("direct.") && value == true }
            .keys
            .map { it.removePrefix("direct.") }
            .filter { it.isNotBlank() }
        if (directNodes.isEmpty()) return

        val fallback = SensorIdentity.resolveMainSensor()
        val assignedSensors = directNodes.mapNotNull { nodeId ->
            prefs.getString("sensor.$nodeId", null)?.takeIf { it.isNotBlank() } ?: fallback
        }.distinctBy(::key)
        directNodes.forEach { nodeId -> MessageSender.sendDirectSensorStop(nodeId) }
        val editor = prefs.edit()
        directNodes.forEach { nodeId ->
            editor.putBoolean("direct.$nodeId", false)
            editor.remove("sensor.$nodeId")
        }
        editor.apply()

        peerReports.clear()
        peerSerials.clear()
        yieldStartedAt.clear()
        assignedSensors.forEach { serial ->
            releaseState.resume(serial)
            findGatt(serial)?.let { gatt ->
                gatt.setPause(false)
            }
        }
        Applic.setbluetooth(context, true)
        UiRefreshBus.requestStatusRefresh()
        Log.i(
            LOG_ID,
            "recovered ${assignedSensors.size} phone sensor(s) from stale disabled-Wear routing",
        )
    }

    private fun release(serial: String) {
        // Mark first. A callback may be created after this reconciliation; it
        // must still be unable to connect.
        releaseState.release(serial)
        Log.i(LOG_ID, "standing down from $serial: the other device is reading it")
        val gatt = findGatt(serial) ?: return
        runCatching {
            gatt.setPause(true)
            gatt.disconnect()
        }.onFailure { Log.stack(LOG_ID, "release($serial)", it) }
    }

    private fun resume(serial: String, reason: String = "the other device is no longer reading it") {
        releaseState.resume(serial)
        val gatt = findGatt(serial)
        if (gatt == null) {
            // Nothing to reconnect: this device has no driver for the sensor, so
            // arbitration handing it over here achieves nothing. On a watch that
            // means Bluetooth was never brought up, or the sensor's record never
            // arrived — both of which look exactly like "it just stalled".
            Log.w(LOG_ID, "cannot take $serial back ($reason): no local driver for it")
            return
        }
        Log.i(LOG_ID, "taking $serial back: $reason")
        runCatching {
            gatt.setPause(false)
            gatt.connectDevice(RESUME_DELAY_MS)
        }.onFailure { Log.stack(LOG_ID, "resume($serial)", it) }
    }

    /**
     * Ask an idle driver to try the sensor again.
     *
     * SuperGattCallback.reconnect rate-limits itself — it does nothing while a
     * connection attempt is less than a minute old or a reading is still fresh —
     * so this is safe to call from every reconciliation.
     */
    private fun nudge(serial: String) {
        val gatt = findGatt(serial)
        if (gatt == null) {
            // Nothing here can read this sensor: on a watch that means Bluetooth
            // never came up or the sensor's record never arrived.
            Log.w(LOG_ID, "should be reading $serial but there is no local driver for it")
            return
        }
        runCatching {
            gatt.setPause(false)
            gatt.reconnect(System.currentTimeMillis())
        }.onFailure { Log.stack(LOG_ID, "nudge($serial)", it) }
    }

    private fun resumeReleasedSensors(reason: String) {
        val releasedByKey = LinkedHashMap<String, String>()
        releaseState.releasedSerials().forEach { serial -> releasedByKey[key(serial)] = serial }
        sensors().forEach { serial ->
            if (releaseState.isReleased(serial)) releasedByKey[key(serial)] = serial
        }
        releasedByKey.values.forEach { resume(it, reason) }
    }

    private fun forgetPeerReport(serial: String) {
        val matchingKeys = peerSerials.entries
            .filter { (id, spelling) -> id == key(serial) || SensorIdentity.matches(spelling, serial) }
            .map { it.key }
            .toMutableSet()
        matchingKeys.add(key(serial))
        matchingKeys.forEach { id ->
            peerReports.remove(id)
            peerSerials.remove(id)
        }
    }

    /**
     * Every sensor either device might be reading.
     *
     * Deriving this from local connections alone meant a device with no driver
     * for a sensor said nothing about it at all — and silence is what the other
     * side treats as "gone", so the peer could never learn the difference
     * between "I do not hold it" and "I am not here". Both ends therefore answer
     * about the union: what they hold, what the store knows, and whatever the
     * peer has mentioned.
     */
    private fun sensors(): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun add(candidate: String?) {
            val serial = candidate?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
            if (seen.add(key(serial))) out.add(serial)
        }
        runCatching {
            SensorBluetooth.mygatts()?.forEach { add(it.SerialNumber) }
        }
        runCatching { Natives.activeSensors() }.getOrNull()?.forEach(::add)
        peerSerials.values.forEach(::add)
        return out
    }

    private fun findGatt(serial: String): SuperGattCallback? = runCatching {
        SensorBluetooth.mygatts()?.firstOrNull { SensorIdentity.matches(it.SerialNumber, serial) }
    }.getOrNull()

    private fun holdsLiveConnection(serial: String): Boolean = runCatching {
        SensorBluetooth.mygatts()?.any {
            it.hasLocallyConnectedGatt() && SensorIdentity.matches(it.SerialNumber, serial)
        } == true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- wire

    private const val VERSION = 1

    private fun encode(serial: String, owns: Boolean, lastReadingMs: Long): ByteArray {
        val serialBytes = serial.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(1 + 1 + 1 + serialBytes.size + 8)
            .put(VERSION.toByte())
            .put(if (owns) 1 else 0)
            .put(serialBytes.size.toByte())
            .put(serialBytes)
            .putLong(lastReadingMs)
            .array()
    }

    /** [serial, owns, lastReadingMs], or null when malformed. */
    internal fun decode(data: ByteArray?): Triple<String, Boolean, Long>? {
        val bytes = data ?: return null
        if (bytes.size < 11) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.get().toInt() != VERSION) return null
        val owns = buffer.get().toInt() != 0
        val length = buffer.get().toInt() and 0xFF
        if (length == 0 || buffer.remaining() < length + 8) return null
        val serialBytes = ByteArray(length)
        buffer.get(serialBytes)
        val serial = String(serialBytes, StandardCharsets.UTF_8)
        if (!SensorIdentity.isUsableSensorId(serial)) return null
        return Triple(serial, owns, buffer.long)
    }
}
