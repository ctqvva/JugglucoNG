package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnytimeCt5ReconnectCatchupTests {
    @Test
    fun october29OutageIncludesTheNewestOverdueReading() {
        // Last live: id 129 at 17:09:48. Reconnect at 17:18:59 estimates id 132
        // (due at 17:18:48), but used to pull only 130..131 at 17:19:45.
        assertEquals(
            AnytimeIdRange(130, 133),
            ct5ReconnectCatchupGap(129, 129, 132, 480),
        )
    }

    @Test
    fun theEarlierReconnectAlsoIncludesTheMissedCurrentSlot() {
        // At 17:16:25, id 131's scheduled push at 17:15:48 was already missed.
        assertEquals(
            AnytimeIdRange(130, 132),
            ct5ReconnectCatchupGap(129, 129, 131, 480),
        )
    }

    @Test
    fun aSingleOverdueIdStillNeedsCatchup() {
        assertEquals(AnytimeIdRange(130, 131), ct5ReconnectCatchupGap(129, 129, 130, 480))
    }

    @Test
    fun anObservedCurrentIdStaysWithTheLivePath() {
        // A real current frame can carry no glucose. Preserve the live-jump
        // boundary instead of continually asking history for that same id.
        assertEquals(AnytimeIdRange(130, 132), ct5ReconnectCatchupGap(129, 132, 132, 480))
        assertNull(ct5ReconnectCatchupGap(131, 132, 132, 480))
    }

    @Test
    fun aFrozenFinalIdDoesNotCreateFutureHistory() {
        assertNull(ct5ReconnectCatchupGap(8175, 8175, 8175, 480))
        assertNull(ct5ReconnectCatchupGap(8174, 8175, 8175, 480))
    }

    @Test
    fun liveDataThatOvertakesTheEstimateIsNotRequestedAgain() {
        assertNull(ct5ReconnectCatchupGap(133, 133, 132, 480))
        assertNull(ct5ReconnectCatchupGap(132, 132, 132, 480))
    }

    @Test
    fun anUnknownSessionDoesNotReplayFromZero() {
        assertNull(ct5ReconnectCatchupGap(-1, -1, 132, 480))
        assertNull(ct5ReconnectCatchupGap(129, 129, -1, 480))
    }

    @Test
    fun longOutagesKeepTheNewestBoundedTailIncludingTheOverdueId() {
        assertEquals(AnytimeIdRange(521, 1001), ct5ReconnectCatchupGap(0, 0, 1000, 480))
    }
}
