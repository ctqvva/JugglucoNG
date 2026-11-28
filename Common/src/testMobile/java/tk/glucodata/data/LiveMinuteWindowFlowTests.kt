package tk.glucodata.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.ui.GlucosePoint

/**
 * A tester saw the dashboard take new Libre 3 minutes two or three at a time. This pins the
 * part of that path that runs without a screen: a minute stored the way the Libre 3 driver
 * stores it (one Room batch per live minute) reaches the window flow the dashboard's chart
 * collects, on its own and at once, minute after minute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [LiveMinuteWindowFlowTests.RefreshSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveMinuteWindowFlowTests {
    private val serial = "0RFAFKP3E"

    // Notification delivery and broadcasts after a write are outside this path.
    @org.robolectric.annotation.Implements(tk.glucodata.UiRefreshBus::class)
    class RefreshSink {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation fun requestDataRefresh() {}
        }
    }

    @Test
    fun everyLiveMinuteReachesTheWindowFlowOnItsOwn() = runBlocking {
        val repository = HistoryRepository(ApplicationProvider.getApplicationContext())
        val start = (System.currentTimeMillis() / 60_000L - 30L) * 60_000L
        val emissions = Channel<List<GlucosePoint>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            repository.observeMergedWindow(serial, start - 60 * 60_000L, Long.MAX_VALUE).collect { emissions.send(it) }
        }
        try {
            for (minute in 0 until 6) {
                val sampleMs = start + minute * 60_000L + 19_000L
                val stored = HistoryRepository.storeHistoryBatchWithSourceBlocking(
                    serial, longArrayOf(sampleMs), floatArrayOf(120f + minute), floatArrayOf(Float.NaN),
                    GlucoseReadingSource.SENSOR,
                )
                assertEquals(true, stored)
                val shown = withTimeout(5_000L) {
                    var newest: GlucosePoint? = null
                    while (newest?.timestamp != sampleMs) newest = emissions.receive().lastOrNull()
                    newest
                }
                assertEquals("minute $minute", 120f + minute, shown!!.value)
            }
        } finally {
            collector.cancel()
        }
    }
}
