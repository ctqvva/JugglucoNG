package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupOnceTests {
    @Test fun aMissingAnswerIsAskedForOnce() {
        val asked = ArrayList<String>()
        val lookup = LookupOnce<String, Int> { key -> asked += key; if (key == "calibrated") 1 else null }
        repeat(3_000) {
            assertNull(lookup["plain"])
            assertEquals(1, lookup["calibrated"])
        }
        assertEquals(listOf("plain", "calibrated"), asked)
    }
}
