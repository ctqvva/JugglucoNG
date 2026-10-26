package tk.glucodata

import android.content.Context
import java.util.Locale

enum class CloneTransport(val code: Int) {
    UNKNOWN(0),
    LOCAL_ICE(1),
    TURN(2);

    companion object {
        fun fromCode(code: Int): CloneTransport = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

internal object CloneSensorKeyCodec {
    fun normalize(sensorId: String?): String? = sensorId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase(Locale.ROOT)

    fun decode(encoded: String?): Map<String, CloneTransport> = encoded
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val key = normalize(line.substringBefore('|')) ?: return@mapNotNull null
            val transport = CloneTransport.fromCode(line.substringAfter('|', "0").trim().toIntOrNull() ?: 0)
            key to transport
        }
        .toMap()

    fun encode(entries: Map<String, CloneTransport>): String = entries
        .mapNotNull { (key, transport) -> normalize(key)?.let { it to transport } }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .joinToString("\n") { (key, transport) -> "$key|${transport.code}" }

    fun transportFor(encoded: String?, sensorId: String?): CloneTransport? =
        transportForAny(encoded, listOfNotNull(sensorId))

    fun transportForAny(encoded: String?, sensorIds: Iterable<String>): CloneTransport? {
        val stored = decode(encoded)
        return sensorIds.asSequence().mapNotNull(::normalize).mapNotNull(stored::get).firstOrNull()
    }

    fun nonPrimarySensorIds(sensorIds: Iterable<String>, primarySensorId: String?): List<String> {
        return nonPrimarySensorIds(sensorIds, listOfNotNull(primarySensorId))
    }

    fun nonPrimarySensorIds(
        sensorIds: Iterable<String>,
        primarySensorIds: Iterable<String>
    ): List<String> {
        val primaryKeys = primarySensorIds.mapNotNull(::normalize).toSet()
        return sensorIds.mapNotNull(::normalize).filterNot { it in primaryKeys }
    }
}

internal object CloneSensorConnectionCodec {
    fun decode(encoded: String?): Map<String, String> = encoded
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val key = CloneSensorKeyCodec.normalize(line.substringBefore('|'))
                ?: return@mapNotNull null
            val connectionIdentity = line.substringAfter('|', "").trim()
                .takeIf { it.isNotEmpty() && '|' !in it }
                ?: return@mapNotNull null
            key to connectionIdentity
        }
        .toMap()

    fun encode(entries: Map<String, String>): String = entries
        .mapNotNull { (key, connectionIdentity) ->
            CloneSensorKeyCodec.normalize(key)
                ?.let { normalized ->
                    connectionIdentity.trim()
                        .takeIf { it.isNotEmpty() && '|' !in it && '\n' !in it && '\r' !in it }
                        ?.let { normalized to it }
                }
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .joinToString("\n") { (key, connectionIdentity) -> "$key|$connectionIdentity" }

    fun connectionForAny(encoded: String?, sensorIds: Iterable<String>): String? {
        val stored = decode(encoded)
        return sensorIds.asSequence().mapNotNull(CloneSensorKeyCodec::normalize)
            .mapNotNull(stored::get)
            .firstOrNull()
    }
}

internal object CloneLiveTransportPolicy {
    private fun CloneTransport.isKnownRoute(): Boolean =
        this == CloneTransport.LOCAL_ICE || this == CloneTransport.TURN

    fun resolve(
        mappedTransport: CloneTransport?,
        connectedTransports: Iterable<CloneTransport>,
    ): CloneTransport {
        if (mappedTransport?.isKnownRoute() == true) return mappedTransport
        return connectedTransports.filter { it.isKnownRoute() }.singleOrNull()
            ?: CloneTransport.UNKNOWN
    }
}

/**
 * Moves a mirrored sensor between the receiver's Clone set and its taken-over
 * set, keeping the route it arrived by so handing it back restores the card as
 * it was. [matches] says whether a stored key names the sensor being moved.
 */
internal object CloneTakeOverPolicy {
    data class Entries(
        val clone: Map<String, CloneTransport>,
        val takenOver: Map<String, CloneTransport>,
    )

