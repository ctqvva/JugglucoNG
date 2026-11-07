package tk.glucodata.drivers.aidex.bloodcontrol

import android.content.Context
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.drivers.aidex.AiDexCnCloudClient
import tk.glucodata.drivers.aidex.AiDexCnResult
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexProvisionedKeys

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
        val result = requestKeys(context, serial)
        val keys = result.keys ?: return result
        return if (AiDexProvisioningStore.saveAndInstall(context, serial, keys.secret, keys.iv)) result
        else BloodControlKeyResult(error = "Не удалось сохранить ключи сенсора")
    }

    /** Fetch only. Automatic callers must validate their exchange before saving anything. */
    @Synchronized
    private fun requestKeys(context: Context, serial: String): BloodControlKeyResult {
        if (!isRussian(context)) return BloodControlKeyResult(error = "BloodControl API недоступен")
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
        return instance.request(serial)
    }

    /** Called only after a rejected local F001 exchange. CN account gets the first attempt. */
    class AutomaticResult(
        val keys: AiDexProvisionedKeys?,
        val usedBloodControl: Boolean,
        val retryAtMillis: Long? = null,
    )

    fun automaticAfterLocalFailure(context: Context, serial: String, tryCn: Boolean): AutomaticResult =
        recoverAfterLocalFailure(tryCn, cn = {
            val token = AiDexProvisioningStore.token(context)
            if (token.isBlank()) null else {
                requestCnKeys(token,
                    request = { AiDexCnCloudClient.getProvisionedKeys(serial, it) },
                    expire = { AiDexProvisioningStore.clearSessionIfMatches(context, it) })
            }
        }, bloodControl = { requestKeys(context, serial) })

    internal fun recoverAfterLocalFailure(
        tryCn: Boolean,
        cn: () -> AiDexProvisionedKeys?,
        bloodControl: () -> BloodControlKeyResult,
    ): AutomaticResult {
        val cnKeys = if (tryCn) cn() else null
        if (cnKeys != null) return AutomaticResult(cnKeys, false)
        val bc = bloodControl()
        return AutomaticResult(bc.keys, true, bc.retryAtMillis)
    }

    internal fun requestCnKeys(
        token: String,
        request: (String) -> AiDexCnResult<AiDexProvisionedKeys>,
        expire: (String) -> Unit,
    ): AiDexProvisionedKeys? {
        val result = request(token)
        if (result.code == 800) expire(token)
        return result.value
    }
}
