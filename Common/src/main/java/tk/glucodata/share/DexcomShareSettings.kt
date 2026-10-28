package tk.glucodata.share

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.Keep
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import tk.glucodata.Log

/**
 * The Dexcom Share settings and the state the settings screen shows.
 *
 * The account and the password live AES/GCM-encrypted under an AndroidKeyStore key, the same way
 * the AiDEX provisioning store keeps its token — this file copies that pattern rather than
 * importing it, so a change to the AiDEX store can never break the Dexcom one.
 *
 * [isEnabled] is read on the per-reading hot path in `SuperGattCallback`, so it must stay a plain
 * boolean read with no decryption and no allocation.
 */
@Keep
object DexcomShareSettings {
    private const val LOG_ID = "DexcomShare"
    private const val PREFS = "dexcom_share"
    private const val KEY_ALIAS = "juggluco_dexcom_share"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_REGION = "region"
    private const val KEY_SERIAL = "serial"
    private const val KEY_ENABLED_AT_MS = "enabled_at_ms"
    private const val KEY_QUEUE_JSON = "queue_json"
    private const val KEY_PENDING_COUNT = "pending_count"
    private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
    private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
    private const val KEY_LAST_READING_MS = "last_uploaded_reading_ms"
    private const val KEY_LAST_CODE = "last_code"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_AUTH_BLOCKED = "auth_blocked"

    data class Config(
        val enabled: Boolean,
        val account: String,
        val password: String,
        val region: DexcomShareRegion,
        val serial: String
    ) {
        fun isReady(): Boolean =
            enabled && account.isNotBlank() && password.isNotBlank() && serial.isNotBlank()

        /**
         * Whether a login verdict taken for [other] still describes this configuration.
         *
         * The on/off switch is deliberately not part of it: a password Dexcom rejected is
         * rejected whether Share is currently on or off. The serial must already be normalized.
         */
        fun sameLoginTarget(other: Config): Boolean =
            account == other.account &&
                password == other.password &&
                region == other.region &&
                serial == other.serial
    }

    data class Status(
        val lastAttemptMs: Long,
        val lastSuccessMs: Long,
        val lastUploadedReadingMs: Long,
        val lastCode: Int,
        val lastError: String,
        val authBlocked: Boolean,
        val pendingCount: Int
    )

    @JvmStatic
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Switched on, or an account was saved: the settings entry stays reachable either way. */
    fun isSetUp(context: Context): Boolean =
        isEnabled(context) || readEncrypted(context, KEY_ACCOUNT).isNotBlank()

    fun load(context: Context): Config {
        val stored = prefs(context)
        return Config(
            enabled = stored.getBoolean(KEY_ENABLED, false),
            account = normalizeAccount(readEncrypted(context, KEY_ACCOUNT)),
            password = readEncrypted(context, KEY_PASSWORD),
            region = regionOf(stored.getString(KEY_REGION, null)),
            serial = normalizeSerial(stored.getString(KEY_SERIAL, null).orEmpty())
        )
    }

