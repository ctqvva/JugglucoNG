package tk.glucodata.share.ican

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.share.ican.ICanHealthCloudUploader.Companion.resolveFlushRequest

class FlushScheduleTests {

    private val now = 1_000_000L

    @Test
    fun immediateRequestWithNoPendingSchedulesNow() {
        assertEquals(0L, resolveFlushRequest(pendingDueAtMs = 0L, newDelayMs = 0L, nowMs = now))
    }

    @Test
    fun cooldownRequestWithNoPendingSchedulesCooldown() {
        assertEquals(300_000L, resolveFlushRequest(pendingDueAtMs = 0L, newDelayMs = 300_000L, nowMs = now))
    }

    @Test
    fun immediateRequestDoesNotCancelPendingCooldown() {
        assertNull(resolveFlushRequest(pendingDueAtMs = now + 300_000L, newDelayMs = 0L, nowMs = now))
    }

    @Test
    fun laterCooldownDoesNotCancelPendingImmediate() {
        assertNull(resolveFlushRequest(pendingDueAtMs = now, newDelayMs = 300_000L, nowMs = now))
    }

    @Test
    fun immediateRequestDoesNotCancelPendingImmediate() {
        assertNull(resolveFlushRequest(pendingDueAtMs = now, newDelayMs = 0L, nowMs = now))
    }
}
