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
        // persistMarks may only be called from flushBackfill.
        assertTrue(m.split("persistMarks()").size - 1 == 3) // definition + two calls in flushBackfill
    }

    @Test fun noUnmeasuredConnectModeOverride() {
        assertFalse(manager.contains("useAuto" + "Connect")) // split so ConnectModeLeverTests does not flag this file
    }

    @Test fun libre3PairingPrefsNeverLeaveThePhoneInSettingsExport() {
        val exporter = src("Common/src/mobile/java/tk/glucodata/data/SettingsExporter.kt")
        assertTrue(exporter.contains("Libre3Persistence.PREFS_NAME"))
        assertTrue(exporter.contains("it.name.removeSuffix(\".xml\") in DEVICE_LOCAL_PREFERENCES"))
        assertTrue(exporter.contains("importedNames.filterNot { it in DEVICE_LOCAL_PREFERENCES }"))
    }

    @Test fun noWearDurationCutOffOnGlucose() {
        val m = manager
        assertFalse(m.contains("!pastWear(rec.lifeCount)"))
        assertFalse(m.contains("inWarmup(lifeCount) || pastWear(lifeCount)"))
    }
}