    /**
     * Stores the credentials and clears what belonged to the old ones: the pending queue, the
     * moment Share was switched on, and — when the account or the password actually changed — the
     * blocked-login flag.
     *
     * Enabling the feature starts the backfill window at that moment, so history from before it is
     * never uploaded. The blocked-login flag is released by an edit of the credentials, not by
     * every save: clearing it on a save that changes nothing would spend a login attempt with the
     * password Dexcom has already rejected.
     *
     * The queue belongs to one destination. When the account, the region or the receiver serial
     * changes, the queue is cleared: its pending readings and its memory of what was delivered
     * describe the old destination, and kept they would send the old account's backlog to the new
     * one and refuse readings the new one never received. A password-only correction keeps it.
     *
     * The session id is not stored here — it lives in memory in [DexcomShareUploader]. Call this
     * through [DexcomShareUploader.applySettings], which fences off results of the old settings.
     */
    fun save(context: Context, rawConfig: Config) {
        val config = rawConfig.copy(account = normalizeAccount(rawConfig.account))
        val wasEnabled = isEnabled(context)
        val stored = load(context)
        val credentialsChanged =
            stored.account != config.account || stored.password != config.password
        val destinationChanged = stored.account != config.account ||
            stored.region != config.region ||
            stored.serial != normalizeSerial(config.serial)
        val encryptedAccount = encrypt(config.account)
        val encryptedPassword = encrypt(config.password)
        if (encryptedAccount == null || encryptedPassword == null) {
            Log.e(LOG_ID, "could not store the Dexcom Share credentials in the keystore")
            return
        }
        runCatching {
            val editor = prefs(context).edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_ACCOUNT, encryptedAccount)
                .putString(KEY_PASSWORD, encryptedPassword)
                .putString(KEY_REGION, config.region.name)
                .putString(KEY_SERIAL, normalizeSerial(config.serial))
            if (credentialsChanged) editor.putBoolean(KEY_AUTH_BLOCKED, false)
            if (config.enabled && !wasEnabled) {
                editor.putLong(KEY_ENABLED_AT_MS, System.currentTimeMillis())
            }
            if ((config.enabled && !wasEnabled) || destinationChanged) {
                editor.remove(KEY_QUEUE_JSON)
                editor.putInt(KEY_PENDING_COUNT, 0)
            }
            editor.commit()
        }.onFailure { Log.stack(LOG_ID, "save", it) }
    }

    /**
     * A keyboard suggestion often leaves a trailing space, and Dexcom answers that with "invalid
     * password". The password is left exactly as typed: spaces may belong to it.
     */
    @JvmStatic
    fun normalizeAccount(raw: String): String = raw.trim()

    @JvmStatic
    fun normalizeSerial(raw: String): String {
        val trimmed = raw.trim().uppercase(Locale.ROOT)
        return trimmed.ifEmpty { DexcomShareJson.DEFAULT_RECEIVER_SERIAL }
    }

    fun recordSuccess(context: Context, count: Int, newestMs: Long) {
        val now = System.currentTimeMillis()
        runCatching {
            val editor = prefs(context).edit()
                .putLong(KEY_LAST_ATTEMPT_MS, now)
                .putLong(KEY_LAST_SUCCESS_MS, now)
                .putInt(KEY_LAST_CODE, 200)
                .remove(KEY_LAST_ERROR)
                .putBoolean(KEY_AUTH_BLOCKED, false)
            if (newestMs > 0L) editor.putLong(KEY_LAST_READING_MS, newestMs)
            if (count > 0) {
                val left = prefs(context).getInt(KEY_PENDING_COUNT, 0) - count
                editor.putInt(KEY_PENDING_COUNT, left.coerceAtLeast(0))
            }
            editor.apply()
        }.onFailure { Log.stack(LOG_ID, "recordSuccess", it) }
    }

    fun recordFailure(context: Context, code: Int, message: String) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis())
                .putInt(KEY_LAST_CODE, code)
                .putString(KEY_LAST_ERROR, message)
                .apply()
        }.onFailure { Log.stack(LOG_ID, "recordFailure", it) }
    }

    fun status(context: Context): Status {
        val stored = prefs(context)
        return Status(
            lastAttemptMs = stored.getLong(KEY_LAST_ATTEMPT_MS, 0L),
            lastSuccessMs = stored.getLong(KEY_LAST_SUCCESS_MS, 0L),
            lastUploadedReadingMs = stored.getLong(KEY_LAST_READING_MS, 0L),
            lastCode = stored.getInt(KEY_LAST_CODE, 0),
            lastError = stored.getString(KEY_LAST_ERROR, null).orEmpty(),
            authBlocked = stored.getBoolean(KEY_AUTH_BLOCKED, false),
            pendingCount = stored.getInt(KEY_PENDING_COUNT, 0)
        )
    }

    @JvmStatic
    fun setAuthBlocked(context: Context, blocked: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_AUTH_BLOCKED, blocked).apply()
        }.onFailure { Log.stack(LOG_ID, "setAuthBlocked", it) }
    }

    @JvmStatic
    fun isAuthBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTH_BLOCKED, false)

    fun recordPending(context: Context, count: Int) {
        runCatching {
            prefs(context).edit().putInt(KEY_PENDING_COUNT, count).apply()
        }.onFailure { Log.stack(LOG_ID, "recordPending", it) }
    }

    /** The moment Share was switched on; history older than this is never uploaded. */
    fun enabledAtMs(context: Context): Long = prefs(context).getLong(KEY_ENABLED_AT_MS, 0L)

    fun queueJson(context: Context): String? = prefs(context).getString(KEY_QUEUE_JSON, null)

    fun saveQueueJson(context: Context, json: String) {
        runCatching {
            prefs(context).edit().putString(KEY_QUEUE_JSON, json).apply()
        }.onFailure { Log.stack(LOG_ID, "saveQueueJson", it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun regionOf(name: String?): DexcomShareRegion =
        if (name == DexcomShareRegion.US.name) DexcomShareRegion.US else DexcomShareRegion.OUS

    private fun readEncrypted(context: Context, key: String): String {
        val encrypted = prefs(context).getString(key, null) ?: return ""
        return decrypt(encrypted).orEmpty()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), SecureRandom())
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        "$iv:$ciphertext"
    }.getOrNull()

    private fun decrypt(value: String): String? = runCatching {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}
