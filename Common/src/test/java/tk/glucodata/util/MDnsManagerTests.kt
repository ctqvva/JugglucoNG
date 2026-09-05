package tk.glucodata.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MDnsManagerTests {
    @Test
    fun exactRegisteredServiceIsOwn() {
        assertTrue(isOwnNsdService("JugglucoNG-Pixel 9", "JugglucoNG-Pixel 9"))
    }

    @Test
    fun androidRenamedPeerIsNotOwn() {
        assertFalse(isOwnNsdService("JugglucoNG-Pixel 9 (2)", "JugglucoNG-Pixel 9"))
    }

    @Test
    fun noRegistrationMeansNoServiceIsOwn() {
        assertFalse(isOwnNsdService("JugglucoNG-Pixel 9", null))
    }
}
