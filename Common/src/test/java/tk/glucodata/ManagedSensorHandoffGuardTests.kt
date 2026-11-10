package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A handoff can name a managed sensor and a Dexcom at once. What the watch
 * already reads is not overwritten by the copy in flight, but a sensor it does
 * not read is still taken: a stale managed id must not hold back the Dexcom
 * setup beside it (#862).
 */
class ManagedSensorHandoffGuardTests {
    private fun reads(vararg serials: String): (String) -> Boolean = { it in serials }

    @Test
    fun aPayloadNamingNothingIsTakenWhole() {
        val parts = handoffToApply(null, null, reads())
        assertNotNull(parts)
        assertTrue(parts!!.managed)
        assertTrue(parts.dexcom)
    }

    @Test
    fun aSensorThisDeviceReadsIsNotTaken() {
        assertNull(handoffToApply("MANAGED", null, reads("MANAGED")))
        assertNull(handoffToApply(null, "DEXSERIAL", reads("DEXSERIAL")))
    }

    @Test
    fun aSensorThisDeviceDoesNotReadIsTaken() {
        assertTrue(handoffToApply("MANAGED", null, reads("OTHER"))!!.managed)
        assertTrue(handoffToApply(null, "DEXSERIAL", reads("OTHER"))!!.dexcom)
    }

    @Test
    fun aStaleManagedSensorDoesNotKeepTheDexcomSetupOut() {
        val parts = handoffToApply("MANAGED", "DEXSERIAL", reads("MANAGED"))
        assertNotNull(parts)
        assertFalse(parts!!.managed)
        assertTrue(parts.dexcom)
    }

    @Test
    fun aDexcomThisDeviceReadsDoesNotKeepTheManagedStateOut() {
        val parts = handoffToApply("MANAGED", "DEXSERIAL", reads("DEXSERIAL"))
        assertNotNull(parts)
        assertTrue(parts!!.managed)
        assertFalse(parts.dexcom)
    }

    @Test
    fun bothSensorsReadHereTakeNothing() {
        assertNull(handoffToApply("MANAGED", "DEXSERIAL", reads("MANAGED", "DEXSERIAL")))
    }

    @Test
    fun neitherSensorReadHereTakesBoth() {
        val parts = handoffToApply("MANAGED", "DEXSERIAL", reads())
        assertEquals(true, parts!!.managed)
        assertEquals(true, parts.dexcom)
    }
}
