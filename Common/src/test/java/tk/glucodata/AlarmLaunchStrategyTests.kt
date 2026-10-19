package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmLaunchStrategyTests {

    @Test
    fun interactiveDeviceUsesDirectLaunchOnly() {
        assertTrue(AlarmLaunchStrategy.shouldAttemptDirectStart())
        assertFalse(AlarmLaunchStrategy.shouldQueueBackup(true))
    }

    @Test
    fun lockedDeviceAttemptsDirectLaunchAndQueuesBackup() {
        assertTrue(AlarmLaunchStrategy.shouldAttemptDirectStart())
        assertTrue(AlarmLaunchStrategy.shouldQueueBackup(false))
    }
}
