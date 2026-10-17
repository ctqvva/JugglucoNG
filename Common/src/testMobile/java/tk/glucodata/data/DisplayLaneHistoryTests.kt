package tk.glucodata.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.data.DisplayLaneHistory.Switch
import tk.glucodata.drivers.ManagedSensorViewModeStore

/**
 * The switch log behind "a raw/auto switch never moves what had sealed".
 * See [DisplayLaneHistory].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DisplayLaneHistoryTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DisplayLaneHistoryTests {
    class TestApplication : Applic() {
        override fun onCreate() { /* No native initialization in the JVM harness. */ }
    }

    private companion object {
        const val SENSOR = "X-222227KUFY"
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        DisplayLaneHistory.clear(context)
        ManagedSensorViewModeStore.clear(context, SENSOR)
    }

    @After
    fun tearDown() {
        DisplayLaneHistory.clear(context)
        DisplayLaneHistory.resetCache()
    }

    @Test
    fun aMinuteTakesTheModeOfTheFirstSwitchAfterIt() {
        val log = listOf(Switch(1_000, 0, 1), Switch(2_000, 1, 2), Switch(3_000, 2, 3))
        assertEquals(0, DisplayLaneHistory.viewModeAt(log, 0))
        assertEquals(0, DisplayLaneHistory.viewModeAt(log, 999))
        assertEquals(1, DisplayLaneHistory.viewModeAt(log, 1_000))
        assertEquals(1, DisplayLaneHistory.viewModeAt(log, 1_999))
        assertEquals(2, DisplayLaneHistory.viewModeAt(log, 2_500))
        assertNull("since the last switch the current mode applies", DisplayLaneHistory.viewModeAt(log, 3_000))
        assertNull(DisplayLaneHistory.viewModeAt(emptyList(), 0))
    }

    @Test
    fun onlyAChangeOfLaneIsASwitch() {
        assertTrue(DisplayLaneHistory.isLaneChange(0, 1))
        assertTrue(DisplayLaneHistory.isLaneChange(2, 3))
        assertTrue(DisplayLaneHistory.isLaneChange(3, 0))
        assertEquals(false, DisplayLaneHistory.isLaneChange(0, 2))
        assertEquals(false, DisplayLaneHistory.isLaneChange(1, 3))
    }

    @Test
    fun theLogStaysInTimeOrderAndBounded() {
        var log = emptyList<Switch>()
        log = DisplayLaneHistory.appended(log, Switch(5_000, 0, 1))
        log = DisplayLaneHistory.appended(log, Switch(4_000, 1, 0)) // the clock stepped back
        assertEquals(listOf(5_000L, 5_000L), log.map { it.atMs })
        repeat(DisplayLaneHistory.MAX_SWITCHES + 10) { i ->
            log = DisplayLaneHistory.appended(log, Switch(10_000L + i, i % 2, (i + 1) % 2))
        }
        assertEquals(DisplayLaneHistory.MAX_SWITCHES, log.size)
        assertEquals(10_000L + DisplayLaneHistory.MAX_SWITCHES + 9, log.last().atMs)
    }

    @Test
    fun theStoredFormRoundTripsAndSkipsGarbage() {
        val log = listOf(Switch(1_000, 0, 1), Switch(2_000, 1, 0))
        assertEquals(log, DisplayLaneHistory.decode(DisplayLaneHistory.encode(log)))
        assertEquals(log, DisplayLaneHistory.decode("2000:1:0;junk;1000:0:1;3000:9:0;"))
        assertEquals(emptyList<Switch>(), DisplayLaneHistory.decode(null))
    }

    @Test
    fun aViewModeWriteThatChangesTheLaneIsLogged() {
        ManagedSensorViewModeStore.write(context, SENSOR, 0)
        assertEquals("the first write has nothing to switch from", 0, logged().size)

        ManagedSensorViewModeStore.write(context, SENSOR, 2)
        assertEquals("auto stays the main line in mode 2", 0, logged().size)

        ManagedSensorViewModeStore.write(context, SENSOR, 1)
        ManagedSensorViewModeStore.write(context, SENSOR, 1)
        assertEquals(listOf(2 to 1), logged().map { it.fromMode to it.toMode })

        ManagedSensorViewModeStore.write(context, SENSOR, 0)
        assertEquals(listOf(2 to 1, 1 to 0), logged().map { it.fromMode to it.toMode })
    }

    @Test
    fun aSensorWhoseModeLivedOnlyInNativeIsLoggedFromTheCallersHint() {
        ManagedSensorViewModeStore.write(context, SENSOR, 1, previousMode = 0)
        assertEquals(listOf(0 to 1), logged().map { it.fromMode to it.toMode })
    }

    @Test
    fun theLogSurvivesAProcessRestart() {
        ManagedSensorViewModeStore.write(context, SENSOR, 0)
        ManagedSensorViewModeStore.write(context, SENSOR, 1)
        DisplayLaneHistory.resetCache()
        assertEquals(listOf(0 to 1), logged().map { it.fromMode to it.toMode })
    }

    @Test
    fun clearingForgetsEverySwitch() {
        ManagedSensorViewModeStore.write(context, SENSOR, 0)
        ManagedSensorViewModeStore.write(context, SENSOR, 1)
        val before = DisplayLaneHistory.revision.value
        DisplayLaneHistory.clear(context)
        DisplayLaneHistory.resetCache()
        assertEquals(0, logged().size)
        assertTrue("history flows re-emit", DisplayLaneHistory.revision.value > before)
    }

    private fun logged() = DisplayLaneHistory.switchesFor(SENSOR, context)
}
