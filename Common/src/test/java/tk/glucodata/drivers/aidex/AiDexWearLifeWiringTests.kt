package tk.glucodata.drivers.aidex

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog rating is for display. Reading cutoffs, expiry and the native end stay on the
 * sensor's own startup `0x10` byte. These read the source (comments stripped), as
 * ConnectModeLeverTests does, because the paths need the Android runtime.
 */
class AiDexWearLifeWiringTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun read(path: String): String =
        File(repoRoot(), path).readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")

    private val manager by lazy {
        read("Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt")
    }

    @Test
    fun theReadingCutoffIsTheSensorByteOnly() {
        assertTrue(manager.contains(
            "private fun reportedWearDaysOrNull(): Int? = _wearDays.takeIf { sensorReportedWearDays && it > 0 }",
        ))
        assertTrue(manager.contains(
            "override fun getSensorReportedWearDays(): Int = reportedWearDaysOrNull() ?: -1",
        ))
    }

    @Test
    fun theRatingIsReadOnlyForDisplay() {
        val display = "override fun getDisplayWearDays(): Int = AiDexWearProfile.resolve( " +
            "reportedWearDaysOrNull(), rememberedSensorWearDays, AiDexWearProfile.ratedDays(_modelName), ) ?: -1"
        assertTrue(manager.contains(display))
        assertEquals(
            "AiDexWearProfile is used by getDisplayWearDays only",
            2,
            Regex("AiDexWearProfile\\.").findAll(manager).count(),
        )
        // Defined here, never called here: no reading gate, expiry or native write can use it.
        assertEquals(1, Regex("getDisplayWearDays\\(").findAll(manager).count())
        val driver = read("Common/src/main/java/tk/glucodata/drivers/aidex/AiDexDriver.kt")
        // Its default and the snapshot's officialEndMs are the only other uses.
        assertEquals(2, Regex("getDisplayWearDays\\(").findAll(driver).count())
        assertTrue(driver.contains("val sensorWearDays = runCatching { getDisplayWearDays() }.getOrDefault(-1)"))
        assertTrue(driver.contains("fun getDisplayWearDays(): Int = getSensorReportedWearDays()"))
    }

    @Test
    fun aRememberedByteIsReadOnlyForDisplay() {
        // Declared, compared and set in rememberSensorWearDays, read by getDisplayWearDays.
        // Any other use would let an earlier connection's byte reach a cutoff or native write.
        assertEquals(4, Regex("\\brememberedSensorWearDays\\b").findAll(manager).count())
        // Written only from the sensor's own 0x10 byte.
        assertEquals(2, Regex("rememberSensorWearDays\\(").findAll(manager).count())
        assertTrue(manager.contains("sensorReportedWearDays = true rememberSensorWearDays(parsed.wearDays)"))
    }

    @Test
    fun theNativeEndIsNotShortenedByTheRating() {
        assertTrue(read("Common/src/main/cpp/aidex/java.cpp").contains("if (days < 10 || days > maxdays) {"))
        assertTrue(read("Common/src/main/cpp/SensorGlucoseData.hpp").contains(
            "std::max(hours * 60, static_cast<int>(info->wearduration2));",
        ))
    }
}
