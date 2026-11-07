package tk.glucodata.drivers.aidex

import android.content.Context
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairKeyVault
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry

/** Setup policy only; product labels never change the driver's cryptographic strategy. */
internal object AiDexSetupKeyGate {
    private val fName = Regex("(?i)(?:^|\\s)(?:AIDEX\\s+)?F\\s*-\\s*[A-Z0-9]{8,14}(?:$|\\s)")

    fun requiresKeys(context: Context, serial: String, advertisedName: String): Boolean {
        val bare = AiDexPairingMaterialFile.normalizeSerial(serial) ?: return false
        val prefs = context.applicationContext.getSharedPreferences("aidex_setup_key_requirements", Context.MODE_PRIVATE)
        // Some F-series batches have G/H serials. Retain the observed product label even
        // when a later selection has only the stored serial and no advertisement name.
        if (bare.startsWith("22222F") || fName.containsMatchIn(advertisedName)) {
            if (!prefs.getBoolean(bare, false)) prefs.edit().putBoolean(bare, true).apply()
            return true
        }
        return prefs.getBoolean(bare, false)
    }

    fun hasKeys(context: Context, serial: String): Boolean =
        AiDexProvisioningStore.hasSaved(context, serial) ||
            AiDexPairingMaterialRegistry.find(serial) != null ||
            AiDexPairKeyVault.load(context, serial) != null

    fun mayConnect(context: Context, serial: String, advertisedName: String): Boolean =
        !requiresKeys(context, serial, advertisedName) || hasKeys(context, serial)
}
