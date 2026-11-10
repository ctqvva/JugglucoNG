package tk.glucodata.data.calibration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Natives

/**
 * A driver whose serial outlives its disposable sensor files the old electrode's calibrations away
 * when a new sensor starts, so an old single-point offset is not applied to the replacement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class, shadows = [CalibrationArchiveNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalibrationArchiveTest {
    private val serial = "2329895446"

    private companion object {
        const val CUTOFF = 5_000L
    }

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
    }

    @Test
    fun archivingStopsTheOldCalibrationFromMatchingTheLiveSensor() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 6f, 7f, sensorId = serial)
        assertTrue(CalibrationManager.hasActiveCalibration(false, serial))

        val moved = CalibrationManager.archiveSensorCalibrations(serial, "$serial@1000", CUTOFF)

        assertEquals(1, moved)
        assertFalse("the replacement sensor starts without the old offset", CalibrationManager.hasActiveCalibration(false, serial))
    }

    @Test
    fun archivingKeepsTheRowsAndLeavesOtherSensorsAlone() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 6f, 7f, sensorId = serial)
        CalibrationManager.addCalibration(2_000L, 6f, 6f, 8f, sensorId = "3MH01M0MWYR")

        CalibrationManager.archiveSensorCalibrations(serial, "$serial@1000", CUTOFF)

        val rows = CalibrationManager.calibrations.value
        assertEquals(setOf("$serial@1000", "3MH01M0MWYR"), rows.map { it.sensorId }.toSet())
        assertEquals("nothing is deleted", 2, rows.size)
        assertEquals(7f, rows.first { it.sensorId == "$serial@1000" }.userValue, 0f)
        assertTrue(CalibrationManager.hasActiveCalibration(false, "3MH01M0MWYR"))
    }

    @Test
    fun archivingLeavesPointsOfTheReplacementInsertion() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 6f, 7f, sensorId = serial)
        CalibrationManager.addCalibration(CUTOFF + 1_000L, 6f, 6f, 8f, sensorId = serial)

        val moved = CalibrationManager.archiveSensorCalibrations(serial, "$serial@1000", CUTOFF)

        assertEquals("only the previous insertion's point moves", 1, moved)
        val rows = CalibrationManager.calibrations.value
        assertEquals(8f, rows.single { it.sensorId == serial }.userValue, 0f)
        assertEquals(7f, rows.single { it.sensorId == "$serial@1000" }.userValue, 0f)
        assertTrue("the replacement keeps its own correction", CalibrationManager.hasActiveCalibration(false, serial))
    }

    @Test
    fun declaredBoundaryTakesEffectBeforeTheDatabaseMove() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 6f, 7f, sensorId = serial)
        assertTrue(CalibrationManager.hasActiveCalibration(false, serial))

        CalibrationManager.declareSessionBoundary(serial, "$serial@1000", CUTOFF)

        assertFalse("no archive has run, yet the old point no longer matches", CalibrationManager.hasActiveCalibration(false, serial))
        CalibrationManager.addCalibration(CUTOFF + 1_000L, 6f, 6f, 8f, sensorId = serial)
        assertTrue("a point for the replacement still counts", CalibrationManager.hasActiveCalibration(false, serial))
        CalibrationManager.loadCalibrations()
        assertEquals("a reload keeps the boundary", 1, CalibrationManager.calibrations.value.count { it.sensorId == serial })
    }

    @Test
    fun boundaryDeclaredBeforeTheFirstLoadAppliesToTheBackgroundLoader() = runBlocking {
        CalibrationManager.addCalibration(1_000L, 6f, 6f, 7f, sensorId = serial)
        CalibrationManager.resetLoadedStateForTests()

        CalibrationManager.declareSessionBoundary(serial, "$serial@1000", CUTOFF)

        assertFalse(
            "the first lookup after a restart loads the rows itself and must still honour the boundary",
            CalibrationManager.hasActiveCalibration(false, serial)
        )
    }

    @Test
    fun archivingASensorWithoutCalibrationsChangesNothing() = runBlocking {
        CalibrationManager.addCalibration(2_000L, 6f, 6f, 8f, sensorId = "3MH01M0MWYR")

        assertEquals(0, CalibrationManager.archiveSensorCalibrations(serial, "$serial@1000", CUTOFF))
        assertEquals(1, CalibrationManager.calibrations.value.size)
    }
}

class CalibrationArchiveTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class CalibrationArchiveNatives {
    companion object {
        @JvmStatic @Implementation
        fun __staticInitializer__() {}
    }
}
