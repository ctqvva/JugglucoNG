package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCalibrationPayloadTests {
    @Test
    fun roundTrip_preservesCanonicalAnchorsAndScalarState() {
        val payload = WearCalibrationPayload(
            sensorId = "SIBI:6CA04230E260",
            revision = 42L,
            valuesPrecalibrated = true,
            hideInitialWhenCalibrated = true,
            sourceUnitMgdlPerUnit = 18.0182,
            rawTuning = tk.glucodata.data.calibration.CalibrationTuning(
                algorithm = "xdrip_median_slope",
                weightMode = "stable",
                applyToPast = true,
                lockPastHistory = false,
                keepDisabledHistory = true,
            ),
            auto = WearCalibrationMode(
                doubleArrayOf(100.0, 110.0, 1_700_000_000_000.0),
            ),
            raw = WearCalibrationMode(
                doubleArrayOf(
                    95.0, 110.0, 1_700_000_000_000.0,
                    130.0, 125.0, 1_700_000_300_000.0,
                ),
            ),
        )

        val decoded = WearCalibrationPayload.decode(WearCalibrationPayload.encode(payload))

        requireNotNull(decoded)
        assertEquals(payload.sensorId, decoded.sensorId)
        assertEquals(payload.revision, decoded.revision)
        assertTrue(decoded.valuesPrecalibrated)
        assertTrue(decoded.hideInitialWhenCalibrated)
        assertEquals(payload.sourceUnitMgdlPerUnit, decoded.sourceUnitMgdlPerUnit, 0.0)
        assertEquals(payload.rawTuning, decoded.rawTuning)
        assertArrayEquals(payload.auto.anchorsMgdl, decoded.auto.anchorsMgdl, 0.0)
        assertArrayEquals(payload.raw.anchorsMgdl, decoded.raw.anchorsMgdl, 0.0)
    }

    @Test
    fun watchRunsCalibrationInThePhonesSourceUnit() {
        val timestamp = 1_786_358_640_000L
        val scale = 18.0182
        val tuning = tk.glucodata.data.calibration.CalibrationTuning(
            algorithm = "adaptive_ensemble",
            weightMode = "fresh",
            applyToPast = true,
            lockPastHistory = true,
            keepDisabledHistory = false,
        )
        val phoneUnitAnchors = doubleArrayOf(
            4.1, 5.0, (timestamp - 6 * 3_600_000L).toDouble(),
            4.8, 5.6, (timestamp - 3 * 3_600_000L).toDouble(),
            5.2, 5.5, (timestamp - 60_000L).toDouble(),
        )
        val payload = WearCalibrationPayload(
            sensorId = "6CA04230E260",
            revision = 1L,
            valuesPrecalibrated = false,
            hideInitialWhenCalibrated = false,
            auto = WearCalibrationMode(phoneUnitAnchors.copyOf().also { packed ->
                for (index in packed.indices step 3) {
                    packed[index] *= scale
                    packed[index + 1] *= scale
                }
            }),
            raw = WearCalibrationMode(DoubleArray(0)),
            tuning = tuning,
            sourceUnitMgdlPerUnit = scale,
        )
        val inputMmol = 4.05f
        val points = phoneUnitAnchors.toList().chunked(3).map {
            tk.glucodata.data.calibration.CalPoint(it[0], it[1], it[2].toLong())
        }
        val expected = tk.glucodata.data.calibration.CalibrationMath.computeAlgorithm(
            tuning.algorithm,
            inputMmol.toDouble(),
            timestamp,
            points,
            tuning,
        ).prediction.toFloat()

        val actual = SyncedWearCalibrationProvider.calibrateWithPayload(
            inputMmol,
            timestamp,
            false,
            scale.toFloat(),
            payload,
        )

        assertEquals(expected, actual, 0.0001f)
    }

    @Test
    fun driverIntegratedLaneIsLeftAlone_soTheWatchDoesNotCorrectTwice() {
        // Sibionics in STOCK_CALIBRATED folds the fit into the auto value it
        // stores, and CalibrationManager.getCalibratedValue returns such a value
        // untouched. The watch received the corrected value plus the anchors and
        // applied them again: the phone's 7,8 read 7,4 on the wrist. The raw
        // lane is not integrated, so it must still be corrected here.
        val timestamp = 1_786_714_440_000L
        val anchors = doubleArrayOf(
            2.9, 3.2, (timestamp - 12 * 3_600_000L).toDouble(),
            8.7, 8.2, (timestamp - 4 * 3_600_000L).toDouble(),
        )
        val scale = 18.0182
        fun canonical(values: DoubleArray) = values.copyOf().also { packed ->
            for (index in packed.indices step 3) {
                packed[index] *= scale
                packed[index + 1] *= scale
            }
        }
        val payload = WearCalibrationPayload(
            sensorId = "SIBI:0683013AQT9",
            revision = 7L,
            valuesPrecalibrated = false,
            hideInitialWhenCalibrated = true,
            autoIntegratedByDriver = true,
            rawIntegratedByDriver = false,
            auto = WearCalibrationMode(canonical(anchors)),
            raw = WearCalibrationMode(canonical(anchors)),
            sourceUnitMgdlPerUnit = scale,
        )

        val decoded = requireNotNull(
            WearCalibrationPayload.decode(WearCalibrationPayload.encode(payload)),
        )
        assertTrue(decoded.autoIntegratedByDriver)
        assertFalse(decoded.rawIntegratedByDriver)

        val alreadyCorrected = 7.8f
        assertEquals(
            alreadyCorrected,
            SyncedWearCalibrationProvider.calibrateWithPayload(
                alreadyCorrected,
                timestamp,
                false,
                scale.toFloat(),
                decoded,
            ),
            0.0001f,
        )
        val rawLane = SyncedWearCalibrationProvider.calibrateWithPayload(
            alreadyCorrected,
            timestamp,
            true,
            scale.toFloat(),
            decoded,
        )
        assertNotEquals(alreadyCorrected, rawLane, 0.01f)
    }

    @Test
    fun integrationFlagsSurviveAPayloadThatCarriesTheOtherFlagsToo() {
        val payload = WearCalibrationPayload(
            sensorId = "SIBI:0683013AQT9",
            revision = 3L,
            valuesPrecalibrated = true,
            hideInitialWhenCalibrated = true,
            overwriteSensorValues = true,
            autoIntegratedByDriver = true,
            rawIntegratedByDriver = true,
            auto = WearCalibrationMode(DoubleArray(0)),
            raw = WearCalibrationMode(DoubleArray(0)),
        )

        val decoded = requireNotNull(
            WearCalibrationPayload.decode(WearCalibrationPayload.encode(payload)),
        )

        assertTrue(decoded.valuesPrecalibrated)
        assertTrue(decoded.hideInitialWhenCalibrated)
        assertTrue(decoded.overwriteSensorValues)
        assertTrue(decoded.autoIntegratedByDriver)
        assertTrue(decoded.rawIntegratedByDriver)
    }

    @Test
    fun decode_rejectsMalformedOrTrailingData() {
        assertNull(WearCalibrationPayload.decode(null))
        assertNull(WearCalibrationPayload.decode(byteArrayOf(1, 0, 4, 1, 2)))

        val valid = WearCalibrationPayload.encode(
            WearCalibrationPayload(
                sensorId = "sensor",
                revision = 1L,
                valuesPrecalibrated = false,
                hideInitialWhenCalibrated = false,
                auto = WearCalibrationMode(DoubleArray(0)),
                raw = WearCalibrationMode(DoubleArray(0)),
            ),
        )
        assertFalse(WearCalibrationPayload.decode(valid)!!.valuesPrecalibrated)
        assertNull(WearCalibrationPayload.decode(valid + byteArrayOf(1)))
    }

    @Test
    fun displayGate_rejectsOnlyValuesTooLargeToBeGlucose() {
        // A driver diagnostic (Ottai's electrode current) must never render as
        // a reading, but a genuinely low reading still has to be shown — some
        // drivers' raw lanes sit far below the physiological floor.
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(20f, isMmol = false))
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(600f, isMmol = false))
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(10.8f, isMmol = false))
        assertFalse(GlucoseValuePlausibility.isPlausibleDisplayValue(0f, isMmol = false))
        assertFalse(GlucoseValuePlausibility.isPlausibleDisplayValue(11_557f, isMmol = false))
        assertTrue(GlucoseValuePlausibility.isPlausibleDisplayValue(0.6f, isMmol = true))
        assertFalse(GlucoseValuePlausibility.isPlausibleDisplayValue(34f, isMmol = true))
    }

    @Test
    fun storageGate_keepsBothPhysiologicalBounds() {
        // What gets stored or synced is held to the tighter range.
        assertTrue(GlucoseValuePlausibility.isPlausibleMgdl(20f))
        assertTrue(GlucoseValuePlausibility.isPlausibleMgdl(600f))
        assertFalse(GlucoseValuePlausibility.isPlausibleMgdl(19.9f))
        assertFalse(GlucoseValuePlausibility.isPlausibleMgdl(11_557f))
    }
}
