package tk.glucodata.drivers.ottai

import android.content.Context
import java.io.File
import java.io.DataOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class, shadows = [OttaiRemovalNatives::class])
class OttaiOtaPersistenceTests {
    private lateinit var context: Context
    private val id = "AABBCCDDEEFF"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun nullableFactoryCalibrationSurvivesStorageAndExportWithoutDefaults() {
        val original = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.8.0", 1,
            calibrationValueK = 1.25, calibrationValueB = -0.5)
        assertTrue(OttaiRegistry.saveMaterials(context, id, original))
        assertEquals(1.25, OttaiRegistry.loadMaterials(context, id).calibrationValueK!!, 0.0)
        val export = requireNotNull(OttaiRegistry.exportJson(context, id))
        assertTrue(OttaiRegistry.saveMaterials(context, id, original.copy(calibrationValueK = null, calibrationValueB = null)))
        assertNull(OttaiRegistry.loadMaterials(context, id).calibrationValueK)
        assertEquals(id, OttaiRegistry.importJson(context, export))
        assertEquals(-0.5, OttaiRegistry.loadMaterials(context, id).calibrationValueB!!, 0.0)
        OttaiRegistry.forgetCredentials(context, id)
        assertNull(OttaiRegistry.loadMaterials(context, id).calibrationValueK)
        assertNull(OttaiRegistry.loadMaterials(context, id).calibrationValueB)
    }

    @Test fun preparationBindEchoCannotBecomeTheWarmupStart() {
        val now = System.currentTimeMillis()
        val actualActivationTime = now - 60_000L
        val preparationTime = actualActivationTime - 3_600_000L
        val echoed = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", preparationTime,
            "V1.7.0", 1)
        val prepared = OttaiFirmwareSession.savePreparedMaterials(context, id, echoed)
        assertEquals(0L, prepared.activeTimeMs)
        assertEquals(0L, OttaiRegistry.loadMaterials(context, id).activeTimeMs)
        val manager = OttaiBleManager(id, 0)
        try {
            manager.restoreFromPersistence(context)
            val anchor = OttaiBleManager::class.java.getDeclaredMethod("warmupAnchorMs").apply { isAccessible = true }
            assertEquals(0L, anchor.invoke(manager))
            OttaiBleManager::class.java.getDeclaredField("activationCommandSentAtMs").apply {
                isAccessible = true
                setLong(manager, actualActivationTime)
            }
            assertEquals(actualActivationTime, anchor.invoke(manager))
            assertTrue(OttaiConstants.isWithinWarmup(anchor.invoke(manager) as Long, now))
            val confirm = OttaiBleManager::class.java.getDeclaredMethod("offerConfirmedActiveTime", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            confirm.invoke(manager, actualActivationTime)
            confirm.invoke(manager, actualActivationTime)
            assertEquals(actualActivationTime, OttaiRegistry.loadMaterials(context, id).activeTimeMs)
        } finally { manager.close() }
    }

    @Test fun otaResponseKeepsMandatoryAndAllowedSeparateAndRejectsMissingFlags() {
        val offer = OttaiCloudClient.parseOtaOffer(JSONObject("""{"data":{"requiredUpgrade":true,"allowUpgrade":false,"downloadUrl":"https://cdn.example/V1.8.0.gbl"}}"""))
        assertTrue(offer.required)
        assertFalse(offer.allowed)
        assertEquals("https://cdn.example/V1.8.0.gbl", offer.downloadUrl)
        assertThrows(IllegalArgumentException::class.java) {
            OttaiCloudClient.parseOtaOffer(JSONObject("""{"data":{"requiredUpgrade":false}}"""))
        }
    }

    @Test fun discardClearsAtomicRecoveryFilesButKeepsCredentialsAndOtherSensors() {
        val material = OttaiRegistry.DeviceMaterials("00".repeat(96), "V0", "1,2", 0, "V1.7.0", 1)
        assertTrue(OttaiRegistry.saveMaterials(context, id, material))
        val base = File(context.filesDir, "ottai-ota/$id.bin")
        base.parentFile!!.mkdirs()
        val backup = File(base.path + ".bak")
        val other = File(base.parentFile, "001122334455.bin").also { it.writeBytes(byteArrayOf(42)) }
        DataOutputStream(backup.outputStream()).use {
            OttaiFirmwareJournal.write(it, id, "AA:BB:CC:DD:EE:FF",
                OttaiFirmwareImage("V1.7.0", "V1.8.0", OttaiOtaPackage.parse(testGbl()), OttaiFirmwareMode.STARTED_SENSOR))
        }
        try {
            assertEquals(OttaiFirmwareMode.STARTED_SENSOR, OttaiFirmwareSession.pendingMode(context, id, "AA:BB:CC:DD:EE:FF"))
            assertNull(OttaiFirmwareSession.pendingMode(context, id, "00:11:22:33:44:55"))
            assertTrue(OttaiFirmwareSession.hasPendingTransfer(context, id))
            OttaiFirmwareSession.discardPendingTransfer(context, id)
            assertFalse(base.exists())
            assertFalse(backup.exists())
            assertFalse(OttaiFirmwareSession.hasPendingTransfer(context, id))
            assertEquals(material.keyAHex, OttaiRegistry.loadMaterials(context, id).keyAHex)
            assertArrayEquals(byteArrayOf(42), other.readBytes())
        } finally { base.delete(); backup.delete(); other.delete() }
    }

    @Test fun recoveryLedgerSurvivesInterruptedAtomicWriteAndBlocksForcedActivation() {
        val file = File(context.filesDir, "ottai-ota/$id.bin.bak")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        assertTrue(OttaiFirmwareSession.hasPendingTransfer(context, "AA:BB:CC:DD:EE:FF"))
        val manager = OttaiBleManager(id, 0)
        try {
            assertFalse(manager.requestActivation())
            assertFalse(manager.requestForceActivation())
        } finally {
            manager.close()
            file.delete()
        }
        assertFalse(OttaiFirmwareSession.hasPendingTransfer(context, id))
    }
}
