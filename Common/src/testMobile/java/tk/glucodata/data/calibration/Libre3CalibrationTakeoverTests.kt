package tk.glucodata.data.calibration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.libre3.Libre3Persistence
import tk.glucodata.drivers.libre3.Libre3SensorRecord

/** Calibrations entered under the legacy Libre 3 record keep working after the Kotlin driver takes the sensor over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class,
    shadows = [MirrorCalibrationTransferNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class Libre3CalibrationTakeoverTests {
    private val serial = "0D1234ABC"
    private val full = "E07A-XX$serial"
    private val short = "XX$serial"
    private val time = 1_000_000L
    private val context get() = RuntimeEnvironment.getApplication() as Applic
    private val dao get() = CalibrationDatabase.getInstance(Applic.app).calibrationDao()

    @Before
    fun setUp() = runBlocking {
        Applic.app = context
        Applic.unit = 0
        Libre3Persistence.removeSensor(context, serial)
        SensorIdentity.invalidateCaches()
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationManager.init(context)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        dao.insertAll(listOf(point(full, 0), point(short, 600_000), point("E07A-XX0D9999ABC", 1_200_000)))
        CalibrationManager.loadCalibrations()
    }

    @After
    fun tearDown() {
        Libre3Persistence.removeSensor(context, serial)
        SensorIdentity.invalidateCaches()
    }

    private fun point(id: String, offset: Long) = CalibrationEntity(
        timestamp = time + offset, sensorId = id, sensorValue = 100f, sensorValueRaw = 90f, userValue = 120f)

    private fun takeOver() {
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(
            sensorId = serial, bleAddress = "AA:BB:CC:DD:EE:FF", blePin = byteArrayOf(1, 2, 3, 4),
            startTimeMs = time, warmupMinutes = 60, wearMinutes = 14 * 24 * 60, receiverId = 1L,
            firmware = "", securityVersion = 0))
        SensorIdentity.invalidateCaches()
    }

    private fun anchors(id: String) = CalibrationManager.getActiveCalibrationAnchors(id, false).size / 3

    /** Asked before pairing, as retained history does: a cached "none" must not outlive the pairing. */
    @Test fun pairingForgettingAndPairingAgainNeverLosesThem() {
        val asked = { CalibrationManager.getCalibratedValue(100f, time + 700_000, false, false, serial) }
        val corrected = asked()
        assertNotEquals(100f, corrected)
        takeOver()
        assertEquals(corrected, asked(), 0f)
        Libre3Persistence.removeSensor(context, serial)
        SensorIdentity.invalidateCaches()
        assertEquals(corrected, asked(), 0f)
        takeOver()
        assertEquals(corrected, asked(), 0f)
        assertEquals(2, anchors(serial))
    }

    @Test fun onlyTheLegacyNamesOfTheSameSerialAreShared() {
        for (id in listOf(full, short, full.lowercase(), serial)) assertTrue(id, SensorIdentity.sharesCalibrations(id, serial))
        for (id in listOf("XX0D1234ABD", "E07A-XX0D1234ABCD", "E07A-0D1234ABC", "YY$serial", "3MH$serial", "XX")) {
            assertFalse(id, SensorIdentity.sharesCalibrations(id, serial))
        }
    }

    @Test fun bothLegacySpellingsApplyToTheManagedSensorAndStayOnTheLegacyRecord() {
        takeOver()
        assertEquals(2, anchors(serial))
        assertEquals(2, anchors(full))
        // Storage and removal still see two sensors.
        assertFalse(SensorIdentity.matches(full, serial))
        assertFalse(SensorIdentity.matches(short, serial))
        assertFalse(CalibrationManager.calibrationMatchesSensor("E07A-XX0D9999ABC", serial))
    }

    @Test fun aPointAddedAfterTheMoveJoinsTheOldOnes() = runBlocking {
        takeOver()
        assertTrue(CalibrationManager.addCalibration(time + 900_000, 100f, 90f, 118f, sensorId = serial))
        assertEquals(3, anchors(serial))
    }

    @Test fun theOffSwitchFollowsTheSensor() {
        CalibrationManager.setEnabledForMode(isRawMode = false, enabled = false, sensorIdOverride = full)
        takeOver()
        assertFalse(CalibrationManager.isEnabledForMode(isRawMode = false, sensorIdOverride = serial))
        CalibrationManager.setEnabledForMode(isRawMode = false, enabled = true, sensorIdOverride = serial)
        assertTrue(CalibrationManager.isEnabledForMode(isRawMode = false, sensorIdOverride = full))
    }

    @Test fun replacingTheProfileLeavesNoOldPointBehind() = runBlocking {
        takeOver()
        CalibrationManager.importProfileFromJson(
            """{"sensorId":"$serial","units":"mg/dL","calibrations":[{"timestamp":${time + 5},"sensorValue":100,"sensorValueRaw":90,"userValue":125}]}""",
            replaceExisting = true)
        assertEquals(setOf(serial, "E07A-XX0D9999ABC"), dao.getAllSync().map { it.sensorId }.toSet())
        assertEquals(1, anchors(serial))
    }
}
