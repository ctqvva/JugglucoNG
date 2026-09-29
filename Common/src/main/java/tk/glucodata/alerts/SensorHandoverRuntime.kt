package tk.glucodata.alerts

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.CloneSensorRegistry
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.MultiSensorSelection
import tk.glucodata.NativeSensorTermination
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorHandoverNotifier
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver

/**
 * Automatic sensor handover: when the primary sensor reaches its official end
 * time and exactly one successor is active, the successor becomes primary and
 * the expired sensor is deselected (optionally removed). Opt-in via
 * [PREF_ENABLED]; with the switch off, [evaluate] returns before touching
 * anything, so default behaviour is identical to before.
 *
 * [evaluate] runs on the [AlertRuntimeManager] tick (the same 15s cadence and
 * end-time source as the sensor-expiry alarm) and only decides and latches
 * there; the actual switch work (selection writes, BLE teardown on REMOVE,
 * notifications) runs on a dedicated single thread so the alert lock is not
 * held across it and incoming readings are never blocked.
 */
internal object SensorHandoverRuntime {
    private const val LOG_ID = "SensorHandover"

    const val PREF_ENABLED = "sensor_handover_enabled"
    const val PREF_OLD_ACTION = "sensor_handover_old_action"
    const val OLD_ACTION_DEACTIVATE = 0
    const val OLD_ACTION_REMOVE = 1

    private const val PREF_HANDLED = "sensor_handover_handled"
    private const val PREF_WARNED = "sensor_handover_warned"
    private const val PREF_SUPPRESS_UNTIL_MS = "sensor_handover_suppress_until_ms"
    private const val PREF_SUPPRESS_SUCCESSOR = "sensor_handover_suppress_successor"

    private const val PREFS_NAME = "tk.glucodata_preferences"

    /**
     * A sensor counts as delivering when it has a reading at most this old: a successor at the
     * switch, and the sensor switched away from when REMOVE decides about it.
     */
    const val RECENT_READING_MAX_AGE_MS = 15L * 60_000L

    /**
     * How long past its end a sensor that then stops still counts as ended with it when REMOVE
     * decides ([oldSensorRemovalDelayMs]): a start recorded a few minutes off, or a firmware that
     * stops a little after the end the app computes (an Ottai unit measured against the 15-day
     * default stops about 28 min past it). A tuning handle: wider keeps a sensor that has stopped
     * in the list that much longer.
     */
    const val OLD_SENSOR_END_OVERRUN_MS = 60L * 60_000L

