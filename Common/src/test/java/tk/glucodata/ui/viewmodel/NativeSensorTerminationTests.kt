package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.NativeSensorTermination

class NativeSensorTerminationTests {
    private class FakeAccess(
        private val sensorPointer: Long = 42L,
        private val active: Array<String>? = emptyArray(),
        private val finishFailure: Throwable? = null,
    ) : NativeSensorTermination.Access {
        var finishedPointer: Long? = null
        var requestedSensorId: String? = null

        override fun findSensorPointer(sensorId: String): Long {
            requestedSensorId = sensorId
            return sensorPointer
        }

        override fun finish(dataPointer: Long) {
            finishFailure?.let { throw it }
            finishedPointer = dataPointer
        }

        override fun activeSensors(): Array<String>? = active
    }

    private val exactMatch: (String, String) -> Boolean = { candidate, expected ->
        candidate.equals(expected, ignoreCase = true)
    }

    @Test
    fun finishAndConfirm_targetsTheExactNamedSensorInsteadOfTheLiveCallbackPointer() {
        val access = FakeAccess()

        val result = NativeSensorTermination.finishAndConfirm(
            "OLD-SENSOR",
            73L,
            access,
            exactMatch,
        )

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertEquals(42L, access.finishedPointer)
        assertEquals("OLD-SENSOR", access.requestedSensorId)
    }

    @Test
    fun finishAndConfirm_rejectsAnEntryThatRemainsActive() {
        val access = FakeAccess(active = arrayOf("old-sensor", "new-sensor"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 73L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertEquals(42L, access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_doesNotClaimSuccessWhenActiveStateIsUnavailable() {
        val access = FakeAccess(active = null)

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 73L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.ACTIVE_STATE_UNAVAILABLE, result)
    }

    @Test
    fun finishAndConfirm_usesTheSameNamedPathForCallbackFreeSensor() {
        val access = FakeAccess(active = arrayOf("new-sensor"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 0L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertEquals("OLD-SENSOR", access.requestedSensorId)
        assertEquals(42L, access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_rejectsAnActiveSensorWithoutAPointer() {
        val access = FakeAccess(sensorPointer = 0L, active = arrayOf("OLD-SENSOR"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 0L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertEquals(null, access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_reportsNativeFailure() {
        val access = FakeAccess(finishFailure = IllegalStateException("test failure"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 73L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.FAILED, result)
    }

    @Test
    fun finishAndConfirm_reportsNamedNativeFailureWithoutReconnecting() {
        val access = FakeAccess(finishFailure = IllegalStateException("test failure"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", 0L, access, exactMatch)

        assertEquals(NativeSensorTermination.Result.FAILED, result)
        assertEquals("OLD-SENSOR", access.requestedSensorId)
    }
}
