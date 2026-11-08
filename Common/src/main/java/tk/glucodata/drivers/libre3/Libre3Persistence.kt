// Libre3Persistence.kt — durable state for the Kotlin Libre 3 driver.
//
// Plain SharedPreferences for per-sensor connection metadata (the same fields the legacy native
// record keeps: address, PIN, start, warmup, wear) plus backfill bookkeeping. Secrets — imported
// app identities and each sensor's cached kAuth — are sealed with an AES-GCM key that lives in the
// Android Keystore and never leaves this device, so a backup restored elsewhere carries ciphertext
// only: the identity must be imported again and the next connection runs full authorization.

package tk.glucodata.drivers.libre3

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import tk.glucodata.Log

internal object Libre3SecretBox {
    private const val TAG = "Libre3SecretBox"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "tk.glucodata.libre3.secrets.v1"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + ct, Base64.NO_WRAP)
    }

    /** Null when the blob is not ours (restored from another device, or the key was reset). */
    fun open(sealed: String?): ByteArray? {
        if (sealed.isNullOrEmpty()) return null
        return try {
            val all = Base64.decode(sealed, Base64.NO_WRAP)
            val ivLen = all[0].toInt() and 0xFF
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 1, ivLen))
            cipher.doFinal(all, 1 + ivLen, all.size - 1 - ivLen)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open sealed Libre 3 secret: ${t.javaClass.simpleName}")
            null
        }
    }
}

/** Connection metadata the NFC scan established for one sensor. */
data class Libre3SensorRecord(
    val sensorId: String,
    val bleAddress: String,
    val blePin: ByteArray,
    val startTimeMs: Long,
    val warmupMinutes: Int,
    val wearMinutes: Int,
    val receiverId: Long,
    val firmware: String,
    val securityVersion: Int,
) {
    val expectedEndMs: Long get() = if (startTimeMs > 0L && wearMinutes > 0) startTimeMs + wearMinutes * 60_000L else 0L

    override fun equals(other: Any?): Boolean =
        other is Libre3SensorRecord && sensorId == other.sensorId && bleAddress == other.bleAddress &&
            blePin.contentEquals(other.blePin) && startTimeMs == other.startTimeMs
    override fun hashCode(): Int = sensorId.hashCode()
}

object Libre3Persistence {
    /** Excluded from settings export and import (SettingsExporter.DEVICE_LOCAL_PREFERENCES). */
    const val PREFS_NAME = "tk.glucodata_libre3_managed"
    private const val PREFS = PREFS_NAME
    private const val KEY_SENSORS = "sensors"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RECEIVER_ID = "receiver_id"
    private const val KEY_RECEIVER_SOURCE = "receiver_source"

    /** Where this install's receiver ID comes from. */
    const val RECEIVER_FROM_LIBREVIEW_ACCOUNT = 0
    const val RECEIVER_OWN = 1

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- install-wide settings ----

    /** Route Libre 3 NFC scans to this driver instead of the legacy native one. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getReceiverSource(context: Context): Int = prefs(context).getInt(KEY_RECEIVER_SOURCE, RECEIVER_FROM_LIBREVIEW_ACCOUNT)

    fun setReceiverSource(context: Context, source: Int) {
        prefs(context).edit().putInt(KEY_RECEIVER_SOURCE, source).apply()
    }

    /** This install's own receiver ID, created on first use and kept for every later sensor. */
    @Synchronized
    fun ownReceiverId(context: Context): Long {
        val p = prefs(context)
        val stored = p.getLong(KEY_RECEIVER_ID, 0L)
        if (stored != 0L) return stored
        val fresh = Libre3ReceiverId.random()
        p.edit().putLong(KEY_RECEIVER_ID, fresh).apply()
        return fresh
    }

