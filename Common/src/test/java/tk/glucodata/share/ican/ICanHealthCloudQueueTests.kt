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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ICanHealthCloudQueueTestApplication::class)
class ICanHealthCloudQueueTests {

    @Test
    fun roundTripEnqueuePeekRemove() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudQueueTestApplication
        val context = app
        val queue = ICanHealthCloudQueue(context)
        // clear file by removing all
        queue.remove(queue.peek())
        val egv = EgvUpload(serial = "SN1", timeMs = 1000L, mgdl = 120, trend = 1)
        queue.enqueue(egv)
        assertEquals(1, queue.size())
        assertEquals(1, queue.peek().size)
        assertEquals("SN1", queue.peek().first().serial)
        queue.remove(queue.peek())
        assertEquals(0, queue.size())
    }

    @Test
    fun dedupsBySerialAndTime() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudQueueTestApplication
        val context = app
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        val egv = EgvUpload(serial = "SN1", timeMs = 1000L, mgdl = 120, trend = 1)
        queue.enqueue(egv)
        queue.enqueue(egv.copy(mgdl = 999))
        assertEquals(1, queue.size())
        assertEquals(120, queue.peek().first().mgdl.coerceIn(40, 400)) // clamped stored
    }

    @Test
    fun clampsOutOfRange() {
        val app = RuntimeEnvironment.getApplication() as ICanHealthCloudQueueTestApplication
        val context = app
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 1L, mgdl = 10, trend = 1))
        queue.enqueue(EgvUpload(serial = "SN1", timeMs = 2L, mgdl = 500, trend = 1))
        val vals = queue.peek().map { it.mgdl }
        assertTrue(vals.contains(40))
        assertTrue(vals.contains(400))
    }
    @Test
    fun clearingAnotherInstanceInvalidatesAlreadyLoadedItems() {
        val context = RuntimeEnvironment.getApplication()
        val queue = ICanHealthCloudQueue(context)
        queue.clear()
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        ICanHealthCloudQueue(context).clear()
        assertTrue(queue.peek().isEmpty())
    }

    @Test
    fun queueWithoutAccountOwnershipIsNotAdoptedByLoggedInPatient() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.userId = "new-patient"
        val legacy = org.json.JSONArray().put(EgvUpload("SN1", 1L, 100, 1).toJson())
        context.filesDir.resolve("ican_cloud_queue.json").writeText(legacy.toString())
        assertTrue(ICanHealthCloudQueue(context).peek().isEmpty())
    }

    @Test
    fun writeFailureKeepsQueuedRowsForRetry() {
        val context = RuntimeEnvironment.getApplication()
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val dir = context.filesDir
        assertTrue(dir.setWritable(false))
        try {
            queue.enqueue(EgvUpload("SN2", 2L, 100, 1))
            // The failed write must not let the next load() drop the unsaved row.
            assertEquals(2, queue.size())
            assertTrue(queue.peek().any { it.timeMs == 2L })
        } finally {
            assertTrue(dir.setWritable(true))
        }
        // A later successful persist makes the unsaved rows durable.
        queue.enqueue(EgvUpload("SN3", 3L, 100, 1))
        val times = ICanHealthCloudQueue(context).peek().map { it.timeMs }
        assertEquals(listOf(1L, 2L, 3L), times)
    }

    @Test
    fun readFailureKeepsInMemoryState() {
        val context = RuntimeEnvironment.getApplication()
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        val file = context.filesDir.resolve("ican_cloud_queue.json")
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        assertTrue(file.setReadable(false))
        try {
            // A failed read must not erase rows that were loaded earlier.
            assertEquals(listOf(1L), queue.peek().map { it.timeMs })
        } finally {
            assertTrue(file.setReadable(true))
        }
    }

    @Test
    fun corruptFileDoesNotCarryRowsAcrossAccountSwitch() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.userId = "patient-a"
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        context.filesDir.resolve("ican_cloud_queue.json").writeText("CORRUPT")
        settings.userId = "patient-b"
        // Rows queued under patient-a must never surface under patient-b.
        assertTrue(queue.peek().isEmpty())
        assertTrue(ICanHealthCloudQueue(context).peek().isEmpty())
    }

    @Test
    fun readFailureDoesNotCarryRowsAcrossAccountSwitch() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.userId = "patient-a"
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        val file = context.filesDir.resolve("ican_cloud_queue.json")
        assertTrue(file.setReadable(false))
        settings.userId = "patient-b"
        try {
            assertTrue(queue.peek().isEmpty())
            assertTrue(ICanHealthCloudQueue(context).peek().isEmpty())
        } finally {
            assertTrue(file.setReadable(true))
        }
    }

    @Test
    fun corruptFileRowsSurviveForTheSameAccount() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ICanHealthCloudSettings(context)
        settings.userId = "patient-a"
        val queue = ICanHealthCloudQueue(context)
        queue.remove(queue.peek())
        queue.enqueue(EgvUpload("SN1", 1L, 100, 1))
        context.filesDir.resolve("ican_cloud_queue.json").writeText("CORRUPT")
        assertEquals(listOf(1L), queue.peek().map { it.timeMs })
    }

}

class ICanHealthCloudQueueTestApplication : Applic() {
    override fun onCreate() {}
}