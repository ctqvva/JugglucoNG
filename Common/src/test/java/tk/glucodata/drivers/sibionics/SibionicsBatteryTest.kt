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

    @Test
    fun stabilizesAfterThreeCloseSuccessfulSamples() {
        assertEquals(
            SibionicsBattery.StabilizationDecision.CONTINUE,
            SibionicsBattery.stabilizationDecision(2, listOf(36, 35)),
        )
        assertEquals(
            SibionicsBattery.StabilizationDecision.STABLE,
            SibionicsBattery.stabilizationDecision(3, listOf(36, 35, 35)),
        )
        assertEquals(
            SibionicsBattery.StabilizationDecision.STABLE,
            SibionicsBattery.stabilizationDecision(5, listOf(39, 25, 25, 25)),
        )
    }

    @Test
    fun capsAnUnstableOrFailingStartupSequence() {
        assertEquals(
            SibionicsBattery.StabilizationDecision.CONTINUE,
            SibionicsBattery.stabilizationDecision(4, listOf(40, 39, 25)),
        )
        assertEquals(
            SibionicsBattery.StabilizationDecision.EXHAUSTED,
            SibionicsBattery.stabilizationDecision(5, listOf(40, 39, 25)),
        )
        assertEquals(
            SibionicsBattery.StabilizationDecision.EXHAUSTED,
            SibionicsBattery.stabilizationDecision(5, emptyList()),
        )
    }
}
