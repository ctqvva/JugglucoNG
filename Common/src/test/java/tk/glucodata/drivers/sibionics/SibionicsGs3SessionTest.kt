package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.sibionics.SibionicsGs3Protocol.FrameCipher
import tk.glucodata.drivers.sibionics.SibionicsGs3ProtocolTest.Companion.bytes
import tk.glucodata.drivers.sibionics.SibionicsGs3ProtocolTest.Companion.page
import tk.glucodata.drivers.sibionics.SibionicsGs3Session.Action
import tk.glucodata.drivers.sibionics.SibionicsGs3Session.AuthAttempt
import tk.glucodata.drivers.sibionics.SibionicsGs3Session.State

class SibionicsGs3SessionTest {
    private val mac = bytes("11 22 33 44 55 66")
    private var now = 1_700_000_000_000L
    private val nowSeconds get() = now / 1000L

    private fun session(cursor: Int = 0) = SibionicsGs3Session(mac, cursor) { now }

    /** The sensor's reply, encrypted the way the session currently talks. */
    private fun reply(session: SibionicsGs3Session, plain: ByteArray): ByteArray =
        SibionicsGs3Protocol.applyCipher(session.cipher, mac, plain)!!

    private fun ack(cmd: Int, result: Int = 0, arg: Int = 0) =
        SibionicsGs3Protocol.frame(cmd, byteArrayOf(result.toByte(), arg.toByte()))

    private fun sysInfo(subtype: Int) = SibionicsGs3Protocol.frame(0xF1, byteArrayOf(subtype.toByte(), 0))

    private fun List<Action>.sends() = filterIsInstance<Action.Send>()

    private fun List<Action>.onlySend(): Action.Send = sends().single()

    @Test
    fun `v3 handshake reaches streaming and asks from the cursor`() {
        val s = session(cursor = 41)
        val auth = s.start().onlySend()
        assertEquals(State.WAITING_AUTH, s.state)
        assertEquals(FrameCipher.REGISTERED_V3, s.cipher)
        assertArrayEquals(SibionicsGs3Protocol.buildAuthV3(mac), auth.plain)
        assertArrayEquals(
            SibionicsGs3Protocol.aesOfb(SibionicsGs3Protocol.AUTH_KEY, mac + ByteArray(10), auth.plain),
            auth.wire,
        )

        val bindIn = s.onNotification(reply(s, ack(0xE3))).onlySend()
        assertEquals(State.WAITING_BIND_IN, s.state)
        assertArrayEquals(SibionicsGs3Protocol.buildBindIn(nowSeconds), bindIn.plain)

        // The BindIn ACK arrives in its 10-byte form.
        val bindAck = SibionicsGs3Protocol.frame(0x36, bytes("00 00 00 00 00 00 00"))
        val sysInfo = s.onNotification(reply(s, bindAck)).onlySend()
        assertEquals(State.WAITING_SYSINFO15, s.state)
        assertArrayEquals(bytes("03 F0 0F FE"), sysInfo.plain)

        val getData = s.onNotification(reply(s, sysInfo(15))).onlySend()
        assertEquals(State.STREAMING, s.state)
        assertArrayEquals(SibionicsGs3Protocol.buildGetDataV3(42), getData.plain)
        assertTrue(s.isV3)
    }

    @Test
    fun `bind-in alt ack 0xC039 is accepted`() {
        val s = session()
        s.start()
        s.onNotification(reply(s, ack(0xE3)))
        s.onNotification(reply(s, ack(0x39)))
        assertEquals(State.WAITING_SYSINFO15, s.state)
    }

    @Test
    fun `auth falls back plain then v2 rc4 then fails`() {
        val s = session()
        s.start()
        assertEquals(AuthAttempt.V3_REGISTERED, s.authAttempt)

        val plain = s.onStepTimeout().onlySend()
        assertEquals(AuthAttempt.V3_PLAIN, s.authAttempt)
        assertEquals(FrameCipher.PLAIN, s.cipher)
        assertArrayEquals(SibionicsGs3Protocol.buildAuthV3(mac), plain.wire)

        val v2 = s.onStepTimeout().onlySend()
        assertEquals(AuthAttempt.V2_RC4, s.authAttempt)
        assertFalse(s.isV3)
        assertEquals(FrameCipher.RC4, s.cipher)
        assertArrayEquals(SibionicsProtocol.encrypt(SibionicsGs3Protocol.buildAuthV2(mac)), v2.wire)

        val failed = s.onStepTimeout()
        assertEquals(State.FAILED, s.state)
        assertTrue(failed.single() is Action.Failed)
    }

