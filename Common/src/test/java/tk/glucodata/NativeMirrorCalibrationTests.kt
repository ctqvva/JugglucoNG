package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.drivers.VirtualGlucoseSensorBridge
import tk.glucodata.drivers.VirtualSensorNativeMirror

/** Real mirror and export bridge with an enabled +15 mg/dL automatic calibration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [NativeMirrorCalibrationInputs::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeMirrorCalibrationTests {
    private var calibrationCalls = 0
    private var previousUnit = 0

    @Before
    fun setUp() {
        previousUnit = Applic.unit
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = true
            override fun getCalibratedValue(
                value: Float, timestamp: Long, isRawMode: Boolean,
                emitDiagnostics: Boolean, sensorId: String?,
            ): Float {
                calibrationCalls++
                return value + if (Applic.unit == 1) 15f / 18.0182f else 15f
            }
        })
    }

    @After
    fun tearDown() {
        CalibrationAccess.unregisterForTests()
        Applic.unit = previousUnit
    }

    private fun mirror(reading: VirtualGlucoseSensorBridge.Reading, primaryIsResolved: Boolean = true): Int {
        var stored = 0
        VirtualSensorNativeMirror(
            readShellStartSeconds = { reading.timestampMs / 1000L - 60L },
            openShell = { _, _, _ -> error("existing shell") },
            rebaseShell = { _, _ -> error("existing window") },
            writeBatch = { _, values, _, _, resolved ->
                assertEquals(primaryIsResolved, resolved)
                // storeGlucoseStreamSample multiplies this argument by 10.
                stored = (values.single() * 10f).toInt()
                1
            },
        ).mirror(
            if (primaryIsResolved) "API-123456789ABC" else "1234567890",
            listOf(reading), "source", primaryIsResolved,
        )
        return stored
    }

    @Test
    fun apiMirrorWritesFinalPrimaryWithExplicitRepresentation() {
        val reading = VirtualGlucoseSensorBridge.Reading(
            timestampMs = 1_760_000_000_000L,
            glucoseMgdl = 130f, autoMgdl = 115f, calibratedMgdl = 130f,
        )
        val storedMgdl = mirror(reading)
        assertEquals(130, storedMgdl)
        assertEquals(0, calibrationCalls)
    }

    @Test
    fun roomAutomaticValuesAreCalibratedEvenForFollowerIdsAndAliases() {
        for (unit in 0..1) {
            Applic.unit = unit
            for (sensor in listOf("API-123456789ABC", "23456789ABC", "NSF-123456789ABC", "MQF-123456789ABC")) {
                // This shared method receives stock lane values. Native final
                // values bypass it at the native export call site instead.
                assertEquals(130, NightscoutCalibration.resolveExportedValueMgdl(sensor, 0, 115, 0, 1L))
                val row = XdripBackfill.Row(1_760_000_000_000L, 115f, 90f, null)
                assertEquals(130.0, XdripBackfill.candidates(sensor, listOf(row), 0, 0).single().mgdl, 0.001)
                assertEquals(130, XdripBackfill.displayedRows(sensor, listOf(row), 0, 0, null).single().mgdl)
            }
        }
        assertEquals(24, calibrationCalls)
    }

    @Test
    fun stockBleValueStillReceivesOneAutomaticCorrection() {
        val storedMgdl = mirror(VirtualGlucoseSensorBridge.Reading(
            timestampMs = 1_760_000_000_000L,
            glucoseMgdl = 130f, autoMgdl = 115f, calibratedMgdl = 130f,
        ), primaryIsResolved = false)
        assertEquals(115, storedMgdl)
        for (unit in 0..1) {
            Applic.unit = unit
            for (sensor in listOf("1234567890", "X-222227KUFY", "70D07EDCB416")) {
                assertEquals(130, NightscoutCalibration.resolveExportedValueMgdl(sensor, 0, storedMgdl, 0, 1L))
            }
        }
        assertEquals(6, calibrationCalls)
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class NativeMirrorCalibrationInputs {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}
    }
}
