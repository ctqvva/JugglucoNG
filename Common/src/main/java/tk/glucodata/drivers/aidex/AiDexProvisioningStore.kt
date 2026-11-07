package tk.glucodata.drivers.aidex

import android.content.Context
import android.content.SharedPreferences
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
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry

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

    @Synchronized
    fun saveSession(context: Context, accountLabel: String, token: String): Boolean {
        if (token.isBlank()) return false
        val encryptedToken = encrypt(token) ?: return false
        val encryptedAccount = encrypt(accountLabel) ?: return false
        return prefs(context).edit()
            .putString(TOKEN, encryptedToken)
            .putString(ACCOUNT, encryptedAccount)
            .commit()
    }

    @Synchronized
    fun clearSession(context: Context) {
        prefs(context).edit().remove(TOKEN).remove(ACCOUNT).apply()
    }

    fun addSessionListener(context: Context, onChanged: () -> Unit): () -> Unit {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TOKEN) onChanged()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Persist only complete material, then install a defensive copy into the live registry. */
    @Synchronized
    fun saveAndInstall(
        context: Context,
        serial: String,
        secret: ByteArray,
        iv: ByteArray,
    ): Boolean {
        if (secret.size != 16 || iv.size != 16) return false
        val normalized = AiDexPairingMaterialFile.normalizeSerial(serial) ?: return false
        val value = JSONObject()
            .put("serialNumber", normalized)
            .put("secret", Base64.getEncoder().encodeToString(secret))
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .toString()
        val encryptedValue = encrypt(value) ?: return false
        if (!prefs(context).edit().putString(materialKey(serial), encryptedValue).commit()) return false
        return AiDexNativeFactory.installProvisionedPairingMaterial(serial, secret, iv)
    }

    fun hasSaved(context: Context, serial: String): Boolean = loadSaved(context, serial) != null

    /** The serial lives inside the encrypted record, alongside its keys. Old records
     * without it can still be found through known/selected sensor identities. */
    fun savedSerials(context: Context, knownSerials: Collection<String> = emptyList()): List<String> {
        val identified = prefs(context).all.keys.filter { it.startsWith(MATERIAL_PREFIX) }.mapNotNull { key ->
            val json = runCatching { JSONObject(readEncrypted(context, key)) }.getOrNull() ?: return@mapNotNull null
            val serial = AiDexPairingMaterialFile.normalizeSerial(json.optString("serialNumber"))
                ?: return@mapNotNull null
            serial.takeIf { materialKey(it) == key && loadSaved(context, it) != null }
        }
        return (identified + knownSerials.mapNotNull(AiDexPairingMaterialFile::normalizeSerial)
            .filter { hasSaved(context, it) }).distinct().sorted()
    }

    @Synchronized
    fun removeSaved(context: Context, serial: String): Boolean {
        if (!prefs(context).edit().remove(materialKey(serial)).commit()) return false
        AiDexPairingMaterialRegistry.remove(serial)
        return true
    }

    fun exportJson(context: Context, serial: String): String? {
        val material = loadSaved(context, serial) ?: return null
        return AiDexPairingMaterialFile.encode(serial, material.secret, material.iv)
    }

    fun importJson(context: Context, json: String): String? {
        val parsed = AiDexPairingMaterialFile.decode(json) ?: return null
        return parsed.serial.takeIf {
            saveAndInstall(context, parsed.serial, parsed.secret, parsed.iv)
        }
    }

    /** Restore material before a BLE manager constructs its F001 key-exchange state. */
    fun installSaved(context: Context, serial: String): Boolean {
        val material = loadSaved(context, serial) ?: return false
        return AiDexNativeFactory.installProvisionedPairingMaterial(
            serial,
            material.secret,
            material.iv,
        )
    }

    @Synchronized
    fun loadSaved(context: Context, serial: String): AiDexProvisionedKeys? {
        val value = readEncrypted(context, materialKey(serial)).takeIf(String::isNotBlank) ?: return null
        val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (json.has("serialNumber") &&
            AiDexPairingMaterialFile.normalizeSerial(json.optString("serialNumber")) !=
            AiDexPairingMaterialFile.normalizeSerial(serial)) return null
        val secret = decodeMaterial(json.optString("secret")) ?: return null
        val iv = decodeMaterial(json.optString("iv")) ?: return null
        // Original records had only a hashed preference key and no recoverable serial.
        // Once a known/selected identity finds one, index it inside the encrypted value.
        // A metadata-write failure must not make otherwise valid keys unavailable.
        if (!json.has("serialNumber")) {
            AiDexPairingMaterialFile.normalizeSerial(serial)?.let { normalized ->
                encrypt(json.put("serialNumber", normalized).toString())?.let { encrypted ->
                    prefs(context).edit().putString(materialKey(serial), encrypted).commit()
                }
            }
        }
        return AiDexProvisionedKeys(secret, iv)
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
