package tk.glucodata

import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityRegistry
import tk.glucodata.drivers.ManagedSensorRuntime

object SensorIdentity {
    private const val NULL_SENTINEL = "\u0000"
    private const val UNKNOWN_SENSOR_SENTINEL = "?"
    private val nativeCanonicalCache = ConcurrentHashMap<String, String>()
    // raw id -> resolved app (canonical) sensor id. resolveAppSensorId iterates every driver's
    // identity adapter (each hitting SharedPreferences + regex), so it is far too costly to run
    // per glucose point per frame — which the calibrated-chart draw path does. The mapping only
    // changes when the sensor set / auth material changes, so memoize it and clear on those events.
    private val appSensorIdCache = ConcurrentHashMap<String, String>()
    // normalized "candidate\u0000expected" -> matches() result. The same reasoning as
    // appSensorIdCache above, for the one path it did not cover: managedMatches asks
    // every driver adapter to match live, and those adapters run regexes and registry
    // lookups. matches() is called per reading per sensor, from resolveAvailableMainSensor,
    // from dowithglucose, from resolveUiSnapshot and from the notification build, so the
    // matcher itself has to be memoized, not just the canonical-id lookup underneath it.
    private val matchCache = ConcurrentHashMap<String, Boolean>()
    // Ids arrive from many sources (native short names, MACs, advertised names, aliases),
    // so bound the key space rather than assume it stays as small as the sensor count.
    private const val MATCH_CACHE_MAX_ENTRIES = 4096

    private fun normalized(sensorId: String?): String? {
        return sensorId
            ?.trim()
            ?.takeIf { isUsableSensorId(it) }
    }

    /** Reject native/UI placeholders before they can participate in sensor selection. */
    @JvmStatic
    fun isUsableSensorId(sensorId: String?): Boolean {
        val value = sensorId?.trim() ?: return false
        return value.isNotEmpty() &&
            value != UNKNOWN_SENSOR_SENTINEL &&
            value.none { it.isISOControl() }
    }

    private fun managedMatches(left: String?, right: String?): Boolean {
        val normalizedLeft = normalized(left) ?: return false
        val normalizedRight = normalized(right) ?: return false
        return ManagedSensorIdentityRegistry.all.any { adapter ->
            adapter.matchesCallbackId(normalizedLeft, normalizedRight) ||
                adapter.matchesCallbackId(normalizedRight, normalizedLeft)
        }
    }

    private fun canonicalOrRaw(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return resolveAppSensorId(raw) ?: raw
    }

    private fun isManagedCanonicalSensorId(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return false
        return ManagedSensorIdentityRegistry.all.any { adapter ->
            val canonical = adapter.resolveCanonicalSensorId(raw)
            !canonical.isNullOrBlank() && canonical.equals(raw, ignoreCase = true)
        }
    }

    private fun resolveNativeBackedCanonicalSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return nativeCanonicalCache.getOrPut(raw) {
            runCatching { Natives.resolveFullSensorName(raw) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: NULL_SENTINEL
        }.takeIf { it != NULL_SENTINEL }
    }

    private fun nativeShortAlias(sensorId: String?): String? {
        val canonical = resolveNativeBackedCanonicalSensorId(sensorId) ?: normalized(sensorId) ?: return null
        if (isManagedCanonicalSensorId(canonical) || canonical.length <= 11) {
            return null
        }
        return canonical.takeLast(11)
    }

    @JvmStatic
    fun invalidateCaches() {
        nativeCanonicalCache.clear()
        appSensorIdCache.clear()
        matchCache.clear()
        ManagedSensorRuntime.clearCaches()
    }