    @Test
    fun `an unexpected reply counts as not accepted`() {
        val s = session()
        s.start()
        // The ACK for another command.
        s.onNotification(reply(s, ack(0x02)))
        assertEquals(AuthAttempt.V3_PLAIN, s.authAttempt)
        // Bytes that decode under no cipher.
        s.onNotification(bytes("01 02 03"))
        assertEquals(AuthAttempt.V2_RC4, s.authAttempt)
    }

    @Test
    fun `v3 accepted in plain keeps talking plain`() {
        val s = session()
        s.start()
        s.onStepTimeout()
        val bindIn = s.onNotification(ack(0xE3)).onlySend()
        assertEquals(State.WAITING_BIND_IN, s.state)
        assertArrayEquals(bindIn.plain, bindIn.wire)
    }

    @Test
    fun `v2 handshake runs id-bind, sysinfo 1 and 7, activate and time`() {
        val s = session(cursor = 0)
        s.start()
        s.onStepTimeout()
        s.onStepTimeout()
        assertEquals(AuthAttempt.V2_RC4, s.authAttempt)

        val idBind = s.onNotification(reply(s, ack(0x02))).onlySend()
        assertEquals(State.WAITING_ID_BIND, s.state)
        assertArrayEquals(SibionicsGs3Protocol.buildIdBind(1), idBind.plain)

        // Any result other than 0 or 2 retries once with seq 2.
        val retry = s.onNotification(reply(s, ack(0x14, result = 1))).onlySend()
        assertArrayEquals(SibionicsGs3Protocol.buildIdBind(2), retry.plain)
        assertEquals(State.WAITING_ID_BIND, s.state)

        assertArrayEquals(bytes("03 F0 01 0C"), s.onNotification(reply(s, ack(0x14))).onlySend().plain)
        assertEquals(State.WAITING_SYSINFO1, s.state)
        assertArrayEquals(bytes("03 F0 07 06"), s.onNotification(reply(s, sysInfo(1))).onlySend().plain)
        assertEquals(State.WAITING_SYSINFO7, s.state)
        assertArrayEquals(
            SibionicsGs3Protocol.buildActivate(nowSeconds),
            s.onNotification(reply(s, sysInfo(7))).onlySend().plain,
        )
        assertEquals(State.WAITING_ACTIVATE, s.state)
        assertArrayEquals(
            SibionicsGs3Protocol.buildTime(nowSeconds),
            s.onNotification(reply(s, ack(0x10))).onlySend().plain,
        )
        assertEquals(State.WAITING_TIME, s.state)
        val getData = s.onNotification(reply(s, ack(0x04))).onlySend()
        assertEquals(State.STREAMING, s.state)
        assertArrayEquals(SibionicsGs3Protocol.buildGetDataV2(1), getData.plain)
        assertArrayEquals(SibionicsProtocol.encrypt(getData.plain), getData.wire)

        val pageActions = s.onNotification(reply(s, page(0x32, start = 5, trailer = 0, glucoseX10 = listOf(64))))
        val received = pageActions.filterIsInstance<Action.Page>().single().page
        assertEquals(64, received.records.single().glucoseX10)
        assertEquals(5, s.cursor)
    }

    private fun toIdBind(s: SibionicsGs3Session) {
        s.start()
        s.onStepTimeout()
        s.onStepTimeout()
        s.onNotification(reply(s, ack(0x02)))
    }

    @Test
    fun `id-bind result 2 is a wrong account`() {
        val s = session()
        toIdBind(s)
        val actions = s.onNotification(reply(s, ack(0x14, result = 2)))
        assertEquals(State.FAILED, s.state)
        assertTrue((actions.single() as Action.Failed).reason.contains("another account"))
    }

    @Test
    fun `id-bind rejected twice fails`() {
        val s = session()
        toIdBind(s)
        s.onNotification(reply(s, ack(0x14, result = 3)))
        s.onNotification(reply(s, ack(0x14, result = 3)))
        assertEquals(State.FAILED, s.state)
    }

    private fun streamingV3(cursor: Int = 0): SibionicsGs3Session {
        val s = session(cursor)
        s.start()
        s.onNotification(reply(s, ack(0xE3)))
        s.onNotification(reply(s, ack(0x36)))
        s.onNotification(reply(s, sysInfo(15)))
        assertEquals(State.STREAMING, s.state)
        return s
    }

