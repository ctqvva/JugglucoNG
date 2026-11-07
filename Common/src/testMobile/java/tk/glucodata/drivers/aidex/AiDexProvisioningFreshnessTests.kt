package tk.glucodata.drivers.aidex

import android.app.Application
import android.content.Context
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AiDexProvisioningFreshnessTests {
    @Test fun aNewerStoredValueRejectsTheDelayedCommitBeforeKeystoreOrRegistryMutation() {
        val context = RuntimeEnvironment.getApplication()
        val serial = "BCTEST0001"
        val digest = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val prefs = context.getSharedPreferences("aidex_cn_provisioning", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("material_$digest", "opaque-encrypted-original").commit()
        val before = AiDexProvisioningStore.savedMaterialRevision(context, serial)
        prefs.edit().putString("material_$digest", "opaque-encrypted-manual-import").commit()
        AiDexPairingMaterialRegistry.clearForTests()

        assertFalse(AiDexProvisioningStore.saveAndInstallIfUnchanged(
            context, serial, ByteArray(16), ByteArray(16), before,
        ))
        assertEquals("opaque-encrypted-manual-import", AiDexProvisioningStore.savedMaterialRevision(context, serial))
        assertNull(AiDexPairingMaterialRegistry.find(serial))
    }
}
