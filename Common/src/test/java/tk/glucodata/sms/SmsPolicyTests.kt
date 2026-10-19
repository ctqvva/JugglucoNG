package tk.glucodata.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPolicyTests {

    @Test
    fun contactNumbersKeepALeadingPlusAndDropFormatting() {
        assertEquals("+31612345678", SmsContact.normalizeNumber(" +31 (6) 12-34 56 78 "))
        assertEquals("0612345678", SmsContact.normalizeNumber("06 12 34 56 78"))
        assertEquals("", SmsContact.normalizeNumber("not a number"))
    }

    @Test
    fun implausibleNumbersAreRejected() {
        assertTrue(SmsContact.isPlausibleNumber("+31612345678"))
        assertFalse(SmsContact.isPlausibleNumber("112"))
        assertFalse(SmsContact.isPlausibleNumber("+1234567890123456789"))
    }

    @Test
    fun sanitizingDropsDuplicateNumbers() {
        val policy = SmsPolicy(
            contacts = listOf(
                SmsContact(number = "+3161111111"),
                SmsContact(number = "+31 6 11 11 111")
            )
        ).sanitized()

        assertEquals(listOf("+3161111111"), policy.contacts.map { it.number })
    }

    @Test
    fun freshlyAddedBlankContactsSurviveSanitizingSoTheyCanBeTypedInto() {
        // The editor adds an empty row and the user fills it in afterwards; dropping
        // blanks here deleted the row the moment it appeared.
        val policy = SmsPolicy(
            contacts = listOf(SmsContact(number = ""), SmsContact(number = ""))
        ).sanitized()

        assertEquals(2, policy.contacts.size)
        assertFalse("a blank row is not something we can text", policy.hasUsableContacts())
    }

    @Test
    fun halfTypedContactsAreNeverTexted() {
        val policy = SmsPolicy(
            contacts = listOf(
                SmsContact(number = "", stage = 0),
                SmsContact(number = "+3161111111", stage = 1, relay = true)
            )
        ).sanitized()

        assertEquals(listOf("+3161111111"), policy.numbers())
        assertTrue(policy.contactsForStage(0).isEmpty())
        assertEquals(1, policy.lastStage())
        assertEquals(listOf("+3161111111"), policy.relayContacts().map { it.number })
    }

    @Test
    fun contactIdsSurviveAJsonRoundTripSoEditorStateStaysAttached() {
        val contact = SmsContact(number = "+3161111111", label = "Mum")
        val restored = SmsContact.decode(SmsContact.encode(contact))

        assertEquals(contact.id, restored.id)
    }

    @Test
    fun sanitizingClampsEveryTimingIntoAUsableRange() {
        val policy = SmsPolicy(
            unackedMinutes = 0,
            noDataMinutes = 5,
            maxPerHour = 9999,
            maxSendsPerEpisode = 0,
            relayIntervalMinutes = 1
        ).sanitized()

        assertEquals(1, policy.unackedMinutes)
        assertEquals(10, policy.noDataMinutes)
        assertEquals(60, policy.maxPerHour)
        assertEquals(1, policy.maxSendsPerEpisode)
        assertEquals(5, policy.relayIntervalMinutes)
    }

    @Test
    fun aPolicySurvivesAJsonRoundTrip() {
        val original = SmsPolicy(
            subjectName = "Mia",
            contacts = listOf(
                SmsContact(number = "+3161111111", label = "Mum", stage = 0, relay = true),
                SmsContact(number = "+3162222222", label = "Dad", stage = 1, enabled = false)
            ),
            unackedMinutes = 7,
            alarmAlertIds = setOf(0, 5),
            criticalLowMgdl = 50,
            relayMode = SmsPolicy.RELAY_ALWAYS,
            escalateOnlyWhenOffline = true,
            maxPerDay = 33
        ).sanitized()

        val restored = SmsPolicy.decode(SmsPolicy.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun anUnknownRelayModeFallsBackToOff() {
        assertEquals(SmsPolicy.RELAY_OFF, SmsPolicy(relayMode = "sideways").normalizedRelayMode())
    }

    @Test
    fun relayContactsAndStagesAreResolvedFromTheEnabledContactsOnly() {
        val policy = SmsPolicy(
            contacts = listOf(
                SmsContact(number = "+3161111111", stage = 0, relay = true),
                SmsContact(number = "+3162222222", stage = 2, enabled = false),
                SmsContact(number = "+3163333333", stage = 1)
            )
        ).sanitized()

        assertEquals(1, policy.lastStage())
        assertEquals(listOf("+3161111111"), policy.relayContacts().map { it.number })
        assertEquals(listOf("+3163333333"), policy.contactsForStage(1).map { it.number })
        assertEquals(listOf("+3161111111", "+3163333333"), policy.numbers())
    }
}
