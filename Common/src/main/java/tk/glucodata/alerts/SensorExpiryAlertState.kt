package tk.glucodata.alerts

import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.ManagedBluetoothSensorDriver

/**
 * Pick the expiry-relevant sensor end among per-sensor candidates
 * (serial -> official end, ms). The sensor currently on the display wins;
 * otherwise the farthest end, since the running sensor outlives finished ones.
 *
 * Ends not in the future are rejected: 0 means unknown, a past end means the
 * sensor is already expired. This also keeps a clamped source out of the latch —
 * Natives.getendtime() returns the chart's data range end, capped at "now"
 * while the sensor runs, which made every window look open on every tick and
 * silenced all expiry warnings.
 */
internal fun selectSensorExpiryEndMs(
    candidates: List<Pair<String?, Long>>,
    preferredSensorId: String?,
    nowMs: Long
): Long {
    val plausible = candidates.filter { it.second > nowMs }
    if (plausible.isEmpty()) return 0L
    if (preferredSensorId != null) {
        plausible.firstOrNull { SensorIdentity.matches(it.first, preferredSensorId) }
            ?.let { return it.second }
    }
    return plausible.maxOf { it.second }
}

/**
 * Resolve the official end (start + wear duration) of the relevant sensor from
 * the live gatt registry. Kotlin-managed drivers without native backing fall
 * back to their UI snapshot.
 */
internal fun resolveSensorExpiryEndMs(preferredSensorId: String?, nowMs: Long): Long {
    val gatts = try {
        SensorBluetooth.mygatts()
    } catch (t: Throwable) {
        null
    } ?: return 0L
    val candidates = gatts.map { gatt ->
        val nativeEnd = runCatching { Natives.getSensorEndTime(gatt.dataptr, true) }.getOrDefault(0L)
        val end = if (nativeEnd > 0L) {
            nativeEnd
        } else {
            runCatching {
                (gatt as? ManagedBluetoothSensorDriver)?.getManagedUiSnapshot()?.officialEndMs ?: 0L
            }.getOrDefault(0L)
        }
        gatt.SerialNumber to end
    }
    return selectSensorExpiryEndMs(candidates, preferredSensorId, nowMs)
}

/**
 * Thresholds that are new to the configuration and whose warning window is
 * already open, so a settings save can adopt them (mark them warned) instead of
 * letting them fire retroactively on the next tick.
 *
 * "New" means absent from the previous configuration, independent of whether the
 * alert was enabled back then: an enable/disable cycle mid-sensor must not turn
 * every configured threshold into a new one. Doing so adopted every currently
 * open window at once and left the rest of the sensor without a single warning.
 */
internal fun newlyOpenExpiryThresholds(
    previousMinutes: Set<Int>,
    currentMinutes: Set<Int>,
    endTimeMs: Long,
    nowMs: Long
): Set<Int> {
    if (endTimeMs <= 0L) {
        return emptySet()
    }
    val previouslyConfigured = sanitizeExpiryWarningMinutes(previousMinutes)
    return sanitizeExpiryWarningMinutes(currentMinutes).filter {
        it !in previouslyConfigured && endTimeMs - nowMs <= it.toLong() * 60_000L
    }.toSet()
}

/**
 * Durable memory of which expiry thresholds already warned, keyed by the
 * sensor's end time. Production backs this with SharedPreferences
 * ([AlertRepository.sensorExpiryWarnedStore]); tests inject a fake.
 */
internal interface ExpiryWarnedStore {
    /** Thresholds (minutes) already warned for the sensor ending at [endTimeMs]. */
    fun load(endTimeMs: Long): Set<Int>

    /** Replace the stored set with [thresholds] for [endTimeMs], dropping other sensors' entries. */
    fun save(endTimeMs: Long, thresholds: Set<Int>)
}

/**
 * Edge-triggered latch for the sensor-expiry pre-warnings, one latch per
 * configured threshold. Each threshold fires exactly once per sensor when the
 * clock first crosses into its warning window (`endTimeMs - now <= threshold`).
 *
 * Preserved semantics from the original single-threshold version, now per
 * threshold:
 *  - **Edge-triggered:** fire on window entry, not on every 15s tick.
 *  - **New sensor** (`endTimeMs` changes): rearm every threshold.
 *  - **Baseline:** the first active pass reloads the persisted warned-set for
 *    the current sensor. Already-warned windows (from this or an earlier
 *    process run) stay silent; open windows that were never warned are due
 *    now, so a restart between window entry and the next tick cannot swallow
 *    a warning. The cascade guard caps the catch-up at one alert.
 *  - **Newly enabled thresholds** whose window is already open are adopted
 *    (marked warned, never fired retroactively). Mid-process that happens
 *    here; across a restart [AlertRepository.saveConfig] persists the
 *    adoption at save time. Only a threshold never configured in this episode
 *    counts as new: deselecting and reselecting one keeps its window history,
 *    so it is neither adopted nor fired for an edge it already passed.
 *  - **Cascade guard:** if several thresholds come due in the same tick (e.g. the
 *    app resumes deep inside multiple windows), fire only the smallest (most
 *    urgent) and silently mark the rest as warned.
 *  - **Delivery handshake:** a threshold counts as warned only once the caller
 *    reports it delivered via [confirmDelivered]. A tick that cannot deliver
 *    (no current glucose reading, notification suppressed) leaves it pending and
 *    it is offered again, instead of being marked warned and lost for good.
 */
