// OttaiRegistry.kt — SharedPreferences persistence for Ottai sensors.
//
// Account-level: accessToken, glucoseSecretKey, userId.
// Per-sensor (keyed by canonical cloud id): decrypted keyA (192 hex) / method /
// coefficient, activeTime, deviceVersion, lastDataNo, deviceId.
//
// SECURITY: accessToken, glucoseSecretKey, keyA-plaintext, method and coefficient
// are credentials/IP. They live only in prefs (same posture as MQ cloud creds)
// and must never be logged or committed.

package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.security.SecureRandom
import java.util.UUID
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorUiSignals

/**
 * Ottai's older responses use epoch milliseconds, while the CN V3 bind response echoes the
 * official request's epoch-seconds activeTime. Keep the persistence/export contract uniformly ms.
 */
internal fun normalizeOttaiActiveTimeMs(value: Long): Long =
    if (value in 1L..9_999_999_999L) value * 1_000L else value

object OttaiRegistry {

    enum class SessionProfile {
        WATCH,
        CN_PHONE,
    }
    private const val TAG = OttaiConstants.TAG
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_DRAFT_SENSORS_KEY = "ottai_draft_sensors"
    private const val PREF_V3_BOOTSTRAP_PENDING_PREFIX = "ottai_v3_bootstrap_pending_"
    private const val CN_COMMON_DEVICE_ID_LENGTH = 32
    private const val CN_COMMON_DEVICE_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val CN_COMMON_NATIVE_PREFIX = "n:"
    private const val CN_COMMON_GENERATED_PREFIX = "g:"
    // Pre-1.0.6-merge key for the accepted lifetime; read once by loadAcceptedMaxActive migration.
    private const val LEGACY_ACTIVATED_MAXACTIVE_PREFIX = "ottai_activated_maxactive_"
    // ~1 min cadence: 9000 samples is about 6 days of temperature, matching Anytime.
    private const val TEMPERATURE_HISTORY_LIMIT = 9000

    data class SensorRecord(
        val sensorId: String, // canonical cloud id used by server + BLE auth
        val address: String,  // Android BLE address, colon form; may be blank
        val displayName: String,
    ) {
        fun matchesId(id: String?): Boolean =
            OttaiConstants.matchesCanonicalOrKnownNativeAlias(sensorId, id)
    }

    data class DeviceMaterials(
        val keyAHex: String,        // decrypted, 192 hex -> 6 auth keys
        val method: String,         // decrypted RPN expression
        val coefficient: String,    // decrypted CSV
        val activeTimeMs: Long,
        val deviceVersion: String,
        val deviceId: Int,
        // From the cloud validate-by-mac response; used verbatim by BLE activation.
        // activeExpireTimeMs -> maxActive write (p.D); retainTimeMs -> destruction
        // write (p.E, defaults to 172800000 when the server omits it).
        val activeExpireTimeMs: Long = 0L,
        val retainTimeMs: Long = 0L,
        val preheatPeriodMs: Long = 0L,
    ) {
        val authKeys: List<ByteArray>? get() = OttaiCrypto.parseAuthKeys(keyAHex)
        val coefficients: List<Double>
            get() = coefficient.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        val hasAll: Boolean get() = keyAHex.isNotBlank() && method.isNotBlank() && activeTimeMs > 0L
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- account ----

    @JvmStatic fun loadAccessToken(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_ACCESS_TOKEN, null).orEmpty()
    @JvmStatic fun saveAccessToken(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_ACCESS_TOKEN, v).apply()
    }

