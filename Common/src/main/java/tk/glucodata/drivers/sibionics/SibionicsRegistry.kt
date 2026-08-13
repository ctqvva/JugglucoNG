package tk.glucodata.drivers.sibionics

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorUiSignals

object SibionicsRegistry {
    private val orphanMirrorReconcileLock = Any()
    @Volatile private var reconcilingOrphanMirrors = false

    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_SENSORS = "sibionics_managed_sensors"
    private const val PREF_LAST_INDEX_PREFIX = "sibionics_managed_last_index_"
    private const val PREF_START_TIME_PREFIX = "sibionics_managed_start_time_"
    private const val PREF_PROTOCOL_PREFIX = "sibionics_managed_protocol_"
    private const val PREF_VARIANT_PREFIX = "sibionics_managed_variant_"
    private const val PREF_AUTH_KEY_HINT_PREFIX = "sibionics_managed_auth_key_hint_"
    private const val PREF_SHORT_CODE_PREFIX = "sibionics_managed_short_code_"
    private const val PREF_LAST_GLUCOSE_MGDL_PREFIX = "sibionics_managed_last_glucose_mgdl_"
    private const val PREF_LAST_RAW_MGDL_PREFIX = "sibionics_managed_last_raw_mgdl_"
    private const val PREF_LAST_READING_TIME_PREFIX = "sibionics_managed_last_reading_time_"
    private const val PREF_ALGORITHM_STATE_PREFIX = "sibionics_managed_algorithm_state_"
    private const val PREF_AUTO_RESET_DAYS_PREFIX = "sibionics_managed_auto_reset_days_"
    private const val PREF_RESET_POSTPONED_UNTIL_PREFIX = "sibionics_managed_reset_postponed_until_"
    private const val PREF_RESET_REMINDER_AT_PREFIX = "sibionics_managed_reset_reminder_at_"
    private const val PREF_RESET_REQUESTED_PREFIX = "sibionics_managed_reset_requested_"
    private const val PREF_CUSTOM_ALGORITHM_PREFIX = "sibionics_managed_custom_algorithm_"
    private const val PREF_ALGORITHM_SELECTION_PREFIX = "sibionics_managed_algorithm_selection_"
    private const val PREF_ALGORITHM_SENSITIVITY_PREFIX = "sibionics_managed_algorithm_sensitivity_"
    private const val PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX = "sibionics_managed_rebuild_fingerprint_"
    private const val PREF_INTEGRATED_CALIBRATION_BASELINE_PREFIX = "sibionics_managed_calibration_baseline_"
    private const val SIBIONICS_GTIN_PREFIX = "0697283164"
    private val SENSOR_QR_PATTERN = Regex(
        "^\u001D?01(\\d{14})11\\d{6}17\\d{6}10[A-Z0-9]{8,20}\u001D?21([A-Z0-9]{10,20})\u001D?$",
    )

    data class IntegratedCalibrationBaseline(
        val unit: Int,
        val values: FloatArray,
        val timestamps: LongArray,
    )

    data class SensorRecord(
        val sensorId: String,
        val address: String,
        val displayName: String,
        val variant: SibionicsConstants.Variant,
        val shortCode: String,
        val bleName: String = "",
        val legacyNativeName: String = "",
    ) {
        fun matchesId(id: String?): Boolean =
            SibionicsConstants.matchesId(sensorId, id) ||
                SibionicsConstants.matchesId(displayName, id) ||
                SibionicsConstants.matchesId(shortCode, id) ||
                SibionicsConstants.matchesId(bleName, id)
    }

