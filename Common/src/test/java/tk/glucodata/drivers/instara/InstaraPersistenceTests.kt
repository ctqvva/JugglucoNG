package tk.glucodata.drivers.instara

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstaraPersistenceTests {

    @Test
    fun validSnIsExactlyEightAsciiAlphanumericCharacters() {
        assertTrue(InstaraPersistence.isValidSn("AB12cd34"))
        assertFalse(InstaraPersistence.isValidSn("AB12cd3"))
        assertFalse(InstaraPersistence.isValidSn("AB12cd345"))
        assertFalse(InstaraPersistence.isValidSn("AB12cd3\u044F"))
        assertEquals("AB12cd34", InstaraPersistence.canonicalSensorId("  AB12cd34  "))
    }
}