    @Test
    fun `pages move the cursor to their first index and never back`() {
        val s = streamingV3(cursor = 10)
        s.onNotification(reply(s, page(0x39, start = 20, trailer = 0, glucoseX10 = listOf(50, 51))))
        assertEquals(20, s.cursor)
        s.onNotification(reply(s, page(0x39, start = 12, trailer = 9, glucoseX10 = listOf(50))))
        assertEquals(20, s.cursor)
        // An empty page carries no index to resume from.
        s.onNotification(reply(s, page(0x39, start = 90, trailer = 0, glucoseX10 = emptyList())))
        assertEquals(20, s.cursor)
        assertArrayEquals(SibionicsGs3Protocol.buildGetDataV3(21), s.requestNextPage()!!.plain)
    }

    @Test
    fun `next page is only requested while streaming`() {
        val s = session()
        assertNull(s.requestNextPage())
        s.start()
        assertNull(s.requestNextPage())
    }

    @Test
    fun `get-data ack and stray frames do not disturb streaming`() {
        val s = streamingV3()
        val actions = s.onNotification(reply(s, ack(0x3A)))
        assertEquals(State.STREAMING, s.state)
        assertTrue(actions.sends().isEmpty())
        assertTrue(s.onStepTimeout().isEmpty())
    }

    @Test
    fun `a page split over default-mtu notifications is reassembled`() {
        val s = streamingV3()
        val wire = reply(s, page(0x39, start = 3, trailer = 0, glucoseX10 = listOf(55, 56, 57)))
        assertEquals(56, wire.size)
        val chunks = wire.toList().chunked(20).map { it.toByteArray() }
        assertTrue(s.onNotification(chunks[0]).filterIsInstance<Action.Page>().isEmpty())
        assertTrue(s.onNotification(chunks[1]).filterIsInstance<Action.Page>().isEmpty())
        val page = s.onNotification(chunks[2]).filterIsInstance<Action.Page>().single().page
        assertEquals(listOf(55, 56, 57), page.records.map { it.glucoseX10 })
    }

    @Test
    fun `a stale fragment is dropped`() {
        val s = streamingV3()
        val first = reply(s, page(0x39, start = 3, trailer = 0, glucoseX10 = listOf(55, 56))).copyOf(20)
        s.onNotification(first)
        now += 5_000L
        val whole = reply(s, page(0x39, start = 4, trailer = 0, glucoseX10 = listOf(60)))
        val page = s.onNotification(whole.copyOf(20)).filterIsInstance<Action.Page>()
        assertTrue(page.isEmpty())
        val done = s.onNotification(whole.copyOfRange(20, whole.size)).filterIsInstance<Action.Page>().single()
        assertEquals(4, done.page.startIndex)
    }

    @Test
    fun `pages before auth are not taken as an auth answer`() {
        val s = session()
        s.start()
        s.onNotification(reply(s, page(0x32, start = 1, trailer = 0, glucoseX10 = listOf(50))))
        assertEquals(AuthAttempt.V3_REGISTERED, s.authAttempt)
        assertEquals(State.WAITING_AUTH, s.state)
    }

    @Test
    fun `only auth frames are marked as carrying the session key`() {
        val s = session()
        assertTrue(s.start().onlySend().sensitive)
        assertTrue(s.onStepTimeout().onlySend().sensitive)
        assertTrue(s.onStepTimeout().onlySend().sensitive)
        val idBind = s.onNotification(reply(s, ack(0x02))).onlySend()
        assertFalse(idBind.sensitive)
        val v3 = session()
        v3.start()
        assertFalse(v3.onNotification(reply(v3, ack(0xE3))).onlySend().sensitive)
    }

    @Test
    fun `each real request bumps the send count and ignored frames do not`() {
        val s = session()
        toIdBind(s)
        val afterFirst = s.sendCount
        // An ignored frame in the same state sends nothing.
        s.onNotification(reply(s, sysInfo(9)))
        assertEquals(afterFirst, s.sendCount)
        assertEquals(State.WAITING_ID_BIND, s.state)
        // The seq 2 retry stays in WAITING_ID_BIND but is a new attempt.
        s.onNotification(reply(s, ack(0x14, result = 1)))
        assertEquals(State.WAITING_ID_BIND, s.state)
        assertEquals(afterFirst + 1, s.sendCount)
    }

    @Test
    fun `handshake silence after auth fails the connection`() {
        val s = session()
        s.start()
        s.onNotification(reply(s, ack(0xE3)))
        assertTrue(s.onStepTimeout().single() is Action.Failed)
        assertEquals(State.FAILED, s.state)
    }
}
