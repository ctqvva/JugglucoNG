package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SibionicsBatteryTest {
    @Test
    fun parsesStandardUnsignedBatteryLevel() {
        assertEquals(0, SibionicsBattery.parsePercent(byteArrayOf(0)))
        assertEquals(73, SibionicsBattery.parsePercent(byteArrayOf(73)))
        assertEquals(100, SibionicsBattery.parsePercent(byteArrayOf(100)))
    }

    @Test
    fun rejectsMalformedOrOutOfRangeBatteryLevel() {
        assertNull(SibionicsBattery.parsePercent(byteArrayOf()))
        assertNull(SibionicsBattery.parsePercent(byteArrayOf(101)))
        assertNull(SibionicsBattery.parsePercent(byteArrayOf(0xFF.toByte())))
        assertNull(SibionicsBattery.parsePercent(byteArrayOf(50, 0)))
    }
}