    /** Side-effect thread; the alert lock is never held while a handover executes. */
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SensorHandover").apply { isDaemon = true }
    }

    private fun prefs() =
        Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun latchKey(serial: String, endMs: Long) = "$serial|$endMs"

    private val store = object : SensorHandoverStore {
        override fun isHandled(serial: String, endMs: Long): Boolean =
            runCatching { prefs().getString(PREF_HANDLED, null) == latchKey(serial, endMs) }
                .getOrDefault(false)

        override fun markHandled(serial: String, endMs: Long) {
            runCatching { prefs().edit { putString(PREF_HANDLED, latchKey(serial, endMs)) } }
        }

        override fun isWarned(serial: String, endMs: Long): Boolean =
            runCatching { prefs().getString(PREF_WARNED, null) == latchKey(serial, endMs) }
                .getOrDefault(false)

        override fun markWarned(serial: String, endMs: Long) {
            runCatching { prefs().edit { putString(PREF_WARNED, latchKey(serial, endMs)) } }
        }

        override fun suppressionUntilMs(): Long =
            runCatching { prefs().getLong(PREF_SUPPRESS_UNTIL_MS, 0L) }.getOrDefault(0L)

        override fun suppressionSuccessor(): String? =
            runCatching { prefs().getString(PREF_SUPPRESS_SUCCESSOR, null) }.getOrNull()

        override fun openSuppression(untilMs: Long, successorSerial: String) {
            runCatching {
                prefs().edit {
                    putLong(PREF_SUPPRESS_UNTIL_MS, untilMs)
                    putString(PREF_SUPPRESS_SUCCESSOR, successorSerial)
                }
            }
        }

        override fun clearSuppression() {
            runCatching {
                prefs().edit {
                    remove(PREF_SUPPRESS_UNTIL_MS)
                    remove(PREF_SUPPRESS_SUCCESSOR)
                }
            }
        }
    }

    private val state = SensorHandoverState(store, SensorIdentity::matches)

    fun isEnabled(): Boolean =
        runCatching { prefs().getBoolean(PREF_ENABLED, false) }.getOrDefault(false)

    private fun oldSensorAction(): Int =
        runCatching { prefs().getInt(PREF_OLD_ACTION, OLD_ACTION_DEACTIVATE) }
            .getOrDefault(OLD_ACTION_DEACTIVATE)

    /** Snapshot of the live sensor registry, split into primary and the rest. */
    private class Roster(
        val primary: HandoverSensor?,
        val others: List<HandoverSensor>,
        val allSerials: List<String>
    )

    private fun buildRoster(): Roster? {
        val gatts = runCatching { SensorBluetooth.mygatts() }.getOrNull() ?: return null
        if (gatts.isEmpty()) return null
        val primarySerial = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
        if (primarySerial.isNullOrEmpty()) return null
        val activeSet = (runCatching { Natives.activeSensors() }.getOrNull() ?: emptyArray())
            .filterNotNull()
            .toHashSet()

        var primary: HandoverSensor? = null
        val others = mutableListOf<HandoverSensor>()
        val allSerials = mutableListOf<String>()
        for (gatt in gatts) {
            val serial = gatt.SerialNumber ?: continue
            if (!SensorIdentity.isUsableSensorId(serial)) continue
            val managedOutsideNative = gatt is ManagedBluetoothSensorDriver &&
                gatt.isManagedOutsideNativeActiveSet()
            if (!managedOutsideNative && !activeSet.contains(serial)) continue

            val snapshot = if (gatt is ManagedBluetoothSensorDriver) {
                runCatching { gatt.getManagedUiSnapshot(primarySerial) }.getOrNull()
            } else {
                null
            }
            val nativeEnd = if (gatt.dataptr != 0L) {
                runCatching { Natives.getSensorEndTime(gatt.dataptr, true) }.getOrDefault(0L)
            } else {
                0L
            }
            val endMs = if (nativeEnd > 0L) nativeEnd else snapshot?.officialEndMs ?: 0L
            val nativeStart = if (gatt.dataptr != 0L) {
                runCatching { Natives.getSensorStartmsec(gatt.dataptr) }.getOrDefault(0L)
            } else {
                0L
            }
            val startMs = if (nativeStart > 0L) nativeStart else snapshot?.startTimeMs ?: 0L

            allSerials.add(serial)
            val sensor = HandoverSensor(serial, startMs, endMs) { hasRecentReading(serial) }
            if (SensorIdentity.matches(serial, primarySerial)) {
                if (primary == null) primary = sensor
            } else if (others.none { SensorIdentity.matches(it.serial, serial) }) {
                // One physical sensor can sit in the registry under several
                // serial forms (ICN- alias, short tail, vendor-padded); collapse
                // them so an alias twin cannot fake a second candidate.
                others.add(sensor)
            }
        }
        return Roster(primary, others, allSerials)
    }

    private fun hasRecentReading(serial: String): Boolean {
        val snapshot = runCatching {
            CurrentDisplaySource.resolveCurrent(RECENT_READING_MAX_AGE_MS, serial)
        }.getOrNull() ?: return false
        if (!snapshot.primaryValue.isFinite() || snapshot.timeMillis <= 0L) return false
        return System.currentTimeMillis() - snapshot.timeMillis <= RECENT_READING_MAX_AGE_MS
    }

    /** Called from the alert tick; decides and latches only, never throws. */
    fun evaluate(nowMs: Long) {
        try {
            if (!isEnabled()) return
            val roster = buildRoster() ?: return
            val primary = roster.primary ?: return
            when (val decision = state.evaluate(true, primary, roster.others, nowMs)) {
                is HandoverDecision.None -> {}
                is HandoverDecision.WarnMultiple -> {
                    store.markWarned(primary.serial, primary.endMs)
                    Log.i(
                        LOG_ID,
                        "Multiple successor candidates ${decision.candidateSerials} for expired " +
                            "${decision.expiredSerial}; not switching automatically"
                    )
                    executor.execute {
                        runCatching { SensorHandoverNotifier.notifyMultipleCandidates(Applic.app) }
                    }
                }
                is HandoverDecision.Switch -> {
                    // Latch before acting: a crash mid-switch must not retrigger
                    // the handover on the next tick (at-most-once per expiry).
                    store.markHandled(primary.serial, primary.endMs)
                    val warmupMinutes = warmupMinutesEstimate(roster, decision.newSerial, nowMs)
                    if (decision.successorWarming) {
                        // Open the window before the switch so a missed-reading
                        // check between switch and notification cannot fire.
                        state.openMissedReadingSuppression(nowMs, decision.newSerial)
                    }
                    executor.execute { performHandover(roster, decision, warmupMinutes) }
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "evaluate", t)
        }
    }

    private fun performHandover(
        roster: Roster,
        decision: HandoverDecision.Switch,
        warmupMinutes: Int
    ) {
        Log.i(
            LOG_ID,
            "Handover: ${decision.oldSerial} expired -> promoting ${decision.newSerial}" +
                if (decision.successorWarming) " (successor still warming up)" else ""
        )

        // Promote the successor through the existing selection machinery and
        // deselect the expired sensor (the equivalent of unchecking it on its
        // card) in a single write. The expired sensor stays in the sensor list
        // until removed or aged out. Note: unlike a manual promotion this does
        // not run HistorySync.mergeFullSyncForSensor (mobile-only); the
        // successor's history is already in Room from its time as a peer.
        runCatching {
            val selected = MultiSensorSelection.selectedAvailable(roster.allSerials, decision.newSerial)
            val next = listOf(decision.newSerial) + selected.filterNot {
                SensorIdentity.matches(it, decision.newSerial) ||
                    SensorIdentity.matches(it, decision.oldSerial)
            }
            MultiSensorSelection.setSelectedOrder(next)
        }
        runCatching { SensorBluetooth.setCurrentSensorSelection(decision.newSerial) }

        val promoted = runCatching {
            SensorIdentity.matches(SensorIdentity.resolveMainSensor(), decision.newSerial)
        }.getOrDefault(false)
        if (!promoted) {
            // Latched already, so this expiry will not be retried - surface it
            // loudly in the log instead of sending a false success receipt.
            Log.e(LOG_ID, "Handover promotion did not take: ${decision.newSerial} is not primary")
            store.clearSuppression()
            return
        }

        if (oldSensorAction() == OLD_ACTION_REMOVE) {
            // Not decided here: the switch comes within a tick of the old sensor's end, when one
            // that measured up to that end still has a recent reading. Until the check scheduled
            // below, the old sensor is deselected as with DEACTIVATE (the selection write above).
            val oldSerial = decision.oldSerial
            val oldGatt = findGatt(oldSerial)
            val oldEndMs = roster.primary?.endMs ?: 0L
            val delayMs = oldSensorRemovalDelayMs(oldEndMs, System.currentTimeMillis())
            Log.i(LOG_ID, "Removal of $oldSerial after handover is decided in ${delayMs / 60_000L} min")
            executor.schedule(
                Runnable { removeOldSensorIfSilent(oldSerial, oldGatt, oldEndMs) }, delayMs, TimeUnit.MILLISECONDS
            )
        }

        runCatching {
            if (decision.successorWarming) {
                SensorHandoverNotifier.notifySwitchedWarming(
                    Applic.app, decision.newSerial, warmupMinutes
                )
            } else {
                SensorHandoverNotifier.notifySwitched(
                    Applic.app, decision.oldSerial, decision.newSerial
                )
            }
        }

        runCatching { UiRefreshBus.requestDataRefresh() }
    }

    private fun warmupMinutesEstimate(roster: Roster, successorSerial: String, nowMs: Long): Int {
        val startMs = roster.others.firstOrNull { it.serial == successorSerial }?.startMs ?: 0L
        val warmupMinutes = SensorHandoverState.WARMUP_DURATION_MS / 60_000L
        if (startMs <= 0L) return warmupMinutes.toInt()
        val remaining = (startMs + SensorHandoverState.WARMUP_DURATION_MS - nowMs) / 60_000L
        return remaining.coerceIn(1L, warmupMinutes).toInt()
    }

    /**
     * The REMOVE decision [performHandover] schedules ([oldSensorRemovalDelayMs]). REMOVE runs the
     * forget path, which cannot be undone (pairing keys wiped; for Ottai also a cloud unbind
     * request), while an end can pass with the sensor still measuring: the end is only as good as
     * its inputs — a start recorded off, or, while an Ottai unit's firmware lifetime is not yet read
     * here, the cloud rating, which can sit days to weeks before the firmware's end. So a sensor that still
     * delivers here stays deselected, as with DEACTIVATE, and so does one taken back meanwhile:
     * handover or REMOVE turned off, the sensor selected again, or its entry in the sensor list
     * gone or replaced. A skipped removal is not retried: the expiry was latched before the switch.
     *
     * Silence only means "ended" for a sensor that delivered here up to its end
     * ([deliveredUpToEnd]). One whose readings here stop well before that end - a record imported
     * with an end already in the past - was not measuring here when its end passed, so its silence
     * now says nothing about it and it stays deselected too. A sensor another phone is holding is
     * screened before that, by its Clone mark: its mirrored readings are stored here under its own
     * serial and run up to its end, so the store cannot tell it from one measured here, while its
     * silence is only the mirror link being down - it is never dialled locally, so the mirror is
     * its only source of freshness.
     */
    private fun removeOldSensorIfSilent(serial: String, gatt: SuperGattCallback?, oldEndMs: Long) {
        val takenBack = runCatching {
            !isEnabled() || oldSensorAction() != OLD_ACTION_REMOVE || findGatt(serial) !== gatt ||
                SensorIdentity.matches(SensorIdentity.resolveMainSensor(), serial) ||
                MultiSensorSelection.selectedOrder().any { SensorIdentity.matches(it, serial) }
        }.getOrDefault(true)
        when {
            takenBack -> Log.i(
                LOG_ID, "Not removing $serial after handover: taken back, or its sensor-list entry gone or replaced"
            )
            CloneSensorRegistry.isCloneSensor(serial) -> Log.i(
                LOG_ID, "Not removing $serial after handover: it is mirrored from another device, so it stays deselected"
            )
            hasRecentReading(serial) -> Log.i(
                LOG_ID, "Not removing $serial after handover: it still delivers, so it stays deselected"
            )
            // Blocking Room read (runBlocking inside the bridge) on this executor's own thread,
            // and only here, where the sensor is already known to be silent. Bound it if it ever
            // stalls the handover queue.
            !deliveredUpToEnd(HistorySyncAccess.getLatestTimestampForSensor(serial), oldEndMs) -> Log.i(
                LOG_ID, "Not removing $serial after handover: no reading stored here up to its end, so it stays deselected"
            )
            else -> removeOldSensor(serial)
        }
    }

    /**
     * The REMOVE option: the full forget path of the existing remove dialog
     * (mirrors SensorViewModel.forgetSensor, which is UI-scoped and therefore
     * not callable from here). Wipes pairing keys and removes the sensor from
     * list and prefs; measurement history is untouched (there is no history
     * delete in this path).
     */
    private fun removeOldSensor(serial: String) {
        Log.i(LOG_ID, "Removing expired sensor $serial after handover")
        val gatt = findGatt(serial)
        if (gatt != null) {
            if (gatt is ManagedBluetoothSensorDriver) {
                // Managed drivers own their teardown (AiDex wipes vendor keys here).
                runCatching {
                    gatt.terminateManagedSensor(wipeData = false)
                    gatt.removeManagedPersistence(Applic.app)
                }.onFailure { Log.e(LOG_ID, "managed teardown failed: ${it.message}") }
                runCatching { SensorBluetooth.sensorEnded(serial) }
                runCatching { SensorBluetooth.startscan() }
                return
            }
            // Legacy native path: stop BLE processing and mark finished before
            // removing from the Java list (the same checked primitive as the remove dialog).
            runCatching { gatt.setPause(true) }
                .onFailure { Log.e(LOG_ID, "pause before removal failed: ${it.message}") }
            runCatching { gatt.closeGattTransport() }
                .onFailure { Log.e(LOG_ID, "GATT close before removal failed: ${it.message}") }
            val nativeSerial = gatt.SerialNumber ?: serial
            val result = NativeSensorTermination.removeAndConfirm(nativeSerial)
            if (result != NativeSensorTermination.Result.CONFIRMED) {
                Log.e(LOG_ID, "Native removal of $serial was not confirmed: $result")
                return
            }
            runCatching {
                if (gatt.dataptr != 0L && Natives.isSibionics(gatt.dataptr)) {
                    Natives.siClearTransmitterBinding(gatt.dataptr)
                    runCatching { gatt.setDeviceAddress(null) }
                }
            }.onFailure { Log.e(LOG_ID, "clear transmitter binding failed: ${it.message}") }
        }
        removeAiDexFromPrefs(serial)
        runCatching { SensorBluetooth.sensorEnded(serial) }
        runCatching { SensorBluetooth.startscan() }
    }

    private fun findGatt(serial: String): SuperGattCallback? {
        val gatts = runCatching { SensorBluetooth.mygatts() }.getOrNull() ?: return null
        return gatts.firstOrNull { it.SerialNumber == serial }
            ?: gatts.firstOrNull { SensorIdentity.matches(it.SerialNumber, serial) }
            ?: gatts.firstOrNull {
                (it as? ManagedBluetoothSensorDriver)?.matchesManagedSensorId(serial) == true
            }
    }

    /** Mirror of SensorViewModel.removeAiDexFromPrefs (UI-scoped there). */
    private fun removeAiDexFromPrefs(serial: String) {
        runCatching {
            val prefs = prefs()
            val sensors = prefs.getStringSet("aidex_sensors", null) ?: return
            val updated = HashSet(sensors)
            val removed = updated.removeAll { it.startsWith("$serial|") || it == serial }
            if (removed) {
                prefs.edit().putStringSet("aidex_sensors", updated).commit()
                SensorIdentity.invalidateCaches()
            }
        }.onFailure { Log.e(LOG_ID, "removeAiDexFromPrefs failed: ${it.message}") }
    }

    /** Missed-reading gate for [AlertRuntimeManager]: true while the post-handover window is open. */
    fun missedReadingSuppressed(nowMs: Long): Boolean {
        return runCatching {
            if (!isEnabled()) {
                // Turning the feature off must not leave a suppression behind.
                if (store.suppressionUntilMs() > 0L) store.clearSuppression()
                return@runCatching false
            }
            state.missedReadingSuppressed(nowMs)
        }.getOrDefault(false)
    }

    /** Reading tap-in from [AlertRuntimeManager.onNewReading]; closes the window on the successor's first data. */
    fun onReading(sensorId: String?) {
        runCatching { state.onReading(sensorId) }
    }

    /**
     * Sensor-expiry alarm interlock: when the handover is armed and a unique,
     * already-delivering successor exists, the expiry warning is reworded as
     * "successor ready" instead of a bare problem report - the app is about to
     * solve this itself. The expiry latch and its persistence stay untouched.
     */
    fun decorateExpiryMessage(message: String, nowMs: Long): String {
        return try {
            if (!isEnabled()) return message
            val roster = buildRoster() ?: return message
            if (roster.primary == null) return message
            val candidates = roster.others.filter { it.endMs <= 0L || it.endMs > nowMs }
            val successor = candidates.singleOrNull() ?: return message
            if (!successor.hasRecentReading()) return message
            message + " - " + Applic.app.getString(R.string.sensor_handover_successor_ready)
        } catch (t: Throwable) {
            message
        }
    }
}

