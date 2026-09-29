package tk.glucodata

/**
 * Keeps the watch on the same sensors as the phone.
 *
 * With two sensors running, the watch used to choose for itself: whichever had
 * reported most recently became the one on screen, and every synced chunk moved
 * native's "current sensor" slot to the sensor it carried. Two live sensors
 * report alternately, so the complications, the sensor list and the home
 * screen's sensor card each flipped between them on their own schedule, while
 * the phone sat on one primary with the other drawn beside it.
 *
 * The phone's selection — [MultiSensorSelection], primary first — now travels
 * with the mirrored display preferences ([WearPrefsSync]), and everything on the
 * watch resolves through [selected]. The native slot is a consequence of that
 * choice, not an input to it: [alignCurrentSensor] moves it to the primary
 * whenever a chunk lands or the mirrored order changes.
 *
 * Choosing on the watch goes the other way. [requestPrimary] and
 * [requestToggle] are the phone's own two controls — promote a sensor to the
 * primary, show or hide a sensor on the chart — applied locally, so they take
 * effect at once and work with the phone out of reach, then asked of the phone:
 * at once when it is reachable, otherwise when it is back ([flushPending]). The
 * phone applies and pushes its preferences back, which is how the two converge
 * if it disagreed.
 */
object WearSensorSelectionSync {
    private const val LOG_ID = "WearSensorSelectionSync"

    /** Wire: `<action>:<serial>`, one command per message. */
    private const val ACTION_PRIMARY = "primary"
    private const val ACTION_TOGGLE = "toggle"

    /**
     * Watch: commands not yet handed to a reachable phone, oldest first, one per line as
     * `<queued at, ms> <command>`. The stamp stays on the watch; the wire format is unchanged.
     */
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_PENDING = "wear_sensor_selection_pending"
    /**
     * Owes the phone a request for its preferences: set when commands are dropped (an overflow
     * past [MAX_PENDING], a stale backlog, a failed send). It stays set until the phone's
     * preferences have been applied here ([onPhonePrefsApplied]). Commands queued meanwhile were
     * made against a state the phone never confirmed, so they are dropped too. If no answer has
     * come [RESYNC_MAX_MS] after the first request, the next flush asks once more, and the watch
     * stops waiting once the transport takes that request.
     */
    private const val KEY_RESYNC = "wear_sensor_selection_resync"
    /** When the transport first took a request of the owed resync; 0 until then. */
    private const val KEY_RESYNC_ASKED_AT = "wear_sensor_selection_resync_asked_at"
    /** A phone that never answers must not hold the watch's commands back for good. */
    private const val RESYNC_MAX_MS = 10 * 60_000L
    /** Bounds the queue while the phone stays out of reach; past it the backlog is dropped. */
    private const val MAX_PENDING = 16
    /**
     * How long a queued command stays worth sending. A backlog holding an older one is dropped
     * whole: the phone may have been set differently since.
     *
     * When a flush starts, the backlog is sent or dropped as a whole. The commands are relative
     * (a toggle flips whatever the phone has), so skipping one and sending what follows could
     * land the phone on a selection neither device ever had. If the link drops part-way, what
     * went out stays applied on the phone and the rest is requeued, to be sent or dropped as
     * stale at the next flush; a failed send ends the batch outright. Whatever is dropped was
     * already applied on the watch, so the watch then asks for the phone's preferences, and the
     * answer brings it to what the phone applied.
     */
    private const val PENDING_MAX_AGE_MS = 15 * 60_000L

    private class PendingCommand(val queuedAtMs: Long, val command: String)

    /** One thread, so queued commands leave in order and each is sent before the next. */
    private val flushExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /**
     * True when native holds a record for [sensorId], under the id itself or
     * the native spelling of it. A read-only lookup: [Natives.getdataptr] would
     * create a record for an unknown id.
     */
    @JvmStatic
    fun hasLocalRecord(sensorId: String?): Boolean = localName(sensorId) != null

    /**
     * The spelling native knows [sensorId] by, or null when it holds no record.
     * The phone lists a managed sensor by its canonical id (`SIBI:…`); the
     * watch's store has it under the short native name the chunks carried.
     */
    @JvmStatic
    fun localName(sensorId: String?): String? {
        val raw = sensorId?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return null
        if (nativeIndex(raw) >= 0) return raw
        val native = runCatching { SensorIdentity.resolveNativeSensorName(raw) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals(raw, ignoreCase = true) }
        if (native != null && nativeIndex(native) >= 0) return native
        return null
    }

    private fun nativeIndex(name: String): Int =
        runCatching { Natives.getSensorIndex(name) }.getOrDefault(-1)