internal class SensorExpiryAlertState(private val store: ExpiryWarnedStore) {
    private var baselineReady = false
    private var lastEndTimeMs = 0L
    private val wasInWindow = mutableMapOf<Int, Boolean>()   // threshold -> inside window last tick
    private val alertedForEnd = mutableMapOf<Int, Long>()    // threshold -> endTime already warned for
    private val pendingDelivery = mutableSetOf<Int>()        // returned, not yet confirmed delivered

    fun reset() {
        baselineReady = false
        lastEndTimeMs = 0L
        wasInWindow.clear()
        alertedForEnd.clear()
        pendingDelivery.clear()
    }

    /**
     * The caller delivered the alert for [threshold]; only now is it warned.
     * Until this arrives the threshold stays pending and is offered again on
     * every tick, because its window edge has already passed and nothing else
     * would bring it back.
     */
    fun confirmDelivered(threshold: Int) {
        if (lastEndTimeMs <= 0L || threshold !in pendingDelivery) {
            return
        }
        pendingDelivery.remove(threshold)
        alertedForEnd[threshold] = lastEndTimeMs
        persistWarned()
    }

    /**
     * @return the thresholds (in minutes) that should fire an alert right now.
     *   At most one element (cascade guard); empty means do nothing.
     */
    fun triggeredThresholds(
        enabled: Boolean,
        activeNow: Boolean,
        snoozed: Boolean,
        endTimeMs: Long,
        nowMs: Long,
        thresholdsMinutes: Set<Int>
    ): Set<Int> {
        if (!enabled || endTimeMs <= 0L || thresholdsMinutes.isEmpty()) {
            reset()
            return emptySet()
        }

        // New sensor -> new episode: rearm everything, then adopt what an
        // earlier process run already warned for this end time.
        if (endTimeMs != lastEndTimeMs) {
            baselineReady = false
            lastEndTimeMs = endTimeMs
            wasInWindow.clear()
            alertedForEnd.clear()
            pendingDelivery.clear()
            for (t in store.load(endTimeMs)) {
                alertedForEnd[t] = endTimeMs
            }
        }

        if (!activeNow || snoozed) {
            return emptySet()
        }

        fun inWindow(minutes: Int): Boolean =
            endTimeMs - nowMs <= minutes.toLong() * 60_000L

        val newlyDue = mutableListOf<Int>()
        if (!baselineReady) {
            // First active pass: open windows that were never warned - not in
            // this process, not in an earlier one - are due now.
            baselineReady = true
            for (t in thresholdsMinutes) {
                val inside = inWindow(t)
                wasInWindow[t] = inside
                if (inside && alertedForEnd[t] != endTimeMs) {
                    newlyDue.add(t)
                }
            }
        } else {
            // A deselected threshold keeps being tracked for the rest of the
            // episode: its window state must stay current so re-selecting it is
            // neither mistaken for a brand-new threshold (silent adoption) nor
            // fired for an edge that passed while it was off. The maps stay
            // bounded by EXPIRY_WARNING_PRESETS.
            for (t in wasInWindow.keys.toList()) {
                if (t !in thresholdsMinutes) {
                    wasInWindow[t] = inWindow(t)
                }
            }
            for (t in thresholdsMinutes) {
                val inside = inWindow(t)
                val known = wasInWindow.containsKey(t)
                val prev = wasInWindow[t] ?: false
                wasInWindow[t] = inside
                if (!known) {
                    // Threshold never configured in this episode: adopt like the
                    // baseline, never fire retroactively for an open window.
                    if (inside && alertedForEnd[t] != endTimeMs) {
                        alertedForEnd[t] = endTimeMs
                        persistWarned()
                    }
                    continue
                }
                if (inside && !prev && alertedForEnd[t] != endTimeMs) {
                    newlyDue.add(t)
                }
            }
        }

        if (newlyDue.isEmpty()) {
            // Nothing new, but a warning that was offered and never delivered is
            // still owed: its edge has passed, so re-offering here is the only
            // thing that can bring it back.
            return pendingDelivery.minOrNull()?.let { setOf(it) } ?: emptySet()
        }

        // Cascade guard: offer only the most urgent (smallest lead time); mark the
        // rest as warned so they never fire late. An undelivered threshold that a
        // more urgent one overtakes is dropped for the same reason.
        val candidates = newlyDue.toSet() + pendingDelivery
        val fire = candidates.min()
        for (t in candidates) {
            if (t != fire) {
                alertedForEnd[t] = endTimeMs
            }
        }
        pendingDelivery.clear()
        pendingDelivery.add(fire)
        persistWarned()
        return setOf(fire)
    }

    /** Union with the stored set so a concurrent settings-save adoption is not lost. */
    private fun persistWarned() {
        val warned = alertedForEnd.filterValues { it == lastEndTimeMs }.keys
        store.save(lastEndTimeMs, store.load(lastEndTimeMs) + warned)
    }
}
