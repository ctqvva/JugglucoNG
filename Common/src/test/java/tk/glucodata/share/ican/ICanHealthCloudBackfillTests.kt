package tk.glucodata.share.ican

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.Applic
import tk.glucodata.share.ican.ICanHealthCloudClient.HttpResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanHealthCloudBackfillTestApplication::class)
class ICanHealthCloudBackfillTests {

    private class FakeTransport : ICanHealthCloudClient.Transport {
        override fun post(url: String, jsonBody: String?): HttpResult = HttpResult(200, """{"code":0}""")
        override fun get(url: String): HttpResult = HttpResult(200, """{}""")
        override fun post(url: String, jsonBody: String?, headers: Map<String, String>): HttpResult = HttpResult(200, """{"code":0}""")
        override fun get(url: String, headers: Map<String, String>): HttpResult = HttpResult(200, """{}""")
    }

    class FakeGlucoseSource : ICanHealthCloudBackfill.GlucoseSource {
        val calls = mutableListOf<Pair<Long, Long>>()
        var readings = mutableListOf<ICanHealthCloudBackfill.Reading>()

        override fun getReadings(fromMs: Long, toMs: Long): List<ICanHealthCloudBackfill.Reading> {
            calls.add(fromMs to toMs)
            return readings
        }
    }

    @Test
    fun backfillEnqueuesLastDaysAndReturnsCount() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudBackfillTestApplication
        val context = app
        val client = ICanHealthCloudClient(FakeTransport())
        val settings = ICanHealthCloudSettings(context)
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        settings.lastSyncMs = 0L

        val source = FakeGlucoseSource()
        val now = System.currentTimeMillis()
        source.readings.addAll(
            listOf(
                ICanHealthCloudBackfill.Reading(now - 60_000, 120, 0),
                ICanHealthCloudBackfill.Reading(now - 120_000, 110, 1),
            ),
        )

        val result = ICanHealthCloudBackfill.backfill(client, settings, queue, source, days = 7)
        assertEquals(2, result.count)
        assertEquals(2, queue.size())
    }

    @Test
    fun backfillSkipsAlreadySyncedWithCursor() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudBackfillTestApplication
        val context = app
        val client = ICanHealthCloudClient(FakeTransport())
        val settings = ICanHealthCloudSettings(context)
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        val now = System.currentTimeMillis()
        settings.lastSyncMs = now - 30_000

        val source = FakeGlucoseSource()
        source.readings.addAll(
            listOf(
                ICanHealthCloudBackfill.Reading(now - 60_000, 120, 0),
                ICanHealthCloudBackfill.Reading(now - 10_000, 110, 1),
            ),
        )

        val result = ICanHealthCloudBackfill.backfill(client, settings, queue, source, days = 7)
        assertEquals(1, result.count)
        assertEquals(1, queue.size())
        val firstCall = source.calls.first()
        assertTrue(firstCall.first >= settings.lastSyncMs)
    }
}

class ICanHealthCloudBackfillTestApplication : Applic() {
    override fun onCreate() {}
}
