package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.SensorVendor

class SensorTraceLogTests {
    @Test
    fun filterRecentSensorTraceLines_matchesIdentifiersAndKeepsNewestLines() {
        val lines = listOf(
            "1 10 unrelated",
            "2 10 sensor ABC123 connecting",
            "3 10 address aa:bb:cc:dd:ee:ff",
            "4 10 ABC123 connected",
        )

        assertEquals(
            listOf(lines[2], lines[3]),
            filterRecentSensorTraceLines(
                lines = lines,
                identifiers = listOf("ABC123", "AA:BB:CC:DD:EE:FF"),
                limit = 2,
            ),
        )
    }

    // Real lines from the 2026-09-03 trace. Identity matching alone returned only the
    // storage bookkeeping, which is exactly what the log showed on screen.
    private val realTrace = listOf(
        "1788437835 22678 I/SensorBluetooth D76C7BB368A3 checkBluetoothAddress(D7:6C:7B:B3:68:A3) succeeded",
        "1788437839 22689 I/Anytime Connected to D7:6C:7B:B3:68:A3",
        "1788437841 22690 get previous state /data/user/0/tk.glucodata.ng.debug/files/sensors/D76C7BB368A3",
        "1788437841 22690 setSensorWearDays: D76C7BB368A3 days=32 wear=46080",
        "1788437841 22690 getdataptr(D76C7BB368A3) Libre2",
        "1788437944 22793 D/Anytime TX ct5-endCycle-querySSN bytes=3F 55 AA 3E",
        "1788437953 22709 E/Anytime CT5 end-cycle failed (no response); preserving the existing session",
    )

    @Test
    fun storageBookkeepingIsDroppedEvenThoughItNamesTheSensor() {
        assertFalse(isUsefulSensorTraceLine(realTrace[2]))
        assertFalse(isUsefulSensorTraceLine(realTrace[3]))
        assertFalse(isUsefulSensorTraceLine(realTrace[4]))
        assertFalse(isUsefulSensorTraceLine(realTrace[0]))
    }

    @Test
    fun driverTrafficIsKeptEvenThoughItNeverNamesTheSensor() {
        // The lines worth reading carry the driver tag and no identifier at all.
        val kept = filterRecentSensorTraceLines(
            lines = realTrace,
            identifiers = listOf("D76C7BB368A3"),
            driverTags = sensorTraceDriverTags(SensorVendor.YUWELL),
        )

        assertEquals(
            listOf(realTrace[1], realTrace[5], realTrace[6]),
            kept,
        )
    }

    @Test
    fun anErrorSurvivesEveryNoiseRule() {
        // A noise marker inside a failure must not hide the failure.
        val failure = "1788437953 22709 E/Anytime get previous state failed for D76C7BB368A3"

        assertTrue(isUsefulSensorTraceLine(failure))
    }

    @Test
    fun otherFamiliesDoNotBorrowEachOthersTraffic() {
        val lines = listOf(
            "1788437944 1 I/Ottai BG dataNo=15794 mmol=4,50",
            "1788437945 1 I/Anytime CT5 identity check OK",
        )

        assertEquals(
            listOf(lines[0]),
            filterRecentSensorTraceLines(
                lines = lines,
                identifiers = emptyList(),
                driverTags = sensorTraceDriverTags(SensorVendor.OTTAI),
            ),
        )
        assertEquals(emptyList<String>(), sensorTraceDriverTags(SensorVendor.ABBOTT))
    }

    @Test
    fun formattingTradesEpochAndPidForAWallClock() {
        val formatted = formatSensorTraceLine(realTrace[6]) { millis -> "17:19:13[$millis]" }

        assertEquals(
            "17:19:13[1788437953000]  E/Anytime CT5 end-cycle failed (no response); preserving the existing session",
            formatted,
        )
    }

    @Test
    fun aLineWithoutTheExpectedShapeIsLeftAlone() {
        // Native lines and stack traces do not carry the epoch/pid prefix.
        val odd = "INFO: Initialized TensorFlow Lite runtime."

        assertEquals(odd, formatSensorTraceLine(odd) { "never" })
    }
}