    data class SetupIdentity(
        val sensorId: String,
        val displayName: String,
        val shortCode: String,
        val bleName: String,
        val qrDerived: Boolean,
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun isSupportedQrPayload(source: String?): Boolean {
        return supportedQrMatch(source) != null
    }

    internal fun isSupportedQrPayload(
        source: String?,
        variant: SibionicsConstants.Variant,
    ): Boolean {
        val v120 = qrPayloadIsV120Identity(source) ?: return false
        return acceptsIdentityForm(variant, v120)
    }

    /**
     * Whether a supported QR payload carries the V120 (Sibionics 2) serial form, or null when the
     * payload is not a Sibionics sensor code at all. Lets setup tell "wrong sensor type selected"
     * apart from "this is not a Sibionics code".
     */
    internal fun qrPayloadIsV120Identity(source: String?): Boolean? {
        val ai21 = supportedQrMatch(source)?.groupValues?.get(2) ?: return null
        return isV120IdentityToken(ai21)
    }

    /**
     * Same family check for a hand-typed serial, which never carries the GS1 framing the QR gate
     * validates. Without it, typing a Sibionics 2 serial under a single-piece sensor type (or the
     * reverse) registers the sensor under the wrong variant, and nothing downstream disagrees.
     */
    @JvmStatic
    fun isManualSerialForVariant(serial: String?, variant: SibionicsConstants.Variant): Boolean {
        val normalized = SibionicsConstants.normalizeBleName(serial)
        if (normalized.length !in 10..20) return false
        return acceptsIdentityForm(variant, isV120IdentityToken(normalized))
    }

    private fun acceptsIdentityForm(
        variant: SibionicsConstants.Variant,
        isV120Identity: Boolean,
    ): Boolean =
        when (variant) {
            SibionicsConstants.Variant.SIBIONICS2 -> isV120Identity
            SibionicsConstants.Variant.GS3 -> false
            else -> !isV120Identity
        }

    private fun isV120IdentityToken(value: String): Boolean =
        value.startsWith("P") || value.startsWith("XPT")

    private fun supportedQrMatch(source: String?): MatchResult? {
        val match = SENSOR_QR_PATTERN.matchEntire(cleanQrPayload(source)) ?: return null
        val gtin = match.groupValues[1]
        if (!gtin.startsWith(SIBIONICS_GTIN_PREFIX)) return null
        val body = gtin.dropLast(1)
        val checksum = body.mapIndexed { index, char ->
            val digit = char.digitToInt()
            if ((body.length - index) % 2 == 1) digit * 3 else digit
        }.sum()
        if ((10 - checksum % 10) % 10 != gtin.last().digitToInt()) return null
        if (deriveNativeQrName(source) == null) return null
        return match
    }

    @JvmStatic
    fun buildIdentity(
        rawInput: String?,
        bleName: String?,
        variant: SibionicsConstants.Variant,
    ): SetupIdentity {
        val normalizedBle = SibionicsConstants.normalizeBleName(bleName)
        val normalizedRaw = SibionicsConstants.normalizeBleName(rawInput)
        // The native V120 identity window is defined against the framed QR payload. Keep GS
        // separators here: normalizing first shortens a 65-byte payload and selects the wrong
        // extraction branch, shifting both the leading and trailing identity characters.
        val useStructuredV120Serial = variant == SibionicsConstants.Variant.SIBIONICS2
        val framedQrName = deriveNativeQrName(rawInput, useStructuredV120Serial)
        val source = when {
            normalizedRaw.isNotBlank() -> normalizedRaw
            normalizedBle.isNotBlank() -> normalizedBle
            else -> variant.displayLabel
        }
        val qrName = framedQrName ?: deriveNativeQrName(source, useStructuredV120Serial)
        val qrShortView = qrName?.takeLast(11)?.takeIf { it.length == 11 }
        val resolvedBleName = deriveBleName(normalizedBle.takeIf { it.isNotBlank() } ?: source).orEmpty()
        val display = qrShortView ?: deriveDisplayName(source, variant)
        val fallback = variant.fallbackShortCode
        val shortCode = qrShortView
            ?.take(8)
            ?.takeIf { it.length == 8 }
            ?: SibionicsSensitivity.deriveShortCode(
                resolvedBleName.takeIf { it.isNotBlank() } ?: source.takeIf { it.isNotBlank() } ?: rawInput,
                fallback,
            )
        val idBase = if (display.isNotBlank()) display else shortCode
        return SetupIdentity(
            sensorId = SibionicsConstants.canonicalSensorId(idBase),
            displayName = display.ifBlank { "${variant.displayLabel} $shortCode" },
            shortCode = shortCode,
            bleName = resolvedBleName,
            qrDerived = framedQrName != null,
        )
    }

    /**
     * @param variantIsUserChoice true only when the caller is the setup flow acting on a type the
     *   user just picked. Every other caller inherits the recorded type: nothing the driver
     *   observes on air may re-type a sensor. See [SibionicsVariantLock].
     */
    @JvmStatic
    @JvmOverloads
    fun ensureSensorRecord(
        context: Context,
        rawInput: String?,
        address: String?,
        displayName: String?,
        variant: SibionicsConstants.Variant,
        shortCodeOverride: String? = null,
        bleNameOverride: String? = null,
        variantIsUserChoice: Boolean = false,
    ): SensorRecord {
        val records = persistedRecords(context).toMutableList()
        // The requested variant shapes the identity (only Sibionics 2 uses the structured V120
        // serial), so resolve the locked variant first and rebuild the identity under it.
        val probe = locateRecord(records, buildIdentity(rawInput, bleNameOverride ?: displayName, variant))
        val lockedVariant = SibionicsVariantLock.variantForWrite(
            existingVariant = records.getOrNull(probe)?.variant,
            requestedVariant = variant,
            isUserChoice = variantIsUserChoice,
        )
        if (lockedVariant != variant) {
            Log.w(
                SibionicsConstants.TAG,
                "refusing to re-type ${records.getOrNull(probe)?.sensorId} as ${variant.id}; " +
                    "it stays ${lockedVariant.id} until setup says otherwise",
            )
        }
        val identity = buildIdentity(rawInput, bleNameOverride ?: displayName, lockedVariant)
        val sensorId = identity.sensorId
        val shortCode = shortCodeOverride
            ?.let { SibionicsConstants.normalizeBleName(it) }
            ?.takeIf { it.length == 8 }
            ?: identity.shortCode
        val idx = if (lockedVariant == variant) probe else locateRecord(records, identity)
        val existing = records.getOrNull(idx)
        val normalizedAddress = SibionicsConstants.normalizeBleAddress(address)
            ?: SibionicsConstants.normalizeBleAddress(existing?.address)
            ?: ""
        val visible = usableDisplayName(displayName)
            ?: identity.displayName.takeIf { identity.qrDerived }
            ?: usableDisplayName(existing?.displayName)
            ?: identity.displayName
        val bleName = deriveBleName(bleNameOverride)
            .orEmpty()
            .ifBlank { identity.bleName }
            .ifBlank { deriveBleName(displayName) ?: "" }
            .ifBlank { existing?.bleName.orEmpty() }
        val record = SensorRecord(
            sensorId = sensorId,
            address = normalizedAddress,
            displayName = visible,
            variant = lockedVariant,
            shortCode = shortCode,
            bleName = bleName,
            legacyNativeName = existing?.legacyNativeName.orEmpty(),
        )
        if (idx >= 0) records[idx] = record else records.add(record)
        writeRecords(context, records)
        saveVariant(context, sensorId, lockedVariant)
        saveShortCode(context, sensorId, shortCode)
        ManagedSensorUiSignals.markDeviceListDirty()
        SensorIdentity.invalidateCaches()
        return record
    }

    private fun locateRecord(records: List<SensorRecord>, identity: SetupIdentity): Int =
        records.indexOfFirst {
            it.matchesId(identity.sensorId) ||
                (identity.bleName.isNotBlank() && it.matchesId(identity.bleName))
        }

    /** Setup adding a sensor: the one path allowed to state, or restate, what a sensor is. */
    @JvmStatic
    fun addSensorAndStart(
        context: Context,
        rawInput: String?,
        address: String?,
        displayName: String?,
        variant: SibionicsConstants.Variant,
        bleName: String? = null,
    ): SensorRecord {
        val record = ensureSensorRecord(
            context = context,
            rawInput = rawInput,
            address = address,
            displayName = displayName,
            variant = variant,
            bleNameOverride = bleName,
            variantIsUserChoice = true,
        )
        runCatching {
            if (tk.glucodata.Natives.getusebluetooth()) {
                SensorBluetooth.updateDevices()
            }
        }.onFailure {
            Log.stack(SibionicsConstants.TAG, "addSensorAndStart(updateDevices)", it)
        }
        UiRefreshBus.requestStatusRefresh()
        return record
    }

    @JvmStatic
    fun persistedRecords(context: Context): List<SensorRecord> {
        val raw = prefs(context).getStringSet(PREF_SENSORS, emptySet()) ?: return emptyList()
        return raw.mapNotNull { parseRecord(it) }
    }

    @JvmStatic
    fun findRecord(context: Context?, sensorId: String?): SensorRecord? {
        val ctx = context ?: return null
        val id = sensorId?.takeIf { it.isNotBlank() } ?: return null
        val records = persistedRecords(ctx)
        records.firstOrNull { it.matchesId(id) }?.let { return it }
        val stripped = SibionicsConstants.stripManagedPrefix(id)
        return records
            .filter {
                val display = SibionicsConstants.normalizeBleName(it.displayName)
                stripped.isNotBlank() && display.endsWith(stripped.takeLast(6), ignoreCase = true)
            }
            .singleOrNull()
    }

    internal fun canClaimUnboundSibionics2Device(
        record: SensorRecord,
        records: List<SensorRecord>,
        deviceName: String?,
        address: String?,
    ): Boolean {
        if (record.variant != SibionicsConstants.Variant.SIBIONICS2 || record.address.isNotBlank()) return false
        if (!SibionicsConstants.isSibionics2TransmitterName(deviceName)) return false
        val normalizedAddress = SibionicsConstants.normalizeBleAddress(address) ?: return false
        if (records.any {
                !it.matchesId(record.sensorId) &&
                    SibionicsConstants.normalizeBleAddress(it.address)?.equals(normalizedAddress, ignoreCase = true) == true
            }
        ) return false
        val unbound = records.filter {
            it.variant == SibionicsConstants.Variant.SIBIONICS2 && it.address.isBlank()
        }
        return unbound.size == 1 && unbound.single().matchesId(record.sensorId)
    }

    @JvmStatic
    fun resolveCanonicalSensorId(context: Context?, sensorId: String?): String? =
        findRecord(context, sensorId)?.sensorId

    @JvmStatic
    fun removeSensor(
        context: Context,
        sensorId: String?,
        preserveResumeState: Boolean = false,
    ) {
        val record = findRecord(context, sensorId) ?: return
        val wasMainSensor = record.matchesId(SensorIdentity.resolveMainSensor())
        val replacementSensor = if (wasMainSensor) {
            runCatching { SensorBluetooth.resolveReplacementSensorSerial(record.sensorId) }
                .onFailure { Log.stack(SibionicsConstants.TAG, "removeSensor(resolveReplacement)", it) }
                .getOrNull()
        } else {
            null
        }
        finishNativeMirror(record)
        val id = record.sensorId
        val remainingRecords = persistedRecords(context).filter { !it.matchesId(record.sensorId) }
        val remainingSet = remainingRecords.map { encodeRecord(it) }.toSet()
        val committed = prefs(context).edit().apply {
            // Ordinary UI disconnect removes ownership but deliberately retains
            // the exact algorithm cursor/checkpoint and raw source journal. If the
            // same physical sensor is added again, it can continue at the first
            // missing index instead of downloading and replacing its whole history.
            // An explicit wipe still removes every piece of resumable state.
            putStringSet(PREF_SENSORS, remainingSet)
            if (!preserveResumeState) {
                remove(PREF_LAST_INDEX_PREFIX + id)
                remove(PREF_START_TIME_PREFIX + id)
                remove(PREF_PROTOCOL_PREFIX + id)
                remove(PREF_VARIANT_PREFIX + id)
                remove(PREF_SHORT_CODE_PREFIX + id)
                remove(PREF_LAST_GLUCOSE_MGDL_PREFIX + id)
                remove(PREF_LAST_RAW_MGDL_PREFIX + id)
                remove(PREF_LAST_READING_TIME_PREFIX + id)
                remove(PREF_ALGORITHM_STATE_PREFIX + id)
                remove(PREF_AUTO_RESET_DAYS_PREFIX + id)
                remove(PREF_CUSTOM_ALGORITHM_PREFIX + id)
                remove(PREF_ALGORITHM_SELECTION_PREFIX + id)
                remove(PREF_ALGORITHM_SENSITIVITY_PREFIX + id)
                remove(PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX + id)
                remove(PREF_INTEGRATED_CALIBRATION_BASELINE_PREFIX + id)
            }
            // A detached sensor must never execute a stale scheduled reset when
            // it is later re-added, even though its glucose continuation is kept.
            remove(PREF_RESET_POSTPONED_UNTIL_PREFIX + id)
            remove(PREF_RESET_REMINDER_AT_PREFIX + id)
            remove(PREF_RESET_REQUESTED_PREFIX + id)
        }.commit()
        if (!committed) {
            Log.e(SibionicsConstants.TAG, "Failed to persist removal of $id")
        } else if (wasMainSensor) {
            runCatching { SensorBluetooth.setCurrentSensorSelection(replacementSensor.orEmpty()) }
                .onFailure { Log.stack(SibionicsConstants.TAG, "removeSensor(rehomeCurrent)", it) }
        }
        SibionicsResetReminder.cancel(context, id)
        ManagedSensorUiSignals.markDeviceListDirty()
        SensorIdentity.invalidateCaches()
    }

    /**
     * Managed Sibionics readings are mirrored into a native sensor shell for
     * charts, followers and Wear sync. That shell is deliberately not the BLE
     * owner, but it still appears in Natives.activeSensors(). If it is left
     * active, SensorBluetooth immediately recreates a legacy callback after the
     * managed record is removed.
     */
    private fun finishNativeMirror(record: SensorRecord) {
        val aliases = linkedSetOf<String>()
        fun addAlias(value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let(aliases::add)
        }
        addAlias(record.sensorId)
        addAlias(SibionicsConstants.stripManagedPrefix(record.sensorId))
        addAlias(record.legacyNativeName)
        addAlias(record.displayName)
        addAlias(record.bleName)

        val activeMatches = runCatching { Natives.activeSensors().orEmpty() }
            .getOrDefault(emptyArray())
            .filter { active -> aliases.any { it.equals(active, ignoreCase = true) } }
        for (candidate in activeMatches.distinctBy { it.lowercase() }) {
            if (finishNativeMirror(candidate, record.sensorId)) return
        }
    }

    internal fun finishNativeMirror(candidate: String, sensorId: String): Boolean {
        val streamPtr = runCatching { Natives.getdataptr(candidate) }
            .onFailure { Log.stack(SibionicsConstants.TAG, "finishNativeMirror(getdataptr $candidate)", it) }
            .getOrDefault(0L)
        if (streamPtr == 0L) return false
        return try {
            // finishfromSensorptr() derives the list index from the mirror's
            // namespaced directory and cannot resolve direct-stream SIBI shells.
            // A temporary stream owns the exact sensors.dat index, so finishSensor()
            // retires the shell synchronously without changing shared/native code.
            Natives.finishSensor(streamPtr)
            Log.i(SibionicsConstants.TAG, "Finished native mirror for $sensorId as $candidate")
            true
        } catch (error: Throwable) {
            Log.stack(SibionicsConstants.TAG, "finishNativeMirror(finishSensor $candidate)", error)
            false
        } finally {
            runCatching { Natives.freedataptr(streamPtr) }
                .onFailure { Log.stack(SibionicsConstants.TAG, "finishNativeMirror(freedataptr $candidate)", it) }
        }
    }

    internal fun matchesNativeMirrorIdentity(left: String?, right: String?): Boolean {
        val a = left?.trim().orEmpty()
        val b = right?.trim().orEmpty()
        if (a.isBlank() || b.isBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        return SibionicsConstants.stripManagedPrefix(a)
            .equals(SibionicsConstants.stripManagedPrefix(b), ignoreCase = true)
    }

    internal fun reconcileOrphanedNativeMirrors(context: Context) {
        synchronized(orphanMirrorReconcileLock) {
            if (reconcilingOrphanMirrors) return
            reconcilingOrphanMirrors = true
        }
        try {
            val records = persistedRecords(context)
            val activeSensors = runCatching { Natives.activeSensors().orEmpty() }
                .onFailure { Log.stack(SibionicsConstants.TAG, "reconcileOrphanMirrors(activeSensors)", it) }
                .getOrDefault(emptyArray())
            activeSensors.forEach { nativeId ->
                val fullNativeName = runCatching { Natives.resolveFullSensorName(nativeId) }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                if (!fullNativeName.startsWith(SibionicsConstants.MANAGED_PREFIX, ignoreCase = true) ||
                    records.any { it.matchesId(nativeId) || it.matchesId(fullNativeName) }
                ) {
                    return@forEach
                }

                // Older builds could delete the Kotlin record while leaving this
                // direct-stream shell active. Retire it before shared BLE restore
                // can mistake the short native alias for a legacy sensor.
                val mainSensor = SensorIdentity.resolveMainSensor()
                val wasMainSensor = matchesNativeMirrorIdentity(mainSensor, nativeId) ||
                    matchesNativeMirrorIdentity(mainSensor, fullNativeName)
                val replacementSensor = if (wasMainSensor) {
                    runCatching { SensorBluetooth.resolveReplacementSensorSerial(nativeId) }
                        .onFailure { Log.stack(SibionicsConstants.TAG, "orphanMirror(resolveReplacement)", it) }
                        .getOrNull()
                } else {
                    null
                }
                val retired = finishNativeMirror(nativeId, fullNativeName)
                if (retired && wasMainSensor) {
                    runCatching { SensorBluetooth.setCurrentSensorSelection(replacementSensor.orEmpty()) }
                        .onFailure { Log.stack(SibionicsConstants.TAG, "orphanMirror(rehomeCurrent)", it) }
                }
            }
        } finally {
            synchronized(orphanMirrorReconcileLock) {
                reconcilingOrphanMirrors = false
            }
        }
    }

    internal fun bindLegacyNativeName(context: Context, sensorId: String, legacyNativeName: String) {
        val normalized = legacyNativeName.trim().takeIf(SensorIdentity::isUsableSensorId) ?: return
        val records = persistedRecords(context).toMutableList()
        val index = records.indexOfFirst { it.matchesId(sensorId) }
        val existing = records.getOrNull(index) ?: return
        if (existing.legacyNativeName.equals(normalized, ignoreCase = true)) return
        records[index] = existing.copy(legacyNativeName = normalized)
        writeRecords(context, records)
        SensorIdentity.invalidateCaches()
    }

    fun loadLastIndex(context: Context, sensorId: String): Int =
        prefs(context).getInt(PREF_LAST_INDEX_PREFIX + sensorId, 0)

    fun saveLastIndex(context: Context, sensorId: String, index: Int) {
        prefs(context).edit().putInt(PREF_LAST_INDEX_PREFIX + sensorId, index.coerceAtLeast(0)).apply()
    }

    fun loadAlgorithmState(context: Context, sensorId: String): ByteArray? {
        val encoded = prefs(context).getString(PREF_ALGORITHM_STATE_PREFIX + sensorId, null) ?: return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    fun saveAlgorithmCheckpoint(context: Context, sensorId: String, nextIndex: Int, state: ByteArray) {
        if (state.isEmpty()) return
        prefs(context).edit().apply {
            putInt(PREF_LAST_INDEX_PREFIX + sensorId, nextIndex.coerceAtLeast(0))
            putString(PREF_ALGORITHM_STATE_PREFIX + sensorId, Base64.getEncoder().encodeToString(state))
        }.apply()
    }

    fun clearAlgorithmState(context: Context, sensorId: String) {
        prefs(context).edit().remove(PREF_ALGORITHM_STATE_PREFIX + sensorId).apply()
    }

    fun loadAutoResetDays(context: Context, sensorId: String): Int =
        prefs(context).getInt(PREF_AUTO_RESET_DAYS_PREFIX + sensorId, 300)

    fun hasAutoResetSetting(context: Context, sensorId: String): Boolean =
        prefs(context).contains(PREF_AUTO_RESET_DAYS_PREFIX + sensorId)

    fun saveAutoResetDays(context: Context, sensorId: String, days: Int) {
        prefs(context).edit()
            .putInt(PREF_AUTO_RESET_DAYS_PREFIX + sensorId, days.coerceIn(1, 300))
            .apply()
    }

    fun loadResetPostponedUntilMs(context: Context, sensorId: String): Long =
        prefs(context).getLong(PREF_RESET_POSTPONED_UNTIL_PREFIX + sensorId, 0L)

    fun postponeReset(context: Context, sensorId: String, untilMs: Long) {
        prefs(context).edit()
            .putLong(PREF_RESET_POSTPONED_UNTIL_PREFIX + sensorId, untilMs.coerceAtLeast(0L))
            .apply()
    }

    fun loadResetReminderAtMs(context: Context, sensorId: String): Long =
        prefs(context).getLong(PREF_RESET_REMINDER_AT_PREFIX + sensorId, 0L)

    fun markResetReminderShown(context: Context, sensorId: String, nowMs: Long) {
        prefs(context).edit()
            .putLong(PREF_RESET_REMINDER_AT_PREFIX + sensorId, nowMs.coerceAtLeast(0L))
            .apply()
    }

    fun requestReset(context: Context, sensorId: String) {
        prefs(context).edit().putBoolean(PREF_RESET_REQUESTED_PREFIX + sensorId, true).apply()
    }

    fun isResetRequested(context: Context, sensorId: String): Boolean =
        prefs(context).getBoolean(PREF_RESET_REQUESTED_PREFIX + sensorId, false)

    fun clearResetMaintenanceState(context: Context, sensorId: String) {
        prefs(context).edit().apply {
            remove(PREF_RESET_POSTPONED_UNTIL_PREFIX + sensorId)
            remove(PREF_RESET_REMINDER_AT_PREFIX + sensorId)
            remove(PREF_RESET_REQUESTED_PREFIX + sensorId)
        }.apply()
    }

    fun loadCustomAlgorithmEnabled(context: Context, sensorId: String): Boolean =
        prefs(context).getBoolean(PREF_CUSTOM_ALGORITHM_PREFIX + sensorId, false)

    fun saveCustomAlgorithmEnabled(context: Context, sensorId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_CUSTOM_ALGORITHM_PREFIX + sensorId, enabled).apply()
    }

    fun loadAlgorithmSelection(context: Context, sensorId: String): SibionicsAlgorithmSelection {
        val preferences = prefs(context)
        val selectionKey = PREF_ALGORITHM_SELECTION_PREFIX + sensorId
        if (preferences.contains(selectionKey)) {
            return SibionicsAlgorithmSelection.fromStorage(
                preferences.getInt(selectionKey, SibionicsAlgorithmSelection.DEFAULT.storageId),
            )
        }
        val legacyKey = PREF_CUSTOM_ALGORITHM_PREFIX + sensorId
        return if (preferences.contains(legacyKey)) {
            if (preferences.getBoolean(legacyKey, false)) {
                SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED
            } else {
                SibionicsAlgorithmSelection.STOCK_CALIBRATED
            }
        } else {
            SibionicsAlgorithmSelection.DEFAULT
        }
    }

    fun saveAlgorithmSelection(
        context: Context,
        sensorId: String,
        selection: SibionicsAlgorithmSelection,
    ) {
        prefs(context).edit()
            .putInt(PREF_ALGORITHM_SELECTION_PREFIX + sensorId, selection.storageId)
            .apply()
    }

    fun loadAlgorithmSensitivityOverride(context: Context, sensorId: String): Float? =
        prefs(context)
            .getFloat(PREF_ALGORITHM_SENSITIVITY_PREFIX + sensorId, Float.NaN)
            .takeIf(SibionicsSensitivity::isSupported)

    fun saveAlgorithmSensitivityOverride(
        context: Context,
        sensorId: String,
        sensitivity: Float?,
    ) {
        prefs(context).edit().apply {
            if (sensitivity == null) {
                remove(PREF_ALGORITHM_SENSITIVITY_PREFIX + sensorId)
            } else if (SibionicsSensitivity.isSupported(sensitivity)) {
                putFloat(PREF_ALGORITHM_SENSITIVITY_PREFIX + sensorId, sensitivity)
            }
        }.apply()
    }

    fun loadLocalRebuildFingerprint(context: Context, sensorId: String): String =
        prefs(context).getString(PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX + sensorId, "").orEmpty()

    fun saveLocalRebuildFingerprint(context: Context, sensorId: String, fingerprint: String) {
        prefs(context).edit()
            .putString(PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX + sensorId, fingerprint)
            .apply()
    }

    fun loadIntegratedCalibrationBaseline(
        context: Context,
        sensorId: String,
    ): IntegratedCalibrationBaseline? {
        val encoded = prefs(context).getString(
            PREF_INTEGRATED_CALIBRATION_BASELINE_PREFIX + sensorId,
            null,
        ) ?: return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                val unit = input.readInt()
                val count = input.readInt()
                require(count in 1..128)
                val values = FloatArray(count)
                val timestamps = LongArray(count)
                repeat(count) { index ->
                    timestamps[index] = input.readLong()
                    values[index] = input.readFloat()
                    require(timestamps[index] > 0L && values[index].isFinite() && values[index] > 0f)
                }
                IntegratedCalibrationBaseline(unit, values, timestamps)
            }
        }.getOrNull()
    }

