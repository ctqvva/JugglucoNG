package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
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
import tk.glucodata.drivers.ManagedSensorViewModeStore
import tk.glucodata.drivers.sibionics.SibionicsBleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [SibionicsExportNative::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SibionicsExportModeTests {
    private val sensor = "SIBI:0683013AQT9"
    private val drivers = mutableListOf<SibionicsBleManager>()
    private var previousApp: Applic? = null
    private var previousUnit = 0

    @Before
    fun setUp() {
        previousApp = Applic.app
        previousUnit = Applic.unit
        Applic.app = RuntimeEnvironment.getApplication() as Applic
        Applic.unit = 0
        Applic.app.getSharedPreferences("managed_sensor_view_modes", 0).edit().clear().commit()
        SensorBluetooth.gattcallbacks.clear()
        SensorIdentity.invalidateCaches()
        SibionicsExportNative.modes.clear()
    }

    @After
    fun tearDown() {
        SensorBluetooth.gattcallbacks.clear()
        drivers.forEach { it.close() }
        CalibrationAccess.unregisterForTests()
        SensorIdentity.invalidateCaches()
        Applic.app = previousApp
        Applic.unit = previousUnit
    }

    private fun driver() = SibionicsBleManager(sensor, 0L).also { drivers += it }

    @Test
    fun liveDriverWinsAndSwitchingPrimaryUpdatesNativeDespiteZeroUiPointer() {
        val driver = driver()
        SensorBluetooth.gattcallbacks.add(driver)
        SibionicsExportNative.modes[sensor] = 0
        driver.viewMode = 2
        // Deliberately stale preference: the live driver is the UI's first source.
        ManagedSensorViewModeStore.write(Applic.app, sensor, 1)
        assertEquals(0L, driver.getManagedUiSnapshot().dataptr)
        assertEquals(2, CurrentDisplaySource.resolveViewModeForNativeExport(sensor, 0))

        assertTrue(CurrentDisplaySource.setViewModeForSensor(sensor, 3))

        assertEquals(3, driver.viewMode)
        assertEquals(3, SibionicsExportNative.modes[sensor])
        assertEquals(3, CurrentDisplaySource.resolveViewModeForSensor(sensor))
        assertEquals(3, CurrentDisplaySource.resolveViewModeForNativeExport(sensor, 0))
    }

    @Test
    fun changingModeWithoutALiveDriverUpdatesTheExistingMirror() {
        SibionicsExportNative.modes[sensor] = 0
        assertTrue(CurrentDisplaySource.setViewModeForSensor(sensor, 1))
        assertEquals(1, SibionicsExportNative.modes[sensor])
        assertEquals(1, CurrentDisplaySource.resolveViewModeForNativeExport(sensor, 0))
    }

    @Test
    fun savedModeSurvivesDriverRestartAndMirrorRecreation() {
        val first = driver()
        first.viewMode = 3 // no shell exists yet
        assertTrue(SibionicsExportNative.modes.isEmpty())
        val restarted = driver()
        assertEquals(3, restarted.viewMode)
        assertEquals(3, CurrentDisplaySource.resolveViewModeForNativeExport(sensor, 0))
        SibionicsExportNative.modes[sensor] = 0
        restarted.restoreFromPersistence(Applic.app)
        assertEquals(3, SibionicsExportNative.modes[sensor])

        // Exercise the production mirror entry point without BLE protocol traffic.
        SibionicsExportNative.modes.clear()
        val readingClass = Class.forName("tk.glucodata.drivers.sibionics.SibionicsBleManager\$EmittedReading")
        val constructor = readingClass.declaredConstructors.single { it.parameterCount == 8 }
            .apply { isAccessible = true }
        val reading = constructor.newInstance(1_760_000_000_000L, 110f, 160f, 32f, 1000f, 1, true, null)
        val mirror = SibionicsBleManager::class.java.getDeclaredMethod("mirrorReadingsIntoNative", List::class.java)
            .apply { isAccessible = true }
        assertEquals(true, mirror.invoke(restarted, listOf(reading)))
        assertEquals(3, SibionicsExportNative.modes[sensor])
    }

    @Test
    fun nativeOnlySensorsKeepTheirModeAndAnotherSensorsChoiceCannotOverrideIt() {
        ManagedSensorViewModeStore.write(Applic.app, sensor, 3)
        for (nativeMode in 0..3) {
            assertEquals(nativeMode, CurrentDisplaySource.resolveViewModeForNativeExport("LEGACY:0683013AQT9", nativeMode))
            assertEquals(nativeMode, CurrentDisplaySource.resolveViewModeForNativeExport(null, nativeMode))
        }
        // A saved Auto choice must also override a stale native Raw mode.
        ManagedSensorViewModeStore.write(Applic.app, sensor, 0)
        assertEquals(0, CurrentDisplaySource.resolveViewModeForNativeExport(sensor, 3))
    }

    @Test
    fun nativeBridgeResolvesSelectedModeButExplicitModeBackfillKeepsItsContract() {
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean {
                assertEquals(sensor, sensorId)
                return true
            }
            override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean,
                emitDiagnostics: Boolean, sensorId: String?): Float = value + if (isRawMode) -20f else 15f
        })
        for (mode in 0..3) {
            ManagedSensorViewModeStore.write(Applic.app, sensor, mode)
            val expected = if (mode == 1 || mode == 3) 140 else 125
            assertEquals(expected, NightscoutCalibration.resolveNativeExportedValueMgdl(sensor, 0, 110, 89, 1L))
            assertEquals(expected, NightscoutCalibration.getNightscoutCalibrationOverride(sensor, 0, 110, 89, 1L))
            assertEquals(125, NightscoutCalibration.resolveExportedValueMgdl(sensor, 0, 110, 89, 1L))
        }
        assertEquals(110, NightscoutCalibration.resolveNativeExportedValueMgdl(sensor, 0, 110, 0, 1L))
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class SibionicsExportNative {
    companion object {
        val modes = mutableMapOf<String, Int>()
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun setSensorViewMode(id: String, mode: Int): Boolean {
            if (!modes.containsKey(id)) return false
            modes[id] = mode
            return true
        }
        @JvmStatic @Implementation fun ensureSensorShell(id: String, start: Long): Long {
            modes.putIfAbsent(id, 0)
            return start
        }
        @JvmStatic @Implementation fun hasSensorStreamCapacity(id: String, count: Int) = true
        @JvmStatic @Implementation fun addGlucoseStreamBatchWithRawTemp(
            times: LongArray, values: FloatArray, raws: FloatArray, temperatures: FloatArray, id: String,
        ): Int {
            assertEquals(3, modes[id]) // mode is restored before any batch becomes exportable
            return times.size
        }
    }
}
