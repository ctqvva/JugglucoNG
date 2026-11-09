package tk.glucodata.ui

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.ui.components.russianSubscriberFromAccount
import tk.glucodata.ui.components.russianSubscriberFromCanonicalAccount
import tk.glucodata.ui.components.russianSubscriberInput

class AccountPhoneFieldTests {
    @Test
    fun phonePasteAcceptsNationalInternationalAndDomesticFormatsWithoutDuplicatingThePrefix() {
        for (raw in listOf("9991234567", "+7 (999) 123-45-67", "79991234567", "89991234567")) {
            assertEquals("9991234567", russianSubscriberInput(raw))
        }
        assertEquals("7123456789", russianSubscriberFromAccount("7123456789"))
        assertEquals("9991234567", russianSubscriberFromAccount("79991234567"))
        assertEquals("9991234567", russianSubscriberFromCanonicalAccount("89991234567"))
        for (length in 0..10) {
            val partial = "9991234567".take(length)
            assertEquals(partial, russianSubscriberFromCanonicalAccount("7$partial"))
        }
    }

}