    fun saveIntegratedCalibrationBaseline(
        context: Context,
        sensorId: String,
        unit: Int,
        values: FloatArray,
        timestamps: LongArray,
    ) {
        if (values.size != timestamps.size || values.isEmpty() || values.size > 128) return
        val encoded = runCatching {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(unit)
                    output.writeInt(values.size)
                    values.indices.forEach { index ->
                        val value = values[index]
                        val timestamp = timestamps[index]
                        require(value.isFinite() && value > 0f && timestamp > 0L)
                        output.writeLong(timestamp)
                        output.writeFloat(value)
                    }
                }
                Base64.getEncoder().encodeToString(bytes.toByteArray())
            }
        }.getOrNull() ?: return
        prefs(context).edit()
            .putString(PREF_INTEGRATED_CALIBRATION_BASELINE_PREFIX + sensorId, encoded)
            .apply()
    }

    fun loadStartTimeMs(context: Context, sensorId: String): Long =
        prefs(context).getLong(PREF_START_TIME_PREFIX + sensorId, 0L)

    fun saveStartTimeMs(context: Context, sensorId: String, startTimeMs: Long) {
        if (startTimeMs <= 0L) return
        prefs(context).edit().putLong(PREF_START_TIME_PREFIX + sensorId, startTimeMs).apply()
    }

    fun clearStartTimeMs(context: Context, sensorId: String) {
        prefs(context).edit().remove(PREF_START_TIME_PREFIX + sensorId).apply()
    }

    fun loadProtocolMode(context: Context, sensorId: String): SibionicsConstants.ProtocolMode =
        runCatching {
            SibionicsConstants.ProtocolMode.valueOf(
                prefs(context).getString(PREF_PROTOCOL_PREFIX + sensorId, null).orEmpty(),
            )
        }.getOrDefault(SibionicsConstants.ProtocolMode.UNKNOWN)

    fun saveProtocolMode(context: Context, sensorId: String, mode: SibionicsConstants.ProtocolMode) {
        prefs(context).edit().putString(PREF_PROTOCOL_PREFIX + sensorId, mode.name).apply()
    }

    /**
     * The type this sensor was set up as. The record written by setup wins over the per-sensor
     * copy, which older builds rewrote from an authentication guess; a disagreement is that damage
     * and is healed here, so a sensor that came back as the wrong type after a reconnect returns to
     * what the user chose.
     */
    fun loadVariant(context: Context, sensorId: String): SibionicsConstants.Variant {
        val record = findRecord(context, sensorId)
        val cached = variantById(prefs(context).getString(PREF_VARIANT_PREFIX + sensorId, null))
        val locked = SibionicsVariantLock.lockedVariant(record?.variant, cached)
        if (record != null && cached != null && cached != locked) {
            Log.w(
                SibionicsConstants.TAG,
                "healing cached variant ${cached.id} back to the recorded ${locked.id} for $sensorId",
            )
            saveVariant(context, sensorId, locked)
            if (!record.sensorId.equals(sensorId, ignoreCase = true)) {
                saveVariant(context, record.sensorId, locked)
            }
        }
        return locked
    }

    fun saveVariant(context: Context, sensorId: String, variant: SibionicsConstants.Variant) {
        prefs(context).edit().putString(PREF_VARIANT_PREFIX + sensorId, variant.id).apply()
    }

    private fun variantById(raw: String?): SibionicsConstants.Variant? {
        val id = raw?.takeIf { it.isNotBlank() } ?: return null
        return SibionicsConstants.Variant.entries.firstOrNull { it.id == id }
    }

    /**
     * Key group that last authenticated this sensor. Only an ordering hint for the next
     * connection — never identity. See [SibionicsVariantLock].
     */
    fun loadAuthKeyHint(context: Context, sensorId: String): SibionicsConstants.Variant? {
        val raw = prefs(context).getString(PREF_AUTH_KEY_HINT_PREFIX + sensorId, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return SibionicsConstants.Variant.entries.firstOrNull { it.id == raw }
    }

    fun saveAuthKeyHint(context: Context, sensorId: String, variant: SibionicsConstants.Variant) {
        prefs(context).edit().putString(PREF_AUTH_KEY_HINT_PREFIX + sensorId, variant.id).apply()
    }

    fun loadShortCode(context: Context, sensorId: String): String =
        prefs(context).getString(PREF_SHORT_CODE_PREFIX + sensorId, null)
            ?.takeIf { it.isNotBlank() }
            ?: findRecord(context, sensorId)?.shortCode
            ?: SibionicsConstants.Variant.EU.fallbackShortCode

    fun saveShortCode(context: Context, sensorId: String, shortCode: String) {
        if (shortCode.isBlank()) return
        prefs(context).edit().putString(PREF_SHORT_CODE_PREFIX + sensorId, shortCode).apply()
    }

    fun saveLastReading(
        context: Context,
        sensorId: String,
        timeMs: Long,
        glucoseMgdl: Float,
        rawMgdl: Float,
    ) {
        prefs(context).edit().apply {
            putLong(PREF_LAST_READING_TIME_PREFIX + sensorId, timeMs)
            putFloat(PREF_LAST_GLUCOSE_MGDL_PREFIX + sensorId, glucoseMgdl)
            putFloat(PREF_LAST_RAW_MGDL_PREFIX + sensorId, rawMgdl)
        }.apply()
    }

    fun loadLastReading(context: Context, sensorId: String): Triple<Long, Float, Float> =
        Triple(
            prefs(context).getLong(PREF_LAST_READING_TIME_PREFIX + sensorId, 0L),
            prefs(context).getFloat(PREF_LAST_GLUCOSE_MGDL_PREFIX + sensorId, Float.NaN),
            prefs(context).getFloat(PREF_LAST_RAW_MGDL_PREFIX + sensorId, Float.NaN),
        )

    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val record = findRecord(context, sensorId) ?: return null
        return SibionicsBleManager(record.sensorId, dataptr).also { it.restoreFromPersistence(context) }
    }

    private fun writeRecords(context: Context, records: List<SensorRecord>) {
        val set = records.map { encodeRecord(it) }.toSet()
        prefs(context).edit().putStringSet(PREF_SENSORS, set).apply()
    }

    private fun encodeRecord(r: SensorRecord): String =
        listOf(
            r.sensorId,
            r.address,
            r.displayName.replace('|', ' '),
            r.variant.id,
            r.shortCode,
            r.bleName,
            r.legacyNativeName.replace('|', ' '),
        ).joinToString("|")

    private fun parseRecord(line: String): SensorRecord? {
        val parts = line.split('|')
        if (parts.size < 5) return null
        val id = SibionicsConstants.canonicalSensorId(parts[0]).takeIf { it.isNotBlank() } ?: return null
        return SensorRecord(
            sensorId = id,
            address = SibionicsConstants.normalizeBleAddress(parts[1]).orEmpty(),
            displayName = parts[2].ifBlank { SibionicsConstants.stripManagedPrefix(id) },
            variant = SibionicsConstants.Variant.fromId(parts[3]),
            shortCode = parts[4].ifBlank { SibionicsConstants.Variant.fromId(parts[3]).fallbackShortCode },
            bleName = parts.getOrNull(5).orEmpty(),
            legacyNativeName = parts.getOrNull(6).orEmpty(),
        )
    }

    private fun deriveDisplayName(source: String, variant: SibionicsConstants.Variant): String {
        val normalized = SibionicsConstants.normalizeBleName(source)
        if (normalized.isBlank()) return variant.displayLabel
        if (normalized.length <= 18) return normalized
        val magic = "0697283164"
        val magicPos = normalized.indexOf(magic)
        if (magicPos >= 0 && normalized.length >= 17) {
            return normalized.takeLast(17).dropLast(1).takeLast(11).ifBlank { normalized.takeLast(11) }
        }
        return normalized.takeLast(16)
    }

    private fun usableDisplayName(raw: String?): String? {
        val normalized = SibionicsConstants.normalizeBleName(raw)
        if (normalized.isBlank()) return null
        if (deriveNativeQrName(raw) != null) return null
        return normalized.takeIf { it.length <= 18 }
    }

    internal fun deriveBleName(source: String?): String? {
        val compact = SibionicsConstants.normalizeBleName(source)
        if (compact.length in 8..16) return compact
        val ai10 = findBatchAi10(compact)
        if (ai10 < 0) return null
        val start = ai10 + 2
        val nextAi21 = compact.indexOf("21", startIndex = start + 8)
        val end = when {
            nextAi21 > start -> nextAi21
            compact.length - start in 8..16 -> compact.length
            else -> -1
        }
        if (end <= start) return null
        val candidate = compact.substring(start, end)
        return candidate.takeIf { it.length in 8..16 }
    }

    private fun findBatchAi10(compact: String): Int {
        val magic = "0697283164"
        val searchStart = compact.indexOf(magic)
            .takeIf { it >= 0 }
            ?.let { it + magic.length }
            ?: 0
        var pos = compact.indexOf("10", startIndex = searchStart)
        while (pos >= 0) {
            val valueStart = pos + 2
            val nextAi21 = compact.indexOf("21", startIndex = valueStart + 8)
            if (nextAi21 > valueStart && nextAi21 - valueStart in 8..16) return pos
            val tailLength = compact.length - valueStart
            if (tailLength in 8..16) return pos
            pos = compact.indexOf("10", startIndex = pos + 1)
        }
        return -1
    }

    private fun deriveNativeQrName(
        source: String?,
        useStructuredV120Serial: Boolean = false,
    ): String? {
        val payload = cleanQrPayload(source)
        val magic = "0697283164"
        if (!payload.contains(magic) || payload.length < 50) return null

        // V120 AI (21) values beginning with P contain the calibration-bearing
        // serial consumed by the validated local decoder. Some labels use an XPT
        // probe identifier as the entire AI (21) value instead; it does not expose
        // that P-format token, so retain the native identity window below rather
        // than deriving calibration data from unrelated characters.
        if (useStructuredV120Serial) {
            deriveGs1V120CalibrationSerial(payload)?.let { serial ->
                // The legacy/native visible identity excludes the serial check
                // character: its 16-byte end window consists of the final AI digit
                // followed by the first 15 serial characters.
                return "1" + serial.dropLast(1)
            }
        }

        return if (payload.length < 65) {
            val endLen = payload.length - 49
            val startLen = 16 - endLen
            if (endLen <= 0 || startLen < 0 || payload.length < 49 + endLen || payload.length < 22 + startLen) {
                null
            } else {
                (payload.substring(22, 22 + startLen) + payload.substring(49, 49 + endLen))
                    .takeIf { it.length == 16 }
            }
        } else {
            val start = payload.length - 17
            payload.substring(start, start + 16).takeIf { it.length == 16 }
        }?.let { SibionicsConstants.normalizeBleName(it) }
    }

    private fun deriveGs1V120CalibrationSerial(payload: String): String? {
        val magicPos = payload.indexOf(SIBIONICS_GTIN_PREFIX)
        if (magicPos < 0) return null

        val explicitAi21 = payload.indexOf("\u001D21", startIndex = magicPos + 10)
        val valueStart = if (explicitAi21 >= 0) {
            explicitAi21 + 3
        } else {
            // Some scanners omit FNC1/group separators. In that representation,
            // locate AI (21) only after the validated variable-length AI (10)
            // batch field rather than matching an arbitrary "21" in the dates.
            val ai10 = findBatchAi10(payload)
            if (ai10 < 0) return null
            val batchStart = ai10 + 2
            val ai21 = payload.indexOf("21", startIndex = batchStart + 8)
            if (ai21 < 0 || ai21 - batchStart !in 8..16) return null
            ai21 + 2
        }

        // Only the established P-format V120 serial has a validated sensitivity
        // token. XPT is a different AI (21) identifier form, not a P serial with
        // a shifted prefix, so never manufacture calibration data from it.
        if (payload.length - valueStart < 16) return null
        return payload.substring(valueStart, valueStart + 16)
            .takeIf { serial ->
                serial.startsWith('P') && serial.all(Char::isLetterOrDigit)
            }
    }

    private fun cleanQrPayload(source: String?): String =
        source.orEmpty()
            .trim()
            .uppercase(java.util.Locale.US)
            .removePrefix("]D2")
            .replace("^]", "\u001D")
            .filter { it.isLetterOrDigit() || it == '\u001D' }

}