    fun setOwnReceiverId(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_RECEIVER_ID, value and 0xFFFFFFFFL).apply()
    }

    // ---- identities ----
    //
    // Identity sets are installed as immutable versions (see Libre3IdentityProvisioning). Each
    // identity is referred to as "<version>/<label>", and a sensor stores the reference it paired
    // with, so selecting or installing a newer set never changes the key an existing pairing uses.

    private const val KEY_VERSIONS = "identity_versions"
    private const val KEY_SELECTED = "identity_selected"
    private fun payloadKey(version: String) = "identity_payload.$version"

    data class IdentityVersion(val id: String, val sha256: String, val installedAtMs: Long, val origin: String)

    fun identityVersions(context: Context): List<IdentityVersion> {
        val raw = prefs(context).getString(KEY_VERSIONS, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                IdentityVersion(o.getString("id"), o.getString("sha256"), o.optLong("installedAt"), o.optString("origin"))
            }
        }.getOrDefault(emptyList())
    }

    fun selectedIdentityVersion(context: Context): String? =
        prefs(context).getString(KEY_SELECTED, null)?.takeIf { v -> identityVersions(context).any { it.id == v } }

    fun selectIdentityVersion(context: Context, version: String) {
        require(identityVersions(context).any { it.id == version }) { "identity version $version is not installed" }
        prefs(context).edit().putString(KEY_SELECTED, version).apply()
    }

    fun hasIdentities(context: Context): Boolean = selectedIdentityVersion(context) != null

    private const val KEY_CUSTOM_SOURCE = "identity_custom_source"

    /** A source the user entered; it takes precedence over the build's own. */
    fun customIdentitySource(context: Context): Libre3IdentitySource? =
        Libre3IdentitySource.parse(prefs(context).getString(KEY_CUSTOM_SOURCE, null))

    fun setCustomIdentitySource(context: Context, source: Libre3IdentitySource?) {
        prefs(context).edit().apply { if (source == null) remove(KEY_CUSTOM_SOURCE) else putString(KEY_CUSTOM_SOURCE, source.toSpec()) }.apply()
    }

    /** The build's pinned source (Gradle property libre3IdentitySource), if it names one. */
    val buildIdentitySource: Libre3IdentitySource?
        get() = Libre3IdentitySource.parse(tk.glucodata.BuildConfig.LIBRE3_IDENTITY_SOURCE)

    fun effectiveIdentitySource(context: Context): Libre3IdentitySource? =
        customIdentitySource(context) ?: buildIdentitySource

    /**
     * Downloads and installs [source] unless that exact version is already installed. Blocking:
     * call off the main thread.
     */
    fun provisionIdentities(context: Context, source: Libre3IdentitySource): List<Libre3AppIdentity> {
        identityVersions(context).firstOrNull { it.id == source.version && it.sha256.equals(source.sha256, true) }?.let {
            val installed = loadIdentityVersion(context, it.id)
            if (installed.isNotEmpty()) {
                selectIdentityVersion(context, it.id)
                return installed
            }
        }
        val bytes = Libre3IdentityProvisioning.fetch(source)
        val origin = source.urls.joinToString { runCatching { java.net.URL(it).host }.getOrDefault(it) }
        return installIdentities(context, source.version, source.sha256, bytes, origin)
    }

    /**
     * Installs [bytes] as identity set [version] after checking it hashes to [sha256]. A version
     * id is immutable: reinstalling it with other content is refused. The new set is selected.
     */
    @Synchronized
    fun installIdentities(context: Context, version: String, sha256: String, bytes: ByteArray, origin: String): List<Libre3AppIdentity> {
        val parsed = Libre3IdentityProvisioning.validate(bytes, sha256)
        val versions = identityVersions(context)
        versions.firstOrNull { it.id == version }?.let { existing ->
            if (!existing.sha256.equals(sha256, ignoreCase = true)) {
                throw Libre3IdentityProvisioning.ProvisioningException("version $version is already installed with other content")
            }
        }
        val sealed = Libre3SecretBox.seal(bytes)
        val updated = versions.filterNot { it.id == version } +
            IdentityVersion(version, sha256.lowercase(), System.currentTimeMillis(), origin)
        val array = org.json.JSONArray()
        updated.forEach {
            array.put(org.json.JSONObject().put("id", it.id).put("sha256", it.sha256).put("installedAt", it.installedAtMs).put("origin", it.origin))
        }
        // Payload before metadata: a failed write leaves the previous selection usable.
        prefs(context).edit().putString(payloadKey(version), sealed).commit()
        prefs(context).edit().putString(KEY_VERSIONS, array.toString()).putString(KEY_SELECTED, version).apply()
        return relabel(version, parsed)
    }

    /** A user-picked file: its digest is computed here and becomes its version. */
    fun importIdentities(context: Context, bytes: ByteArray): List<Libre3AppIdentity> {
        val sha = Libre3IdentityProvisioning.sha256Hex(bytes)
        return installIdentities(context, "import-${sha.take(12)}", sha, bytes, "file")
    }

    fun loadIdentityVersion(context: Context, version: String): List<Libre3AppIdentity> {
        val meta = identityVersions(context).firstOrNull { it.id == version } ?: return emptyList()
        val bytes = Libre3SecretBox.open(prefs(context).getString(payloadKey(version), null)) ?: return emptyList()
        return runCatching { relabel(version, Libre3IdentityProvisioning.validate(bytes, meta.sha256)) }
            .onFailure { Log.w("Libre3Persistence", "identity set $version unreadable: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** The selected set. */
    fun loadIdentities(context: Context): List<Libre3AppIdentity> =
        selectedIdentityVersion(context)?.let { loadIdentityVersion(context, it) }.orEmpty()

    /** The set this sensor paired with first, then the selected one. */
    fun loadIdentitiesForSensor(context: Context, sensorId: String): List<Libre3AppIdentity> {
        val pinned = pairedIdentityLabel(context, sensorId)?.substringBefore('/', "")?.takeIf { it.isNotEmpty() }
        val first = pinned?.let { loadIdentityVersion(context, it) }.orEmpty()
        return (first + loadIdentities(context)).distinctBy { it.label }
    }

    private fun relabel(version: String, identities: List<Libre3AppIdentity>): List<Libre3AppIdentity> =
        identities.map { Libre3AppIdentity.create("$version/${it.label}", it.scalarCopy(), it.certificate) }

    /** Removes every installed set and every sensor's cached authorization derived from one. */
    fun clearIdentities(context: Context) {
        val p = prefs(context)
        val edit = p.edit().remove(KEY_VERSIONS).remove(KEY_SELECTED).remove("identities_sealed")
        p.all.keys.filter { it.startsWith("identity_payload.") || it.endsWith(".kauth") }.forEach { edit.remove(it) }
        edit.apply()
    }

    // ---- sensors ----

    fun sensorIds(context: Context): List<String> =
        prefs(context).getStringSet(KEY_SENSORS, emptySet()).orEmpty().sorted()

    fun hasSensor(context: Context, sensorId: String?): Boolean =
        sensorId != null && sensorIds(context).any { it.equals(sensorId, ignoreCase = true) }

    fun saveSensor(context: Context, record: Libre3SensorRecord) {
        val p = prefs(context)
        val ids = p.getStringSet(KEY_SENSORS, emptySet()).orEmpty().toMutableSet()
        ids += record.sensorId
        val k = record.sensorId
        val previous = loadSensor(context, k)
        val edit = p.edit()
            .putStringSet(KEY_SENSORS, ids)
            .putString("$k.address", record.bleAddress)
            .putString("$k.pin", Libre3Bytes.hex(record.blePin))
            .putLong("$k.start", record.startTimeMs)
            .putInt("$k.warmup", record.warmupMinutes)
            .putInt("$k.wear", record.wearMinutes)
            .putLong("$k.receiver", record.receiverId)
            .putString("$k.firmware", record.firmware)
            .putInt("$k.secver", record.securityVersion)
            .putBoolean("$k.ended", false)
        // Every A0/A8 starts a new pairing on the sensor's side, so the cached kAuth goes (the
        // legacy native record clears haskAuth on each scan for the same reason). Backfill marks
        // survive a rescan of the same activation and are reset only for a different one.
        edit.remove("$k.kauth")
        if (previous == null || previous.startTimeMs != record.startTimeMs) {
            edit.remove("$k.lastLife").remove("$k.lastHist").remove("$k.identity").remove("$k.anchor")
        }
        edit.apply()
    }

    fun loadSensor(context: Context, sensorId: String): Libre3SensorRecord? {
        val p = prefs(context)
        val k = sensorId
        val address = p.getString("$k.address", null) ?: return null
        val pin = Libre3Bytes.parseHex(p.getString("$k.pin", null)) ?: return null
        return Libre3SensorRecord(
            sensorId = sensorId,
            bleAddress = address,
            blePin = pin,
            startTimeMs = p.getLong("$k.start", 0L),
            warmupMinutes = p.getInt("$k.warmup", 60),
            wearMinutes = p.getInt("$k.wear", 0),
            receiverId = p.getLong("$k.receiver", 0L),
            firmware = p.getString("$k.firmware", "") ?: "",
            securityVersion = p.getInt("$k.secver", 0),
        )
    }

    fun removeSensor(context: Context, sensorId: String) {
        val p = prefs(context)
        val ids = p.getStringSet(KEY_SENSORS, emptySet()).orEmpty().filterNot { it.equals(sensorId, ignoreCase = true) }.toSet()
        val edit = p.edit().putStringSet(KEY_SENSORS, ids)
        p.all.keys.filter { it.startsWith("$sensorId.") }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** The kAuth Phase 5 used on the last successful pairing, with the identity it was derived under. */
    fun loadCachedAuth(context: Context, sensorId: String): Pair<ByteArray, String>? {
        val p = prefs(context)
        val key = Libre3SecretBox.open(p.getString("$sensorId.kauth", null)) ?: return null
        val label = p.getString("$sensorId.identity", null) ?: return null
        return if (key.size == 16) key to label else null
    }

    fun saveCachedAuth(context: Context, sensorId: String, kAuth: ByteArray, identityLabel: String) {
        prefs(context).edit()
            .putString("$sensorId.kauth", Libre3SecretBox.seal(kAuth))
            .putString("$sensorId.identity", identityLabel)
            .apply()
    }

    fun clearCachedAuth(context: Context, sensorId: String) {
        prefs(context).edit().remove("$sensorId.kauth").apply()
    }

    /** The identity this sensor last authorized with; tried first so a reconnect keeps its pairing. */
    fun pairedIdentityLabel(context: Context, sensorId: String): String? =
        prefs(context).getString("$sensorId.identity", null)

    fun getLastLifeCount(context: Context, sensorId: String): Int = prefs(context).getInt("$sensorId.lastLife", 0)
    fun getLastHistoricLifeCount(context: Context, sensorId: String): Int = prefs(context).getInt("$sensorId.lastHist", 0)

    fun setBackfillMarks(context: Context, sensorId: String, lastLife: Int, lastHistoric: Int) {
        prefs(context).edit().putInt("$sensorId.lastLife", lastLife).putInt("$sensorId.lastHist", lastHistoric).apply()
    }

    /** Phone time of the sensor's life count 0, learned from realtime frames. */
    fun getAnchor(context: Context, sensorId: String): Long = prefs(context).getLong("$sensorId.anchor", 0L)

    fun setAnchor(context: Context, sensorId: String, anchorMs: Long) {
        prefs(context).edit().putLong("$sensorId.anchor", anchorMs).apply()
    }

    fun isEnded(context: Context, sensorId: String): Boolean = prefs(context).getBoolean("$sensorId.ended", false)

    fun setEnded(context: Context, sensorId: String, ended: Boolean) {
        prefs(context).edit().putBoolean("$sensorId.ended", ended).apply()
    }
}
