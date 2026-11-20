package tk.glucodata.glucosemeter

import org.junit.Assert.assertEquals
import org.junit.Test

class MeterClockSyncTests {

    @Test
    fun `i-SENS meter without FFF1 reads the standard current time`() {
        // Discussion #787 trace: CareSens exposes 2A18/2A34/2A52 and 2A2B, no FFF1.
        assertEquals(
            MeterClockStep.READ_CURRENT_TIME,
            MeterClockSync.step("i-SENS", hasDateTime = false, hasIsensTime = false, hasCurrentTime = true),
        )
    }

    @Test
    fun `i-SENS meter with FFF1 keeps the vendor time write`() {
        assertEquals(
            MeterClockStep.WRITE_ISENS_TIME,
            MeterClockSync.step("i-SENS", hasDateTime = false, hasIsensTime = true, hasCurrentTime = true),
        )
    }

    @Test
    fun `i-SENS meter without any clock goes straight to records`() {
        assertEquals(
            MeterClockStep.NONE,
            MeterClockSync.step("i-SENS", hasDateTime = false, hasIsensTime = false, hasCurrentTime = false),
        )
    }

    @Test
    fun `Roche reads date time when present and falls back otherwise`() {
        assertEquals(
            MeterClockStep.READ_DATE_TIME,
            MeterClockSync.step("Roche", hasDateTime = true, hasIsensTime = false, hasCurrentTime = true),
        )
        assertEquals(
            MeterClockStep.READ_CURRENT_TIME,
            MeterClockSync.step("Roche", hasDateTime = false, hasIsensTime = false, hasCurrentTime = true),
        )
    }

    @Test
    fun `other meters read current time only when it exists`() {
        assertEquals(
            MeterClockStep.READ_CURRENT_TIME,
            MeterClockSync.step("Ascensia", hasDateTime = true, hasIsensTime = false, hasCurrentTime = true),
        )
        assertEquals(
            MeterClockStep.NONE,
            MeterClockSync.step("Ascensia", hasDateTime = true, hasIsensTime = false, hasCurrentTime = false),
        )
    }
}
