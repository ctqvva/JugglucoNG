package tk.glucodata.share.ican

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.Keep
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Keep
class ICanHealthCloudSettings(
    context: Context,
    val passwordCipher: PasswordCipher = AndroidKeystorePasswordCipher,
) {
    interface PasswordCipher {
        fun encrypt(value: String): String
        fun decrypt(value: String): String
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ACCOUNT, value).apply()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
        }

    var tenantId: String
        get() = prefs.getString(KEY_TENANT_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TENANT_ID, value).apply()
        }

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_ID, value).apply()
        }

    var lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_MS, value).apply()
        }

    var password: String
        get() {
            val raw = prefs.getString(KEY_PASSWORD, "") ?: ""
            if (raw.isEmpty()) return ""
            return passwordCipher.decrypt(raw)
        }
        set(value) {
            if (value.isEmpty()) {
                prefs.edit().remove(KEY_PASSWORD).apply()
            } else {
                prefs.edit().putString(KEY_PASSWORD, passwordCipher.encrypt(value)).apply()
            }
        }

    internal data class Session(val token: String, val accountKey: String)

    internal val accountKey: String
        get() = accountKey(tenantId, userId, account)

    private fun accountKey(tenant: String, user: String, account: String): String {
        val identity = org.json.JSONArray().put(tenant).put(
            user.ifBlank { account.trim().lowercase(java.util.Locale.ROOT) },
        ).toString()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    internal fun session(): Session = synchronized(sessionLock) { Session(token, accountKey) }

    fun installLogin(account: String, password: String, result: ICanHealthCloudClient.LoginResult.Ok) {
        val encryptedPassword = passwordCipher.encrypt(password)
        synchronized(sessionLock) {
            val nextAccount = accountKey(result.model.tenantId, result.model.userId, account)
            val progress = if (accountKey == nextAccount) lastSyncMs else 0L
            prefs.edit()
                .putString(KEY_ACCOUNT, account.trim())
                .putString(KEY_TOKEN, result.sessionId)
                .putString(KEY_REFRESH_TOKEN, result.model.refreshToken)
                .putString(KEY_USER_ID, result.model.userId)
                .putString(KEY_TENANT_ID, result.model.tenantId)
                .putString(KEY_PASSWORD, encryptedPassword)
                .putLong(KEY_LAST_SYNC_MS, progress)
                .apply()
        }
    }

    fun clear() = synchronized(sessionLock) {
        prefs.edit().clear().apply()
    }

    companion object {
        internal val sessionLock = Any()
        private const val PREFS = "ican_cloud"
        private const val KEY_ENABLED = "ican_cloud_enabled"

        @JvmStatic
        fun isEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        @JvmStatic
        fun isSetUp(context: Context): Boolean =
            isEnabled(context) || context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACCOUNT, "").orEmpty().isNotBlank()
        private const val KEY_ACCOUNT = "ican_cloud_account"
        private const val KEY_TOKEN = "ican_cloud_token"
        private const val KEY_REFRESH_TOKEN = "ican_cloud_refresh_token"
        private const val KEY_TENANT_ID = "ican_cloud_tenant_id"
        private const val KEY_USER_ID = "ican_cloud_user_id"
        private const val KEY_LAST_SYNC_MS = "ican_cloud_last_sync_ms"
        private const val KEY_PASSWORD = "ican_cloud_password"
    }
}

object AndroidKeystorePasswordCipher : ICanHealthCloudSettings.PasswordCipher {
    private const val KEY_ALIAS = "juggluco_ican_cloud"

    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), SecureRandom())
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        return "$iv:$ciphertext"
    }

    override fun decrypt(value: String): String = runCatching {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrDefault("")

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
