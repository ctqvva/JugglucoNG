package tk.glucodata.drivers.aidex.bloodcontrol

import android.content.Context
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.drivers.aidex.AiDexCnCloudClient
import tk.glucodata.drivers.aidex.AiDexProvisioningStore

internal object BloodControlAidex {
    private val credentials by lazy {
        BloodControlCredentials.decode(BuildConfig.BC_AIDEX_BEARER_TOKEN,
            BuildConfig.BC_AIDEX_ENVELOPE_AES_BASE64, BuildConfig.BC_AIDEX_SIGNING_KEY_PKCS8_BASE64,
            BuildConfig.BC_AIDEX_SERVER_PUBLIC_DER_BASE64)
    }
    val available: Boolean get() = credentials != null
    private var provider: BloodControlAidexKeyProvider? = null

    fun isRussian(context: Context): Boolean = context.resources.configuration.locales[0]?.language == "ru"

    /** Product detection gates only this optional fallback, never the local pairing strategy. */
    fun automaticEligible(language: String?, serial: String, advertisedName: String?): Boolean =
        language == "ru" && (BloodControlProtocol.normalizeSerial(serial)?.startsWith("22222F") == true ||
            advertisedName.orEmpty().matches(Regex("(?i)\\s*(?:AIDEX\\s+)?F\\s*-\\s*[A-Z0-9]{8,14}\\s*")))

    @Synchronized
    fun requestAndInstall(context: Context, serial: String, reuseSaved: Boolean = true): BloodControlKeyResult {
        if (!isRussian(context)) return BloodControlKeyResult(error = "BloodControl API недоступен")
        // Imported or previously fetched material always wins over another network request.
        if (reuseSaved && AiDexProvisioningStore.installSaved(context, serial)) return BloodControlKeyResult()
        val credentials = credentials ?: return BloodControlKeyResult(error = "BloodControl API не настроен в этой сборке")
        val instance = provider ?: run {
            val prefs = context.applicationContext.getSharedPreferences("bloodcontrol_aidex", Context.MODE_PRIVATE)
            val cache = object : BloodControlCache {
                override fun string(key: String) = prefs.getString(key, null)
                override fun long(key: String) = prefs.getLong(key, 0)
                override fun put(key: String, value: String) { prefs.edit().putString(key, value).commit() }
                override fun put(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
            }
            BloodControlAidexKeyProvider(credentials,
                BloodControlHttpsTransport(context.resources.openRawResource(R.raw.bloodcontrol_ca).use { it.readBytes() }, credentials.bearer),
                cache).also { provider = it }
        }
        val result = instance.request(serial)
        val keys = result.keys ?: return result
        return if (AiDexProvisioningStore.saveAndInstall(context, serial, keys.secret, keys.iv)) result
        else BloodControlKeyResult(error = "Не удалось сохранить ключи сенсора")
    }

    /** Called only after a rejected local F001 exchange. CN account gets the first attempt. */
    class AutomaticResult(val installed: Boolean, val usedBloodControl: Boolean)

    fun automaticAfterLocalFailure(context: Context, serial: String, tryCn: Boolean): AutomaticResult =
        recoverAfterLocalFailure(tryCn, cn = {
            val token = AiDexProvisioningStore.token(context)
            if (token.isBlank()) false else {
                val result = AiDexCnCloudClient.getProvisionedKeys(serial, token)
                if (result.code == 800) AiDexProvisioningStore.clearSession(context)
                val keys = result.value
                keys != null && AiDexProvisioningStore.saveAndInstall(context, serial, keys.secret, keys.iv)
            }
        }, bloodControl = { requestAndInstall(context, serial, reuseSaved = false).error.isEmpty() })

    internal fun recoverAfterLocalFailure(tryCn: Boolean, cn: () -> Boolean, bloodControl: () -> Boolean): AutomaticResult =
        if (tryCn && cn()) AutomaticResult(true, false) else AutomaticResult(bloodControl(), true)
}
