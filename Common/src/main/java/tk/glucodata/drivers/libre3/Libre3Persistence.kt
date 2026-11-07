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
    private const val KEY_IDENTITIES = "identities_sealed"

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

    fun hasIdentities(context: Context): Boolean = prefs(context).contains(KEY_IDENTITIES)

    fun loadIdentities(context: Context): List<Libre3AppIdentity> {
        val plain = Libre3SecretBox.open(prefs(context).getString(KEY_IDENTITIES, null)) ?: return emptyList()
        return runCatching { Libre3AppIdentity.parseJson(String(plain, Charsets.UTF_8)) }
            .onFailure { Log.w("Libre3Persistence", "stored identities unreadable: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Validates first; nothing is replaced unless every identity in [json] is well formed. */
    fun importIdentities(context: Context, json: String): List<Libre3AppIdentity> {
        val parsed = Libre3AppIdentity.parseJson(json)
        val sealed = Libre3SecretBox.seal(Libre3AppIdentity.toJson(parsed).toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(KEY_IDENTITIES, sealed).apply()
        return parsed
    }

    fun clearIdentities(context: Context) {
        prefs(context).edit().remove(KEY_IDENTITIES).apply()
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
