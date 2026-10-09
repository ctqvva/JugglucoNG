package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tk.glucodata.ui.overlay.nextOverlayFreshnessCheckDelay

class SecondarySensorDisplayTests {
    private val now = 1_700_000_000_000L
    private val timeout = 330_000L
    private val main = DisplayValues(primaryValue = 112f, primaryStr = "112", fullFormatted = "112")

    private fun peer(id: String, text: String = "85", at: Long = now) =
        PeerCurrentReading(id, text, secondaryStr = "90", rate = -1f, timeMillis = at)

    private fun fallback(primary: DisplayValues? = main, peers: List<PeerCurrentReading>, at: Long = now) =
        SecondarySensorDisplay.fallback(primary, "main", peers, at, timeout)

    @Test
    fun emptySecondarySlotUsesFirstFreshSelectedPeerAndItsPrimaryLane() {
        val first = peer("peer-a")
        val second = peer("peer-b", "100")
        assertSame(first, fallback(peers = listOf(first, second)))
        assertEquals("85", fallback(peers = listOf(first, second))?.primaryStr)
        assertSame(second, fallback(peers = listOf(second, first)))
    }

    @Test
    fun rawAndCalibrationLanesKeepPriorityAcrossViewModes() {
        val peers = listOf(peer("peer"))
        for (mode in 0..3) {
            val calibrated = DisplayValueResolver.resolve(112f, 138f, mode, false, calibratedValue = 120f)
            assertNull("calibration mode $mode", fallback(calibrated, peers))
        }
        for (mode in listOf(2, 3)) {
            val raw = DisplayValueResolver.resolve(112f, 138f, mode, false)
            assertNull("raw mode $mode", fallback(raw, peers))
        }
        assertNull(fallback(main.copy(tertiaryStr = "138"), peers))
    }

    @Test
    fun singleLaneViewAndMissingRawLaneAllowFallback() {
        val peers = listOf(peer("peer"))
        for (mode in 0..3) {
            val missingRaw = DisplayValueResolver.resolve(112f, Float.NaN, mode, false)
            assertSame(peers.first(), fallback(missingRaw, peers))
        }
    }

    @Test
    fun staleOrInvalidPeersAndMainSensorAreSkipped() {
        val fresh = peer("peer-c")
        val peers = listOf(
            peer("MAIN"),
            peer("peer-a", at = now - timeout - 1L),
            peer("peer-b", text = "--"),
            fresh
        )
        assertSame(fresh, fallback(peers = peers))
        assertNull(fallback(peers = peers.dropLast(1)))
        assertNull(fallback(peers = listOf(peer("peer", at = 0L))))
    }

    @Test
    fun absentMainDataOrSingleSensorDoesNotInventASecondaryValue() {
        assertNull(fallback(primary = null, peers = listOf(peer("peer"))))
        assertNull(fallback(peers = emptyList()))
        assertNull(SecondarySensorDisplay.fallback(main, null, listOf(peer("peer")), now, timeout))
    }

    @Test
    fun anOlderPeerExpiresWhileMainReadingRemainsFreshThenNextPeerTakesOver() {
        val older = peer("peer-a", at = now - timeout + 1L)
        val newer = peer("peer-b")
        val peers = listOf(older, newer)
        assertSame(older, fallback(peers = peers))
        val wait = listOf(now, older.timeMillis, newer.timeMillis)
            .mapNotNull { nextOverlayFreshnessCheckDelay(it, now, timeout) }.minOrNull()!!
        assertEquals(2L, wait)
        assertSame(newer, fallback(peers = peers, at = now + wait))
        assertNull(fallback(peers = listOf(older), at = now + wait))
    }

    @Test
    fun fallbackUsesThePeersFormattedDisplayUnitWithoutConvertingAgain() {
        for (isMmol in listOf(false, true)) {
            val primary = DisplayValueResolver.resolve(if (isMmol) 6.2f else 112f, Float.NaN, 0, isMmol)
            val peerValues = DisplayValueResolver.resolve(if (isMmol) 4.7f else 85f, Float.NaN, 0, isMmol)
            val reading = peer("peer", text = peerValues.primaryStr)
            assertEquals(peerValues.primaryStr, fallback(primary, listOf(reading))?.primaryStr)
        }
    }
}