    @JvmStatic fun loadGlucoseSecretKey(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_GLUCOSE_SECRET, null).orEmpty()
    @JvmStatic fun saveGlucoseSecretKey(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_GLUCOSE_SECRET, v).apply()
    }

    @JvmStatic fun loadUserId(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_USER_ID, null).orEmpty()
    @JvmStatic fun saveUserId(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_USER_ID, v).apply()
    }

    /** The login the user typed at sign-in (phone / email / username) — for display only. */
    @JvmStatic fun loadAccountLogin(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_ACCOUNT_LOGIN, null).orEmpty()
    @JvmStatic fun saveAccountLogin(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_ACCOUNT_LOGIN, v).apply()
    }

    /** Backend host for the signed-in account (CN, Ottai global, or Syai global). */
    @JvmStatic fun loadApiBase(c: Context): String {
        val stored = prefs(c).getString(OttaiConstants.PREF_API_BASE, null)
        val normalized = normalizeApiBase(stored)
        if (!stored.isNullOrBlank() && stored != normalized) {
            prefs(c).edit().putString(OttaiConstants.PREF_API_BASE, normalized).apply()
        }
        return normalized
    }

    internal fun normalizeApiBase(stored: String?): String {
        val trimmed = stored?.trim()
        return when (trimmed) {
            null, "" -> OttaiConstants.API_BASE
            OttaiConstants.API_BASE_SYAI_LEGACY -> OttaiConstants.API_BASE_SYAI
            else -> trimmed
        }
    }

    @JvmStatic fun saveApiBase(c: Context, v: String) {
        prefs(c).edit().putString(OttaiConstants.PREF_API_BASE, v).apply()
    }

    /** Missing means a legacy token issued to the recovered watch client. Never relabel tokens. */
    internal fun parseSessionProfile(stored: String?): SessionProfile =
        runCatching { SessionProfile.valueOf(stored ?: SessionProfile.WATCH.name) }
            .getOrDefault(SessionProfile.WATCH)

    @JvmStatic fun loadSessionProfile(c: Context): SessionProfile =
        parseSessionProfile(prefs(c).getString(OttaiConstants.PREF_SESSION_PROFILE, null))

    @JvmStatic fun saveSessionProfile(c: Context, profile: SessionProfile?) {
        prefs(c).edit().apply {
            if (profile == null) remove(OttaiConstants.PREF_SESSION_PROFILE)
            else putString(OttaiConstants.PREF_SESSION_PROFILE, profile.name)
        }.apply()
    }

    /** Device version the CN V3 validate call accepted for [sensorId]; bindV3 must repeat it. */
    @JvmStatic fun saveLastValidatedDeviceVersion(c: Context, sensorId: String, version: String) {
        val v = version.trim()
        if (sensorId.isBlank() || v.isEmpty()) return
        prefs(c).edit().putString(lastValidatedVersionKey(sensorId), v).apply()
    }

    @JvmStatic fun loadLastValidatedDeviceVersion(c: Context, sensorId: String): String? =
        prefs(c).getString(lastValidatedVersionKey(sensorId), null)?.trim()?.ifEmpty { null }

    private fun lastValidatedVersionKey(sensorId: String) = "ottai_v3_validated_version_$sensorId"

    /** Stable per-install device id used in the cloud signature + deviceId header. */
    @JvmStatic
    fun loadOrCreateDeviceId(c: Context): String {
        val existing = prefs(c).getString(OttaiConstants.PREF_SELF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs(c).edit().putString(OttaiConstants.PREF_SELF_DEVICE_ID, generated).apply()
        return generated
    }

    /**
     * The CN phone SDK stores the suffix of its common_uniqueid. The official format is
     * appName:a:n:<native UUID> when Android supplies a stable native ID, or
     * appName:a:g:<32-character fallback> otherwise. The appName:a: part is added by the
     * request identity formatter, so callers that sign legacy CN requests and callers that build
     * the common header use the same suffix without double-prefixing it.
     */
    @JvmStatic
    fun loadCnHeaderDeviceId(c: Context): String {
        val p = prefs(c)
        val existing = p.getString(OttaiConstants.PREF_CN_COMMON_DEVICE_ID, null)
        cnHeaderDeviceId(existing, "").takeIf { it.isNotEmpty() }?.let { normalized ->
            if (existing?.trim() != normalized) {
                p.edit().putString(OttaiConstants.PREF_CN_COMMON_DEVICE_ID, normalized).apply()
            }
            return normalized
        }
        val native = nativeCnDeviceId(
            androidId = runCatching {
                Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull(),
            board = Build.BOARD,
            brand = Build.BRAND,
            device = Build.DEVICE,
            model = Build.MODEL,
            product = Build.PRODUCT,
        )
        val generated = native?.let { "$CN_COMMON_NATIVE_PREFIX$it" } ?: run {
            val random = SecureRandom()
            buildString(CN_COMMON_GENERATED_PREFIX.length + CN_COMMON_DEVICE_ID_LENGTH) {
                append(CN_COMMON_GENERATED_PREFIX)
                repeat(CN_COMMON_DEVICE_ID_LENGTH) {
                    append(CN_COMMON_DEVICE_ID_ALPHABET[random.nextInt(CN_COMMON_DEVICE_ID_ALPHABET.length)])
                }
            }
        }
        p.edit().putString(OttaiConstants.PREF_CN_COMMON_DEVICE_ID, generated).apply()
        return generated
    }

    /** Official DeviceUtil.c() native ID derivation, kept pure for regression testing. */
    internal fun nativeCnDeviceId(
        androidId: String?,
        board: String,
        brand: String,
        device: String,
        model: String,
        product: String,
    ): String? {
        val id = androidId?.takeIf { it.isNotEmpty() && it != "9774d56d682e549c" } ?: return null
        return UUID.nameUUIDFromBytes(
            (id + board + brand + device + model + product).toByteArray(Charsets.UTF_8),
        ).toString()
    }

    internal fun cnHeaderDeviceId(stored: String?, fallback: String): String {
        val value = stored?.trim().orEmpty()
        val suffix = value.removePrefix("ottai:a:")
        if (isNativeCnDeviceId(suffix)) return "$CN_COMMON_NATIVE_PREFIX${suffix.substring(2)}"
        if (isGeneratedCnDeviceId(suffix)) return "$CN_COMMON_GENERATED_PREFIX${suffix.substring(2)}"
        // Migrate the pre-format random value without changing the install's stable identity.
        if (suffix.length == CN_COMMON_DEVICE_ID_LENGTH &&
            suffix.all { it in CN_COMMON_DEVICE_ID_ALPHABET }
        ) {
            return "$CN_COMMON_GENERATED_PREFIX$suffix"
        }
        return fallback
    }

    private fun isNativeCnDeviceId(value: String): Boolean {
        if (!value.startsWith(CN_COMMON_NATIVE_PREFIX)) return false
        return runCatching { UUID.fromString(value.substring(2)) }.isSuccess
    }

    private fun isGeneratedCnDeviceId(value: String): Boolean =
        value.length == CN_COMMON_GENERATED_PREFIX.length + CN_COMMON_DEVICE_ID_LENGTH &&
            value.startsWith(CN_COMMON_GENERATED_PREFIX) &&
            value.drop(CN_COMMON_GENERATED_PREFIX.length).all { it in CN_COMMON_DEVICE_ID_ALPHABET }

    // ---- sensor record set ----

    @JvmStatic
    fun ensureSensorRecord(context: Context, sensorId: String, address: String, displayName: String) {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val records = persistedRecords(context).toMutableList()
        val normalizedAddress = OttaiConstants.normalizeBleAddress(address, allowPlain = false)
        val existingRecord = findRecord(context, canonical) ?: normalizedAddress?.let { target ->
            records.filter {
                OttaiConstants.normalizeBleAddress(it.address, allowPlain = false)
                    ?.equals(target, ignoreCase = true) == true
            }.singleOrNull()
        }
        val idx = existingRecord?.let { existing ->
            records.indexOfFirst { it.sensorId.equals(existing.sensorId, ignoreCase = true) }
        } ?: -1
        val existing = records.getOrNull(idx)
        val bleAddress = normalizedAddress
            ?: OttaiConstants.normalizeBleAddress(existing?.address, allowPlain = false).orEmpty()
        // A scan may know this device by its full BLE address while the cloud/UI later supplies
        // a shorter id. Keep the already material-backed canonical id instead of creating a
        // second record whose auth keys do not exist.
        val stableId = existing?.sensorId ?: canonical
        val record = SensorRecord(stableId, bleAddress, displayName.ifBlank { stableId })
        if (idx >= 0) records[idx] = record else records.add(record)
        writeRecords(context, records)
    }

    @JvmStatic
    fun persistedRecords(context: Context): List<SensorRecord> {
        return readRecords(context, OttaiConstants.PREF_SENSORS_KEY)
    }

    /**
     * Credentials fetched or imported during setup are drafts. Managed sensor discovery
     * must not see them until the user explicitly presses Connect.
     */
    @JvmStatic
    fun saveDraftRecord(context: Context, sensorId: String, address: String, displayName: String) {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val records = draftRecords(context).toMutableList()
        val index = records.indexOfFirst { it.matchesId(canonical) }
        val existing = records.getOrNull(index)
        val normalizedAddress = OttaiConstants.normalizeBleAddress(address, allowPlain = false)
            ?: existing?.address.orEmpty()
        val record = SensorRecord(canonical, normalizedAddress, displayName.ifBlank { canonical })
        if (index >= 0) records[index] = record else records.add(record)
        writeRecords(context, records, PREF_DRAFT_SENSORS_KEY)
    }

    @JvmStatic
    fun draftRecords(context: Context): List<SensorRecord> =
        readRecords(context, PREF_DRAFT_SENSORS_KEY)

    @JvmStatic
    fun findDraftRecord(context: Context, sensorId: String): SensorRecord? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        return draftRecords(context).firstOrNull { it.matchesId(canonical) }
    }

    @JvmStatic
    fun savedMaterialRecords(context: Context): List<SensorRecord> {
        val managed = persistedRecords(context)
        val drafts = draftRecords(context).filter { draft ->
            managed.none { it.matchesId(draft.sensorId) }
        }
        return (managed + drafts).filter { hasStoredAuthMaterial(context, it.sensorId) }
    }

    @JvmStatic
    fun isManagedConnectionAuthorized(context: Context, sensorId: String): Boolean {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val materials = loadMaterials(context, canonical)
        return materials.activeTimeMs > 0L ||
            loadProvisionalActiveTime(context, canonical) > 0L ||
            loadActivationAttempted(context, canonical)
    }

    private fun readRecords(context: Context, key: String): List<SensorRecord> {
        val raw = prefs(context).getStringSet(key, emptySet()) ?: return emptyList()
        return raw.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) null else SensorRecord(parts[0], parts[1], parts[2])
        }
    }

    @JvmStatic
    fun findRecord(context: Context?, sensorId: String?): SensorRecord? {
        val ctx = context ?: return null
        val id = sensorId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val records = persistedRecords(ctx)
        val canonical = OttaiConstants.canonicalSensorId(id)
        val candidates = records.filter { record ->
            val recordId = OttaiConstants.canonicalSensorId(record.sensorId)
            record.matchesId(canonical) ||
                (canonical.length >= 6 && recordId.endsWith(canonical, ignoreCase = true)) ||
                (recordId.length >= 6 && canonical.endsWith(recordId, ignoreCase = true))
        }
        if (candidates.isEmpty()) return null
        // Prefer the identity that owns the BLE authentication material. This also repairs the
        // old full-MAC + short-id duplicate state without requiring another cloud bind.
        candidates.firstOrNull { hasStoredAuthMaterial(ctx, it.sensorId) }?.let { return it }
        candidates.firstOrNull { it.matchesId(canonical) }?.let { return it }
        return candidates.singleOrNull()
    }

    private fun hasStoredAuthMaterial(context: Context, sensorId: String): Boolean {
        val id = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        return !prefs(context).getString(OttaiConstants.PREF_KEYA_PREFIX + id, null).isNullOrBlank()
    }

    @JvmStatic
    fun resolveCanonicalSensorId(context: Context?, sensorId: String?): String? =
        findRecord(context, sensorId)?.sensorId

    private fun writeRecords(
        context: Context,
        records: List<SensorRecord>,
        key: String = OttaiConstants.PREF_SENSORS_KEY,
    ) {
        val set = records.map { "${it.sensorId}|${it.address}|${it.displayName}" }.toSet()
        prefs(context).edit().putStringSet(key, set).apply()
        // The record set feeds SensorIdentity.resolveAppSensorId (memoized) — drop its cache.
        tk.glucodata.SensorIdentity.invalidateCaches()
    }

    @JvmStatic
    fun removeSensor(context: Context, sensorId: String?) {
        val id = sensorId?.trim() ?: return
        val canonical = OttaiConstants.canonicalSensorId(id).ifEmpty { id }
        // When this sensor started is the one fact removal must not destroy. It takes a BLE
        // session to re-derive, and the cloud does not reliably carry it — a re-bind answers with
        // activeTime = now, or with nothing at all. Wiped, a sensor two weeks into its life came
        // back from a remove/re-add reading "Ready to activate" and offering to start warmup
        // (2026-08-07, 6CA04230E260, real start 2026-07-23).
        //
        // Carry it into the provisional slot, whose meaning is exactly "a start recovered locally
        // that the cloud has not confirmed": a later cloud activeTime still overrides it, and it
        // cannot resurrect the sensor on its own, because every managed-connection path reaches
        // it only through a persisted record that this call just deleted.
        val recoveredStartMs = loadMaterials(context, canonical).activeTimeMs
            .takeIf { it > 0L }
            ?: loadProvisionalActiveTime(context, canonical)
        writeRecords(context, persistedRecords(context).filter { !it.matchesId(canonical) })
        writeRecords(
            context,
            draftRecords(context).filter { !it.matchesId(canonical) },
            PREF_DRAFT_SENSORS_KEY,
        )
        prefs(context).edit().apply {
            listOf(
                OttaiConstants.PREF_KEYA_PREFIX, OttaiConstants.PREF_METHOD_PREFIX,
                OttaiConstants.PREF_COEFF_PREFIX, OttaiConstants.PREF_ACTIVE_TIME_PREFIX,
                OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX,
                OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX, OttaiConstants.PREF_RETAIN_TIME_PREFIX,
                // PREF_ACCEPTED_MAX_ACTIVE is deliberately NOT in this list: it is the lifetime
                // the sensor itself accepted at activation, kept for the same reason as the start
                // below. Dropping it sends an extended 28-day unit back to the 15-day rated
                // default, which reads as EXPIRED partway through a working sensor.
                OttaiConstants.PREF_PREHEAT_PERIOD_PREFIX,
                OttaiConstants.PREF_DEVICE_VERSION_PREFIX, OttaiConstants.PREF_LAST_DATA_NO_PREFIX,
                OttaiConstants.PREF_DEVICE_ID_PREFIX, OttaiConstants.PREF_ACTIVATION_ATTEMPTED_PREFIX,
                OttaiConstants.PREF_CONTINUITY_BASELINE_PREFIX,
                OttaiConstants.PREF_HISTORY_HOLES_PREFIX,
                LEGACY_ACTIVATED_MAXACTIVE_PREFIX,
                OttaiConstants.PREF_TEMPERATURE_HISTORY_PREFIX,
                PREF_V3_BOOTSTRAP_PENDING_PREFIX,
            ).forEach { remove(it + canonical) }
            remove(lastValidatedVersionKey(canonical))
        }.apply()
        saveProvisionalActiveTime(context, canonical, recoveredStartMs)
    }

    // ---- per-sensor materials ----

    @JvmStatic
    fun saveMaterials(context: Context, sensorId: String, m: DeviceMaterials): Boolean {
        val id = resolveCanonicalSensorId(context, sensorId)
            ?: OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val existing = loadMaterials(context, id)
        val coefficient = m.coefficient.ifBlank { existing.coefficient }
        val method = OttaiMethodDefaults.resolve(m.method.ifBlank { existing.method }, coefficient)
        val activeExpireTimeMs = OttaiConstants.sanitizeActiveExpireMs(m.activeExpireTimeMs)
        val saved = prefs(context).edit().apply {
            putString(OttaiConstants.PREF_KEYA_PREFIX + id, m.keyAHex)
            putString(OttaiConstants.PREF_METHOD_PREFIX + id, method)
            putString(OttaiConstants.PREF_COEFF_PREFIX + id, coefficient)
            putLong(
                OttaiConstants.PREF_ACTIVE_TIME_PREFIX + id,
                normalizeOttaiActiveTimeMs(m.activeTimeMs),
            )
            if (activeExpireTimeMs > 0L) {
                putLong(OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + id, activeExpireTimeMs)
            } else {
                remove(OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + id)
            }
            putLong(OttaiConstants.PREF_RETAIN_TIME_PREFIX + id, m.retainTimeMs)
            putLong(OttaiConstants.PREF_PREHEAT_PERIOD_PREFIX + id, m.preheatPeriodMs)
            putString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + id, m.deviceVersion)
            putInt(OttaiConstants.PREF_DEVICE_ID_PREFIX + id, m.deviceId)
        }.commit()
        if (!saved) {
            Log.w(OttaiConstants.TAG, "failed to persist materials for $id")
            return false
        }
        Log.i(OttaiConstants.TAG, "persisted materials for $id")
        setV3CredentialBootstrapPending(context, id, false)
        // Stored auth material decides which record findRecord prefers, which feeds the memoized
        // SensorIdentity.resolveAppSensorId — refresh it so a newly-material-backed id resolves.
        tk.glucodata.SensorIdentity.invalidateCaches()
        return true
    }

    @JvmStatic
    fun loadMaterials(context: Context, sensorId: String): DeviceMaterials {
        val id = resolveCanonicalSensorId(context, sensorId)
            ?: OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val p = prefs(context)
        val coefficient = p.getString(OttaiConstants.PREF_COEFF_PREFIX + id, null).orEmpty()
        val method = OttaiMethodDefaults.resolve(
            p.getString(OttaiConstants.PREF_METHOD_PREFIX + id, null).orEmpty(),
            coefficient,
        )
        val activeTimeKey = OttaiConstants.PREF_ACTIVE_TIME_PREFIX + id
        val storedActiveTime = p.getLong(activeTimeKey, 0L)
        val activeTimeMs = normalizeOttaiActiveTimeMs(storedActiveTime)
        if (activeTimeMs != storedActiveTime) {
            p.edit().putLong(activeTimeKey, activeTimeMs).apply()
            Log.w(TAG, "migrated activeTime seconds to milliseconds for $id")
        }
        val activeExpireKey = OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + id
        val storedActiveExpire = p.getLong(activeExpireKey, 0L)
        val activeExpireTimeMs = OttaiConstants.sanitizeActiveExpireMs(storedActiveExpire)
        if (activeExpireTimeMs != storedActiveExpire) {
            p.edit().remove(activeExpireKey).apply()
            Log.w(TAG, "removed invalid activeExpire duration for $id")
        }
        return DeviceMaterials(
            keyAHex = p.getString(OttaiConstants.PREF_KEYA_PREFIX + id, null).orEmpty(),
            method = method,
            coefficient = coefficient,
            activeTimeMs = activeTimeMs,
            activeExpireTimeMs = activeExpireTimeMs,
            retainTimeMs = p.getLong(OttaiConstants.PREF_RETAIN_TIME_PREFIX + id, 0L),
            preheatPeriodMs = p.getLong(OttaiConstants.PREF_PREHEAT_PERIOD_PREFIX + id, 0L),
            deviceVersion = p.getString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + id, null).orEmpty(),
            deviceId = p.getInt(OttaiConstants.PREF_DEVICE_ID_PREFIX + id, 0),
        )
    }

    /**
     * Persist just the activation start (PREF_ACTIVE_TIME) without touching the rest of the
     * materials. Used when the driver recovers the true activation from the live dataNo counter
     * for a sensor activated on-device (no cloud activeTime), so the wizard/export recognise it as
     * activated. Uses the same id resolution as save/loadMaterials.
     */
    @JvmStatic fun saveActiveTimeMs(c: Context, id: String, activeTimeMs: Long) {
        val canonical = resolveCanonicalSensorId(c, id)
            ?: OttaiConstants.canonicalSensorId(id).ifEmpty { id }
        prefs(c).edit().putLong(
            OttaiConstants.PREF_ACTIVE_TIME_PREFIX + canonical,
            normalizeOttaiActiveTimeMs(activeTimeMs),
        ).apply()
    }

    @JvmStatic fun loadProvisionalActiveTime(c: Context, id: String): Long =
        prefs(c).getLong(
            OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + OttaiConstants.canonicalSensorId(id),
            0L,
        )

    @JvmStatic fun saveProvisionalActiveTime(c: Context, id: String, activeTimeMs: Long) {
        val canonical = OttaiConstants.canonicalSensorId(id)
        prefs(c).edit().apply {
            if (activeTimeMs > 0L) putLong(OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + canonical, activeTimeMs)
            else remove(OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + canonical)
        }.apply()
    }

    @JvmStatic fun loadAcceptedMaxActive(c: Context, id: String): Long {
        val canonical = OttaiConstants.canonicalSensorId(id)
        val current = prefs(c).getLong(OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + canonical, 0L)
        val sanitizedCurrent = OttaiConstants.sanitizeActiveExpireMs(current)
        if (sanitizedCurrent > 0L) return sanitizedCurrent
        if (current != 0L) {
            prefs(c).edit().remove(OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + canonical).apply()
        }
        // Lazy migration: pre-1.0.6-merge builds persisted the accepted lifetime under the
        // dropped PREF_ACTIVATED_MAXACTIVE_PREFIX key. Rewrite it under the new key once so an
        // upgraded install doesn't lose an extended sensor's real accepted lifetime.
        val legacy = prefs(c).getLong(LEGACY_ACTIVATED_MAXACTIVE_PREFIX + canonical, 0L)
        val sanitizedLegacy = OttaiConstants.sanitizeActiveExpireMs(legacy)
        if (sanitizedLegacy > 0L) {
            prefs(c).edit()
                .putLong(OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + canonical, sanitizedLegacy)
                .remove(LEGACY_ACTIVATED_MAXACTIVE_PREFIX + canonical)
                .apply()
        } else if (legacy != 0L) {
            prefs(c).edit().remove(LEGACY_ACTIVATED_MAXACTIVE_PREFIX + canonical).apply()
        }
        return sanitizedLegacy
    }

    @JvmStatic fun saveAcceptedMaxActive(c: Context, id: String, durationMs: Long) {
        val canonical = OttaiConstants.canonicalSensorId(id)
        val sanitized = OttaiConstants.sanitizeActiveExpireMs(durationMs)
        prefs(c).edit().apply {
            if (sanitized > 0L) {
                putLong(OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + canonical, sanitized)
            } else {
                remove(OttaiConstants.PREF_ACCEPTED_MAX_ACTIVE_PREFIX + canonical)
            }
        }.apply()
    }

    /**
     * One-shot guard for auto-activate-on-first-connect: true once the driver has
     * fired (or been asked to fire) the irreversible activation for this sensor, so a
     * virgin sensor activates exactly once and never re-fires on later reconnects.
     */
    @JvmStatic fun loadActivationAttempted(c: Context, id: String): Boolean =
        prefs(c).getBoolean(OttaiConstants.PREF_ACTIVATION_ATTEMPTED_PREFIX + OttaiConstants.canonicalSensorId(id), false)
    @JvmStatic fun setActivationAttempted(c: Context, id: String, v: Boolean) {
        prefs(c).edit().putBoolean(OttaiConstants.PREF_ACTIVATION_ATTEMPTED_PREFIX + OttaiConstants.canonicalSensorId(id), v).apply()
    }

    @JvmStatic
    fun isV3CredentialBootstrapPending(c: Context, id: String): Boolean =
        prefs(c).getBoolean(
            PREF_V3_BOOTSTRAP_PENDING_PREFIX + OttaiConstants.canonicalSensorId(id),
            false,
        )

    @JvmStatic
    fun setV3CredentialBootstrapPending(c: Context, id: String, pending: Boolean) {
        val key = PREF_V3_BOOTSTRAP_PENDING_PREFIX + OttaiConstants.canonicalSensorId(id)
        prefs(c).edit().apply {
            if (pending) putBoolean(key, true) else remove(key)
        }.apply()
    }

    @JvmStatic fun loadLastDataNo(c: Context, id: String): Int =
        prefs(c).getInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + OttaiConstants.canonicalSensorId(id), -1)
    @JvmStatic fun saveLastDataNo(c: Context, id: String, dataNo: Int) {
        prefs(c).edit().putInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + OttaiConstants.canonicalSensorId(id), dataNo).apply()
    }

    /**
     * Hole ledger: requested-but-undelivered history windows, serialized by the driver as
     * "start:endExclusive:attempts" entries joined by ';'. Persisted so a disconnect or app
     * restart cannot turn a missed window into a permanent history gap.
     */
    @JvmStatic fun saveHistoryHoles(c: Context, id: String, serialized: String) {
        val key = OttaiConstants.PREF_HISTORY_HOLES_PREFIX + OttaiConstants.canonicalSensorId(id)
        prefs(c).edit().apply {
            if (serialized.isBlank()) remove(key) else putString(key, serialized)
        }.apply()
    }

    /** Returns (start, endExclusive, attempts) triples; malformed or empty entries are dropped. */
    @JvmStatic
    fun loadHistoryHoles(c: Context, id: String): List<Triple<Int, Int, Int>> {
        val key = OttaiConstants.PREF_HISTORY_HOLES_PREFIX + OttaiConstants.canonicalSensorId(id)
        val raw = prefs(c).getString(key, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 3) return@mapNotNull null
            val start = parts[0].toIntOrNull() ?: return@mapNotNull null
            val endExclusive = parts[1].toIntOrNull() ?: return@mapNotNull null
            val attempts = parts[2].toIntOrNull() ?: return@mapNotNull null
            if (start < 0 || endExclusive <= start || attempts < 0) null
            else Triple(start, endExclusive, attempts)
        }
    }

    /** Last accepted spike-filter baseline, persisted so it survives an app restart. */
    data class ContinuityBaseline(val dataNo: Int, val sampleMs: Long, val mmol: Float, val rawCurrent: Int)

    @JvmStatic
    fun saveContinuityBaseline(c: Context, id: String, dataNo: Int, sampleMs: Long, mmol: Float, rawCurrent: Int) {
        if (!mmol.isFinite()) return
        val key = OttaiConstants.PREF_CONTINUITY_BASELINE_PREFIX + OttaiConstants.canonicalSensorId(id)
        prefs(c).edit().putString(key, "$dataNo,$sampleMs,$mmol,$rawCurrent").apply()
    }

    @JvmStatic
    fun loadContinuityBaseline(c: Context, id: String): ContinuityBaseline? {
        val key = OttaiConstants.PREF_CONTINUITY_BASELINE_PREFIX + OttaiConstants.canonicalSensorId(id)
        val parts = prefs(c).getString(key, null)?.split(',') ?: return null
        if (parts.size != 4) return null
        return ContinuityBaseline(
            dataNo = parts[0].toIntOrNull() ?: return null,
            sampleMs = parts[1].toLongOrNull() ?: return null,
            mmol = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null,
            rawCurrent = parts[3].toIntOrNull() ?: return null,
        )
    }

    // ---- temperature history ----
    //
    // Every record the sensor sends carries a skin temperature; the stats screen shows
    // it, so keep the accepted samples alongside the glucose the Room store already has.

    data class TemperatureRecord(
        val dataNo: Int,
        val timestampMs: Long,
        val temperatureC: Float,
    )

    private fun isPlausibleTemperature(t: Float): Boolean = t.isFinite() && t > -20f && t < 80f

    @JvmStatic
    fun loadTemperatureHistory(c: Context, id: String): List<TemperatureRecord> {
        val key = OttaiConstants.PREF_TEMPERATURE_HISTORY_PREFIX + OttaiConstants.canonicalSensorId(id)
        val encoded = prefs(c).getString(key, null).orEmpty()
        if (encoded.isBlank()) return emptyList()
        val out = ArrayList<TemperatureRecord>()
        encoded.split(';').forEach { token ->
            if (token.isBlank()) return@forEach
            val parts = token.split(',')
            if (parts.size != 3) return@forEach
            val dataNo = parts[0].toIntOrNull() ?: return@forEach
            val timestampMs = parts[1].toLongOrNull() ?: return@forEach
            val temperatureC = (parts[2].toIntOrNull() ?: return@forEach) / 10f
            if (timestampMs <= 0L || !isPlausibleTemperature(temperatureC)) return@forEach
            out.add(TemperatureRecord(dataNo, timestampMs, temperatureC))
        }
        return out.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
    }

    @JvmStatic
    fun appendTemperatureHistory(c: Context, id: String, records: Collection<TemperatureRecord>) {
        if (records.isEmpty()) return
        val key = OttaiConstants.PREF_TEMPERATURE_HISTORY_PREFIX + OttaiConstants.canonicalSensorId(id)
        val merged = (loadTemperatureHistory(c, id).asSequence() + records.asSequence())
            .filter { it.timestampMs > 0L && isPlausibleTemperature(it.temperatureC) }
            .distinctBy { it.timestampMs }
            .sortedBy { it.timestampMs }
            .toList()
            .takeLast(TEMPERATURE_HISTORY_LIMIT)
        if (merged.isEmpty()) {
            prefs(c).edit().remove(key).apply()
            return
        }
        val encoded = buildString(merged.size * 24) {
            merged.forEach { rec ->
                append(rec.dataNo)
                append(',')
                append(rec.timestampMs)
                append(',')
                append(Math.round(rec.temperatureC * 10f))
                append(';')
            }
        }
        prefs(c).edit().putString(key, encoded).apply()
    }

    // ---- portable export / import ----
    //
    // Serializes the DECRYPTED per-sensor materials + record. This is everything
    // the driver needs to connect, authenticate and activate over BLE without ever
    // contacting the server again — so the user does the cloud fetch (login →
    // validate/bind) once on one device, saves the JSON, and loads it on any other
    // device running this app. accessToken / glucoseSecretKey are NOT exported (not
    // needed once keyA/coefficient are decrypted).

    @JvmStatic
    fun exportJson(context: Context, sensorId: String): String? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val record = findRecord(context, canonical)
            ?: findDraftRecord(context, canonical)
            ?: SensorRecord(canonical, "", OttaiConstants.DEFAULT_DISPLAY_NAME)
        val m = loadMaterials(context, canonical)
        if (m.keyAHex.isBlank()) return null
        return org.json.JSONObject().apply {
            put("v", 1)
            put("sensorId", canonical)
            put("bleAddress", record.address)
            put("displayName", record.displayName)
            put("keyAHex", m.keyAHex)
            put("method", m.method)
            put("coefficient", m.coefficient)
            put("activeTimeMs", m.activeTimeMs)
            put("activeExpireTimeMs", m.activeExpireTimeMs)
            put("acceptedMaxActiveMs", loadAcceptedMaxActive(context, canonical))
            put("retainTimeMs", m.retainTimeMs)
            put("preheatPeriodMs", m.preheatPeriodMs)
            // provisionalActiveTimeMs is deliberately NOT exported. It is a local guess from the
            // cgm-info sliding window — usually "just now" for a sensor that has really been
            // running for days — and effectiveActiveTimeMs() consumes it as a start. Carrying it
            // to another device would hand that device a wrong activation it treats as real,
            // where (unlike here) no live anchor of its own has yet had a chance to correct it.
            put("deviceVersion", m.deviceVersion)
            put("deviceId", m.deviceId)
        }.toString(2)
    }

    /** Returns the canonical sensorId on success, or null if the JSON is invalid. */
    @JvmStatic
    fun importJson(context: Context, json: String): String? {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val id = OttaiConstants.canonicalSensorId(o.optString("sensorId")).ifEmpty { return null }
        val keyA = o.optString("keyAHex")
        if (OttaiCrypto.parseAuthKeys(keyA) == null) return null // must be 192 hex = 6 auth keys
        saveDraftRecord(
            context, id, o.optString("bleAddress"),
            o.optString("displayName").ifBlank { OttaiConstants.DEFAULT_DISPLAY_NAME },
        )
        if (!saveMaterials(
            context, id,
            DeviceMaterials(
                keyAHex = keyA,
                method = o.optString("method"),
                coefficient = o.optString("coefficient"),
                activeTimeMs = normalizeOttaiActiveTimeMs(o.optLong("activeTimeMs", 0L)),
                activeExpireTimeMs = OttaiConstants.sanitizeActiveExpireMs(
                    o.optLong("activeExpireTimeMs", 0L),
                ),
                retainTimeMs = o.optLong("retainTimeMs", 0L),
                preheatPeriodMs = o.optLong("preheatPeriodMs", 0L),
                deviceVersion = o.optString("deviceVersion"),
                deviceId = o.optInt("deviceId", 0),
            ),
        )) return null
        // Older exports carry provisionalActiveTimeMs; ignore it rather than adopting another
        // device's guess as this one's activation start. A local live anchor establishes the real
        // start here, and until it does, no start beats a wrong one.
        saveProvisionalActiveTime(context, id, 0L)
        saveAcceptedMaxActive(context, id, o.optLong("acceptedMaxActiveMs", 0L))
        return id
    }

    // ---- restore / wizard ----

    @JvmStatic
    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val record = findRecord(context, canonical) ?: return null
        return runCatching {
            OttaiBleManager(record.sensorId, dataptr).also {
                it.mActiveDeviceAddress = OttaiConstants.normalizeBleAddress(record.address, allowPlain = false)
                it.restoreFromPersistence(context)
            }
        }.onFailure { Log.stack(TAG, "createRestoredCallback", it) }.getOrNull()
    }

    @JvmStatic
    @JvmOverloads
    fun addSensor(
        context: Context,
        sensorId: String,
        address: String,
        displayName: String? = null,
        connectNow: Boolean = true,
    ): String? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        if (canonical.isBlank()) return null
        val draft = findDraftRecord(context, canonical)
        val bleAddress = OttaiConstants.normalizeBleAddress(address, allowPlain = false)
            ?: OttaiConstants.normalizeBleAddress(draft?.address, allowPlain = false)
            ?: ""
        ensureSensorRecord(context, canonical, bleAddress, displayName ?: OttaiConstants.DEFAULT_DISPLAY_NAME)
        writeRecords(
            context,
            draftRecords(context).filter { !it.matchesId(canonical) },
            PREF_DRAFT_SENSORS_KEY,
        )
        val stableId = resolveCanonicalSensorId(context, canonical) ?: canonical
        if (connectNow) connectSensor(context, stableId)
        ManagedSensorUiSignals.markDeviceListDirty()
        return stableId
    }

    /**
     * The setup button is the sole transition from saved credentials to a managed
     * sensor. Arm activation before publishing the record so a device refresh cannot
     * race ahead and connect without the user's request.
     */
    @JvmStatic
    fun addSensorForUserConnect(
        context: Context,
        sensorId: String,
        address: String,
        displayName: String? = null,
        activate: Boolean,
        activateIfNeeded: Boolean,
    ): String? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val activationWasAttempted = loadActivationAttempted(context, canonical)
        val armActivation = activate || activateIfNeeded
        if (armActivation) {
            // Authorize the managed record before publishing it. updateDevices() can run
            // immediately after callback creation; without this marker it removes the
            // fresh sensor and closes its GATT before the first connection callback.
            setActivationAttempted(context, canonical, true)
            OttaiBleManager.activateRequestedFor = canonical
        }
        val stableId = addSensor(
            context,
            canonical,
            address,
            displayName,
            connectNow = false,
        )
        if (stableId == null) {
            if (armActivation) setActivationAttempted(context, canonical, activationWasAttempted)
            if (armActivation && OttaiBleManager.activateRequestedFor == canonical) {
                OttaiBleManager.activateRequestedFor = null
            }
            return null
        }
        if (activate) {
            requestActivation(context, stableId)
        } else {
            connectSensor(
                context,
                stableId,
                awaitFreshActivationAdvertisement = armActivation,
            )
        }
        return stableId
    }

    /**
     * Fetch credentials through a wizard-owned GATT transaction. The manager is intentionally
     * not added to SensorBluetooth.gattcallbacks and no managed record is written: only a draft
     * address exists until cgmAuth + bindV3 have produced usable materials.
     */
    fun startV3CredentialBootstrap(
        context: Context,
        sensorId: String,
        address: String,
        onComplete: (DeviceMaterials?, OttaiCloudClient.CloudFailure?) -> Unit,
    ): OttaiBleManager? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val bleAddress = OttaiConstants.normalizeBleAddress(address, allowPlain = false)
        if (canonical.isBlank() ||
            bleAddress == null ||
            loadLastValidatedDeviceVersion(context, canonical).isNullOrBlank() ||
            loadSessionProfile(context) != SessionProfile.CN_PHONE ||
            loadAccessToken(context).isBlank()
        ) {
            return null
        }
        saveDraftRecord(context, canonical, bleAddress, OttaiConstants.DEFAULT_DISPLAY_NAME)
        // 39745902 briefly represented this transaction as a managed sensor. Demote that orphan
        // locally (without cloud unbind) before retrying, otherwise saving keyA below would make
        // the stale managed record visible again before the user's explicit Connect action.
        if (!hasStoredAuthMaterial(context, canonical)) {
            writeRecords(context, persistedRecords(context).filter { !it.matchesId(canonical) })
            ManagedSensorUiSignals.markDeviceListDirty()
            runCatching { SensorBluetooth.updateDevices() }
        }
        setV3CredentialBootstrapPending(context, canonical, true)
        val manager = OttaiBleManager(canonical, 0L).also {
            it.mActiveDeviceAddress = bleAddress
            val adapter = runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    ?: BluetoothAdapter.getDefaultAdapter()
            }.getOrNull()
            it.mActiveBluetoothDevice = runCatching { adapter?.getRemoteDevice(bleAddress) }.getOrNull()
            it.restoreFromPersistence(context)
        }
        return if (manager.beginV3CredentialBootstrap(onComplete)) {
            manager
        } else {
            setV3CredentialBootstrapPending(context, canonical, false)
            null
        }
    }

    @JvmStatic
    fun connectSensor(context: Context, sensorId: String) {
        connectSensor(context, sensorId, awaitFreshActivationAdvertisement = false)
    }

    private fun connectSensor(
        context: Context,
        sensorId: String,
        awaitFreshActivationAdvertisement: Boolean,
    ) {
        val blue = SensorBluetooth.blueone ?: return
        val record = findRecord(context, sensorId) ?: return
        val existing = SensorBluetooth.gattcallbacks.firstOrNull { cb ->
            val d = cb as? OttaiDriver ?: return@firstOrNull false
            SensorIdentity.matches(cb.SerialNumber, sensorId) || d.matchesManagedSensorId(sensorId)
        }
        val callback = existing ?: createRestoredCallback(context, record.sensorId, 0L)?.also {
            SensorBluetooth.gattcallbacks.add(it)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        } ?: return
        if (callback is OttaiBleManager) {
            val bleAddress = OttaiConstants.normalizeBleAddress(record.address, allowPlain = false)
            callback.mActiveDeviceAddress = bleAddress
            callback.mActiveBluetoothDevice = null
            if (!awaitFreshActivationAdvertisement &&
                bleAddress != null &&
                BluetoothAdapter.checkBluetoothAddress(bleAddress)
            ) {
                val adapter = runCatching {
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                        ?: BluetoothAdapter.getDefaultAdapter()
                }.getOrNull()
                callback.mActiveBluetoothDevice = runCatching { adapter?.getRemoteDevice(bleAddress) }.getOrNull()
            }
            callback.restoreFromPersistence(context)
        }
        runCatching { SensorBluetooth.ensureCurrentSensorSelection() }
        if (SensorBluetooth.blueone === blue) {
            val awaitingAdvertisement =
                awaitFreshActivationAdvertisement &&
                    (callback as? OttaiBleManager)?.awaitFreshActivationAdvertisement() == true
            if (!awaitingAdvertisement) callback.connectDevice(0)
        }
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    /**
     * Arm + trigger the one-time BLE activation (irreversible — starts the sensor
     * lifetime). Fires immediately if the sensor is already connected+authenticated;
     * otherwise arms the flag and (re)connects so it fires on the next auth.
     * Returns true if it fired right now.
     */
    @JvmStatic
    fun requestActivation(context: Context, sensorId: String): Boolean {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        setActivationAttempted(context, canonical, true)
        OttaiBleManager.activateRequestedFor = canonical
        val mgr = SensorBluetooth.gattcallbacks.firstOrNull { cb ->
            (cb as? OttaiBleManager)?.matchesManagedSensorId(canonical) == true
        } as? OttaiBleManager
        // The Advanced "Activate" is an explicit user action — force it so it can also
        // attempt to re-arm/extend an already-started or expired (cmd>3) sensor.
        if (mgr != null && mgr.requestForceActivation()) return true
        connectSensor(context, canonical, awaitFreshActivationAdvertisement = true)
        return false
    }
}
