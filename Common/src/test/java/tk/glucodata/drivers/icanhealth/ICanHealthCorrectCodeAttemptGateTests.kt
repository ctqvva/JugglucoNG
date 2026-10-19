package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one-shot record behind the correctCode write gate.
 *
 * The record itself is a single boolean per serial in `tk.glucodata_preferences`
 * (see [ICanHealthExperimental.hasWriteAttempted] and friends); the decision it
 * feeds — a serial that was ever sent a batch is never written to again, reset is
 * the only way back — is exercised here through the same gate call the manager
 * makes. The in-memory stand-in uses the production storage-key rule
 * ([correctCodeAttemptStorageKey]), so a change to the key or the fail-closed
 * behaviour breaks these tests.
 */
class ICanHealthCorrectCodeAttemptGateTests {

    private val serial = "8760080A00070000"
    private val otherSerial = "1111222233334444"

    private val key = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (it * 3 + 5).toByte() }
    private val table = requireNotNull(
        ICanHealthCorrectCode.parseCorrectCodeTable(TEST_CORRECT_CODE_JSON)
    )

    /** `tk.glucodata_preferences` reduced to what the gate uses: a keyed flag. */
    private val attemptedKeys = HashSet<String>()

    private fun hasAttempted(serial: String?): Boolean =
        correctCodeAttemptStorageKey(serial)?.let { attemptedKeys.contains(it) } ?: true

    private fun markAttempted(serial: String?): Boolean =
        correctCodeAttemptStorageKey(serial)?.let { attemptedKeys.add(it) } ?: false

    private fun resetAttempt(serial: String?): Boolean =
        correctCodeAttemptStorageKey(serial)?.let { attemptedKeys.remove(it) } ?: false

    /** The manager's write decision for [serial] right now. */
    private fun planForSerialNow(serial: String = this.serial): CorrectCodePlan? =
        ICanHealthCorrectCode.planCorrectCodeWrite(
            f6Value = ICanHealthConstants.CORRECT_CODE_ABSENT_VALUE,
            isAuthenticated = true,
            launcherState = ICanHealthConstants.LAUNCHER_STATE_IDLE,
            writeAttempted = hasAttempted(serial),
            table = table,
            key = key,
            iv = iv,
            path = CorrectCodeProvisioningPath.SERVER_TABLE,
            launcherVersionFlag = true,
            layout = CorrectCodeLayout.CBC_SINGLE_BLOB,
        )

    @Test
    fun gateRefusesOnceTheSerialIsMarkedAttemptedAndAllowsOnceAfterReset() {
        assertNotNull("clean serial must be writable", planForSerialNow())
        assertTrue(markAttempted(serial))
        assertNull("attempted serial must refuse", planForSerialNow())
        assertTrue(resetAttempt(serial))
        assertNotNull("after the developer reset a plan is allowed again", planForSerialNow())
    }

    @Test
    fun attemptRecordIsPerSerialAndClearedOnlyByTheDeveloperReset() {
        assertFalse(hasAttempted(serial))
        assertTrue(markAttempted(serial))
        // Stored, so a reconnect or app update still refuses.
        assertTrue(hasAttempted(serial))
        assertNull(planForSerialNow())
        // A different sensor is untouched by this serial's record.
        assertFalse(hasAttempted(otherSerial))
        assertNotNull(planForSerialNow(otherSerial))
        // The reset is deliberate and reports that there was something to clear.
        assertTrue(resetAttempt(serial))
        assertFalse(hasAttempted(serial))
        assertFalse("a second reset has nothing to clear", resetAttempt(serial))
    }

    @Test
    fun recordLandsUnderThePerSensorPreferenceKey() {
        assertEquals(
            ICanHealthConstants.PREF_CORRECT_CODE_ATTEMPTED_PREFIX + serial,
            correctCodeAttemptStorageKey(serial),
        )
        assertEquals(
            ICanHealthConstants.PREF_CORRECT_CODE_ATTEMPTED_PREFIX + serial,
            correctCodeAttemptStorageKey("  $serial  "),
        )
    }

    @Test
    fun blankSerialCannotBeKeyedAndFailsClosed() {
        // Without a concrete serial the driver cannot prove the sensor is clean,
        // so the store reports "attempted" and the gate refuses rather than
        // risking a second write.
        assertNull(correctCodeAttemptStorageKey(null))
        assertNull(correctCodeAttemptStorageKey("   "))
        assertTrue(hasAttempted(null))
        assertTrue(hasAttempted("  "))
        assertFalse(markAttempted(null))
        assertFalse(resetAttempt(null))
    }
}