/**
 * Delay from [nowMs] until REMOVE decides about the sensor a handover switched away from, whose end
 * is [oldEndMs]. The switch comes within a tick of that end, when a sensor that measured up to it
 * still has a reading inside [SensorHandoverRuntime.RECENT_READING_MAX_AGE_MS] and would read as
 * delivering. The decision waits until a reading taken before the end plus
 * [SensorHandoverRuntime.OLD_SENSOR_END_OVERRUN_MS] no longer counts as recent, so a sensor still
 * delivering then has a reading from no earlier than that point. However late the switch, the
 * decision still waits one full freshness window: the old sensor's driver has to scan and connect
 * before it can say anything at all, and a decision taken while it is still doing that would read
 * that silence as an ending.
 */
internal fun oldSensorRemovalDelayMs(oldEndMs: Long, nowMs: Long): Long =
    (oldEndMs + SensorHandoverRuntime.OLD_SENSOR_END_OVERRUN_MS +
        SensorHandoverRuntime.RECENT_READING_MAX_AGE_MS - nowMs)
        .coerceAtLeast(SensorHandoverRuntime.RECENT_READING_MAX_AGE_MS)

/**
 * Whether the sensor a handover switched away from delivered here up to its end [oldEndMs].
 * [latestStoredMs] is the newest reading Room holds for it, 0 both when it has none and when the
 * store cannot be asked at all. Room on purpose, and not the native journal: the journal does not
 * separate what this phone measured from what an import or an NFC backfill put there, so it cannot
 * answer this question. Either way nothing here says the sensor was ever measuring HERE, so nothing
 * here says it has stopped. Only a sensor that did deliver up to its end may have its later silence read as
 * an ending, which is what REMOVE acts on, and REMOVE cannot be undone: it wipes the pairing keys
 * and, for Ottai, asks the cloud to unbind. A record imported with an end already in the past is
 * silent here for a reason that has nothing to do with the sensor being over. A sensor another
 * phone is holding does store readings here, over Clone, so this cannot screen it out;
 * [SensorHandoverRuntime] screens it by its Clone mark instead.
 */
internal fun deliveredUpToEnd(latestStoredMs: Long, oldEndMs: Long): Boolean =
    latestStoredMs > 0L &&
        latestStoredMs >= oldEndMs - SensorHandoverRuntime.RECENT_READING_MAX_AGE_MS
