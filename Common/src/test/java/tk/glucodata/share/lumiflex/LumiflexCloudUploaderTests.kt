package tk.glucodata.share.lumiflex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudUploaderTests {

    private class FakeTransport(private val code: Int) : LumiflexCloudClient.Transport {
        var calls = 0
        override fun post(url: String, body: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult {
            calls++
            return LumiflexCloudClient.HttpResult(code, "")
        }

        override fun get(url: String, headers: Map<String, String>): LumiflexCloudClient.HttpResult =
            LumiflexCloudClient.HttpResult(code, "")
    }

    private val start = 1_700_000_000_000L

    private fun uploader(code: Int): Triple<LumiflexCloudSettings, LumiflexCloudQueue, LumiflexCloudUploader> =
        uploaderWithDevices(code) { LumiflexCloudClient.DeviceList.Ok(mapOf("SN1" to "SID1")) }

    private fun uploaderWithDevices(
        code: Int,
        sensorIds: () -> LumiflexCloudClient.DeviceList,
    ): Triple<LumiflexCloudSettings, LumiflexCloudQueue, LumiflexCloudUploader> {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context)
        settings.enabled = true
        settings.token = "tok"
        settings.userId = "u1"
        val queue = LumiflexCloudQueue(context)
        queue.remove(queue.peek())
        return Triple(
            settings,
            queue,
            LumiflexCloudUploader(
                LumiflexCloudClient(FakeTransport(code), decrypt = { it }),
                settings,
                queue,
                sleep = {},
                sensorIds = sensorIds,
            ),
        )
    }

    @Test
    fun flushUploadsAllAndClearsQueue() {
        val (_, queue, up) = uploader(200)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 2, start + 120_000L, 101, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Success)
        assertEquals(0, queue.size())
    }

    @Test
    fun flushDoesNothingWhenDisabled() {
        val (settings, queue, up) = uploader(200)
        settings.enabled = false
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Success)
        assertEquals(1, queue.size())
    }

    @Test
    fun flush401ClearsTokenAndKeepsQueue() {
        val (settings, queue, up) = uploader(401)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Unauthorized)
        assertEquals("", settings.token)
        assertEquals(1, queue.size())
    }

    @Test
    fun flush401OnDeviceListClearsTokenAndKeepsQueue() {
        val (settings, queue, up) = uploaderWithDevices(200) { LumiflexCloudClient.DeviceList.Unauthorized }
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Unauthorized)
        assertEquals("", settings.token)
        assertEquals(1, queue.size())
    }

    @Test
    fun flushDeviceListFailureKeepsQueueAndDoesNotReportSync() {
        val (settings, queue, up) = uploaderWithDevices(200) {
            LumiflexCloudClient.DeviceList.Failed(0, "Network error")
        }
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 100, 0))
        assertTrue(up.flush() is LumiflexCloudUploader.FlushResult.Failed)
        assertEquals(0L, settings.lastSyncMs)
        assertEquals(1, queue.size())
    }

    @Test
    fun flushDropsNonPositiveReadingsWithoutSending() {
        val (_, queue, up) = uploader(200)
        queue.enqueue(CgmRecord("SN1", "SN1", 0, 1, start + 60_000L, 0, 0))
        up.flush()
        assertEquals(0, queue.size())
    }

    @Test
    fun toRecordsRejectsUnknownStartAndNonPositiveReadings() {
        val rows = listOf(LumiflexCloudUploader.Row("SN1", start + 60_000L, 100, 0))
        assertTrue(LumiflexCloudUploader.toRecords(rows, sensorStartMs = 0L).isEmpty())
        assertTrue(LumiflexCloudUploader.toRecords(rows, sensorStartMs = start + 120_000L).isEmpty())
        assertEquals(1, LumiflexCloudUploader.toRecords(rows, sensorStartMs = start).size)
        val zero = listOf(LumiflexCloudUploader.Row("SN1", start + 60_000L, 0, 0))
        assertTrue(LumiflexCloudUploader.toRecords(zero, sensorStartMs = start).isEmpty())
    }

    @Test
    fun onlyPairedAiDexSensorsAreAccepted() {
        LumiflexCloudUploader.sensorKindProvider = { tk.glucodata.SensorSourceResolver.SENSOR_KIND_DEXCOM }
        assertFalse(LumiflexCloudUploader.shouldUpload("SN1"))
        LumiflexCloudUploader.sensorKindProvider = { tk.glucodata.SensorSourceResolver.SENSOR_KIND_AIDEX }
        assertTrue(LumiflexCloudUploader.shouldUpload("SN1"))
    }

    @Test
    fun onHistoryStoredIsInertWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        LumiflexCloudSettings(context).clear()
        val queue = LumiflexCloudQueue(context)
        queue.remove(queue.peek())
        LumiflexCloudUploader.onHistoryStored(
            "SN1",
            listOf(LumiflexCloudUploader.Row("SN1", start + 60_000L, 100, 0)),
        )
        assertEquals(0, queue.size())
    }
}
