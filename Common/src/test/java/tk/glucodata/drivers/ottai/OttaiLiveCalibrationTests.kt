package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CalibrationAccess
import tk.glucodata.CalibrationProvider
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.CurrentGlucoseSource
import tk.glucodata.GlucosePoint
import tk.glucodata.LiveReadingLanes
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.StandardGlucoseAlertEvaluator

class OttaiLiveCalibrationTests {
    @Test
    fun calibratedLiveValueDoesNotTriggerStockLowBeforeOrAfterHistoryWrite() {
        for (isMmol in listOf(true, false)) {
            val scale = if (isMmol) 1f else 18.0182f
            val stock = 3.7f * scale
            val calibrated = 5.1f * scale
            withCalibration(1.4f * scale) {
                for (viewMode in listOf(0, 2)) {
                    for (history in listOf(emptyList(), listOf(GlucosePoint(TIME, stock, 0f)))) {
                        // Ottai supplies formula glucose and no raw glucose lane. Its
                        // rawCurrent diagnostic must never be treated as raw glucose.
                        val display = resolve(LiveReadingLanes.stock(stock, Float.NaN), history, viewMode, isMmol)
                        assertEquals(calibrated, display.primaryValue, 0.001f)
                        assertFalse(display.rawValue.isFinite() && display.rawValue > 0f)

                        // The external float API marks this value resolved. Both alert
                        // engines and the retained current reading must keep 5.1, not
                        // bypass calibration at 3.7 or apply the correction twice at 6.5.
                        val alert = resolve(LiveReadingLanes.resolved(display.primaryValue), history, viewMode, isMmol,
                            preferIncomingSample = false)
                        assertEquals(calibrated, alert.primaryValue, 0.001f)
                        assertTrue(isLow(stock, isMmol, scale))
                        assertFalse(isLow(alert.primaryValue, isMmol, scale))
                    }
                }
            }
        }
    }

    @Test
    fun liveReadingWithoutCalibrationStillTriggersARealLow() {
        withCalibration(0f, enabled = false) {
            val display = resolve(LiveReadingLanes.stock(3.7f, Float.NaN), emptyList(), 0, true)
            assertEquals(3.7f, display.primaryValue, 0.001f)
            assertTrue(isLow(display.primaryValue, true, 1f))
        }
    }

    @Test
    fun pendingHistoryWriteDoesNotDelayLowOrHighTransition() {
        for (isMmol in listOf(true, false)) {
            val scale = if (isMmol) 1f else 18.0182f
            withCalibration(1.4f * scale) {
                for (viewMode in listOf(0, 2)) {
                    for ((stockMmol, type) in listOf(2.3f to AlertType.LOW, 8.3f to AlertType.HIGH)) {
                        // The previous minute is in range; only the incoming sample crosses
                        // the threshold after calibration. Room has not stored it yet.
                        val history = listOf(GlucosePoint(TIME - 60_000L, 3.7f * scale, 0f))
                        val display = resolve(LiveReadingLanes.stock(stockMmol * scale, Float.NaN), history, viewMode, isMmol)
                        assertEquals((stockMmol + 1.4f) * scale, display.primaryValue, 0.001f)
                        assertEquals(TIME, display.timeMillis)
                        val alert = resolve(LiveReadingLanes.resolved(display.primaryValue), history, viewMode, isMmol,
                            preferIncomingSample = false)
                        assertEquals(display.primaryValue, alert.primaryValue, 0.001f)
                        val active = StandardGlucoseAlertEvaluator.resolveActive(
                            glucoseValue = alert.primaryValue,
                            rate = 0f,
                            configs = mapOf(type to AlertConfig(type, enabled = true,
                                threshold = (if (type == AlertType.LOW) 3.9f else 9.0f) * scale)),
                            alertTypes = listOf(type),
                            isMmol = isMmol,
                            isConfigActive = { true },
                        )
                        assertTrue("missed $type transition in view $viewMode", type in active)
                    }
                }
            }
        }
    }

    @Test
    fun openChunkCannotReplaceLiveLowOrHighWithCompletedChunkValue() {
        val minute = 60_000L
        val time = (TIME / (5 * minute)) * (5 * minute) + minute
        val mode = CurrentDisplaySource.SmoothingMode(true, 5, true)
        for (isMmol in listOf(true, false)) {
            val scale = if (isMmol) 1f else 18.0182f
            withCalibration(1.4f * scale) {
                for (viewMode in listOf(0, 2)) {
                    for ((stockMmol, type) in listOf(2.3f to AlertType.LOW, 8.3f to AlertType.HIGH)) {
                        val history = listOf(
                            GlucosePoint(time - 3 * minute, 3.7f * scale, 0f),
                            GlucosePoint(time - 2 * minute, 3.7f * scale, 0f),
                        )
                        val display = resolve(LiveReadingLanes.stock(stockMmol * scale, Float.NaN),
                            history, viewMode, isMmol, timeMillis = time, smoothingMode = mode)
                        assertEquals("live value replaced by a completed chunk", (stockMmol + 1.4f) * scale,
                            display.primaryValue, 0.001f)
                        assertEquals("live value moved to an older timestamp", time, display.timeMillis)
                        val active = StandardGlucoseAlertEvaluator.resolveActive(
                            glucoseValue = display.primaryValue,
                            rate = 0f,
                            configs = mapOf(type to AlertConfig(type, enabled = true,
                                threshold = (if (type == AlertType.LOW) 3.9f else 9.0f) * scale)),
                            alertTypes = listOf(type),
                            isMmol = isMmol,
                            isConfigActive = { true },
                        )
                        assertTrue("missed $type while the live bucket is open", type in active)
                    }
                }
            }
        }
    }

    private fun resolve(
        reading: LiveReadingLanes,
        history: List<GlucosePoint>,
        viewMode: Int,
        isMmol: Boolean,
        preferIncomingSample: Boolean = true,
        timeMillis: Long = TIME,
        smoothingMode: CurrentDisplaySource.SmoothingMode = CurrentDisplaySource.SmoothingMode(false, 0, false),
        nowMillis: Long = timeMillis,
    ) = requireNotNull(CurrentDisplaySource.resolveSnapshot(
        current = CurrentGlucoseSource.Snapshot.of(reading, timeMillis, "", 0f, SENSOR, 0, 0, "ottai-live"),
        recentPoints = history,
        historyStart = timeMillis - 600_000L,
        viewMode = viewMode,
        isMmol = isMmol,
        smoothingMode = smoothingMode,
        sensorId = SENSOR,
        preferIncomingSample = preferIncomingSample,
        nowMillis = nowMillis,
    ))

    private fun isLow(value: Float, isMmol: Boolean, scale: Float) = AlertType.LOW in
        StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = value,
            rate = 0f,
            configs = mapOf(AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 3.9f * scale)),
            alertTypes = listOf(AlertType.LOW),
            isMmol = isMmol,
            isConfigActive = { true },
        )

    private fun withCalibration(offset: Float, enabled: Boolean = true, block: () -> Unit) {
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) =
                enabled && !isRawMode && sensorId == SENSOR

            override fun getCalibratedValue(
                value: Float, timestamp: Long, isRawMode: Boolean, emitDiagnostics: Boolean, sensorId: String?,
            ): Float {
                assertEquals(SENSOR, sensorId)
                assertFalse(isRawMode)
                return value + offset
            }
        })
        try {
            block()
        } finally {
            CalibrationAccess.unregisterForTests()
        }
    }

    private companion object {
        const val SENSOR = "B0E8E8871A25"
        const val TIME = 1_791_288_000_000L
    }
}
