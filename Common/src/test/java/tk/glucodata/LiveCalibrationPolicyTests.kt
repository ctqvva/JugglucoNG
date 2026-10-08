package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live value handed to the notification and the alert engine is calibrated
 * unless the driver already did it. On 2026-09-19 AiDEX in raw-primary view
 * fed the bare raw lane to alerts — LOW at 3.4 mmol/L against a calibrated
 * 5.9 on screen — because the gate keyed on "stores its own Room rows" instead.
 */
class LiveCalibrationPolicyTests {

    @Test
    fun aDriverThatDoesNotIntegrateCalibrationIsCalibratedLikeAiDex() {
        assertTrue(LiveCalibrationPolicy.appliesGenericCalibration(integratesUserCalibration = false))
    }

    @Test
    fun aDriverThatFoldsCalibrationInItselfIsLeftAloneLikeSibionicsAuto() {
        assertFalse(LiveCalibrationPolicy.appliesGenericCalibration(integratesUserCalibration = true))
    }

    @Test
    fun aidexLeavesGenericCalibrationToTheApp() {
        // Default false is what makes handleGlucoseResult apply getCalibratedValue.
        // AiDEX does not override the flag, so its direct live path stays on that gate.
        // Ottai resolves its stock formula glucose through CurrentDisplaySource
        // before using the external resolved-value API.
        val contract = source("Common/src/main/java/tk/glucodata/drivers/ManagedBluetoothSensorDriver.kt")
        assertTrue(
            contract.contains("fun integratesUserCalibration(isRawMode: Boolean): Boolean = false"),
        )
        for (path in listOf(
            "Common/src/main/java/tk/glucodata/drivers/aidex/AiDexDriver.kt",
            "Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt",
        )) {
            assertFalse(path, source(path).contains("fun integratesUserCalibration"))
        }
    }

    @Test
    fun aidexDirectLivePassesItsRawLaneIntoTheSharedPublishPath() {
        val aidex = source("Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt")
        assertTrue(aidex.contains("handleGlucoseResult(res, sampleTimestampMs, normalizedRawValue ?: Float.NaN)"))
    }

    @Test
    fun ottaiResolvesUserCalibrationBeforePublishingCurrent() {
        val ottai = source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        val publish = ottai.substringAfter("private fun publishCurrentReading(")
            .substringBefore("private fun resolveSampleTimeMs(")
        assertTrue(publish.contains("CurrentDisplaySource.resolveIncomingReading("))
        assertTrue(publish.contains("LiveReadingLanes.stock(reading.displayValue, Float.NaN)"))
        assertTrue(publish.contains("preferredSensorId = id"))
        assertTrue(publish.contains("preferIncomingSample = true"))
        assertTrue(publish.contains("LiveReadingLanes.resolved(display.primaryValue)"))
        assertFalse(publish.contains("processExternalCurrentReading(id, reading.displayValue"))
        assertFalse(ottai.contains("handleGlucoseResult("))
    }

    @Test
    fun sharedLivePublishRecordsTheUncalibratedLanesBeforeItCalibrates() {
        val callback = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val publish = callback.substringAfter("private void handleGlucoseResultInternal")
            .substringBefore("public void searchforDeviceAddress")
        val markers = Regex("LiveReadingLanes\\.stock|getCalibratedValue")
            .findAll(publish)
            .map { it.value }
            .toList()
        assertEquals(
            listOf(
                "LiveReadingLanes.stock",
                "getCalibratedValue",
                "LiveReadingLanes.stock",
                "getCalibratedValue",
            ),
            markers,
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) {
                return File(dir, relative).readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found from ${System.getProperty("user.dir")}")
    }
}
