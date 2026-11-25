package tk.glucodata

import android.content.Context
import org.junit.After
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

/** The watch's saved calibrations across a Libre 3's move to its new record, with the phone's clock set back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [NativeMirrorCalibrationInputs::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SyncedWearCalibrationReplacementTests {
    private val legacy = "E07A-XX0D1234ABC"
    private val serial = "0D1234ABC"
    private val other = "E07A-XX0D9999ABC"
    private val prefs get() =
        RuntimeEnvironment.getApplication().getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    private val watch = SyncedWearCalibrationProvider

    @Before
    fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as Applic
        SensorIdentity.invalidateCaches()
        prefs.edit().clear().commit()
        restart()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        restart()
    }

    /** A new watch process: only what was saved is left. */
    private fun restart() {
        val type = SyncedWearCalibrationProvider::class.java
        (type.getDeclaredField("payloads").apply { isAccessible = true }.get(watch) as MutableMap<*, *>).clear()
        type.getDeclaredField("restored").apply { isAccessible = true }.setBoolean(watch, false)
    }

    private fun payload(sensorId: String, revision: Long, vararg anchors: Double, hideInitial: Boolean = false) =
        WearCalibrationPayload(
            sensorId = sensorId,
            revision = revision,
            valuesPrecalibrated = false,
            hideInitialWhenCalibrated = hideInitial,
            auto = WearCalibrationMode(anchors),
            raw = WearCalibrationMode(DoubleArray(0)),
        )

    private fun anchors(sensorId: String) = watch.getActiveCalibrationAnchors(sensorId, false).toList()

    @Test
    fun aLowerRevisionUnderTheNewSerialStillReplacesTheOldRecord() {
        watch.update(payload(legacy, 9_000, 100.0, 120.0, 1.0, hideInitial = true))
        watch.update(payload(other, 9_500, 100.0, 130.0, 1.0))
        assertEquals(listOf(100.0, 120.0, 1.0), anchors(serial))

        watch.update(payload(serial, 10, 100.0, 90.0, 2.0))
        for (process in 1..2) {
            assertEquals(listOf(100.0, 90.0, 2.0), anchors(serial))
            assertEquals(listOf(100.0, 90.0, 2.0), anchors(legacy))
            assertEquals("another sensor keeps its own", listOf(100.0, 130.0, 1.0), anchors(other))
            assertFalse("the old record's settings are gone too", watch.shouldHideInitialWhenCalibrated())
            restart()
        }
    }

    @Test
    fun removingTheLastPointReachesTheWatch() {
        watch.update(payload(legacy, 9_000, 100.0, 120.0, 1.0))
        watch.update(payload(serial, 10))
        for (process in 1..2) {
            assertFalse(watch.hasActiveCalibration(false, serial))
            assertFalse(watch.hasActiveCalibration(false, legacy))
            restart()
        }
    }

    /** Before its records were kept per sensor the watch had one slot; what is in it must not come back. */
    @Test
    fun theOldSingleSlotIsRetiredToo() {
        prefs.edit().putString(
            "wear_synced_calibration_v1",
            android.util.Base64.encodeToString(
                WearCalibrationPayload.encode(payload(legacy, 9_000, 100.0, 120.0, 1.0)), android.util.Base64.NO_WRAP),
        ).putString("wear_synced_calibration_v1.broken", "not base64!").commit()
        assertTrue(watch.hasActiveCalibration(false, serial))

        watch.update(payload(serial, 10))
        restart()
        assertFalse(watch.hasActiveCalibration(false, serial))
    }

    /** The watch caches corrected values by revision, so every accepted payload has to change it. */
    @Test
    fun anAcceptedPayloadChangesTheRevisionBelowAnotherSensorsToo() {
        watch.update(payload(legacy, 9_000, 100.0, 120.0, 1.0))
        watch.update(payload(other, 9_500))
        val before = watch.getRevision()
        watch.update(payload(serial, 10))
        assertTrue(watch.getRevision() != before)
    }
}
