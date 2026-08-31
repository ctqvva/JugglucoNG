package tk.glucodata.drivers.aidex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Keystore-encrypted persistence for the CN session and already-provisioned sensor keys. */
internal object AiDexProvisioningStore {
    private const val PREFS = "aidex_cn_provisioning"
    private const val KEY_ALIAS = "juggluco_aidex_cn_provisioning"
    private const val TOKEN = "account_token"
    private const val ACCOUNT = "account_label"
    private const val MATERIAL_PREFIX = "material_"

    fun hasSession(context: Context): Boolean = token(context).isNotBlank()

    fun token(context: Context): String = readEncrypted(context, TOKEN)

    fun accountLabel(context: Context): String = readEncrypted(context, ACCOUNT)

    fun saveSession(context: Context, accountLabel: String, token: String): Boolean {
        if (token.isBlank()) return false
        val encryptedToken = encrypt(token) ?: return false
        val encryptedAccount = encrypt(accountLabel) ?: return false
        return prefs(context).edit()
            .putString(TOKEN, encryptedToken)
            .putString(ACCOUNT, encryptedAccount)
            .commit()
    }

    fun clearSession(context: Context) {
        prefs(context).edit().remove(TOKEN).remove(ACCOUNT).apply()
    }

    /** Persist only complete material, then install a defensive copy into the live registry. */
    fun saveAndInstall(
        context: Context,
        serial: String,
        secret: ByteArray,
        iv: ByteArray,
    ): Boolean {
        if (secret.size != 16 || iv.size != 16) return false
        val value = JSONObject()
            .put("secret", Base64.getEncoder().encodeToString(secret))
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .toString()
        val encryptedValue = encrypt(value) ?: return false
        if (!prefs(context).edit().putString(materialKey(serial), encryptedValue).commit()) return false
        return AiDexNativeFactory.installProvisionedPairingMaterial(serial, secret, iv)
    }

    /** Restore material before a BLE manager constructs its F001 key-exchange state. */
    fun installSaved(context: Context, serial: String): Boolean {
        val value = readEncrypted(context, materialKey(serial)).takeIf(String::isNotBlank) ?: return false
        val json = runCatching { JSONObject(value) }.getOrNull() ?: return false
        val secret = decodeMaterial(json.optString("secret")) ?: return false
        val iv = decodeMaterial(json.optString("iv")) ?: return false
        return AiDexNativeFactory.installProvisionedPairingMaterial(serial, secret, iv)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun materialKey(serial: String): String {
        val canonical = AiDexSerialIdentity.bareSerial(serial).uppercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return MATERIAL_PREFIX + digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }

    private fun decodeMaterial(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value).takeIf { it.size == 16 }
    }.getOrNull()

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
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }
}
