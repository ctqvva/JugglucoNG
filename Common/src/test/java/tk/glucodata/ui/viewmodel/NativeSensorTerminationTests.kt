package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.NativeSensorTermination

class NativeSensorTerminationTests {
    private class FakeAccess(
        private val active: Array<String>? = emptyArray(),
        private val removeFailure: Throwable? = null,
    ) : NativeSensorTermination.Access {
        var removedSensorId: String? = null

        override fun remove(sensorId: String): Boolean {
            removeFailure?.let { throw it }
            removedSensorId = sensorId
            return true
        }

        override fun activeSensors(): Array<String>? = active
    }

    private val exactMatch: (String, String) -> Boolean = { candidate, expected ->
        candidate.equals(expected, ignoreCase = true)
    }

    @Test
    fun removeAndConfirm_resolvesTheSelectedRecordBySensorId() {
        val access = FakeAccess(active = arrayOf("XX0TEST000A"))

        val result = NativeSensorTermination.removeAndConfirm(
            "XX0TEST000B",
            access,
            exactMatch,
        )

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
        assertEquals("XX0TEST000B", access.removedSensorId)
    }

    @Test
    fun removeAndConfirm_rejectsAnEntryThatRemainsActive() {
        val access = FakeAccess(active = arrayOf("old-sensor", "new-sensor"))

        val result = NativeSensorTermination.removeAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
        assertEquals("OLD-SENSOR", access.removedSensorId)
    }

    @Test
    fun removeAndConfirm_rejectsOneSurvivingDuplicateEntry() {
        val access = FakeAccess(active = arrayOf("OLD-SENSOR", "OLD-SENSOR", "NEW-SENSOR"))

        val result = NativeSensorTermination.removeAndConfirm("old-sensor", access)

        assertEquals(NativeSensorTermination.Result.STILL_ACTIVE, result)
    }

    @Test
    fun removeAndConfirm_doesNotCollapseDifferentPhysicalSensorIds() {
        val access = FakeAccess(active = arrayOf("XX0TEST000A"))

        val result = NativeSensorTermination.removeAndConfirm("XX0TEST000B", access)

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
    }

    @Test
    fun removeAndConfirm_doesNotClaimSuccessWhenActiveStateIsUnavailable() {
        val access = FakeAccess(active = null)

        val result = NativeSensorTermination.removeAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.ACTIVE_STATE_UNAVAILABLE, result)
    }

    @Test
    fun removeAndConfirm_acceptsAnAlreadyInactiveSensor() {
        val access = FakeAccess(active = arrayOf("NEW-SENSOR"))

        val result = NativeSensorTermination.removeAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.CONFIRMED, result)
    }

    @Test
    fun removeAndConfirm_reportsNativeFailure() {
        val access = FakeAccess(removeFailure = IllegalStateException("test failure"))

        val result = NativeSensorTermination.removeAndConfirm("OLD-SENSOR", access, exactMatch)

        assertEquals(NativeSensorTermination.Result.FAILED, result)
    }
}
