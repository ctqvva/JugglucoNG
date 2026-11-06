package tk.glucodata.share.lumiflex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Local entry draft, independent of the authenticated account. Excluded from settings exports. */
class LumiflexCloudCredentials(
    context: Context,
    private val keyProvider: () -> SecretKey = { deviceKey() },
) {
    data class Draft(val account: String = "", val password: String = "")

    private val prefs = context.applicationContext.getSharedPreferences("lumiflex_cloud", Context.MODE_PRIVATE)
    private val key by lazy { keyProvider() }

    fun load(): Draft = runCatching {
        val stored = prefs.getString(KEY_DRAFT, null) ?: return Draft()
        val parts = stored.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        val json = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8))
        Draft(json.optString("account"), json.optString("password"))
    }.getOrDefault(Draft())

    /** Never falls back to plaintext if the device keystore is unavailable. */
    fun save(account: String, password: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val plain = JSONObject().put("account", account).put("password", password).toString()
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        prefs.edit().putString(KEY_DRAFT, "$iv:$encrypted").apply()
        true
    }.getOrDefault(false)

    companion object {
        internal const val KEY_DRAFT = "credential_draft_encrypted"
        private const val KEY_ALIAS = "juggluco_lumiflex_credentials"

        @Synchronized
        private fun deviceKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
    }
}
