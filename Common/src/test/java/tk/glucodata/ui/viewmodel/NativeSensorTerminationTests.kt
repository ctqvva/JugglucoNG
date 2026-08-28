package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSensorTerminationTests {
    private class FakeAccess(
        private val pointer: Long = 42L,
        private val active: Array<String>? = emptyArray(),
        private val finishFailure: Throwable? = null,
    ) : NativeSensorTermination.Access {
        var finishedPointer: Long? = null

        override fun sensorPointer(sensorId: String): Long = pointer

        override fun finish(sensorPointer: Long) {
            finishFailure?.let { throw it }
            finishedPointer = sensorPointer
        }

        override fun activeSensors(): Array<String>? = active
    }

    private val exactMatch: (String, String) -> Boolean = { candidate, expected ->
        candidate.equals(expected, ignoreCase = true)
    }

    @Test
    fun finishAndConfirm_finishesPointerAndRequiresSensorToBeAbsent() {
        val access = FakeAccess()

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertEquals(42L, access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_rejectsAnEntryThatRemainsActive() {
        val access = FakeAccess(active = arrayOf("old-sensor", "new-sensor"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertEquals(42L, access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_doesNotClaimSuccessWhenActiveStateIsUnavailable() {
        val access = FakeAccess(active = null)

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.ACTIVE_STATE_UNAVAILABLE, result)
    }

    @Test
    fun finishAndConfirm_acceptsAnAlreadyInactiveSensorWithoutAPointer() {
        val access = FakeAccess(pointer = 0L, active = arrayOf("new-sensor"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertNull(access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_rejectsAnActiveSensorWithoutAPointer() {
        val access = FakeAccess(pointer = 0L, active = arrayOf("OLD-SENSOR"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertNull(access.finishedPointer)
    }

    @Test
    fun finishAndConfirm_reportsNativeFailure() {
        val access = FakeAccess(finishFailure = IllegalStateException("test failure"))

        val result = NativeSensorTermination.finishAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.FAILED, result)
    }
}
