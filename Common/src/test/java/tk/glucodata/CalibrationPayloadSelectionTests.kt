package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which sensor's calibration a reading gets (direction.md §6 Q2). The old rule fell back to the
 * highest revision when no sensor was named, so with two calibrated sensors whichever had synced
 * last was applied to the other. These pin the replacement: the named sensor, else the device's
 * main sensor, else — with no context at all — only a single payload is unambiguous.
 */
class CalibrationPayloadSelectionTests {

    private fun payload(sensorId: String, revision: Long) = WearCalibrationPayload(
        sensorId = sensorId,
        revision = revision,
        valuesPrecalibrated = false,
        hideInitialWhenCalibrated = false,
        auto = WearCalibrationMode(DoubleArray(0)),
        raw = WearCalibrationMode(DoubleArray(0)),
    )

    @Test
    fun withTwoPayloadsAndNoSensorContextNothingIsChosen() {
        val candidates = listOf(payload("A", 1), payload("B", 9))

        assertNull(
            "the highest revision is not the right answer for an unnamed sensor",
            SyncedWearCalibrationProvider.selectPayload(candidates, null, null),
        )
    }

    @Test
    fun withOnePayloadAndNoSensorContextItIsUnambiguous() {
        val only = payload("A", 5)

        assertEquals(
            "A",
            SyncedWearCalibrationProvider.selectPayload(listOf(only), null, null)?.sensorId,
        )
    }

    @Test
    fun theNamedSensorWins() {
        val candidates = listOf(payload("A", 1), payload("B", 9))

        assertEquals(
            "B",
            SyncedWearCalibrationProvider.selectPayload(candidates, "B", null)?.sensorId,
        )
    }

    @Test
    fun theMainSensorIsUsedWhenTheCallerNamesNone() {
        val candidates = listOf(payload("A", 1), payload("B", 9))

        assertEquals(
            "A",
            SyncedWearCalibrationProvider.selectPayload(candidates, null, "A")?.sensorId,
        )
    }

    @Test
    fun anUnnamedSensorThatMatchesNothingIsNoCalibration() {
        assertNull(SyncedWearCalibrationProvider.selectPayload(listOf(payload("A", 1)), "Z", null))
    }

    @Test
    fun noPayloadsIsNoCalibration() {
        assertNull(SyncedWearCalibrationProvider.selectPayload(emptyList(), "A", "A"))
    }
}