    @JvmStatic
    fun resolveAppSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        appSensorIdCache[raw]?.let { return it }
        val resolved = ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveCanonicalSensorId(raw) }
            .firstOrNull { it.isNotBlank() }
            ?: raw
        appSensorIdCache[raw] = resolved
        return resolved
    }

    /**
     * One logical id for protocol/state keys.
     *
     * Managed records resolve vendor aliases, while native storage resolves its
     * short names back to the full sensor name. Running both here prevents a
     * handoff from changing identity merely because one side learned more about
     * the sensor than the other.
     */
    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        val managed = resolveAppSensorId(raw) ?: raw
        val native = resolveNativeBackedCanonicalSensorId(managed)
            ?: resolveNativeBackedCanonicalSensorId(raw)
        return native?.let { resolveAppSensorId(it) ?: it } ?: managed
    }

    @JvmStatic
    fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveNativeSensorName(raw) }
            .firstOrNull { it.isNotBlank() }
            ?: raw
    }

    @JvmStatic
    fun resolveNativeHistorySensorNames(sensorId: String?): List<String> {
        val raw = normalized(sensorId) ?: return emptyList()
        val resolved = LinkedHashSet<String>()

        fun addCandidate(candidate: String?) {
            candidate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(resolved::add)
        }

        addCandidate(raw)
        addCandidate(resolveAppSensorId(raw))
        addCandidate(resolveNativeSensorName(raw))
        ManagedSensorIdentityRegistry.all.forEach { adapter ->
            adapter.resolveNativeHistorySensorNames(raw).forEach(::addCandidate)
        }

        val snapshot = resolved.toList()
        snapshot.forEach { candidate ->
            addCandidate(resolveNativeBackedCanonicalSensorId(candidate))
            addCandidate(nativeShortAlias(candidate))
        }

        return resolved.toList()
    }

    @JvmStatic
    fun resolveRoomQuerySensorIds(sensorId: String?): List<String> {
        val raw = normalized(sensorId) ?: return emptyList()
        val resolved = LinkedHashSet<String>()
        fun addCandidate(candidate: String?) {
            candidate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(resolved::add)
        }

        addCandidate(raw)
        addCandidate(resolveAppSensorId(raw))
        addCandidate(resolveNativeSensorName(raw))
        addCandidate(resolveRoomStorageSensorId(raw))
        resolveNativeHistorySensorNames(raw).forEach(::addCandidate)
        return resolved.toList()
    }

    @JvmStatic
    fun resolveRoomStorageSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        val managed = ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveStableStorageSensorId(raw) }
            .firstOrNull { it.isNotBlank() }
        if (!managed.isNullOrBlank()) {
            return managed
        }
        return nativeShortAlias(raw) ?: raw
    }

    @JvmStatic
    fun shouldUseNativeHistorySync(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return true
        // The watch has no Room at all — HistoryRepository is mobile-only — so
        // native storage is its only history, whether readings arrive over
        // WearSync2 or from a driver connected here after a handoff. Letting a
        // managed driver answer "Room owns this" left both sources empty and the
        // screen showing "No data" over a full store.
        if (Applic.isWearable) return true
        managedDriverHistorySync(raw)?.let { return it }
        val canonical = canonicalOrRaw(raw) ?: raw
        if (!canonical.equals(raw, ignoreCase = true)) {
            managedDriverHistorySync(canonical)?.let { return it }
        }
        return ManagedSensorIdentityRegistry.shouldUseNativeHistorySync(canonical)
            ?: ManagedSensorIdentityRegistry.shouldUseNativeHistorySync(raw)
            ?: true
    }

    private fun managedDriverHistorySync(sensorId: String?): Boolean? {
        val raw = normalized(sensorId) ?: return null
        return runCatching {
            SensorBluetooth.mygatts()
                .asSequence()
                .mapNotNull { it as? ManagedBluetoothSensorDriver }
                .mapNotNull { driver ->
                    val matches = runCatching { driver.matchesManagedSensorId(raw) }.getOrDefault(false)
                    if (matches) runCatching { driver.shouldUseNativeHistorySync() }.getOrNull() else null
                }
                .firstOrNull()
        }.getOrNull()
    }

    @JvmStatic
    fun hasNativeSensorBacking(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return true
        val canonical = canonicalOrRaw(raw) ?: raw
        return ManagedSensorIdentityRegistry.hasNativeSensorBacking(canonical)
            ?: ManagedSensorIdentityRegistry.hasNativeSensorBacking(raw)
            ?: true
    }

    @JvmStatic
    fun usesNativeDirectStreamShell(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return false
        val canonical = canonicalOrRaw(raw) ?: raw
        return ManagedSensorIdentityRegistry.usesNativeDirectStreamShell(canonical) ||
            ManagedSensorIdentityRegistry.usesNativeDirectStreamShell(raw)
    }

    @JvmStatic
    fun resolveMainSensor(): String? {
        return resolveAvailableMainSensor(
            selectedMain = Natives.lastsensorname(),
            preferredSensorId = null,
            activeSensors = availableSensorCandidates()
        )
    }

    @JvmStatic
    fun resolveLiveMainSensor(preferredSensorId: String?): String? {
        val activeSensors = availableSensorCandidates()
        if (activeSensors.isNullOrEmpty()) {
            return resolveMainSensor()
        }
        return resolveAvailableMainSensor(
            selectedMain = Natives.lastsensorname(),
            preferredSensorId = preferredSensorId,
            activeSensors = activeSensors
        ) ?: resolveMainSensor()
    }

    private fun availableSensorCandidates(): Array<String?>? {
        val resolved = LinkedHashSet<String?>()

        runCatching {
            Natives.activeSensors()?.forEach { sensorId ->
                normalized(sensorId)?.let(resolved::add)
            }
        }

        runCatching {
            SensorBluetooth.mygatts().forEach { callback ->
                normalized(callback.SerialNumber)?.let(resolved::add)
            }
        }

        return resolved.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    @JvmStatic
    fun resolveAvailableMainSensor(
        selectedMain: String?,
        preferredSensorId: String?,
        activeSensors: Array<String?>?
    ): String? {
        val managed = canonicalOrRaw(ManagedCurrentSensor.get())
        val active = activeSensors
            ?.mapNotNull(::canonicalOrRaw)
            ?.distinct()
            .orEmpty()
        val canonicalSelected = canonicalOrRaw(selectedMain)
        val canonicalPreferred = canonicalOrRaw(preferredSensorId)

        if (active.isEmpty()) {
            return managed ?: canonicalSelected ?: canonicalPreferred
        }

        if (managed != null && active.any { matches(it, managed) }) {
            return managed
        }

        if (canonicalSelected != null && active.any { matches(it, canonicalSelected) }) {
            return canonicalSelected
        }

        if (canonicalPreferred != null && active.any { matches(it, canonicalPreferred) }) {
            return canonicalPreferred
        }

        return active.firstOrNull()
    }

    @JvmStatic
    fun matches(candidate: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            return true
        }
        val normalizedCandidate = normalized(candidate) ?: return false
        val normalizedExpected = normalized(expected) ?: return false
        if (normalizedCandidate.equals(normalizedExpected, ignoreCase = true)) {
            return true
        }
        val cacheKey = normalizedCandidate + NULL_SENTINEL + normalizedExpected
        matchCache[cacheKey]?.let { return it }
        val result = matchesUncached(normalizedCandidate, normalizedExpected)
        if (matchCache.size >= MATCH_CACHE_MAX_ENTRIES) matchCache.clear()
        matchCache[cacheKey] = result
        return result
    }

    /**
     * The identity resolution behind [matches], minus the trivial-equality fast path.
     * Everything here is a registry or regex lookup whose answer only changes when
     * [invalidateCaches] is called, which every driver registry already does on a
     * sensor-set change.
     */
    private fun matchesUncached(normalizedCandidate: String, normalizedExpected: String): Boolean {
        if (managedMatches(normalizedCandidate, normalizedExpected)) {
            return true
        }
        val left = resolveAppSensorId(normalizedCandidate)
        val right = resolveAppSensorId(normalizedExpected)
        if (!left.isNullOrBlank() && !right.isNullOrBlank() &&
            left.equals(right, ignoreCase = true)
        ) {
            return true
        }
        val candidateNative = resolveNativeBackedCanonicalSensorId(normalizedCandidate)
        val expectedNative = resolveNativeBackedCanonicalSensorId(normalizedExpected)
        if (!candidateNative.isNullOrBlank() && !expectedNative.isNullOrBlank() &&
            candidateNative.equals(expectedNative, ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun prefersLogicalCandidate(candidate: String, existing: String): Boolean {
        val candidateResolved = resolveAppSensorId(candidate)
        val existingResolved = resolveAppSensorId(existing)
        val candidateScore = (if (!candidateResolved.isNullOrBlank() && candidateResolved.equals(candidate, ignoreCase = true)) 2 else 0) +
            candidate.length
        val existingScore = (if (!existingResolved.isNullOrBlank() && existingResolved.equals(existing, ignoreCase = true)) 2 else 0) +
            existing.length
        return candidateScore > existingScore
    }

    @JvmStatic
    fun distinctLogicalSensorIds(sensorIds: Iterable<String?>): List<String> {
        val distinct = ArrayList<String>()
        sensorIds.forEach { sensorId ->
            val normalized = canonicalOrRaw(sensorId) ?: return@forEach
            val existingIndex = distinct.indexOfFirst { matches(it, normalized) }
            if (existingIndex < 0) {
                distinct.add(normalized)
            } else if (prefersLogicalCandidate(normalized, distinct[existingIndex])) {
                distinct[existingIndex] = normalized
            }
        }
        return distinct
    }
}
