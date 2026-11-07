package tk.glucodata.drivers.aidex

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairKeyVault
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Keep real encryption/storage; replace only the unavailable device keystore key. */
@Implements(AiDexProvisioningStore::class)
class AiDexTestKeystore {
    @Implementation fun secretKey(): SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [AiDexTestKeystore::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AiDexSavedKeysTests {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val serial = "22222G7SVJ"
    private val secret = ByteArray(16) { it.toByte() }
    private val iv = ByteArray(16) { (it + 16).toByte() }

    @Before fun reset() {
        for (prefs in listOf("aidex_cn_provisioning", "aidex_setup_key_requirements",
            "AiDexPairKeysPrimary", "AiDexPairKeysRecovery", "AiDexNativePrefs")) {
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AiDexPairingMaterialRegistry.clearForTests()
    }

    @Test fun savedSensorsSurviveRegistryLossAndExportImportAndDeleteArePerSensor() {
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, "X-$serial", secret, iv))
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, "22222FY2M8", secret, iv))
        val prefs = context.getSharedPreferences("aidex_cn_provisioning", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it.toString().contains(serial) })
        AiDexPairingMaterialRegistry.clearForTests()
        assertEquals(listOf("22222FY2M8", serial), AiDexProvisioningStore.savedSerials(context))
        val exported = AiDexProvisioningStore.exportJson(context, serial)!!
        assertTrue(AiDexProvisioningStore.removeSaved(context, serial))
        assertFalse(AiDexProvisioningStore.hasSaved(context, serial))
        assertNull(AiDexPairingMaterialRegistry.find(serial))
        assertTrue(AiDexProvisioningStore.hasSaved(context, "22222FY2M8"))
        assertEquals(serial, AiDexProvisioningStore.importJson(context, exported))
        assertArrayEquals(secret, AiDexProvisioningStore.loadSaved(context, serial)!!.secret)
    }

    @Test fun oldUnindexedRecordIsStillFoundForAKnownOrSelectedSensor() {
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, serial, secret, iv))
        val prefs = context.getSharedPreferences("aidex_cn_provisioning", Context.MODE_PRIVATE)
        val key = prefs.all.keys.single()
        val decrypt = AiDexProvisioningStore::class.java.getDeclaredMethod("decrypt", String::class.java).apply { isAccessible = true }
        val encrypt = AiDexProvisioningStore::class.java.getDeclaredMethod("encrypt", String::class.java).apply { isAccessible = true }
        val legacy = JSONObject(decrypt.invoke(AiDexProvisioningStore, prefs.getString(key, "")) as String)
            .apply { remove("serialNumber") }
        prefs.edit().putString(key, encrypt.invoke(AiDexProvisioningStore, legacy.toString()) as String).commit()
        assertEquals(listOf(serial), AiDexProvisioningStore.savedSerials(context, listOf("X-$serial")))
        assertEquals(listOf(serial), AiDexProvisioningStore.savedSerials(context))
        assertArrayEquals(secret, AiDexProvisioningStore.loadSaved(context, serial)!!.secret)
    }

    @Test fun keylessFSelectionIsBlockedAndProvisioningUnlocksIt() {
        assertFalse(AiDexSetupKeyGate.mayConnect(context, serial, "AiDEX F-$serial"))
        assertFalse(AiDexSetupKeyGate.mayConnect(context, serial, ""))
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, serial, secret, iv))
        AiDexPairingMaterialRegistry.clearForTests()
        assertTrue(AiDexSetupKeyGate.mayConnect(context, serial, ""))
        assertFalse(AiDexSetupKeyGate.mayConnect(context, "22222FXYT3", ""))
        assertTrue(AiDexSetupKeyGate.mayConnect(context, "2222267V4E", "AiDEX X-2222267V4E"))
    }

    @Test fun anExistingValidatedPairCredentialPermitsAnFSeriesReconnect() {
        assertTrue(AiDexPairKeyVault.saveValidated(context, serial, ByteArray(16) { 42 }))
        assertTrue(AiDexSetupKeyGate.mayConnect(context, serial, "F-$serial"))
    }

    @Test fun swappedEncryptedRecordsCannotUnlockAnotherSensor() {
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, serial, secret, iv))
        val prefs = context.getSharedPreferences("aidex_cn_provisioning", Context.MODE_PRIVATE)
        val firstKey = prefs.all.keys.single()
        val firstValue = prefs.getString(firstKey, "")
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, "22222FXYT3", secret, iv))
        val secondKey = prefs.all.keys.first { it != firstKey }
        prefs.edit().putString(secondKey, firstValue).commit()
        AiDexPairingMaterialRegistry.clearForTests()
        assertNull(AiDexProvisioningStore.loadSaved(context, "22222FXYT3"))
        assertFalse(AiDexSetupKeyGate.mayConnect(context, "22222FXYT3", ""))
    }
}
