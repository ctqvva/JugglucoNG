package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.NativeSensorTermination

class NativeSensorTerminationTests {
    private class FakeAccess(
        private val acquiredPointer: Long = 42L,
        private val active: Array<String>? = emptyArray(),
        private val finishFailure: Throwable? = null,
    ) : NativeSensorTermination.Access {
        var finishedPointer: Long? = null
        var acquiredSensorId: String? = null
        var releasedPointer: Long? = null

        override fun acquireDataPointer(sensorId: String): Long {
            acquiredSensorId = sensorId
            return acquiredPointer
        }

        override fun finish(dataPointer: Long) {
            finishFailure?.let { throw it }
            finishedPointer = dataPointer
        }

        override fun releaseDataPointer(dataPointer: Long) {
            releasedPointer = dataPointer
        }

        override fun activeSensors(): Array<String>? = active
    }

    private val exactMatch: (String, String) -> Boolean = { candidate, expected ->
        candidate.equals(expected, ignoreCase = true)
    }

    @Test
    fun finishAndConfirm_usesLiveDataPointerWithoutReconstructingIt() {
        val access = FakeAccess()

        val result = NativeSensorTermination.finishAndConfirm(
            "OLD-SENSOR",
            73L,
            access,
            exactMatch,
        )

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertEquals(73L, access.finishedPointer)
        assertNull(access.acquiredSensorId)
        assertNull(access.releasedPointer)
    }

    @Test
    fun finishAndConfirm_rejectsAnEntryThatRemainsActive() {
        val access = FakeAccess(active = arrayOf("old-sensor", "new-sensor"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 73L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertEquals(73L, access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_doesNotClaimSuccessWhenActiveStateIsUnavailable() {
        val access = FakeAccess(active = null)

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 73L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.ACTIVE_STATE_UNAVAILABLE, result)
    }

    @Test
    fun finishAndConfirm_acquiresAndReleasesPointerForCallbackFreeSensor() {
        val access = FakeAccess(active = arrayOf("new-sensor"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 0L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertEquals("OLD-SENSOR", access.acquiredSensorId)
        assertEquals(42L, access.finishedPointer)
        assertEquals(42L, access.releasedPointer)
    }

    @Test
    fun finishAndConfirm_rejectsAnActiveSensorWithoutAPointer() {
        val access = FakeAccess(acquiredPointer = 0L, active = arrayOf("OLD-SENSOR"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 0L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertNull(access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_reportsNativeFailure() {
        val access = FakeAccess(finishFailure = IllegalStateException("test failure"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 73L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.FAILED, result)
    }

    @Test
    fun finishAndConfirm_releasesCallbackFreePointerAfterNativeFailure() {
        val access = FakeAccess(finishFailure = IllegalStateException("test failure"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 0L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.FAILED, result)
        assertEquals("OLD-SENSOR", access.acquiredSensorId)
        assertEquals(42L, access.releasedPointer)
    }
}
