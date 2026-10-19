package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQLinkPolicyTests {
    private val firstFrameTimeout = 210_000L
    private val frameTimeout = 390_000L

    @Test fun otherSensorsReadingMustNotDisconnectWarmupTrafficFromTrace() {
        assertTrue(MQLinkPolicy.hasRecentActivity(
            nowMs = 1789297363000L, // AiDex reading triggers othersworking at 16:02:43
            connectedAtMs = 1789296946000L,
            lastFrameAtMs = 1789297210000L, // MQ packet 15, no calculated glucose yet
            firstFrameTimeout, frameTimeout,
        ))
    }

    @Test fun initialConnectionHasOneCadencePlusGraceToReceiveItsFirstFrame() {
        val connected = 1_000_000L
        assertTrue(MQLinkPolicy.hasRecentActivity(connected + firstFrameTimeout - 1, connected, 0, firstFrameTimeout, frameTimeout))
        assertFalse(MQLinkPolicy.hasRecentActivity(connected + firstFrameTimeout, connected, 0, firstFrameTimeout, frameTimeout))
    }

    @Test fun actualProtocolSilenceStillAllowsRecovery() {
        val connected = 1_000_000L
        val frame = connected + 180_000L
        assertTrue(MQLinkPolicy.hasRecentActivity(frame + frameTimeout - 1, connected, frame, firstFrameTimeout, frameTimeout))
        assertFalse(MQLinkPolicy.hasRecentActivity(frame + frameTimeout, connected, frame, firstFrameTimeout, frameTimeout))
    }

    @Test fun previousConnectionFrameDoesNotExtendNewConnectionGrace() {
        val connected = 1_000_000L
        assertFalse(MQLinkPolicy.hasRecentActivity(connected + firstFrameTimeout, connected, connected - 1, firstFrameTimeout, frameTimeout))
        assertFalse(MQLinkPolicy.hasRecentActivity(connected, 0, 0, firstFrameTimeout, frameTimeout))
    }
}
