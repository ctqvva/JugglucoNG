package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalCalibrationPolicyTests {
    private val now = 1_700_000_000_000L
    private val sensor = "SENSOR-A"
    private val exactMatch: (String, String) -> Boolean = { a, b -> a == b }

    private fun minutesAgo(minutes: Long) = now - minutes * 60_000L

    private fun entry(id: Long, timestamp: Long, mgdl: Float) =
        JournalCalibrationPolicy.JournalBloodGlucose(id, timestamp, mgdl)

    /** A reading every five minutes for the last two hours, flat at [autoValue]. */
    private fun history(autoValue: Float = 100f, rawValue: Float = 96f) =
        (0..24).map { step ->
            JournalCalibrationPolicy.SensorLanes(
                timestamp = minutesAgo(step * 5L),
                autoValue = autoValue,
                rawValue = rawValue,
            )
        }

    private fun plan(
        entries: List<JournalCalibrationPolicy.JournalBloodGlucose>,
        existing: List<CalibrationEntity> = emptyList(),
        history: List<JournalCalibrationPolicy.SensorLanes> = history(),
        isMmol: Boolean = false,
    ) = JournalCalibrationPolicy.plan(
        entries = entries,
        history = history,
        existing = existing,
        sensorId = sensor,
        isMmol = isMmol,
        nowMillis = now,
        matchesSensor = exactMatch,
    )

    @Test
    fun pairsAnEntryWithTheSensorLanesFromTheSameMoment() {
        val result = plan(listOf(entry(id = 1L, timestamp = minutesAgo(10), mgdl = 120f)))

        assertEquals(1, result.inserts.size)
        val point = result.inserts.single()
        assertEquals(minutesAgo(10), point.timestamp)
        assertEquals(sensor, point.sensorId)
        assertEquals(100f, point.sensorValue, 0.001f)
        assertEquals(96f, point.sensorValueRaw, 0.001f)
        assertEquals(120f, point.userValue, 0.001f)
        assertEquals(1L, point.journalEntryId)
    }

    @Test
    fun storesTheReferenceValueInTheDisplayUnit() {
        val result = plan(
            entries = listOf(entry(id = 1L, timestamp = minutesAgo(10), mgdl = 180f)),
            isMmol = true,
        )

        assertEquals(9.99f, result.inserts.single().userValue, 0.01f)
    }

    @Test
    fun skipsAnEntryWithNoReadingNearIt() {
        val stale = listOf(
            JournalCalibrationPolicy.SensorLanes(minutesAgo(120), 100f, 96f),
        )
        val result = plan(
            entries = listOf(entry(id = 1L, timestamp = minutesAgo(10), mgdl = 120f)),
            history = stale,
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun skipsImplausibleValues() {
        val result = plan(
            listOf(
                entry(id = 1L, timestamp = minutesAgo(10), mgdl = 4f),
                entry(id = 2L, timestamp = minutesAgo(20), mgdl = 900f),
                entry(id = 3L, timestamp = now + 60L * 60_000L, mgdl = 120f),
            )
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun leavesAHandEnteredCalibrationToStandForItsOwnFingerStick() {
        val manual = CalibrationEntity(
            id = 7,
            timestamp = minutesAgo(10),
            sensorId = sensor,
            sensorValue = 100f,
            sensorValueRaw = 96f,
            userValue = 118f,
        )
        val result = plan(
            entries = listOf(entry(id = 1L, timestamp = minutesAgo(10) + 20_000L, mgdl = 120f)),
            existing = listOf(manual),
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun countsOneMeasurementOnceWhenItWasLoggedTwice() {
        val result = plan(
            listOf(
                entry(id = 1L, timestamp = minutesAgo(10), mgdl = 120f),
                entry(id = 2L, timestamp = minutesAgo(10) + 15_000L, mgdl = 121f),
            )
        )

        assertEquals(1, result.inserts.size)
        assertEquals(2L, result.inserts.single().journalEntryId)
    }

    @Test
    fun rePairsADerivedPointWhenItsEntryIsEdited() {
        val derived = CalibrationEntity(
            id = 3,
            timestamp = minutesAgo(10),
            sensorId = sensor,
            sensorValue = 100f,
            sensorValueRaw = 96f,
            userValue = 120f,
            isEnabled = false,
            journalEntryId = 1L,
        )
        val result = plan(
            entries = listOf(entry(id = 1L, timestamp = minutesAgo(10), mgdl = 145f)),
            existing = listOf(derived),
        )

        assertTrue(result.inserts.isEmpty())
        val updated = result.updates.single()
        assertEquals(3, updated.id)
        assertEquals(145f, updated.userValue, 0.001f)
        // The user switched this point off; re-pairing must not switch it back on.
        assertEquals(false, updated.isEnabled)
    }

    @Test
    fun leavesAnUnchangedDerivedPointAlone() {
        val derived = CalibrationEntity(
            id = 3,
            timestamp = minutesAgo(10),
            sensorId = sensor,
            sensorValue = 100f,
            sensorValueRaw = 96f,
            userValue = 120f,
            journalEntryId = 1L,
        )
        val result = plan(
            entries = listOf(entry(id = 1L, timestamp = minutesAgo(10), mgdl = 120f)),
            existing = listOf(derived),
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun removesADerivedPointWhoseEntryIsGone() {
        val derived = CalibrationEntity(
            id = 3,
            timestamp = minutesAgo(10),
            sensorId = sensor,
            sensorValue = 100f,
            sensorValueRaw = 96f,
            userValue = 120f,
            journalEntryId = 1L,
        )
        val result = plan(entries = emptyList(), existing = listOf(derived))

        assertEquals(listOf(3), result.deleteIds)
    }

    @Test
    fun neverTouchesHandEnteredOrOtherSensorRows() {
        val manual = CalibrationEntity(
            id = 4,
            timestamp = minutesAgo(45),
            sensorId = sensor,
            sensorValue = 100f,
            sensorValueRaw = 96f,
            userValue = 110f,
        )
        val otherSensor = CalibrationEntity(
            id = 5,
            timestamp = minutesAgo(30),
            sensorId = "SENSOR-B",
            sensorValue = 100f,
            sensorValueRaw = 96f,
            userValue = 130f,
            journalEntryId = 9L,
        )
        val result = plan(entries = emptyList(), existing = listOf(manual, otherSensor))

        assertTrue(result.isEmpty)
    }
}