    /**
     * Every sensor that could be displayed here.
     *
     * On the watch that includes the phone's selection, for as long as the
     * sensor has a record here. Native's "active" list is a streaming
     * heuristic — last poll within a day, still within its rated life and so
     * on — that comes and goes for a sensor whose readings arrive by sync;
     * filtering the selection by it made the second sensor blink in and out
     * of the chart and the list.
     */
    @JvmStatic
    fun candidates(primary: String?): List<String?> {
        val out = ArrayList(NotificationMultiSensorSource.candidateSensorIds(primary))
        if (Applic.isWearable) {
            runCatching { MultiSensorSelection.selectedOrder() }.getOrDefault(emptyList())
                .mapNotNull(::localName)
                .forEach(out::add)
        }
        return out
    }

    /** The sensors this device displays, primary first, as the phone lists them. */
    @JvmStatic
    fun selected(fallbackPrimary: String? = null): List<String> {
        // The mirrored order names the primary; native's own idea of "main"
        // only breaks the tie when the phone has not said.
        val storedPrimary = if (Applic.isWearable) {
            runCatching { MultiSensorSelection.selectedOrder() }.getOrDefault(emptyList())
                .firstNotNullOfOrNull(::localName)
        } else {
            null
        }
        val primary = storedPrimary
            ?: runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: fallbackPrimary
        val candidates = candidates(primary)
        val selected = runCatching { MultiSensorSelection.selectedAvailable(candidates, primary) }
            .getOrDefault(emptyList())
        // Stored ids come back as the phone spells them; hand out the spelling
        // this device's store answers to.
        return selected.map { id ->
            localName(id)
                ?: candidates.firstOrNull { it != null && SensorIdentity.matches(it, id) && hasLocalRecord(it) }
                ?: id
        }
    }

    /** The sensor the screens draw first. */
    @JvmStatic
    fun primary(fallbackPrimary: String? = null): String? =
        selected(fallbackPrimary).firstOrNull() ?: fallbackPrimary

    /**
     * Points native's current-sensor slot at the selection's primary. Returns
     * the primary. [fallback] is adopted when nothing else is selectable — a
     * watch receiving its first chunk before any preferences have arrived.
     */
    @JvmStatic
    fun alignCurrentSensor(fallback: String? = null): String? {
        val current = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val primary = primary(current ?: fallback) ?: return current
        if (current == null || !SensorIdentity.matches(current, primary)) {
            runCatching { SensorBluetooth.setCurrentSensorSelection(primary) }
                .onFailure { Log.stack(LOG_ID, "alignCurrentSensor", it) }
            if (Log.doLog) Log.i(LOG_ID, "current sensor ${current ?: "-"} -> $primary")
        }
        return primary
    }

    /**
     * Makes [serial] the primary sensor — what tapping a peer's chip on the
     * phone's hero does. Applied here first, then asked of the phone.
     */
    @JvmStatic
    fun requestPrimary(serial: String?) {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        applyPrimary(target)
        send(ACTION_PRIMARY, target)
    }

