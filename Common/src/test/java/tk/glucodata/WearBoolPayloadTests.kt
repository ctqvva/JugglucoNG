package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A one-byte on/off payload, and what happens when the byte is not there.
 *
 * `/bluetooth` and `/messages` are both sent as a single byte and both drive real state
 * on the receiving side: a truncated payload used to read `data[0]` regardless, so a peer
 * that sent an empty one threw instead of being ignored. That is the same class of failure
 * the display-prefs and colour payloads already refuse (plan §6 Q2: define what happens
 * with a message you cannot read), and the refusal is the same shape: keep what you have
 * and say so, rather than guess.
 */
class WearBoolPayloadTests {
    @Test
    fun aSetByteIsOn() {
        assertEquals(true, MessageReceiver.booldata(byteArrayOf(1)))
    }

    @Test
    fun aClearByteIsOff() {
        assertEquals(false, MessageReceiver.booldata(byteArrayOf(0)))
    }

    @Test
    fun anyOtherByteIsOn() {
        // The original test is `data[0] != 0`, so a payload this codec never sent still
        // means on. Kept as it was: the change here is about a missing byte, not a
        // stricter reading of the ones that arrive.
        assertEquals(true, MessageReceiver.booldata(byteArrayOf(2, 0, 0)))
    }

    @Test
    fun anEmptyPayloadIsNotOff() {
        assertNull(MessageReceiver.booldata(ByteArray(0)))
    }

    @Test
    fun anAbsentPayloadIsNotOff() {
        assertNull(MessageReceiver.booldata(null))
    }
}
