package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneCurrentGlucoseSourceTests {
    private val sensorId = "clone-test-sensor"
    private val latestTime = 1_700_000_000_000L

    private fun snapshot(
        time: Long = latestTime,
        value: Float = 2.7f,
        sensor: String = sensorId,
        source: String = "native"
    ) = CurrentGlucoseSource.Snapshot(
        timeMillis = time,
        valueText = value.toString(),
        numericValue = value,
        rawNumericValue = Float.NaN,
        rate = -0.7f,
        sensorId = sensor,
        sensorGen = 4,
        index = 1,
        source = source
    )

    @Test
    fun cloneUsesImportedReadingInsteadOfRetainedLocalValue() {
        val imported = snapshot()
        val retained = snapshot(time = latestTime - 120_000L, value = 3.4f, source = "callback")
            .copy(rawNumericValue = 4.6f)
        var localReads = 0

        val current = CurrentGlucoseSource.resolveForOwnership(
            isCloneSensor = true,
            targetSensorId = sensorId,
            readNative = { imported },
            readLocal = { localReads++; retained }
        )

        assertSame(imported, current)
        assertEquals(0, localReads)
        // No raw lane from a paused local driver may be attached to this timestamp.
        requireNotNull(current)
        assertTrue(current.rawNumericValue.isNaN())

        val display = CurrentDisplaySource.resolveSnapshot(
            current = current,
            recentPoints = listOf(
                GlucosePoint(retained.timeMillis, retained.numericValue, retained.rawNumericValue),
                GlucosePoint(latestTime, 2.7f, 4.0f)
            ),
            historyStart = latestTime - 300_000L,
            viewMode = 2,
            isMmol = true,
            smoothingMode = CurrentDisplaySource.SmoothingMode(false, 0, false),
            sensorId = sensorId
        )
        requireNotNull(display)
        assertEquals(latestTime, display.timeMillis)
        assertEquals(2.7f, display.primaryValue, 0.001f)
        assertEquals(4.0f, display.rawValue, 0.001f)
    }

    @Test
    fun cloneWithoutFreshNativeReadingDoesNotResurrectLocalCache() {
        val current = CurrentGlucoseSource.resolveForOwnership(
            isCloneSensor = true,
            targetSensorId = sensorId,
            readNative = { null },
            readLocal = { error("Clone must not read retained local state") }
        )

        assertNull(current)
    }

    @Test
    fun cloneRejectsNativeFallbackFromAnotherSensor() {
        val current = CurrentGlucoseSource.resolveForOwnership(
            isCloneSensor = true,
            targetSensorId = sensorId,
            readNative = { snapshot(sensor = "another-sensor") },
            readLocal = { error("Clone must not read retained local state") }
        )

        assertNull(current)
    }

    @Test
    fun cloneAcceptsMatchingSensorIdentityIgnoringCase() {
        val imported = snapshot(sensor = sensorId.uppercase())
        val current = CurrentGlucoseSource.resolveForOwnership(
            isCloneSensor = true,
            targetSensorId = sensorId,
            readNative = { imported },
            readLocal = { error("Clone must not read retained local state") }
        )

        assertSame(imported, current)
    }

    @Test
    fun cloneAcceptsNativeAliasOfManagedAiDexSensor() {
        val imported = snapshot(sensor = "TESTABC1234")
        val current = CurrentGlucoseSource.resolveForOwnership(
            isCloneSensor = true,
            targetSensorId = "X-TESTABC1234",
            readNative = { imported },
            readLocal = { error("Clone must not read retained local state") }
        )

        assertSame(imported, current)
    }

    @Test
    fun localSensorKeepsExistingSourceResolution() {
        val managed = snapshot(source = "managed").copy(rawNumericValue = 4.0f)
        val current = CurrentGlucoseSource.resolveForOwnership(
            isCloneSensor = false,
            targetSensorId = sensorId,
            readNative = { error("Local source precedence must stay unchanged") },
            readLocal = { managed }
        )

        assertSame(managed, current)
    }
}