    /**
     * Shows or hides [serial] on the chart — the check on the phone's sensor
     * card. The last shown sensor cannot be hidden, and hiding the primary
     * promotes the next one, exactly as [MultiSensorSelection.toggle] does it.
     */
    @JvmStatic
    fun requestToggle(serial: String?) {
        val target = serial?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) } ?: return
        // A refused toggle (the last shown sensor) changed nothing here. Sent anyway, the
        // phone would apply it to its own, possibly longer, list and could hide its primary.
        if (applyToggle(target)) send(ACTION_TOGGLE, target)
    }

    private fun send(action: String, serial: String) {
        if (!Applic.isWearable) return
        runCatching {
            // Sent from the queue, so a command made with the phone out of reach reaches it
            // once it is back, in order with any made since.
            enqueue(encodeCommand(action, serial))
            flushPending()
        }.onFailure { Log.stack(LOG_ID, "send $action", it) }
    }

    private fun enqueue(command: String) = synchronized(this) {
        storePending(readPending() + PendingCommand(System.currentTimeMillis(), command))
    }

    /** Past [MAX_PENDING] the backlog is dropped and a resync is owed instead. */
    private fun storePending(commands: List<PendingCommand>) {
        if (commands.size > MAX_PENDING) {
            Log.w(LOG_ID, "sensor-selection queue overflow; dropping ${commands.size} commands")
            writePending(emptyList())
            setResyncOwed()
        } else {
            writePending(commands)
        }
    }

    /**
     * Watch: hands the queued commands to the phone once it is reachable, oldest first, each
     * handed to the Wear OS transport (its sendMessage task completed, or its 60 s wait ran out)
     * before the next. That is not a delivery receipt from the phone, so a command whose send
     * fails is never sent again (a toggle that did arrive would be undone by a second one): it
     * ends the batch, what follows is dropped, and the phone's preferences are asked for. Only
     * the rest of a batch cut short by a lost link stays queued. Called for every command, by
     * [MessageSender] when the phone becomes reachable, and by [flushNow] after a failed send,
     * so the owed request goes out at once.
     */
    @JvmStatic
    fun flushPending() {
        if (!Applic.isWearable) return
        runCatching { flushExecutor.execute { flushNow() } }
            .onFailure { Log.stack(LOG_ID, "flushPending", it) }
    }

    /** On [flushExecutor]. */
    private fun flushNow() {
        runCatching {
            val sender = MessageSender.getMessageSender() ?: return
            if (sender.nodes.isNullOrEmpty()) return
            var resync = false
            var askedAt = 0L
            val pending = synchronized(this) {
                resync = pendingPrefs().getBoolean(KEY_RESYNC, false)
                askedAt = pendingPrefs().getLong(KEY_RESYNC_ASKED_AT, 0L)
                readPending().also { if (it.isNotEmpty()) writePending(emptyList()) }
            }
            val now = System.currentTimeMillis()
            // Sent or dropped as a whole; see PENDING_MAX_AGE_MS.
            if (resync || pending.any { now - it.queuedAtMs !in 0..PENDING_MAX_AGE_MS }) {
                if (pending.isNotEmpty()) Log.i(LOG_ID, "dropping ${pending.size} queued sensor-selection commands")
                if (resync && askedAt > 0L && now - askedAt !in 0..RESYNC_MAX_MS) {
                    Log.w(LOG_ID, "the phone never answered the sensor-selection resync; asking once more")
                    // Everything dropped while the resync was owed was applied here and never
                    // confirmed, so the phone is still asked. The watch stops waiting only once the
                    // transport took that request; a refused one does not clear it, and if it is
                    // still owed, the next flush gives up and asks again.
                    if (MessageSender.sendSyncMessageAwait(WearMessagePath.DISPLAY_PREFS_REQ, byteArrayOf(1))) {
                        synchronized(this) {
                            // Only the resync given up on: an answer during the wait may have ended
                            // it, and an overflow may have owed a newer one (no stamp yet) since.
                            if (pendingPrefs().getLong(KEY_RESYNC_ASKED_AT, 0L) == askedAt) clearResync()
                        }
                    } else {
                        Log.w(LOG_ID, "give-up request not taken; the next flush asks again if the resync is still owed")
                    }
                    return
                }
                synchronized(this) { setResyncOwed() }
                // The flag stays set whatever this returns; the phone's answer clears it.
                val requested = MessageSender.sendSyncMessageAwait(WearMessagePath.DISPLAY_PREFS_REQ, byteArrayOf(1))
                if (requested && askedAt <= 0L) synchronized(this) {
                    // Only a request the transport took starts the give-up clock, and only while
                    // the resync is still owed: an answer may already have cleared it.
                    val prefs = pendingPrefs()
                    if (prefs.getBoolean(KEY_RESYNC, false) && prefs.getLong(KEY_RESYNC_ASKED_AT, 0L) <= 0L) {
                        prefs.edit().putLong(KEY_RESYNC_ASKED_AT, now).apply()
                    }
                }
                return
            }
            for ((index, entry) in pending.withIndex()) {
                if (MessageSender.getMessageSender()?.nodes.isNullOrEmpty()) {
                    // The phone went away mid-batch, after the commands before this one went out.
                    // The rest goes back to the head of the queue for the next flush.
                    synchronized(this) { storePending(pending.subList(index, pending.size) + readPending()) }
                    return
                }
                val sent = MessageSender.sendSyncMessageAwait(
                    WearMessagePath.DISPLAY_PREFS_MAINSENSOR,
                    entry.command.toByteArray(Charsets.UTF_8),
                )
                if (!sent) {
                    // Whether the phone got it is unknown, so nothing may be applied on top of it:
                    // the rest of the batch is dropped and the phone's preferences are asked for.
                    Log.w(LOG_ID, "sensor-selection command not taken by the transport: ${entry.command}; dropping the rest")
                    synchronized(this) { setResyncOwed() }
                    flushPending()
                    return
                }
            }
        }.onFailure { Log.stack(LOG_ID, "flushNow", it) }
    }

    private fun setResyncOwed() {
        pendingPrefs().edit().putBoolean(KEY_RESYNC, true).apply()
    }

    private fun clearResync() {
        pendingPrefs().edit().remove(KEY_RESYNC).remove(KEY_RESYNC_ASKED_AT).apply()
    }

    /**
     * Watch: the phone's preferences were just applied here. If a resync was owed, it is done,
     * and what was queued before the answer (made against the state it replaced) is dropped.
     * With no resync owed, the queue is kept and sent as usual.
     */
    @JvmStatic
    fun onPhonePrefsApplied() {
        if (!Applic.isWearable) return
        runCatching {
            synchronized(this) {
                if (!pendingPrefs().getBoolean(KEY_RESYNC, false)) return@synchronized
                clearResync()
                writePending(emptyList())
            }
        }.onFailure { Log.stack(LOG_ID, "onPhonePrefsApplied", it) }
    }

    private fun pendingPrefs() =
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private fun readPending(): List<PendingCommand> =
        pendingPrefs().getString(KEY_PENDING, "").orEmpty()
            .split('\n')
            .mapNotNull { line ->
                val split = line.indexOf(' ')
                if (split <= 0) return@mapNotNull null
                val queuedAtMs = line.substring(0, split).toLongOrNull() ?: return@mapNotNull null
                val command = line.substring(split + 1)
                if (decodeCommand(command) == null) null else PendingCommand(queuedAtMs, command)
            }

    private fun writePending(commands: List<PendingCommand>) {
        pendingPrefs().edit()
            .putString(KEY_PENDING, commands.joinToString("\n") { "${it.queuedAtMs} ${it.command}" })
            .apply()
    }

    @JvmStatic
    fun encodeCommand(action: String, serial: String): String = "$action:$serial"

    /** `(action, serial)`, or null when the payload is not a command this build knows. */
    @JvmStatic
    fun decodeCommand(payload: String?): Pair<String, String>? {
        val text = payload?.trim() ?: return null
        val split = text.indexOf(':')
        // A bare serial is the first build's "make primary".
        val action = if (split <= 0) ACTION_PRIMARY else text.substring(0, split)
        val serial = (if (split <= 0) text else text.substring(split + 1)).trim()
        if (action != ACTION_PRIMARY && action != ACTION_TOGGLE) return null
        if (!SensorIdentity.isUsableSensorId(serial)) return null
        return action to serial
    }

    /** Phone: applies a watch's choice. The pushed preferences carry the result back. */
    @JvmStatic
    fun onCommand(data: ByteArray?) {
        if (Applic.isWearable) return
        val (action, serial) = decodeCommand(data?.toString(Charsets.UTF_8)) ?: run {
            Log.w(LOG_ID, "ignoring unusable sensor-selection command")
            return
        }
        when (action) {
            ACTION_PRIMARY -> applyPrimary(serial)
            ACTION_TOGGLE -> applyToggle(serial)
        }
    }

    private fun applyPrimary(serial: String) {
        runCatching {
            MultiSensorSelection.moveToFront(serial, candidates(serial))
            SensorBluetooth.setCurrentSensorSelection(serial)
            // The phone reads its history from Room; the sensor list's own
            // tap merges the new primary's native history in, so this does too.
            if (!Applic.isWearable) HistorySyncAccess.mergeFullSyncForSensor(serial)
            UiRefreshBus.requestDataRefresh()
        }.onFailure { Log.stack(LOG_ID, "applyPrimary", it) }
    }

    /**
     * Returns whether [serial] went from shown to hidden or back. A toggle that could not be
     * applied here at all still counts as a change, so the phone is asked as before.
     */
    private fun applyToggle(serial: String): Boolean =
        runCatching {
            val currentPrimary = SensorIdentity.resolveMainSensor()
            val available = candidates(currentPrimary)
            val shown = MultiSensorSelection.selectedAvailable(available, currentPrimary)
            val wasShown = shown.any { SensorIdentity.matches(it, serial) }
            // The watch refuses to hide the last shown sensor before toggle() runs: toggle()
            // would still store the order cut down to what this watch holds, overwriting its
            // copy of the phone's, and with nothing sent the phone would not push it back.
            // (toggle() refuses on exactly this test.) The phone keeps applying every toggle,
            // since its write and push are what bring an older watch back in line.
            if (Applic.isWearable && wasShown && shown.size <= 1) return@runCatching false
            val selected = MultiSensorSelection.toggle(
                sensorId = serial,
                availableSensorIds = available,
                primarySensorId = currentPrimary,
            )
            // Hiding the primary hands the role to the next shown sensor.
            selected.firstOrNull()?.let { primary ->
                if (!SensorIdentity.matches(currentPrimary, primary)) {
                    SensorBluetooth.setCurrentSensorSelection(primary)
                    if (!Applic.isWearable) HistorySyncAccess.mergeFullSyncForSensor(primary)
                }
            }
            UiRefreshBus.requestDataRefresh()
            wasShown != selected.any { SensorIdentity.matches(it, serial) }
        }.onFailure { Log.stack(LOG_ID, "applyToggle", it) }.getOrDefault(true)
}
