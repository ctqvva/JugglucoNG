package tk.glucodata.glucosemeter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VerioSessionTest {

    private fun hex(value: ByteArray) = value.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `get time command is framed with a trailing crc`() {
        val command = VerioSession.command(0x20, 0x02)
        assertEquals("01 02 09 00 03 20 02 03 D4 92", hex(command))
    }

    @Test
    fun `record counter command is framed with a trailing crc`() {
        val command = VerioSession.command(0x0a, 0x02, 0x06)
        assertEquals("01 02 0A 00 03 0A 02 06 03 0A 3F", hex(command))
    }

    @Test
    fun `record command carries the record number little endian`() {
        assertEquals("01 02 0A 00 03 B3 07 00 03 FA 7C", hex(VerioSession.command(0xb3, 0x07, 0x00)))
        assertEquals("01 02 0A 00 03 B3 00 01 03 5B CA", hex(VerioSession.command(0xb3, 0x00, 0x01)))
    }

    @Test
    fun `size field counts the whole frame but not the leading header`() {
        val command = VerioSession.command(0x27, 0x00)
        // wire layout: 0x01 header, 0x02 frame start, then the size
        assertEquals(command.size - 1, command[2].toInt())
    }

    @Test
    fun `single byte notification is the meter acking a command`() {
        val session = VerioSession()
        session.reset()
        session.onNotification(byteArrayOf(0x81.toByte()), -1)
        assertEquals("nothing queued before a session is begun", null, session.take())
    }

    @Test
    fun `begin queues the meter time command first`() {
        val session = VerioSession()
        session.begin()
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        assertEquals("only one command may be in flight", null, session.take())
    }

    @Test
    fun `the ack alone does not move the handshake on`() {
        val session = VerioSession()
        session.begin()
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        session.onNotification(byteArrayOf(0x81.toByte()), -1)
        assertEquals("ack must not queue the next request", null, session.take())
    }

    @Test
    fun `a data packet acks and then asks for the next thing`() {
        val session = VerioSession()
        session.begin()
        assertArrayEquals(VerioSession.command(0x20, 0x02), session.take())
        session.onNotification(VerioSession.command(0x06, 0x3e, 0x3c, 0x4e, 0x32), -1)
        assertArrayEquals("ack first", byteArrayOf(0x81.toByte()), session.take())
        assertArrayEquals("then the record counter", VerioSession.command(0x0a, 0x02, 0x06), session.take())
        assertEquals("and nothing after that", null, session.take())
    }

    /**
     * The exact bytes the OneTouch sent for record 3184, and what command()
     * reproduces from the 12 payload bytes, crc included:
     * 01 02 13 00 03 06 BB 81 44 31 44 00 00 00 00 00 00 03 F2 9A
     */
    private val deviceRecordPacket = byteArrayOf(
        0x01, 0x02, 0x13, 0x00, 0x03, 0x06, 0xbb.toByte(), 0x81.toByte(), 0x44, 0x31,
        0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xf2.toByte(), 0x9a.toByte())

    private fun recordPacket() = deviceRecordPacket

    @Test
    fun `the framing reproduces a packet the meter really sent`() {
        assertArrayEquals(
            deviceRecordPacket,
            VerioSession.command(0x06, 0xbb, 0x81, 0x44, 0x31, 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    }

    private fun handshakenSession(): VerioSession {
        val session = VerioSession()
        session.begin()
        session.take() // get time
        session.onNotification(VerioSession.command(0x06, 0x39, 0x43, 0x4e, 0x32), -1)
        session.take() // ack
        session.take() // get record counter
        session.onNotification(VerioSession.command(0x06, 0x70, 0x0c, 0x00, 0x00), -1)
        session.take() // ack
        session.take() // get record count
        session.onNotification(VerioSession.command(0x06, 0xf4, 0x01), -1)
        session.take() // ack
        session.take() // first record request
        return session
    }

    @Test
    fun `a reading is taken from offset 4, not from the start of the payload`() {
        val session = handshakenSession()
        session.onNotification(recordPacket(), -1)
        val readings = session.takeReadings()
        assertEquals(1, readings.size)
        // bytes 4 and 5 are 0x44 0x00, not the 0xbb 0x81 at the front
        assertEquals("68 mg/dl", 68 * 10, readings[0].mgdlTenths)
    }

    @Test
    fun `a rejected reading still moves the walk on`() {
        val session = handshakenSession()
        // a record with a wild value in it
        session.onNotification(VerioSession.command(
            0x06, 0xbb, 0x81, 0x44, 0x31, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00), -1)
        assertEquals("nothing stored", 0, session.takeReadings().size)
        session.take() // ack
        // asking for the same record again would loop forever
        assertNotEquals(VerioSession.command(0xb3, 0x70, 0x0c), session.take())
    }

    @Test
    fun `storing a reading does not shorten the walk`() {
        val session = handshakenSession()
        session.onNotification(recordPacket(), -1)
        assertEquals(1, session.takeReadings().size)
        session.take() // ack
        // record 3184 was stored, so the walk must continue to 3183 rather
        // than stopping against the value we just wrote
        assertArrayEquals(VerioSession.command(0xb3, 0x6f, 0x0c), session.take())
    }

    /** A session that got as far as walking records, for the walk bound tests. */
    private fun walkSession(highest: Int, count: Int, alreadyHave: Int = -1): VerioSession {
        val session = VerioSession()
        session.begin()
        session.take()
        session.onNotification(VerioSession.command(0x06, 0x39, 0x43, 0x4e, 0x32), alreadyHave)
        session.take()
        session.take()
        session.onNotification(VerioSession.command(0x06, highest and 0xff, (highest shr 8) and 0xff, 0, 0), alreadyHave)
        session.take()
        session.take()
        session.onNotification(VerioSession.command(0x06, count and 0xff, (count shr 8) and 0xff), alreadyHave)
        return session
    }

    @Test
    fun `the walk stops at the oldest record the meter still keeps`() {
        // 5 readings, highest 10: asking below 6 has to stop, not run to zero
        val session = walkSession(highest = 10, count = 5)
        session.take() // ack
        val first = session.take()
        assertArrayEquals(VerioSession.command(0xb3, 0x0a, 0x00), first)

        var last: ByteArray? = null
        for (i in 0 until 20) {
            session.onNotification(recordPacket(), -1)
            session.take() // ack
            val next = session.take() ?: break
            last = next
            session.putBack(next)
            session.take()
        }
        // the last request must be 6, and record 5 was never asked for
        assertEquals("stopped one above the oldest", "01 02 0A 00 03 B3 06 00 03 CA 4B", hex(last!!))
    }

    @Test
    fun `a meter with fewer readings than the cap is not over-asked`() {
        val session = walkSession(highest = 10, count = 5)
        session.take() // ack
        session.take() // record 10
        session.onNotification(recordPacket(), -1)
        session.take() // ack
        assertArrayEquals(VerioSession.command(0xb3, 0x09, 0x00), session.take())
        session.onNotification(recordPacket(), -1)
        session.take() // ack
        session.take() // record 8
        session.onNotification(recordPacket(), -1)
        session.take() // ack
        session.take() // record 7
        session.onNotification(recordPacket(), -1)
        session.take() // ack
        session.take() // record 6
        session.onNotification(recordPacket(), -1)
        session.take() // ack
        assertEquals("record 5 does not exist, walk is over", null, session.take())
    }

    @Test
    fun `an error answer is acked and ends the walk instead of stalling`() {
        val session = handshakenSession()
        // 0x07 0x03 is "command not allowed"
        session.onNotification(VerioSession.command(0x07, 0x03), -1)
        assertArrayEquals("the meter still gets its ack", byteArrayOf(0x81.toByte()), session.take())
        assertEquals("and no further request is queued", null, session.take())
    }

    @Test
    fun `a command survives a failed write`() {
        val session = VerioSession()
        session.begin()
        val command = session.take()!!
        session.putBack(command)
        assertArrayEquals("taken again after a failed write", command, session.take())
        assertNull(session.take())
    }

    @Test
    fun `a non positive record cannot freeze the walk`() {
        val session = handshakenSession()
        // the meter answers a request we never made, with record 0
        session.onNotification(recordPacket(), -1)
        assertEquals(1, session.takeReadings().size)
        session.take() // ack
        val next = session.take()
        assertNotNull(next)
        // the walk keeps producing decreasing indices, never sticking on one
        val record = (next!![6].toInt() and 0xFF) or ((next[7].toInt() and 0xFF) shl 8)
        assertEquals("record 3183 after storing 3184", 3183, record)
    }

    @Test
    fun `a meter that has not rolled over yet still gives up record one`() {
        // one single reading, nothing stored: record 1 is the only one there is
        val session = walkSession(highest = 1, count = 1, alreadyHave = 0)
        session.take() // ack
        assertArrayEquals(VerioSession.command(0xb3, 0x01, 0x00), session.take())
        session.onNotification(recordPacket(), 0)
        session.take() // ack
        assertEquals("record 1 is all there is, walk is over", null, session.take())
    }

    @Test
    fun `every record a fresh meter still keeps is asked for`() {
        // 3 readings and no history evicted, so the oldest existing is 1 and
        // the floor must not skip it
        val session = walkSession(highest = 3, count = 3, alreadyHave = 0)
        session.take() // ack
        val asked = mutableListOf<Int>()
        while (true) {
            val next = session.take() ?: break
            asked.add((next[6].toInt() and 0xFF) or ((next[7].toInt() and 0xFF) shl 8))
            session.putBack(next)
            session.take()
            session.onNotification(recordPacket(), 0)
            session.take() // ack
        }
        assertEquals(listOf(3, 2, 1), asked)
    }

    /** Every packet a real OneTouch DH48 sent, captured from a live session. */
    private val deviceTimePacket = "01 02 0C 00 03 06 39 43 4E 32 03 2A DB"
    private val deviceCounterPacket = "01 02 0C 00 03 06 70 0C 00 00 03 17 BD"
    private val deviceCountPacket = "01 02 0A 00 03 06 F4 01 03 5D B8"
    private val deviceReadingPacket = "01 02 13 00 03 06 BB 81 44 31 44 00 00 00 00 00 00 03 F2 9A"

    private fun packet(text: String): ByteArray {
        val parts = text.split(' ')
        val out = ByteArray(parts.size)
        for (i in parts.indices) out[i] = parts[i].toInt(16).toByte()
        return out
    }

    /**
     * Walks the handshake with the meter's own packets up to the given step, so
     * that the next packet arrives in the stage it would in a real session.
     * Returns the session with nothing left queued.
     */
    private fun sessionAt(step: Int): VerioSession {
        val session = VerioSession()
        session.begin()
        session.take() // get time
        if (step > 0) {
            session.onNotification(packet(deviceTimePacket), -1)
            session.take(); session.take()
        }
        if (step > 1) {
            session.onNotification(packet(deviceCounterPacket), -1)
            session.take(); session.take()
        }
        if (step > 2) {
            session.onNotification(packet(deviceCountPacket), -1)
            session.take(); session.take()
        }
        return session
    }

    @Test
    fun `crc check accepts every packet the meter really sent`() {
        // a rejected packet leaves only the ack queued, an accepted one also
        // queues the next request, so that is what tells the two apart
        val cases = listOf(
            Triple(0, deviceTimePacket, VerioSession.command(0x0a, 0x02, 0x06)),
            Triple(1, deviceCounterPacket, VerioSession.command(0x27, 0x00)),
            Triple(2, deviceCountPacket, VerioSession.command(0xb3, 0x70, 0x0c)),
        )
        for ((step, text, expected) in cases) {
            val session = sessionAt(step)
            session.onNotification(packet(text), -1)
            assertArrayEquals("ack", byteArrayOf(0x81.toByte()), session.take())
            assertArrayEquals("the $text packet must be accepted", expected, session.take())
        }
        val session = sessionAt(3)
        session.onNotification(packet(deviceReadingPacket), -1)
        assertArrayEquals("ack", byteArrayOf(0x81.toByte()), session.take())
        assertArrayEquals("the reading packet must be accepted",
            VerioSession.command(0xb3, 0x6f, 0x0c), session.take())
    }

    @Test
    fun `crc check rejects a packet with a flipped bit`() {
        val broken = packet(deviceReadingPacket).copyOf()
        broken[10] = (broken[10].toInt() xor 0x01).toByte()
        val session = sessionAt(3)
        session.onNotification(broken, -1)
        assertArrayEquals("the meter is still acked", byteArrayOf(0x81.toByte()), session.take())
        assertNull("and nothing else is queued", session.take())
    }

    @Test
    fun `crc check rejects a packet with a tampered length field`() {
        val broken = packet(deviceCountPacket).copyOf()
        broken[2] = 0x0b
        val session = sessionAt(2)
        session.onNotification(broken, -1)
        assertArrayEquals(byteArrayOf(0x81.toByte()), session.take())
        assertNull(session.take())
    }

    @Test
    fun `crc check rejects a packet with a tampered reading`() {
        val broken = packet(deviceReadingPacket).copyOf()
        broken[14] = (broken[14].toInt() xor 0x10).toByte()
        val session = sessionAt(3)
        session.onNotification(broken, -1)
        assertArrayEquals(byteArrayOf(0x81.toByte()), session.take())
        assertNull(session.take())
    }
}