    fun takeOver(entries: Entries, aliases: Set<String>, matches: (String) -> Boolean): Entries {
        val moving = entries.clone.filterKeys(matches)
        val transport = moving.values.firstOrNull { it != CloneTransport.UNKNOWN }
            ?: moving.values.firstOrNull()
            ?: CloneTransport.UNKNOWN
        return Entries(
            clone = entries.clone.filterKeys { !matches(it) },
            takenOver = entries.takenOver.filterKeys { !matches(it) } +
                aliases.associateWith { transport },
        )
    }

    fun handBack(entries: Entries, aliases: Set<String>, matches: (String) -> Boolean): Entries {
        val returning = entries.takenOver.filterKeys(matches)
        val transport = returning.values.firstOrNull { it != CloneTransport.UNKNOWN }
            ?: CloneTransport.UNKNOWN
        return Entries(
            clone = entries.clone.filterKeys { !matches(it) } + aliases.associateWith { transport },
            takenOver = entries.takenOver.filterKeys { !matches(it) },
        )
    }

    fun isTakenOver(takenOver: Map<String, CloneTransport>, matches: (String) -> Boolean): Boolean =
        takenOver.keys.any(matches)

    /** Native kinds this phone reads from the mirrored record alone. */
    private val nativeReadableKinds = setOf(
        SensorSourceResolver.SENSOR_KIND_LIBRE2,
        SensorSourceResolver.SENSOR_KIND_LIBRE3,
        SensorSourceResolver.SENSOR_KIND_SIBIONICS,
        SensorSourceResolver.SENSOR_KIND_ACCUCHEK,
        SensorSourceResolver.SENSOR_KIND_DEXCOM,
        SensorSourceResolver.SENSOR_KIND_CARESENS_AIR,
    )

    /**
     * Whether this phone can read the sensor itself once it stops deferring.
     *
     * A Kotlin-driven sensor needs its driver record (keys, cursor, algorithm
     * state), and that lives on the sender; Clone carries only the native
     * shell. Without a record here the shell would be dialled as whatever its
     * native kind defaults to, and the Sibionics orphan sweep finishes a SIBI:
     * shell the moment its Clone flag is gone.
     *
     * @param hasLocalManagedRecord a managed adapter holds a record for it here
     * @param stampedFamilyCode the managed family stamped in the native record, 0 if none
     * @param nativeKind the native record's sensor kind
     * @param fullNativeName the native record's full name
     */
    fun canTakeOver(
        hasLocalManagedRecord: Boolean,
        stampedFamilyCode: Int,
        nativeKind: Int,
        fullNativeName: String?,
    ): Boolean {
        if (hasLocalManagedRecord) return true
        if (stampedFamilyCode != 0) return false
        if (fullNativeName?.trim()?.startsWith(
                tk.glucodata.drivers.sibionics.SibionicsConstants.MANAGED_PREFIX,
                ignoreCase = true,
            ) == true) return false
        return nativeKind in nativeReadableKinds
    }
}

/** Records which sensor files are being populated by the phone-to-phone clone path. */
object CloneSensorRegistry {
    /** How long a quiet mirror keeps its claim on a sensor. */
    private const val MIRROR_LIVE_WINDOW_MS = 10L * 60L * 1000L
    private val lastMirrorSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val processStartedAt = android.os.SystemClock.elapsedRealtime()

    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_SENSOR_IDS = "clone_source_sensor_ids_v1"
    private const val KEY_SENSOR_CONNECTIONS = "clone_source_connection_labels_v1"
    private const val KEY_RECEPTION_ENABLED = "clone_reception_enabled_v1"
    private const val KEY_TAKEN_OVER_IDS = "clone_taken_over_sensor_ids_v1"
    private val lock = Any()

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun candidateKeys(sensorId: String?): Set<String> {
        val raw = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        return buildSet {
            CloneSensorKeyCodec.normalize(raw)?.let(::add)
            runCatching { SensorIdentity.canonicalSensorId(raw) }
                .getOrNull()
                ?.let(CloneSensorKeyCodec::normalize)
                ?.let(::add)
            runCatching { SensorIdentity.resolveNativeSensorName(raw) }
                .getOrNull()
                ?.let(CloneSensorKeyCodec::normalize)
                ?.let(::add)
            runCatching { SensorIdentity.resolveRoomStorageSensorId(raw) }
                .getOrNull()
                ?.let(CloneSensorKeyCodec::normalize)
                ?.let(::add)
            // Native shortens a sensor name to everything past its first five
            // characters, an X- prefixed AiDex name excepted, and the GATT
            // callbacks on the receiving device carry that short form while the
            // registry is written from the full one. Without both, a lookup by
            // one name misses an entry stored under the other -- which showed up
            // as a mirrored sensor briefly claiming to be a Libre 2.
            if (!raw.startsWith("X-") && raw.length > 5) {
                CloneSensorKeyCodec.normalize(raw.substring(5))?.let(::add)
            }
        }
    }

