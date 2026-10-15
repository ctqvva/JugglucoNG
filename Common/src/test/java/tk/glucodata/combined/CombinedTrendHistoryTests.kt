package tk.glucodata.combined

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.*
import tk.glucodata.alerts.AlertVolumeTestApplication
import tk.glucodata.settings.KEY_COMBINED_TREND_SEGMENTS
import tk.glucodata.settings.SettingsRegistry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class, shadows = [CombinedTrendHistoryTests.NativeInputs::class])
class CombinedTrendHistoryTests {
    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeInputs {
        companion object {
            @JvmStatic @Implementation fun lastsensorname() = "COMB-1234ABCD"
            @JvmStatic @Implementation fun activeSensors() = arrayOf("COMB-1234ABCD")
        }
    }
    private val id = "COMB-1234ABCD"
    private val base = 1_800_000_000_000L
    private fun time(minute: Int) = base + minute * 60_000L
    private fun point(minute: Int, value: Float = 100f) = GlucosePoint(time(minute), value, 0f).also { it.sensorSerial = id }
    @Before fun setup() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        Applic.unit = 0
        Applic.app.getSharedPreferences(SettingsRegistry.FILE, 0).edit().clear().commit()
        Applic.app.getSharedPreferences("combined_trend_boundaries", 0).edit().clear().commit()
        SensorIdentity.invalidateCaches()
        Log.doLog = false
    }

    @Test fun historicalQueriesKeepTheirSegmentAndDoNotUseTodaysBoundary() {
        for (minute in 0..8) CombinedTrendHistory.recordOutput(id, time(minute), minute % 3 == 0)
        val points = (0..8).map { point(it) }
        assertEquals((0..2).map(::time), CombinedTrendHistory.filter(points, id, time(2)).map { it.timestamp })
        assertEquals((3..5).map(::time), CombinedTrendHistory.filter(points, id, time(5)).map { it.timestamp })
        assertEquals((6..8).map(::time), CombinedTrendHistory.filter(points, id).map { it.timestamp })
        assertTrue(CombinedTrendHistory.sameSegment(id, time(0), time(2)))
        assertFalse(CombinedTrendHistory.sameSegment(id, time(2), time(3)))
        assertEquals(time(0), CombinedTrendHistory.startTime(id, time(2)))
        assertEquals(time(6), CombinedTrendHistory.startTime(id))
    }

    @Test fun legacyLatestCutoffMigratesAndPreservesOlderRows() {
        Applic.app.getSharedPreferences("combined_trend_boundaries", 0).edit().putLong(id, time(6) + 321).commit()
        assertEquals(time(6), CombinedTrendHistory.startTime(id))
        val points = (0..8).map { point(it) }
        assertEquals((0..2).map(::time), CombinedTrendHistory.filter(points, id, time(2)).map { it.timestamp })
        assertTrue(CombinedTrendHistory.filter(points, id, time(8)).isEmpty()) // Not yet confirmed by this version.
        CombinedTrendHistory.recordOutput(id, time(8), false)
        assertEquals((6..8).map(::time), CombinedTrendHistory.filter(points, id).map { it.timestamp })
    }

    @Test fun millisecondBoundaryAlsoCoversTheSecondPrecisionNativeCopy() {
        CombinedTrendHistory.recordOutput(id, time(0) + 321, true)
        CombinedTrendHistory.recordOutput(id, time(1) + 321, false)
        val points = listOf(point(-1), point(0), point(1))
        assertEquals(listOf(time(0), time(1)), CombinedTrendHistory.filter(points, id.takeLast(11)).map { it.timestamp })
    }

    private fun sender() = object : ContextWrapper(Applic.app) {
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("sender.$name", mode)
    }
    private fun payload(sender: Context, through: Int, boundaries: List<Int>): ByteArray {
        val encoded = CombinedTrendHistory.encode(mapOf(id to CombinedTrendHistory.Timeline(time(through), boundaries.map(::time))))
        sender.getSharedPreferences(SettingsRegistry.FILE, 0).edit().putString(KEY_COMBINED_TREND_SEGMENTS, encoded).commit()
        return WearPrefsSync.encode(sender)
    }
    private fun resolved(points: List<GlucosePoint>, minute: Int, value: Float = 100f): CurrentDisplaySource.Snapshot =
        CurrentDisplaySource.resolveSnapshot(
            CurrentGlucoseSource.Snapshot(time(minute), "", value, Float.NaN, value, -42f, id, 0, 0, "native"),
            points, time(0), 0, false, CurrentDisplaySource.SmoothingMode(false, 0, false), id)!!

    @Test fun actualWearPreferencesTransferRetainsUsableReceiverHistory() {
        val sender = sender()
        val points = (0..5).map { point(it) }
        assertTrue(resolved(points, 5).rate.isNaN()) // Unknown continuity cannot use native's cross-mix fallback.
        val data = payload(sender, 5, listOf(0, 3))
        assertTrue(data.toString(Charsets.UTF_8).contains("s:$KEY_COMBINED_TREND_SEGMENTS="))
        assertTrue(WearPrefsSync.apply(Applic.app, data) > 0)
        assertEquals((3..5).map(::time), CombinedTrendHistory.filter(points, id).map { it.timestamp })
        assertEquals(0f, resolved(points, 5).rate, 0.001f)
    }

    @Test fun outOfOrderReadingsAndMetadataDoNotInventOrEraseATrendBoundary() {
        val sender = sender()
        val old = payload(sender, 5, listOf(0, 3))
        WearPrefsSync.apply(Applic.app, old)
        val points = (0..7).map { point(it, if (it < 6) 110f else 100f) }
        assertTrue(resolved(points, 6).rate.isNaN()) // Reading arrived before metadata.
        WearPrefsSync.apply(Applic.app, payload(sender, 6, listOf(0, 3, 6)))
        assertTrue(resolved(points, 6).rate.isNaN()) // First sample in the new segment.
        WearPrefsSync.apply(Applic.app, payload(sender, 7, listOf(0, 3, 6)))
        assertEquals(0f, resolved(points, 7).rate, 0.001f)
        WearPrefsSync.apply(Applic.app, old)
        assertEquals(listOf(time(6), time(7)), CombinedTrendHistory.filter(points, id).map { it.timestamp })
        assertEquals(0f, resolved(points, 7).rate, 0.001f)
    }

    @Test fun malformedMetadataCannotReplaceKnownSegments() {
        val good = CombinedTrendHistory.encode(mapOf(id to CombinedTrendHistory.Timeline(time(5), listOf(time(0), time(3)))))
        for (bad in listOf("garbage", "$id|${time(5)}|${time(6)}", "$id|${time(5)}|${time(3)},${time(0)}")) {
            assertNull(CombinedTrendHistory.mergeEncoded(good, bad))
        }
        assertEquals(good, CombinedTrendHistory.mergeEncoded(good, ""))
    }
}
