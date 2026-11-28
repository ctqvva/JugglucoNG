package tk.glucodata.drivers.caresens.calibration

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CareSensAirDelayFlagTest {
    private val parameters = CareSensAirFactoryParameters(slope100 = 5.0f, vref = 1.5f, eapp = 0.1f)

    private fun adcForCurrent(currentNa: Double): IntArray {
        val adc = ((currentNa / 100.0 + parameters.eapp.toDouble()) * 40950.0 / parameters.vref.toDouble()).roundToInt()
        return IntArray(CareSensAirCalibration.SAMPLES) { adc }
    }

    private fun record(seq: Int, currentNa: Double, timeSeconds: Long = 1_700_000_000L + seq * 300L) =
        CareSensAirCalibration.Record(seq, timeSeconds, adcForCurrent(currentNa), 35.0)

    @Test
    fun phaseTransitionsDependOnSequenceAndDiabetesProfile() {
        fun evalAt(seq: Int, profile: Int, rawMax: Double, glucose: Double, current5Min: Double = 1.0): Boolean {
            val s = CareSensAirCalibrationState().apply {
                count = 100
                delayProfile = profile
                diabetesCount = 0
                previousDelayFlag = true // isolate D0-D4 from drop gate
                delayPreviousRawMax = 0.5
                for (k in 0 until 24) {
                    delaySequence[k] = seq - 24 + k
                    delayGlucose[k] = 100.0
                }
            }
            return CareSensAirDelayFlag.update(s, seq, rawMax, current5Min, glucose, parameters.slope100, false)
        }

        // Phase 1 (seq < 865): D1/D2 inactive, D0 and D3 active.
        assertFalse(evalAt(seq = 864, profile = 100, rawMax = 1.4, glucose = 35.0))
        assertTrue(evalAt(seq = 864, profile = 100, rawMax = 1.0, glucose = 35.0)) // D0
        assertTrue(evalAt(seq = 864, profile = 100, rawMax = 5.0, glucose = 19.4)) // D3 (rR=19)
        assertTrue(evalAt(seq = 864, profile = 100, rawMax = 5.0, glucose = 100.0, current5Min = 0.39)) // D3 (t5<0.4)

        // Phase 2 (seq in 865..2016): D1/D2 active.
        assertTrue(evalAt(seq = 865, profile = 0, rawMax = 1.4, glucose = 39.4)) // D1 & D2
        assertFalse(evalAt(seq = 865, profile = 0, rawMax = 1.4, glucose = 39.6)) // rR=40

        // Post day 7 with profile < 4 stays in Phase 2 (D0/D1/D2 active, D4 inactive).
        assertTrue(evalAt(seq = 2017, profile = 3, rawMax = 1.4, glucose = 35.0)) // D1/D2 still active
        assertTrue(evalAt(seq = 2017, profile = 0, rawMax = 1.0, glucose = 120.0)) // D0 still active

        // Post day 7 with profile >= 4 enters Phase 3 (D0/D1/D2 off; D4 on).
        assertFalse(evalAt(seq = 2017, profile = 4, rawMax = 1.4, glucose = 35.0)) // D1/D2 off, rR=35 >= 30
        assertTrue(evalAt(seq = 2017, profile = 4, rawMax = 1.4, glucose = 29.4)) // D4 (rR=29 < 30)
        assertTrue(evalAt(seq = 2017, profile = 6, rawMax = 0.79, glucose = 120.0)) // D4 current/ISF branch
        assertFalse(evalAt(seq = 2017, profile = 6, rawMax = 0.81, glucose = 120.0)) // rawMax >= 0.8
    }

    @Test
    fun rapidDropGateAndGappedFallbackMatchBoundaries() {
        fun makeState(seq: Int, inWindow: Int, prevGlu: Double, prevFlag: Boolean = false): CareSensAirCalibrationState =
            CareSensAirCalibrationState().apply {
                count = 100
                delayProfile = 0
                previousDelayFlag = prevFlag
                delayPreviousRawMax = 2.0
                for (k in 0 until 24) {
                    delaySequence[k] = if (k >= 24 - inWindow) seq - (24 - k) else seq - 100 + k
                    delayGlucose[k] = prevGlu
                }
            }

        // Dense window (>= 21 in <= 23): requires drop >= 40 ending at rounded glucose < 100.
        assertFalse(CareSensAirDelayFlag.update(makeState(1200, 21, 78.0), 1200, 1.4, 1.0, 39.0, 5.0f, false)) // drop 39
        assertTrue(CareSensAirDelayFlag.update(makeState(1200, 21, 79.0), 1200, 1.4, 1.0, 39.0, 5.0f, false)) // drop 40
        assertFalse(CareSensAirDelayFlag.update(makeState(1200, 21, 200.0), 1200, 0.5, 0.3, 100.0, 5.0f, false)) // dest=100
        assertTrue(CareSensAirDelayFlag.update(makeState(1200, 21, 200.0), 1200, 0.5, 0.3, 99.4, 5.0f, false)) // dest=99

        // Previous delay flag bypasses drop gate.
        assertTrue(CareSensAirDelayFlag.update(makeState(1200, 21, 39.0, prevFlag = true), 1200, 1.4, 1.0, 39.0, 5.0f, false))

        // Gapped window (< 21 in <= 23): only D3 with prev - rR > 50 passes (no < 100 dest ceiling).
        assertFalse(CareSensAirDelayFlag.update(makeState(1200, 20, 200.0), 1200, 1.4, 1.0, 35.0, 5.0f, false)) // D1/D2 only
        assertFalse(CareSensAirDelayFlag.update(makeState(1200, 20, 65.0), 1200, 1.4, 1.0, 15.0, 5.0f, false)) // D3, drop 50
        assertTrue(CareSensAirDelayFlag.update(makeState(1200, 20, 66.0), 1200, 1.4, 1.0, 15.0, 5.0f, false)) // D3, drop 51
        assertTrue(CareSensAirDelayFlag.update(makeState(1200, 20, 171.0), 1200, 0.5, 0.3, 120.0, 5.0f, false)) // D3 t5<0.4, dest 120
    }

    @Test
    fun noiseBreakEmitsDataType4AndWithholdsTrendForSevenRecords() {
        val engine = CareSensAirCalibration(parameters)
        // Warm up 15 normal records so trend is active.
        var last = engine.process(record(1, 8.0))
        for (seq in 2..15) last = engine.process(record(seq, 8.0 + seq * 0.05))
        assertEquals(0, last.dataType)
        assertTrue("expected active trend before noise break", last.trend != CareSensAirCalibration.NO_TREND)

        // Inject noiseBreak on seq 16: dataType becomes 4 and trend is withheld for seq 16..22 (7 records).
        val flagged = engine.process(record(16, 8.8), noiseBreak = true)
        assertEquals(CareSensAirCalibration.DATA_TYPE_LOW_SIGNAL, flagged.dataType)
        assertEquals(CareSensAirCalibration.NO_TREND, flagged.trend, 0.0)
        for (seq in 17..22) {
            val r = engine.process(record(seq, 8.0 + seq * 0.05))
            assertEquals("seq $seq should be dataType 0", 0, r.dataType)
            assertEquals("seq $seq should still withhold trend", CareSensAirCalibration.NO_TREND, r.trend, 0.0)
        }
        val resumed = engine.process(record(23, 8.0 + 23 * 0.05))
        assertEquals(0, resumed.dataType)
        assertTrue("trend should resume on the 7th record after dataType 4", resumed.trend != CareSensAirCalibration.NO_TREND)
    }

    @Test
    fun initialAndReferenceCurrentAccumulatorsEnforceValidBand() {
        val engine = CareSensAirCalibration(parameters)
        // Non-consecutive sequences avoid spike-limiting across the step changes.
        // seq 5..9: include in-band (10 nA), skip below 0.5 nA and above 50 nA.
        engine.process(record(5, 10.7)) // corrected ~ 10.46 nA -> included
        engine.process(record(7, 0.6))  // corrected ~ 0.36 nA  -> skipped
        engine.process(record(9, 52.0)) // corrected ~ 51.76 nA -> skipped
        assertEquals(1, engine.state.initialCount)

        // seq 865..869: same [0.5, 50.0] nA band for sensitivity reference.
        engine.process(record(865, 10.7))
        engine.process(record(867, 0.6))
        engine.process(record(869, 52.0))
        assertEquals(1, engine.state.referenceCount)
    }

    @Test
    fun version2PersistedStateStillDeserializes() {
        val s = CareSensAirCalibrationState().apply {
            count = 50
            previousSequence = 900
            previousTime = 1_700_270_000L
            for (k in 0 until 10) {
                historySequence[k] = 891 + k
                historyRaw[k] = 100.0 + k
            }
            lowRawMax[3] = 2.5
            lowRawCount = 4
        }
        val bytes = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { o ->
                o.writeInt(2)
                o.writeInt(s.count); o.writeInt(s.previousSequence); o.writeLong(s.previousTime); o.writeInt(s.contactCheckStart); o.writeBoolean(s.rejectedSinceAccepted)
                s.rawTail.forEach(o::writeDouble); s.cleanTail.forEach(o::writeDouble); s.flags.forEach(o::writeInt)
                s.history60.forEach(o::writeDouble); s.history60Time.forEach(o::writeLong); s.previousMedians.forEach(o::writeDouble)
                o.writeDouble(s.initialSum); o.writeInt(s.initialCount); o.writeBoolean(s.highPassStarted)
                o.writeDouble(s.highPassInput); o.writeDouble(s.highPass); o.writeDouble(s.referenceSum); o.writeInt(s.referenceCount)
                o.writeInt(s.diabetesCount); o.writeDouble(s.diabetesMean); o.writeDouble(s.diabetesM2)
                o.writeInt(s.diabetesHigh); o.writeInt(s.diabetesLow); o.writeInt(s.diabetesLevel); o.writeBoolean(s.diabetesLevelDone)
                o.writeDouble(s.lastGlucose); o.writeBoolean(s.hasLastGlucose)
                s.temperatures.forEach(o::writeDouble)
                s.kalman.forEach(o::writeDouble); o.writeInt(s.warmup); s.rates.forEach(o::writeDouble)
                o.writeBoolean(s.fluctuating); o.writeInt(s.fluctuationCount); o.writeDouble(s.previousRescaled)
                s.history.forEach(o::writeDouble); s.historyTime.forEach(o::writeLong); s.historySequence.forEach(o::writeInt); s.historyRaw.forEach(o::writeDouble)
                o.writeBoolean(s.timingStarted); o.writeInt(s.timingSequence); o.writeLong(s.timingTime)
                s.gap23.forEach(o::writeInt); o.writeInt(s.gap23Count); s.gap60.forEach(o::writeInt); o.writeInt(s.gap60Count)
                o.writeBoolean(s.timingError)
                s.lowRawMax.forEach(o::writeDouble); o.writeInt(s.lowRawCount); o.writeBoolean(s.lowError)
                repeat(10) { o.writeBoolean(it == 9) }
                o.writeBoolean(true)
            }
        }.toByteArray()

        val restored = CareSensAirCalibrationState.fromBytes(bytes)
        assertNotNull(restored)
        assertEquals(50, restored!!.count)
        assertEquals(900, restored.delaySequence[23])
        assertEquals(109.0, restored.delayGlucose[23], 0.0)
        assertTrue(restored.delayTrendFlags[6])
        assertTrue(restored.previousDelayFlag)
        assertEquals(2.5, restored.delayPreviousRawMax, 0.0)
        assertEquals(0, restored.delayProfile)
    }
}
