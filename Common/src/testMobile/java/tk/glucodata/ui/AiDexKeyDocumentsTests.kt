package tk.glucodata.ui

import android.app.Application
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.RestrictedMode
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexTestKeystore
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry
import tk.glucodata.ui.setup.AiDexKeyDocuments

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [AiDexTestKeystore::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AiDexKeyDocumentsTests {
    private val context get() = RuntimeEnvironment.getApplication()
    private val serial = "22222FY2M8"
    private val uri = Uri.parse("content://aidex-test/keys.json")
    private val payload get() = AiDexPairingMaterialFile.encode(serial, ByteArray(16), ByteArray(16))!!

    @Before fun reset() {
        context.getSharedPreferences("aidex_cn_provisioning", 0).edit().clear().commit()
        AiDexPairingMaterialRegistry.clearForTests()
        RestrictedMode.update(context) { it.copy(enabled = false) }
    }

    @After fun clearMode() { RestrictedMode.update(context) { it.copy(enabled = false) } }

    @Test fun oversizedStreamsStopAfterAtMostTheLimitPlusOneByte() {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int { consumed++; return ' '.code }
        }
        assertNull(AiDexKeyDocuments.readJson(input))
        assertEquals(AiDexKeyDocuments.MAX_BYTES + 1, consumed)
        val exact = " ".repeat(AiDexKeyDocuments.MAX_BYTES)
        assertEquals(exact, AiDexKeyDocuments.readJson(ByteArrayInputStream(exact.toByteArray())))
    }

    @Test fun fullModeAllowsDocumentImportAndExport() {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload.toByteArray()))
        assertEquals(serial, AiDexKeyDocuments.importKeys(context, uri))
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        assertTrue(AiDexKeyDocuments.exportKeys(context, serial, uri))
        assertEquals(serial, AiDexPairingMaterialFile.decode(output.toString("UTF-8"))?.serial)
    }

    @Test fun aPickerCompletingAfterRelockCannotReadOrWriteKeys() {
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, serial, ByteArray(16), ByteArray(16)))
        val input = ByteArrayInputStream(payload.toByteArray())
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        RestrictedMode.update(context) { it.copy(enabled = true) }
        RestrictedMode.unlock()
        RestrictedMode.lock()
        assertNull(AiDexKeyDocuments.importKeys(context, uri))
        assertFalse(AiDexKeyDocuments.exportKeys(context, serial, uri))
        assertEquals(payload.toByteArray().size, input.available())
        assertEquals(0, output.size())
    }

    @Test fun relockingDuringTheProviderReadDiscardsTheImportBeforeInstallation() {
        val input = object : ByteArrayInputStream(payload.toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                RestrictedMode.lock()
                return count
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        RestrictedMode.update(context) { it.copy(enabled = true) }
        RestrictedMode.unlock()
        assertNull(AiDexKeyDocuments.importKeys(context, uri))
        assertFalse(AiDexProvisioningStore.hasSaved(context, serial))
        assertNull(AiDexPairingMaterialRegistry.find(serial))
    }
}
