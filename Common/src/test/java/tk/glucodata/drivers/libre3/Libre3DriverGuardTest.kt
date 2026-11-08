package tk.glucodata.drivers.libre3

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source pins for driver behaviour that cannot run without Android BLE. */
class Libre3DriverGuardTest {
    private fun root(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "Common/src/main/cpp/g.cpp").isFile }

    private fun src(path: String) = File(root(), path).readText()
    private val manager get() = src("Common/src/main/java/tk/glucodata/drivers/libre3/Libre3BleManager.kt")

    @Test fun backfillTimeoutDoesNotSettleMarks() {
        val m = manager
        assertTrue(m.contains("onControlResponse(timedOut = true)"))
        assertTrue(m.contains("val settled = if (timedOut) null else controlInFlight"))
    }

    @Test fun marksArePersistedOnlyAfterRoomStorage() {
        val m = manager
        val flush = m.substringAfter("private fun flushBackfill()").substringBefore("private fun storeBackfill(")
        assertTrue(flush.contains("if (stored) {\n            persistMarks()"))
        // Every persistMarks() call outside its definition sits inside flushBackfill.
        val outside = m.replace(flush, "").replace("private fun persistMarks()", "")
        assertFalse(outside.contains("persistMarks()"))
    }

    @Test fun noUnmeasuredConnectModeOverride() {
        assertFalse(manager.contains("useAutoConnect")) // ConnectModeLeverTests exempts this guard by name
    }

    @Test fun libre3PairingPrefsNeverLeaveThePhoneInSettingsExport() {
        val exporter = src("Common/src/mobile/java/tk/glucodata/data/SettingsExporter.kt")
        assertTrue(exporter.contains("Libre3Persistence.PREFS_NAME"))
        assertTrue(exporter.contains("name !in DEVICE_LOCAL_PREFERENCES"))
        assertTrue(exporter.contains("importedNames.filterNot { it in DEVICE_LOCAL_PREFERENCES }"))
    }

    @Test fun deterministicDeviceRejectionStopsReconnects() {
        val m = manager
        assertTrue(m.contains("deviceRejected = true\n                dropLink(\"missing characteristics"))
        assertTrue(m.contains("if (stop || ended || identityRejected || missingIdentity || deviceRejected)"))
    }

    @Test fun endOfWearIsNotTerminal() {
        assertTrue(manager.contains("if (status.isTerminated) markEnded()"))
    }

    @Test fun noWearDurationCutOffOnGlucose() {
        val m = manager
        assertFalse(m.contains("!pastWear(rec.lifeCount)"))
        assertFalse(m.contains("inWarmup(lifeCount) || pastWear(lifeCount)"))
    }
}
