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
}

class ICanHealthCloudQueueTestApplication : Applic() {
    override fun onCreate() {}
}