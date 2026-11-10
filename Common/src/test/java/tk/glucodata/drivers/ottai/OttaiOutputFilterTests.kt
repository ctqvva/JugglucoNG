package tk.glucodata.drivers.ottai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiOutputFilterTests {

    private fun record(raw: Int = 12_000, temp: Double = 32.0) =
        OttaiRecord(
            dataNo = 100,
            voltage = 0,
            runtimeSec = 6_000,
            rawCurrent = raw,
            temperatureC = temp,
            recordBytes = ByteArray(OttaiParser.PARSER_RECORD_SIZE),
        )

    @Test
    fun hardGate_matchesVendorOutputLimits() {
        assertNull(OttaiOutputFilter.hardRejectReason(record(raw = 12_000, temp = 32.0), 7.4f))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(raw = 999), 7.4f)!!.startsWith("raw="))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(temp = 45.1), 7.4f)!!.startsWith("temp="))
        // Observed corruption decoded high (185.07, 360.93, 421.01 C); nothing stopped the same
        // garbage decoding cold, so the gate is bounded at both ends now.
        assertTrue(OttaiOutputFilter.hardRejectReason(record(temp = 14.9), 7.4f)!!.startsWith("temp="))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(temp = -40.0), 7.4f)!!.startsWith("temp="))
        // The whole range the sensor actually reported over 16 days (25.0-41.1 C) still passes.
        assertNull(OttaiOutputFilter.hardRejectReason(record(temp = 25.0), 7.4f))
        assertNull(OttaiOutputFilter.hardRejectReason(record(temp = 41.1), 7.4f))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(), 40.1f)!!.startsWith("glucose="))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(), 0f)!!.startsWith("glucose="))
    }

    @Test
    fun oneMinuteRawExcursion_rejectsObservedOttaiSpikes() {
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 14.1f,
                candidateRaw = 26_856,
                baselineMmol = 7.8f,
                baselineRaw = 14_534,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 9.9f,
                candidateRaw = 18_053,
                baselineMmol = 8.4f,
                baselineRaw = 15_032,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 9.3f,
                candidateRaw = 17_026,
                baselineMmol = 7.5f,
                baselineRaw = 13_707,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 13.5f,
                candidateRaw = 25_649,
                baselineMmol = 4.5f,
                baselineRaw = 7_706,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 13.5f,
                candidateRaw = 25_649,
                baselineMmol = 5.9f,
                baselineRaw = 10_598,
            )
        )
    }

    @Test
    fun oneMinuteRawExcursion_allowsSmallOrRawStableMovement() {
        assertFalse(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 8.0f,
                candidateRaw = 14_519,
                baselineMmol = 7.5f,
                baselineRaw = 13_707,
            )
        )
        assertFalse(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 9.6f,
                candidateRaw = 15_500,
                baselineMmol = 8.0f,
                baselineRaw = 15_000,
            )
        )
    }

    private fun reading(good: Boolean) = OttaiReading(
        record = record(raw = if (good) 7_691 else 17_017, temp = if (good) 29.6 else 463.6),
        adjustGlucose = 4.5,
        monitorTimeMs = 0L,
        valid = true,
    )

    private fun frame(total: Int, good: Int) = List(total) { reading(good = it < good) }

    @Test
    fun misframedFrame_2026_09_26_traceShape() {
        // 17 records read at the wrong width: 2 coincidental survivors, 15 impossible temperatures.
        assertTrue(OttaiOutputFilter.isMisframedFrame(frame(total = 17, good = 2)))
    }

    @Test
    fun misframedFrame_keepsHealthyAndBorderlineFrames() {
        assertFalse(OttaiOutputFilter.isMisframedFrame(frame(total = 20, good = 20)))
        assertFalse(OttaiOutputFilter.isMisframedFrame(frame(total = 20, good = 5))) // exactly a quarter
        assertTrue(OttaiOutputFilter.isMisframedFrame(frame(total = 20, good = 4)))
    }

    @Test
    fun misframedFrame_keepsAColdButCorrectlyFramedFrame() {
        // Outdoors in winter: 18 records fail the hard gate at 11-14 C, 2 pass at 15.5 C. Every
        // record is real, so the frame is not misframed and the two good readings are kept.
        val cold = List(20) { index ->
            val passes = index < 2
            OttaiReading(
                record = record(raw = 7_691, temp = if (passes) 15.5 else 11.0 + (index % 4)),
                adjustGlucose = 6.2,
                monitorTimeMs = 0L,
                valid = true,
            )
        }
        assertFalse(OttaiOutputFilter.isMisframedFrame(cold))
    }

    @Test
    fun misframedFrame_keepsAFrameThatFailsOnlyOnCurrent() {
        // A failing electrode (raw under the gate) is a sensor problem, not a framing one.
        val weak = List(20) { index ->
            OttaiReading(
                record = record(raw = if (index < 2) 7_691 else 400, temp = 31.0),
                adjustGlucose = 5.0,
                monitorTimeMs = 0L,
                valid = true,
            )
        }
        assertFalse(OttaiOutputFilter.isMisframedFrame(weak))
    }

    @Test
    fun misframedFrame_ignoresFramesTooSmallToJudge() {
        assertFalse(OttaiOutputFilter.isMisframedFrame(frame(total = 7, good = 0)))
        assertFalse(OttaiOutputFilter.isMisframedFrame(emptyList()))
    }
}
