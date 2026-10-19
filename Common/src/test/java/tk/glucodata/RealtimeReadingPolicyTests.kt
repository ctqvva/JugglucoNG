package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeReadingPolicyTests {

    @Test
    fun staleHistoryCallbackCannotRegressRealtimeSurfaces() {
        assertFalse(
            RealtimeReadingPolicy.shouldDispatch(
                1_782_006_372_000L,
                1_782_133_332_000L,
                1_782_133_332_000L
            )
        )
    }

    @Test
    fun equalAndNewerReadingsRemainDispatchable() {
        assertTrue(RealtimeReadingPolicy.shouldDispatch(2_000L, 2_000L, 2_000L))
        assertTrue(RealtimeReadingPolicy.shouldDispatch(2_001L, 2_000L, 2_000L))
    }

    @Test
    fun nativeHighWaterAllowsSubSecondLiveTimestampSkew() {
        assertTrue(
            RealtimeReadingPolicy.shouldDispatch(
                1_783_587_719_998L,
                1_783_587_660_050L,
                1_783_587_720_000L
            )
        )
    }

    @Test
    fun inMemoryHighWaterStillBlocksOlderCallbacks() {
        assertFalse(
            RealtimeReadingPolicy.shouldDispatch(
                1_783_587_719_998L,
                1_783_587_720_000L,
                1_783_587_720_000L
            )
        )
    }

    @Test
    fun nativeHighWaterProtectsAfterProcessStateReset() {
        assertFalse(RealtimeReadingPolicy.shouldDispatch(1_000L, 0L, 2_000L))
    }
}
