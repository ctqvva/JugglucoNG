package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsNotificationDispatcherTest {
    @Test
    fun callbackPayloadIsCopiedBeforeDeferredProcessing() {
        val queued = ArrayDeque<Runnable>()
        val consumed = ArrayList<ByteArray>()
        val dispatcher = SibionicsNotificationDispatcher(queued::addLast, consumed::add)
        dispatcher.beginSession()

        val callbackBuffer = byteArrayOf(1, 2, 3)
        dispatcher.dispatch(callbackBuffer)
        callbackBuffer[0] = 9
        queued.removeFirst().run()

        assertArrayEquals(byteArrayOf(1, 2, 3), consumed.single())
    }

    @Test
    fun queuedPacketsFromDisconnectedGattAreDropped() {
        val queued = ArrayDeque<Runnable>()
        val consumed = ArrayList<ByteArray>()
        val dispatcher = SibionicsNotificationDispatcher(queued::addLast, consumed::add)
        dispatcher.beginSession()
        dispatcher.dispatch(byteArrayOf(1))

        dispatcher.invalidateSession()
        dispatcher.beginSession()
        dispatcher.dispatch(byteArrayOf(2))
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertTrue(consumed.size == 1)
        assertArrayEquals(byteArrayOf(2), consumed.single())
    }
}
