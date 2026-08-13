package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsVariantLockTest {
    private val eu = SibionicsConstants.Variant.EU
    private val sibionics2 = SibionicsConstants.Variant.SIBIONICS2
    private val hematonix = SibionicsConstants.Variant.HEMATONIX
    private val chinese = SibionicsConstants.Variant.CHINESE

    @Test
    fun theSetupRecordWinsOverACachedCopyThatDisagrees() {
        // The field report: a Sibionics 2 whose cached copy an older build rewrote to EU.
        assertEquals(sibionics2, SibionicsVariantLock.lockedVariant(sibionics2, eu))
    }

    @Test
    fun theCachedCopyIsOnlyUsedWhenNoRecordSurvives() {
        assertEquals(hematonix, SibionicsVariantLock.lockedVariant(null, hematonix))
        assertEquals(eu, SibionicsVariantLock.lockedVariant(null, null))
    }

    @Test
    fun onlySetupMayRetypeASensorThatIsAlreadyRecorded() {
        assertEquals(
            sibionics2,
            SibionicsVariantLock.variantForWrite(
                existingVariant = sibionics2,
                requestedVariant = eu,
                isUserChoice = false,
            ),
        )
        assertEquals(
            eu,
            SibionicsVariantLock.variantForWrite(
                existingVariant = sibionics2,
                requestedVariant = eu,
                isUserChoice = true,
            ),
        )
    }

    @Test
    fun aFirstTimeRecordTakesTheRequestedVariant() {
        // Legacy migration and setup both create records this way.
        listOf(true, false).forEach { userChoice ->
            assertEquals(
                chinese,
                SibionicsVariantLock.variantForWrite(
                    existingVariant = null,
                    requestedVariant = chinese,
                    isUserChoice = userChoice,
                ),
            )
        }
    }

    @Test
    fun noKeyGroupOrderingCanEverChangeWhatASensorIs() {
        // Whatever key authenticated last, the locked type is unaffected by the order it produces.
        SibionicsConstants.Variant.entries.forEach { hint ->
            val order = SibionicsVariantLock.keyOrder(sibionics2, hint)
            assertEquals(
                sibionics2,
                SibionicsVariantLock.variantForWrite(
                    existingVariant = sibionics2,
                    requestedVariant = order.first(),
                    isUserChoice = false,
                ),
            )
        }
    }

    @Test
    fun theHintLeadsSoAWorkingKeyCostsNoAuthTimeout() {
        val order = SibionicsVariantLock.keyOrder(eu, sibionics2)
        assertEquals(listOf(sibionics2, eu), order.take(2))
    }

    @Test
    fun withoutAHintTheLockedVariantIsTriedFirst() {
        assertEquals(sibionics2, SibionicsVariantLock.keyOrder(sibionics2, null).first())
        assertEquals(eu, SibionicsVariantLock.keyOrder(eu, null).first())
    }

    @Test
    fun everyKeyGroupStaysReachableWithoutBeingTriedTwice() {
        val order = SibionicsVariantLock.keyOrder(hematonix, sibionics2)
        assertEquals(
            order.size,
            order.distinctBy { it.appId + it.registrationKeyHex }.size,
        )
        SibionicsConstants.Variant.entries
            .filter { it != chinese }
            .forEach { assertTrue(it.id, order.contains(it)) }
    }

    @Test
    fun whatARetypeUsedToCostTheUser() {
        // The lifetime and the auto-reset window hang off the type, which is why a Sibionics 2
        // renamed to EU showed a 14-day official end and lost its 22-day reset.
        assertNotEquals(eu.registrationKeyHex, sibionics2.registrationKeyHex)
        assertNotEquals(eu.officialLifetimeMs, sibionics2.officialLifetimeMs)
        assertEquals(
            SibionicsResetPolicy.DISABLED_DAYS,
            SibionicsResetPolicy.normalizedDays(eu, persistedDays = 22, hasPersistedSetting = true),
        )
        assertEquals(
            SibionicsResetPolicy.ENABLED_DAYS,
            SibionicsResetPolicy.normalizedDays(sibionics2, persistedDays = 22, hasPersistedSetting = true),
        )
    }
}
