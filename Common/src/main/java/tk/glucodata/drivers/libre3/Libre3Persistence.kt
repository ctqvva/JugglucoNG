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
    /** From the NFC patch info; records saved before it was kept are Libre 3 or 3 Plus. */
    val productType: Int = PRODUCT_LIBRE3,
) {
    /** A Plus shares the Libre 3's product type and differs by its rated wear, 15 days instead of 14. */
    val isPlus: Boolean get() = productType == PRODUCT_LIBRE3 && wearMinutes > LIBRE3_WEAR_MINUTES
    val modelName: String get() = if (isPlus) "Libre 3 Plus" else "Libre 3"
    val expectedEndMs: Long get() = if (startTimeMs > 0L && wearMinutes > 0) startTimeMs + wearMinutes * 60_000L else 0L

    override fun equals(other: Any?): Boolean =
        other is Libre3SensorRecord && sensorId == other.sensorId && bleAddress == other.bleAddress &&
            blePin.contentEquals(other.blePin) && startTimeMs == other.startTimeMs
    override fun hashCode(): Int = sensorId.hashCode()

    companion object {
        const val PRODUCT_LIBRE3 = 4
        private const val LIBRE3_WEAR_MINUTES = 14 * 24 * 60
    }
}

object Libre3Persistence {
    /** Excluded from settings export and import (SettingsExporter.DEVICE_LOCAL_PREFERENCES). */
    const val PREFS_NAME = "tk.glucodata_libre3_managed"
    const val SETTINGS_PREFS_NAME = "tk.glucodata_libre3_settings"
    private const val PREFS = PREFS_NAME
    private const val KEY_SENSORS = "sensors"
    private const val KEY_RECEIVER_ID = "receiver_id"
    private const val KEY_RECEIVER_SOURCE = "receiver_source"

    /** Where this install's receiver ID comes from. */
    const val RECEIVER_FROM_LIBREVIEW_ACCOUNT = 0
    const val RECEIVER_OWN = 1

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Exportable choices; credentials and the stable receiver ID remain device-local. */
    @Synchronized
    private fun settings(context: Context, requireDurableMigration: Boolean = false): SharedPreferences {
        val target = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        val old = prefs(context)
        val keys = listOf(KEY_RECEIVER_SOURCE, KEY_CUSTOM_SOURCE).filter { old.contains(it) }
        if (keys.isNotEmpty()) {
            val out = target.edit()
            for (key in keys.filterNot { target.contains(it) }) {
                when (val value = old.all[key]) {
                    is Boolean -> out.putBoolean(key, value)
                    is Int -> out.putInt(key, value)
                    is String -> out.putString(key, value)
                }
            }
            // SharedPreferences updates memory even when its disk write fails. Keep those
            // choices usable in setup, retaining the old durable copy until a retry succeeds.
            if (!out.commit()) {
                check(!requireDurableMigration) { "could not migrate Libre 3 settings" }
                Log.w("Libre3Persistence", "Settings migration write failed; keeping legacy settings for retry")
                return target
            }
            if (!old.edit().apply { keys.forEach { remove(it) } }.commit()) {
                // The destination is already durable. Stale source keys can be cleaned on
                // reopening; existing destination choices take precedence on the next pass.
                Log.w("Libre3Persistence", "Settings migration cleanup failed; destination settings retained")
            }
        }
        return target
    }

    fun migrateSettingsForExport(context: Context) { settings(context, requireDurableMigration = true) }

    // ---- install-wide settings ----

    fun getReceiverSource(context: Context): Int = settings(context).getInt(KEY_RECEIVER_SOURCE, RECEIVER_FROM_LIBREVIEW_ACCOUNT)

    fun setReceiverSource(context: Context, source: Int) {
        settings(context).edit().putInt(KEY_RECEIVER_SOURCE, source).apply()
    }