object SibionicsManagedSensorIdentityAdapter : tk.glucodata.drivers.ManagedSensorIdentityAdapter {
    private fun hasExplicitPrefix(sensorId: String?): Boolean =
        sensorId?.trim()?.startsWith(SibionicsConstants.MANAGED_PREFIX, ignoreCase = true) == true

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val callbackRecord = SibionicsRegistry.findRecord(Applic.app, callbackId)
        val sensorRecord = SibionicsRegistry.findRecord(Applic.app, sensorId)
        if (callbackRecord != null || sensorRecord != null) {
            val record = callbackRecord ?: sensorRecord ?: return false
            return record.matchesId(callbackId) && record.matchesId(sensorId)
        }
        // Never let the Sibionics suffix rules claim two unrelated vendor ids. Without a
        // persisted record, only ids that are already explicitly namespaced are ours.
        return hasExplicitPrefix(callbackId) && hasExplicitPrefix(sensorId) &&
            SibionicsConstants.matchesId(callbackId, sensorId)
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        SibionicsRegistry.findRecord(Applic.app, raw)?.sensorId?.let { return it }
        return if (hasExplicitPrefix(raw)) {
            SibionicsConstants.canonicalSensorId(raw).takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? =
        resolveCanonicalSensorId(sensorId)

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean =
        SibionicsRegistry.findRecord(Applic.app, sensorId) != null

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        SibionicsRegistry.findRecord(Applic.app, sensorId)?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> {
        SibionicsRegistry.reconcileOrphanedNativeMirrors(context)
        return SibionicsRegistry.persistedRecords(context).map { it.sensorId }
    }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? =
        SibionicsRegistry.createRestoredCallback(context, sensorId, dataptr)

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        SibionicsRegistry.removeSensor(context, sensorId)
    }

    override fun resolveNativeHistorySensorNames(sensorId: String?): List<String> =
        SibionicsRegistry.findRecord(Applic.app, sensorId)
            ?.legacyNativeName
            ?.takeIf { it.isNotBlank() }
            ?.let(::listOf)
            .orEmpty()

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        SibionicsRegistry.findRecord(Applic.app, sensorId) != null

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)
            ?.let { SibionicsRegistry.findRecord(Applic.app, it) } != null

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        SibionicsRegistry.findRecord(Applic.app, sensorId) ?: return null
        return false
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        SibionicsRegistry.findRecord(Applic.app, sensorId) ?: return null
        return false
    }
}
