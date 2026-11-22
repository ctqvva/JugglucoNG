package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthCalibrationPendingTests {

    private val bean = ICanHealthCeCalibrationResult(1.2f, true, 1, 120f, 1f, 1f)

    @Test
    fun onlyOneRequestCanBeOutstanding() {
        val pending = ICanHealthCalibrationPending()
        val first = pending.begin(bean)
        assertNotNull(first)
        assertNull(pending.begin(bean.copy(caliK = 2f)))
        assertTrue(pending.hasPending())
        pending.cancel(first!!)
        assertNotNull(pending.begin(bean))
    }

    @Test
    fun settlesOnlyWhenAckAndWriteHaveBothArrived() {
        val pending = ICanHealthCalibrationPending()
        val id = pending.begin(bean)!!
        assertNull(pending.onAck(id, 0x01))
        val settled = pending.onWriteResult(id, 0)
        assertNotNull(settled)
        assertEquals(0x01, settled!!.code)
        assertTrue(settled.writeSucceeded)
        assertEquals(bean, settled.result)
        assertFalse(pending.hasPending())
    }

    @Test
    fun writeFailureDoesNotPersistAndClearsTheRequest() {
        val pending = ICanHealthCalibrationPending()
        val id = pending.begin(bean)!!
        pending.onAck(id, 0x01)
        val settled = pending.onWriteResult(id, 133)
        assertNotNull(settled)
        assertFalse(settled!!.writeSucceeded)
        assertFalse(pending.hasPending())
    }

    @Test
    fun lateAckFromCancelledRequestIsIgnored() {
        val pending = ICanHealthCalibrationPending()
        val old = pending.begin(bean)!!
        pending.cancel(old)
        val current = pending.begin(bean.copy(caliK = 3f))!!
        assertNull(pending.onAck(old, 0x01))
        assertNull(pending.onWriteResult(old, 0))
        val settled = pending.onWriteResult(current, 0)
        assertNull(settled) // ack for the current request has not arrived yet
        val done = pending.onAck(current, 0x01)
        assertEquals(bean.copy(caliK = 3f), done!!.result)
    }

    @Test
    fun clearDropsEveryPendingOutcome() {
        val pending = ICanHealthCalibrationPending()
        val id = pending.begin(bean)!!
        pending.clear()
        assertNull(pending.onAck(id, 0x01))
        assertNull(pending.onWriteResult(id, 0))
    }
}