    /** This install's own receiver ID, created on first use and kept for every later sensor. */
    @Synchronized
    fun ownReceiverId(context: Context): Long {
        val p = prefs(context)
        val stored = p.getLong(KEY_RECEIVER_ID, 0L)
        // A failed commit can still update SharedPreferences in memory. Recheck
        // durability even for a cached value before NFC is allowed to use it.
        val fresh = stored.takeIf { it != 0L } ?: Libre3ReceiverId.random()
        check(p.edit().putLong(KEY_RECEIVER_ID, fresh).commit()) { "could not persist receiver ID" }
        return fresh
    }

    fun setOwnReceiverId(context: Context, value: Long) {
        check(prefs(context).edit().putLong(KEY_RECEIVER_ID, value and 0xFFFFFFFFL).commit()) { "could not persist receiver ID" }
    }

    // ---- identities ----
    //
    // Identity sets are installed as immutable versions (see Libre3IdentityProvisioning). Each
    // identity is referred to as "<version>/<label>", and a sensor stores the reference it paired
    // with, so selecting or installing a newer set never changes the key an existing pairing uses.

    private const val KEY_PROVISION_BLOCKED = "automatic_provisioning_blocked"

    fun autoProvisionBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROVISION_BLOCKED, credentialRevision(context) > 0L)

    /** Called before NFC can change the sensor. Removal is respected until an explicit retry. */
    fun ensureIdentities(context: Context): Boolean {
        if (loadIdentities(context).isNotEmpty()) return true
        if (autoProvisionBlocked(context)) return false
        val source = effectiveIdentitySource(context) ?: return false
        return provisionIdentities(context, source).isNotEmpty()
    }

    private const val KEY_CREDENTIAL_REVISION = "credential_revision"

    @Synchronized
    fun credentialRevision(context: Context): Long = prefs(context).getLong(KEY_CREDENTIAL_REVISION, 0L)

    data class AuthorizationSnapshot(val revision: Long, val cached: Pair<ByteArray, String>?, val identities: List<Libre3AppIdentity>)

    @Synchronized
    fun authorizationSnapshot(context: Context, sensorId: String): AuthorizationSnapshot =
        AuthorizationSnapshot(credentialRevision(context), loadCachedAuth(context, sensorId), loadIdentitiesForSensor(context, sensorId))

    private fun requireRevision(context: Context, expected: Long) {
        check(expected == credentialRevision(context)) { "credentials changed; retry the operation" }
    }

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

    @Synchronized
    fun selectIdentityVersion(context: Context, version: String) {
        require(identityVersions(context).any { it.id == version }) { "identity version $version is not installed" }
        prefs(context).edit().putString(KEY_SELECTED, version).apply()
    }

    fun hasIdentities(context: Context): Boolean = selectedIdentityVersion(context) != null

    private const val KEY_CUSTOM_SOURCE = "identity_custom_source"

    /** A source the user entered; it takes precedence over the build's own. */
    fun customIdentitySource(context: Context): Libre3IdentitySource? =
        Libre3IdentitySource.parse(settings(context).getString(KEY_CUSTOM_SOURCE, null))

    fun setCustomIdentitySource(context: Context, source: Libre3IdentitySource?) {
        // An explicit reset must also override a legacy value waiting for migration.
        settings(context).edit().putString(KEY_CUSTOM_SOURCE, source?.toSpec().orEmpty()).apply()
    }

    /** The build's pinned source (Gradle property libre3IdentitySource), if it names one. */
    val buildIdentitySource: Libre3IdentitySource?
        get() = Libre3IdentitySource.parse(tk.glucodata.BuildConfig.LIBRE3_IDENTITY_SOURCE)
            ?: Libre3IdentityProvisioning.defaultSource

    fun effectiveIdentitySource(context: Context): Libre3IdentitySource? =
        customIdentitySource(context) ?: buildIdentitySource

    private val provisioningLock = Any()

    /**
     * Downloads and installs [source] unless that exact version is already installed. Blocking:
     * call off the main thread.
     */
    fun provisionIdentities(context: Context, source: Libre3IdentitySource, expectedRevision: Long = credentialRevision(context)): List<Libre3AppIdentity> {
        synchronized(provisioningLock) {
            synchronized(this) { requireRevision(context, expectedRevision) }
            identityVersions(context).firstOrNull { it.id == source.version && it.sha256.equals(source.sha256, true) }?.let {
                val installed = loadIdentityVersion(context, it.id)
                if (installed.isNotEmpty()) {
                    synchronized(this) {
                        requireRevision(context, expectedRevision)
                        selectIdentityVersion(context, it.id)
                        return installed
                    }
                }
            }
            val bytes = Libre3IdentityProvisioning.fetch(source)
            val origin = source.urls.joinToString { runCatching { java.net.URL(it).host }.getOrDefault(it) }
            return installIdentities(context, source.version, source.sha256, bytes, origin, expectedRevision)
        }
    }

    /**
     * Installs [bytes] as identity set [version] after checking it hashes to [sha256]. A version
     * id is immutable: reinstalling it with other content is refused. The new set is selected.
     */
    @Synchronized
    fun installIdentities(context: Context, version: String, sha256: String, bytes: ByteArray, origin: String, expectedRevision: Long = credentialRevision(context)): List<Libre3AppIdentity> {
        requireRevision(context, expectedRevision)
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
        check(prefs(context).edit().putString(payloadKey(version), sealed).commit()) { "could not persist identity payload" }
        check(prefs(context).edit().putString(KEY_VERSIONS, array.toString()).putString(KEY_SELECTED, version).putBoolean(KEY_PROVISION_BLOCKED, false).commit()) {
            "could not persist identity selection"
        }
        val installed = relabel(version, parsed)
        // A driver stopped for missing credentials waits for these; Settings, a file import or a
        // watch handoff would otherwise leave it disconnected until the user reconnects it.
        Libre3Registry.wakeDriversWaitingForCredentials()
        return installed
    }

    /** A user-picked file: its digest is computed here and becomes its version. */
    fun importIdentities(context: Context, bytes: ByteArray, expectedRevision: Long = credentialRevision(context)): List<Libre3AppIdentity> {
        val sha = Libre3IdentityProvisioning.sha256Hex(bytes)
        return installIdentities(context, "import-${sha.take(12)}", sha, bytes, "file", expectedRevision)
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
    @Synchronized
    fun clearIdentities(context: Context) {
        val p = prefs(context)
        val edit = p.edit().putBoolean(KEY_PROVISION_BLOCKED, true).putLong(KEY_CREDENTIAL_REVISION, credentialRevision(context) + 1L).remove(KEY_VERSIONS).remove(KEY_SELECTED).remove("identities_sealed")
        p.all.keys.filter { it.startsWith("identity_payload.") || it.endsWith(".kauth") }.forEach { edit.remove(it) }
        check(edit.commit()) { "could not persist credential removal" }
        tk.glucodata.SensorBluetooth.gattcallbacks.filterIsInstance<Libre3BleManager>().forEach { it.onCredentialsRemoved() }
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
            .putInt("$k.product", record.productType)
            .putBoolean("$k.ended", false)
        // Every A0/A8 starts a new pairing on the sensor's side, so the cached kAuth goes (the
        // legacy native record clears haskAuth on each scan for the same reason). Backfill marks
        // survive a rescan of the same activation and are reset only for a different one.
        edit.remove("$k.kauth")
        if (previous == null || previous.startTimeMs != record.startTimeMs) {
            edit.remove("$k.lastLife").remove("$k.lastHist").remove("$k.identity")
                .remove("$k.anchor").remove("$k.activation")
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
            productType = p.getInt("$k.product", Libre3SensorRecord.PRODUCT_LIBRE3),
        )
    }

    fun removeSensor(context: Context, sensorId: String) {
        val p = prefs(context)
        val ids = p.getStringSet(KEY_SENSORS, emptySet()).orEmpty().filterNot { it.equals(sensorId, ignoreCase = true) }.toSet()
        val edit = p.edit().putStringSet(KEY_SENSORS, ids)
        p.all.keys.filter { it.startsWith("$sensorId.", ignoreCase = true) }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** The kAuth Phase 5 used on the last successful pairing, with the identity it was derived under. */
    fun loadCachedAuth(context: Context, sensorId: String): Pair<ByteArray, String>? {
        val p = prefs(context)
        val key = Libre3SecretBox.open(p.getString("$sensorId.kauth", null)) ?: return null
        val label = p.getString("$sensorId.identity", null) ?: return null
        return if (key.size == 16) key to label else null
    }

    /** Whether a kAuth is stored, without the Keystore round trip [loadCachedAuth] costs. */
    fun hasCachedAuth(context: Context, sensorId: String): Boolean = prefs(context).contains("$sensorId.kauth")

    @Synchronized
    fun saveCachedAuth(context: Context, sensorId: String, kAuth: ByteArray, identityLabel: String, expectedRevision: Long): Boolean {
        if (expectedRevision != credentialRevision(context) || !hasIdentities(context)) return false
        return prefs(context).edit()
            .putString("$sensorId.kauth", Libre3SecretBox.seal(kAuth))
            .putString("$sensorId.identity", identityLabel)
            .commit()
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

    /** Room timestamps holding this driver's five-minute fill-ins (Libre3BackfillMerge). */
    fun getHistoryFillers(context: Context, sensorId: String): MutableSet<Long> =
        Libre3BackfillMerge.decode(prefs(context).getString("$sensorId.fillers", null))

    fun setHistoryFillers(context: Context, sensorId: String, fillers: Collection<Long>) {
        prefs(context).edit().putString("$sensorId.fillers", Libre3BackfillMerge.encode(fillers)).apply()
    }

    /**
     * The activation estimated when the driver took over a sensor without one (its first arrival
     * less [Libre3LifeCount.endMs]). Earlier builds kept the time of life count 0's *start* under
     * "anchor", which is the same grid one minute later; it is read back as such, so readings
     * already stored keep their times.
     */
    fun getAnchor(context: Context, sensorId: String): Long {
        val p = prefs(context)
        p.getLong("$sensorId.activation", 0L).takeIf { it > 0L }?.let { return it }
        return Libre3LifeCount.activationFromLegacyAnchor(p.getLong("$sensorId.anchor", 0L))
    }

    fun setAnchor(context: Context, sensorId: String, anchorMs: Long) {
        prefs(context).edit().putLong("$sensorId.activation", anchorMs).remove("$sensorId.anchor").apply()
    }

    fun isEnded(context: Context, sensorId: String): Boolean = prefs(context).getBoolean("$sensorId.ended", false)

    fun setEnded(context: Context, sensorId: String, ended: Boolean) {
        prefs(context).edit().putBoolean("$sensorId.ended", ended).apply()
    }

    // ---- phone → watch ----

    /**
     * Everything a watch needs to run this sensor's driver: the NFC record, backfill progress,
     * the identity sets it authorizes with and its cached kAuth. Secrets are sealed with this
     * device's Keystore, so they travel unsealed inside the Wear Data Layer's own encryption
     * and the watch seals them again with its key. Null for a sensor this driver does not hold.
     */
    fun exportForWatch(context: Context, sensorId: String): org.json.JSONObject? {
        val record = loadSensor(context, sensorId) ?: return null
        val p = prefs(context)
        val selected = selectedIdentityVersion(context)
        val paired = pairedIdentityLabel(context, sensorId)?.substringBefore('/', "")?.takeIf { it.isNotEmpty() }
        val sets = org.json.JSONArray()
        for (version in listOfNotNull(paired, selected).distinct()) {
            val meta = identityVersions(context).firstOrNull { it.id == version } ?: continue
            val bytes = Libre3SecretBox.open(p.getString(payloadKey(version), null)) ?: continue
            sets.put(org.json.JSONObject().put("id", version).put("sha256", meta.sha256).put("origin", meta.origin)
                .put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        val out = org.json.JSONObject()
            .put("sensor", record.sensorId)
            .put("address", record.bleAddress)
            .put("pin", Libre3Bytes.hex(record.blePin))
            .put("start", record.startTimeMs)
            .put("warmup", record.warmupMinutes)
            .put("wear", record.wearMinutes)
            .put("receiver", record.receiverId)
            .put("firmware", record.firmware)
            .put("secver", record.securityVersion)
            .put("product", record.productType)
            .put("lastLife", getLastLifeCount(context, sensorId))
            .put("lastHist", getLastHistoricLifeCount(context, sensorId))
            .put("activation", getAnchor(context, sensorId))
            // What a watch on an earlier build reads.
            .put("anchor", Libre3LifeCount.legacyAnchor(getAnchor(context, sensorId)))
            .put("fillers", p.getString("$sensorId.fillers", "") ?: "")
            .put("ended", isEnded(context, sensorId))
            .put("selected", selected ?: org.json.JSONObject.NULL)
            .put("identities", sets)
        loadCachedAuth(context, sensorId)?.let { (key, label) -> out.put("kauth", Libre3Bytes.hex(key)).put("kauthIdentity", label) }
        return out
    }

    /** The watch side of [exportForWatch]. Returns the sensor id, or null for an unusable entry. */
    @Synchronized
    fun importFromPhone(context: Context, json: org.json.JSONObject): String? {
        val sensorId = json.optString("sensor").trim().takeIf { it.isNotEmpty() } ?: return null
        val pin = Libre3Bytes.parseHex(json.optString("pin"))?.takeIf { it.size == 4 } ?: return null
        val address = json.optString("address").takeIf { it.isNotBlank() } ?: return null
        val sets = json.optJSONArray("identities") ?: org.json.JSONArray()
        for (i in 0 until sets.length()) {
            val set = sets.optJSONObject(i) ?: continue
            runCatching {
                installIdentities(context, set.getString("id"), set.getString("sha256"),
                    Base64.decode(set.getString("payload"), Base64.NO_WRAP), set.optString("origin", "phone"))
            }.onFailure { Log.w("Libre3Persistence", "identity set from phone not installed: ${it.message}") }
        }
        json.optString("selected").takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { selectIdentityVersion(context, it) } }
        saveSensor(context, Libre3SensorRecord(
            sensorId = sensorId,
            bleAddress = address,
            blePin = pin,
            startTimeMs = json.optLong("start"),
            warmupMinutes = json.optInt("warmup", 60),
            wearMinutes = json.optInt("wear"),
            receiverId = json.optLong("receiver"),
            firmware = json.optString("firmware"),
            securityVersion = json.optInt("secver"),
            productType = json.optInt("product", Libre3SensorRecord.PRODUCT_LIBRE3),
        ))
        // saveSensor starts a fresh pairing; this one carries on where the phone was.
        val edit = prefs(context).edit()
            .putInt("$sensorId.lastLife", json.optInt("lastLife"))
            .putInt("$sensorId.lastHist", json.optInt("lastHist"))
            .putString("$sensorId.fillers", json.optString("fillers"))
            .putBoolean("$sensorId.ended", json.optBoolean("ended"))
        (json.optLong("activation").takeIf { it > 0L }
            ?: Libre3LifeCount.activationFromLegacyAnchor(json.optLong("anchor")).takeIf { it > 0L })
            ?.let { edit.putLong("$sensorId.activation", it).remove("$sensorId.anchor") }
        check(edit.commit()) { "could not persist the sensor from the phone" }
        val key = Libre3Bytes.parseHex(json.optString("kauth"))
        val label = json.optString("kauthIdentity").takeIf { it.isNotEmpty() }
        if (key != null && key.size == 16 && label != null) saveCachedAuth(context, sensorId, key, label, credentialRevision(context))
        return sensorId
    }
}
