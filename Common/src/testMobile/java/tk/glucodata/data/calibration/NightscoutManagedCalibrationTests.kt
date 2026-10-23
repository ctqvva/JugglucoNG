package tk.glucodata.data.calibration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import tk.glucodata.CalibrationAccess
import tk.glucodata.Natives
import tk.glucodata.NightscoutCalibration
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.ManagedSensorViewModeStore

/** Real calibration engine behind the native Nightscout export entry point. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CalibrationArchiveTestApplication::class,
    shadows = [NightscoutManagedCalibrationNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NightscoutManagedCalibrationTests {
    private val full = "SIBI:0683013AQT9"
    private val short = "0683013AQT9"
    private var now = 0L

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        Applic.unit = 0
        SensorIdentity.invalidateCaches()
        CalibrationManager.init(context)
        CalibrationManager.clearAll()
        context.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("managed_sensor_view_modes", Context.MODE_PRIVATE).edit().clear().commit()
        CalibrationManager.init(context)
        CalibrationManager.resetLoadedStateForTests(keepBoundaries = false)
        CalibrationManager.loadCalibrations()
        CalibrationAccess.register(MobileCalibrationProvider)
        now = System.currentTimeMillis()
    }

    private suspend fun addRawPoint(sensor: String = full, mmol: Boolean = false) {
        Applic.unit = if (mmol) 1 else 0
        val divisor = if (mmol) 18.0182f else 1f
        CalibrationManager.addCalibration(now, 100f / divisor, 90.091f / divisor, 120f / divisor,
            sensorId = sensor, isRawMode = true)
        CalibrationManager.setEnabledForMode(true, true, sensor)
    }

    @Test
    fun sibionicsRawCalibrationOverridesAStaleAutoModeInTheNativeMirror() = runBlocking {
        addRawPoint()
        ManagedSensorViewModeStore.write(Applic.app, full, 1)

        assertEquals(120, NightscoutCalibration.getNightscoutCalibrationOverride(short, 0, 100, 50, now))
    }

    @Test
    fun sibionicsAutoSelectionOverridesAStaleRawModeInTheNativeMirror() = runBlocking {
        addRawPoint()
        CalibrationManager.addCalibration(now, 100f, 90.091f, 130f, sensorId = full)
        CalibrationManager.setEnabledForMode(false, true, full)
        ManagedSensorViewModeStore.write(Applic.app, full, 0)

        assertEquals(130, NightscoutCalibration.resolveExportedValueMgdl(short, 1, 100, 50, now))
    }

    @Test
    fun rawCalibrationExportKeepsMgdlUnitsWhenTheAppDisplaysMmol() = runBlocking {
        addRawPoint(mmol = true)
        ManagedSensorViewModeStore.write(Applic.app, full, 3)

        assertEquals(120, NightscoutCalibration.resolveExportedValueMgdl(short, 0, 100, 50, now))
    }

    @Test
    fun nativeModeIsStillTheFallbackWithoutAManagedPreference() = runBlocking {
        addRawPoint()

        assertEquals(120, NightscoutCalibration.resolveExportedValueMgdl(short, 1, 100, 50, now))
    }

    @Test
    fun rawSelectionWithNoRawValueKeepsTheAvailableAutoValue() = runBlocking {
        addRawPoint()
        ManagedSensorViewModeStore.write(Applic.app, full, 1)

        assertEquals(100, NightscoutCalibration.resolveExportedValueMgdl(short, 0, 100, 0, now))
    }

    @Test
    fun managedFollowerReexportUsesItsOwnCalibrationAndSavedMode() = runBlocking {
        val follower = "NSF-AABBCCDDEEFF"
        CalibrationManager.addCalibration(now, 100f, 0f, 130f, sensorId = follower)
        CalibrationManager.setEnabledForMode(false, true, follower)
        ManagedSensorViewModeStore.write(Applic.app, follower, 0)

        assertEquals(130, NightscoutCalibration.resolveExportedValueMgdl(follower, 1, 100, 0, now))
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class NightscoutManagedCalibrationNatives {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun resolveFullSensorName(id: String): String? =
            if (id == "0683013AQT9" || id == "SIBI:0683013AQT9") "SIBI:0683013AQT9" else null
    }
}