    private fun registryKey(sensorId: String?): String? =
        runCatching { SensorIdentity.resolveRoomStorageSensorId(sensorId) }
            .getOrNull()
            ?.let(CloneSensorKeyCodec::normalize)
            ?: CloneSensorKeyCodec.normalize(sensorId)

    @JvmStatic
    fun markCloneSensor(sensorId: String?, transportCode: Int, connectionIdentity: String?): Boolean {
        val key = registryKey(sensorId) ?: return false
        val aliases = candidateKeys(sensorId) + key
        val transport = CloneTransport.fromCode(transportCode)
        synchronized(lock) {
            val preferences = prefs() ?: return false
            if (!preferences.getBoolean(KEY_RECEPTION_ENABLED, true)) return false
            // A sensor this phone took over still accepts what the sender
            // mirrors, but the sync must not flag it as Clone again: that would
            // pause the local connection the user just asked for.
            if (isTakenOverLocked(preferences, aliases)) return true
            val current = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null))
            val effectiveTransport = if (transport == CloneTransport.UNKNOWN) {
                aliases.asSequence().mapNotNull(current::get).firstOrNull() ?: CloneTransport.UNKNOWN
            } else {
                transport
            }
            val seenAt = android.os.SystemClock.elapsedRealtime()
            aliases.forEach { lastMirrorSeen[it] = seenAt }
            val updated = current.filterKeys { it !in aliases } +
                aliases.associateWith { effectiveTransport }
            if (updated != current) {
                preferences.edit()
                    .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(updated))
                    .apply()
            }
            val stableConnectionIdentity = connectionIdentity?.trim()?.takeIf { it.isNotEmpty() }
            if (stableConnectionIdentity != null) {
                val currentConnections = CloneSensorConnectionCodec.decode(
                    preferences.getString(KEY_SENSOR_CONNECTIONS, null)
                )
                val updatedConnections = currentConnections.filterKeys { it !in aliases } +
                    (key to stableConnectionIdentity)
                if (updatedConnections != currentConnections) {
                    preferences.edit()
                        .putString(
                            KEY_SENSOR_CONNECTIONS,
                            CloneSensorConnectionCodec.encode(updatedConnections),
                        )
                        .apply()
                }
            }
        }
        runCatching { SensorBluetooth.blockLocalCloneConnection(sensorId) }
        return true
    }

    @JvmStatic
    fun markLocalSensor(sensorId: String?) {
        val localKeys = candidateKeys(sensorId)
        if (localKeys.isEmpty()) return
        // The receiver dials a sensor on a timer but only marks it when the
        // sender syncs it, and the Anytime syncs every three minutes. A dial
        // landing in that gap took a local reading, cleared the flag here, and
        // the sensor never got it back -- so it stayed a local record with a
        // play button. A sensor the mirror is still delivering keeps its flag
        // whatever this device manages to read off it.
        if (isMirrorDelivering(sensorId)) return
        synchronized(lock) {
            val preferences = prefs() ?: return
            val current = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null))
            val updated = current.filterKeys { stored ->
                stored !in localKeys && candidateKeys(stored).none { it in localKeys }
            }
            val currentConnections = CloneSensorConnectionCodec.decode(
                preferences.getString(KEY_SENSOR_CONNECTIONS, null)
            )
            val updatedConnections = currentConnections.filterKeys { it !in localKeys }
            if (updated != current || updatedConnections != currentConnections) {
                preferences.edit()
                    .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(updated))
                    .putString(
                        KEY_SENSOR_CONNECTIONS,
                        CloneSensorConnectionCodec.encode(updatedConnections),
                    )
                    .apply()
            }
        }
    }

    /**
     * Whether readings for [sensorId] are still arriving over Clone.
     *
     * The receiving device stands back from a sensor the sender is mirroring, but
     * it must not hold that position forever: unplug the sender and the follower
     * should be able to take the sensor over, which is how a handover worked
     * before there was any gate at all. A mirror that has gone quiet for
     * [MIRROR_LIVE_WINDOW_MS] releases its claim.
     *
     * Nothing seen yet in this process is treated as live for the same window, so
     * a restart cannot snatch a sensor out from under a sender that is streaming
     * perfectly well and simply has not synced since.
     */
    @JvmStatic
    fun isMirrorDelivering(sensorId: String?): Boolean {
        if (!isCloneSensor(sensorId)) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val seen = candidateKeys(sensorId).mapNotNull(lastMirrorSeen::get).maxOrNull()
            ?: return now - processStartedAt < MIRROR_LIVE_WINDOW_MS
        return now - seen < MIRROR_LIVE_WINDOW_MS
    }

    @JvmStatic
    fun isCloneSensor(sensorId: String?): Boolean {
        return transportForSensor(sensorId) != null
    }

    private fun matcherFor(keys: Set<String>): (String) -> Boolean = { stored ->
        stored in keys || candidateKeys(stored).any { it in keys }
    }

    private fun isTakenOverLocked(
        preferences: android.content.SharedPreferences,
        keys: Set<String>,
    ): Boolean = CloneTakeOverPolicy.isTakenOver(
        CloneSensorKeyCodec.decode(preferences.getString(KEY_TAKEN_OVER_IDS, null)),
        matcherFor(keys),
    )

    /** Whether the user moved this mirrored sensor onto this phone's own Bluetooth. */
    @JvmStatic
    fun isTakenOver(sensorId: String?): Boolean {
        val keys = candidateKeys(sensorId)
        if (keys.isEmpty()) return false
        return synchronized(lock) {
            val preferences = prefs() ?: return@synchronized false
            isTakenOverLocked(preferences, keys)
        }
    }

    /** See [CloneTakeOverPolicy.canTakeOver]. Reads prefs and the native record only. */
    @JvmStatic
    fun canTakeOver(sensorId: String?): Boolean {
        val id = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val hasLocalRecord = runCatching {
            tk.glucodata.drivers.ManagedSensorIdentityRegistry.resolvePersistedFamily(id) != null
        }.getOrDefault(false)
        val snapshot = runCatching { Natives.getSensorUiSnapshot(id) }.getOrNull()
        val fullName = runCatching { Natives.resolveFullSensorName(id) }.getOrNull()
        return CloneTakeOverPolicy.canTakeOver(
            hasLocalManagedRecord = hasLocalRecord,
            stampedFamilyCode = snapshot?.getOrNull(5)?.toInt() ?: 0,
            nativeKind = snapshot?.getOrNull(0)?.toInt() ?: SensorSourceResolver.SENSOR_KIND_UNKNOWN,
            fullNativeName = fullName,
        )
    }

    /**
     * The receiver stops deferring to the sender for [sensorId] and may dial it
     * itself, as a follower could before Clone existed. The sensor record,
     * its history and the mirrored data all stay; only the Clone flag moves
     * aside, so every local connect gate opens. Mirror syncs keep importing.
     */
    @JvmStatic
    fun takeOver(sensorId: String?): Boolean {
        if (!canTakeOver(sensorId)) return false
        val keys = candidateKeys(sensorId) + listOfNotNull(registryKey(sensorId))
        if (keys.isEmpty()) return false
        // A mirror sync decides to pause a Clone callback and pauses it under the
        // roster lock (SensorBluetooth.blockLocalCloneConnection). Clearing the
        // flag under the same lock means a sync either pauses before this and the
        // caller's reconnect undoes it, or sees the flag gone and leaves it alone.
        val stored = SensorBluetooth.withCallbackRoster {
            synchronized(lock) {
                val preferences = prefs() ?: return@synchronized false
                val updated = CloneTakeOverPolicy.takeOver(currentEntriesLocked(preferences), keys, matcherFor(keys))
                storeEntriesLocked(preferences, updated)
                true
            }
        }
        if (!stored) return false
        keys.forEach(lastMirrorSeen::remove)
        Log.i("CloneSensorRegistry", "takeOver $sensorId")
        return true
    }

    /**
     * Undoes [takeOver]: the sensor is a Clone record again and the caller
     * closes the local connection, so the sender can have the transmitter back.
     */
    @JvmStatic
    fun handBack(sensorId: String?): Boolean {
        val keys = candidateKeys(sensorId) + listOfNotNull(registryKey(sensorId))
        if (keys.isEmpty()) return false
        synchronized(lock) {
            val preferences = prefs() ?: return false
            if (!preferences.getBoolean(KEY_RECEPTION_ENABLED, true)) return false
            val updated = CloneTakeOverPolicy.handBack(currentEntriesLocked(preferences), keys, matcherFor(keys))
            storeEntriesLocked(preferences, updated)
        }
        Log.i("CloneSensorRegistry", "handBack $sensorId")
        return true
    }

    private fun currentEntriesLocked(preferences: android.content.SharedPreferences) =
        CloneTakeOverPolicy.Entries(
            clone = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null)),
            takenOver = CloneSensorKeyCodec.decode(preferences.getString(KEY_TAKEN_OVER_IDS, null)),
        )

    private fun storeEntriesLocked(
        preferences: android.content.SharedPreferences,
        entries: CloneTakeOverPolicy.Entries,
    ) {
        preferences.edit()
            .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(entries.clone))
            .putString(KEY_TAKEN_OVER_IDS, CloneSensorKeyCodec.encode(entries.takenOver))
            .apply()
    }

    fun hasAnyCloneSensor(): Boolean = synchronized(lock) {
        CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null)).isNotEmpty()
    }

    /**
     * Local receiver gate. The default keeps existing configured receivers working
     * after an upgrade; once the user turns Clone off, late packets must not be
     * able to repopulate the registry until a local connection is enabled again.
     */
    @JvmStatic
    fun isReceptionEnabled(): Boolean = synchronized(lock) {
        prefs()?.getBoolean(KEY_RECEPTION_ENABLED, true) ?: true
    }

    @JvmStatic
    fun setReceptionEnabled(enabled: Boolean) {
        synchronized(lock) {
            prefs()?.edit()?.putBoolean(KEY_RECEPTION_ENABLED, enabled)?.apply()
            CloneRecoveryWake.setReceptionEnabled(enabled)
        }
        CloneBackgroundLiveness.sync()
    }

    /**
     * Commits receiver state only while the local gate remains open. Disable
     * takes the same monitor, so it either waits for this commit and clears it,
     * or closes the gate before the commit starts.
     */
    internal fun <T> whileReceptionEnabled(block: () -> T): T? = synchronized(lock) {
        val preferences = prefs() ?: return@synchronized null
        if (!preferences.getBoolean(KEY_RECEPTION_ENABLED, true)) return@synchronized null
        block()
    }

    @JvmStatic
    fun deactivateAllCloneSensors() {
        val sensorIds = synchronized(lock) {
            val preferences = prefs()
            val registered = CloneSensorKeyCodec.decode(
                preferences?.getString(KEY_SENSOR_IDS, null)
            ).keys.toList()
            preferences?.edit()
                ?.remove(KEY_SENSOR_IDS)
                ?.remove(KEY_SENSOR_CONNECTIONS)
                ?.apply()
            CloneIobSnapshot.clear()
            registered
        }
        sensorIds.forEach { sensorId ->
            runCatching {
                val sensorPointer = Natives.str2sensorptr(sensorId)
                if (sensorPointer != 0L) Natives.finishfromSensorptr(sensorPointer)
            }
            runCatching { SensorBluetooth.retireCloneSensor(sensorId) }
        }
        runCatching { SensorBluetooth.updateDevices() }
    }

    /**
     * A stream update for [sensorId] has just arrived over Clone.
     *
     * This used to treat that sensor as the sender's one true sensor and retire
     * every other Clone record -- flag removed, native record finished -- on the
     * theory that a receiver mirrors a single sensor. A sender with two sensors
     * updates them in turn, so each update retired the other one; the loser came
     * back on the next roster rebuild as a plain local record the receiver then
     * tried to dial. Nothing in a stream update says which sensor the sender
     * calls primary, so nothing here retires anything any more. What is kept: a
     * receiver that was already following Clone and has no Clone sensor selected
     * adopts this one, so the first sensor to arrive becomes the one shown.
     * Once the user has a Clone sensor selected, their choice stands.
     */
    @JvmStatic
    fun reconcilePrimaryCloneSensor(sensorId: String?) {
        val aliases = candidateKeys(sensorId)
        if (sensorId == null || aliases.isEmpty()) return
        val currentEntries = whileReceptionEnabled {
            CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null))
        } ?: return
        val isClone = currentEntries.keys.any { storedId -> candidateKeys(storedId).any { it in aliases } }
        if (!isClone) return

        runCatching { SensorBluetooth.blockLocalCloneConnection(sensorId) }

        val receiverPrimary = SensorIdentity.resolveMainSensor()
        val primaryIsClone = currentEntries.keys.any { storedId ->
            candidateKeys(storedId).any { it in candidateKeys(receiverPrimary) }
        }
        if (primaryIsClone) return
        runCatching { SensorBluetooth.setCurrentSensorSelection(sensorId) }
        runCatching { MultiSensorSelection.moveToFront(sensorId) }
    }

    @JvmStatic
    fun transportForSensor(sensorId: String?): CloneTransport? {
        val requested = candidateKeys(sensorId)
        if (requested.isEmpty()) return null
        return CloneSensorKeyCodec.transportForAny(
            prefs()?.getString(KEY_SENSOR_IDS, null),
            requested,
        )
    }

    /** Whether any configured Clone host currently has an ICE route up, local or relayed. */
    @JvmStatic
    fun hasLiveCloneConnection(): Boolean = connectedCloneTransports().isNotEmpty()

    private fun connectedCloneTransports(): List<CloneTransport> = runCatching {
        buildList {
            for (index in 0 until Natives.backuphostNr()) {
                if (Natives.getHostDeactivated(index)) continue
                val identity = Natives.getICElabel(index)?.takeIf { it.isNotBlank() }
                    ?: continue
                val transport = CloneTransport.fromCode(
                    Natives.getCloneConnectionTransport(identity),
                )
                if (transport == CloneTransport.LOCAL_ICE || transport == CloneTransport.TURN) {
                    add(transport)
                }
            }
        }
    }.getOrDefault(emptyList())

    /** Returns the route selected by the sensor's current ICE connection, not its last imported row. */
    @JvmStatic
    fun liveTransportForSensor(sensorId: String?): CloneTransport? {
        val requested = candidateKeys(sensorId)
        if (requested.isEmpty() || !isCloneSensor(sensorId)) return null
        val connectionIdentity = CloneSensorConnectionCodec.connectionForAny(
            prefs()?.getString(KEY_SENSOR_CONNECTIONS, null),
            requested,
        )
        val mappedTransport = connectionIdentity?.let { identity ->
            runCatching {
                CloneTransport.fromCode(Natives.getCloneConnectionTransport(identity))
            }.getOrDefault(CloneTransport.UNKNOWN)
        }
        if (mappedTransport == CloneTransport.LOCAL_ICE || mappedTransport == CloneTransport.TURN) {
            return mappedTransport
        }

        // Older Clone registrations and the first metadata packet after a
        // reconnect can briefly lack the sensor-to-host mapping. If exactly
        // one active ICE host is connected, its selected route is still an
        // unambiguous live answer for the sensor card and notification.
        return CloneLiveTransportPolicy.resolve(mappedTransport, connectedCloneTransports())
    }
}
