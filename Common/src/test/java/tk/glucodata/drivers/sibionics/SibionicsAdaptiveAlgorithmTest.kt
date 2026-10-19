package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsAdaptiveAlgorithmTest {
    @Test
    fun exactMeasurementRemainsStableWithoutCalibrationOrNoise() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        val outputs = (1..20).map { index ->
            context.process(6f, 3f, 34f, 100f, index, index * 60_000L, emptyList())
        }
        assertTrue(outputs.all { it == 6f })
    }

    @Test
    fun stockModeUsesIntegratedCalibrationWithoutAdaptiveLag() {
        val algorithm = SibionicsAlgorithmContext("test")
        algorithm.configure(
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            variant = SibionicsConstants.Variant.CHINESE,
            selection = SibionicsAlgorithmSelection.STOCK_CALIBRATED,
        )
        val anchor = SibionicsCalibrationAnchor(8f, 6f, 30_000L)

        val output = algorithm.process(
            rawMmol = 8f,
            temperatureC = 34f,
            index = 1,
            mode = SibionicsAlgorithmMode.LIVE,
            impedance = 100f,
            eventTimeMs = 60_000L,
            calibrationAnchors = listOf(anchor),
        )

        assertEquals(6.4f, output, 0.001f)
    }

    @Test
    fun plainStockModeIgnoresCalibrationAnchors() {
        val algorithm = SibionicsAlgorithmContext("test")
        algorithm.configure(
            shortCode = "46HU804EBJ4",
            sensitivity = 1.4f,
            variant = SibionicsConstants.Variant.CHINESE,
            selection = SibionicsAlgorithmSelection.STOCK,
        )
        val output = algorithm.process(
            rawMmol = 8f,
            temperatureC = 34f,
            index = 1,
            mode = SibionicsAlgorithmMode.LIVE,
            eventTimeMs = 60_000L,
            calibrationAnchors = listOf(SibionicsCalibrationAnchor(8f, 6f, 30_000L)),
        )

        assertEquals(8f, output, 0.001f)
    }

    @Test
    fun algorithmStoragePreservesLegacyModesAndRepresentsEveryModel() {
        assertEquals(SibionicsAlgorithmSelection.STOCK, SibionicsAlgorithmSelection.fromStorage(0))
        assertEquals(SibionicsAlgorithmSelection.STOCK_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(1))
        assertEquals(SibionicsAlgorithmSelection.STATE_MODEL, SibionicsAlgorithmSelection.fromStorage(2))
        assertEquals(SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(3))
        assertEquals(SibionicsAlgorithmSelection.BALANCED_TRACKER, SibionicsAlgorithmSelection.fromStorage(4))
        assertEquals(SibionicsAlgorithmSelection.BALANCED_TRACKER_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(5))
        assertEquals(SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR, SibionicsAlgorithmSelection.fromStorage(6))
        assertEquals(SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR_CALIBRATED, SibionicsAlgorithmSelection.fromStorage(7))
        assertTrue(!SibionicsAlgorithmSelection.STOCK.calibrationEnabled)
        assertTrue(!SibionicsAlgorithmSelection.STOCK.customModelEnabled)
        assertTrue(SibionicsAlgorithmSelection.STOCK_CALIBRATED.calibrationEnabled)
        assertTrue(!SibionicsAlgorithmSelection.STOCK_CALIBRATED.customModelEnabled)
        assertTrue(!SibionicsAlgorithmSelection.STATE_MODEL.calibrationEnabled)
        assertTrue(SibionicsAlgorithmSelection.STATE_MODEL.customModelEnabled)
        assertTrue(SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED.calibrationEnabled)
        assertTrue(SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED.customModelEnabled)
        assertEquals(
            SibionicsAlgorithmSelection.BALANCED_TRACKER_CALIBRATED,
            SibionicsAlgorithmSelection.STOCK_CALIBRATED.withModel(SibionicsCustomAlgorithmModel.BALANCED_TRACKER),
        )
        assertEquals(
            SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR,
            SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR_CALIBRATED.withCalibration(false),
        )
    }

    @Test
    fun adaptiveToggleDoesNotImplicitlyEnableCalibration() {
        val anchor = SibionicsCalibrationAnchor(8f, 6f, 30_000L)
        fun firstOutput(selection: SibionicsAlgorithmSelection): Float {
            val algorithm = SibionicsAlgorithmContext("test-${selection.storageId}")
            algorithm.configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
            return algorithm.process(
                rawMmol = 8f,
                temperatureC = 34f,
                index = 1,
                mode = SibionicsAlgorithmMode.LIVE,
                eventTimeMs = 60_000L,
                calibrationAnchors = listOf(anchor),
            )
        }

        assertEquals(8f, firstOutput(SibionicsAlgorithmSelection.STATE_MODEL), 0.001f)
        assertEquals(6.4f, firstOutput(SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED), 0.001f)
    }

    @Test
    fun bothManagedAlgorithmFamiliesExposeOneMinuteChemicalSignal() {
        listOf(
            SibionicsConstants.Variant.CHINESE,
            SibionicsConstants.Variant.SIBIONICS2,
        ).forEach { variant ->
            val algorithm = SibionicsAlgorithmContext("chemical-${variant.name}")
            algorithm.configure("46HU804EBJ4", 1.4f, variant, SibionicsAlgorithmSelection.STATE_MODEL)
            var output = Float.NaN
            repeat(130) { offset ->
                val index = offset + 1
                output = algorithm.process(
                    rawMmol = 6f,
                    temperatureC = 34f,
                    index = index,
                    mode = SibionicsAlgorithmMode.REPLAY,
                    impedance = 2_900f,
                    eventTimeMs = index * 60_000L,
                )
                val signal = algorithm.latestChemicalSignal()
                assertTrue("variant=$variant index=$index signal=$signal", signal?.mmol?.isFinite() == true)
            }
            assertTrue("variant=$variant output=$output", output.isFinite() && output > 0f)
        }
    }

    @Test
    fun everyModelSnapshotContinuesDeterministically() {
        val selections = listOf(
            SibionicsAlgorithmSelection.STOCK,
            SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR,
            SibionicsAlgorithmSelection.BALANCED_TRACKER,
            SibionicsAlgorithmSelection.STATE_MODEL,
        )
        selections.forEach { selection ->
            val original = SibionicsAlgorithmContext("snapshot-${selection.name}").apply {
                configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
            }
            repeat(40) { offset ->
                val index = offset + 1
                original.process(
                    rawMmol = 5.5f + (offset % 11) * 0.17f,
                    temperatureC = 33.5f + (offset % 3) * 0.2f,
                    index = index,
                    mode = SibionicsAlgorithmMode.REPLAY,
                    impedance = 2_900f + offset,
                    eventTimeMs = index * 60_000L,
                )
            }

            val restored = SibionicsAlgorithmContext("restored-${selection.name}").apply {
                configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
            }
            assertTrue("selection=$selection", restored.restore(original.snapshot()))

            val nextIndex = 41
            val expected = original.process(
                6.7f, 34f, nextIndex, SibionicsAlgorithmMode.REPLAY,
                impedance = 2_950f, eventTimeMs = nextIndex * 60_000L,
            )
            val actual = restored.process(
                6.7f, 34f, nextIndex, SibionicsAlgorithmMode.REPLAY,
                impedance = 2_950f, eventTimeMs = nextIndex * 60_000L,
            )
            assertEquals("selection=$selection", expected, actual, 0.001f)
        }
    }

    @Test
    fun stockSnapshotTransfersExactCoreIntoEveryCustomModel() {
        val stock = SibionicsAlgorithmContext("stock-transfer").apply {
            configure(
                "46HU804EBJ4",
                1.4f,
                SibionicsConstants.Variant.CHINESE,
                SibionicsAlgorithmSelection.STOCK,
            )
        }
        repeat(130) { offset ->
            val index = offset + 1
            stock.processStock(
                rawMmol = 5.8f + (offset % 9) * 0.12f,
                temperatureC = 34f,
                index = index,
                mode = SibionicsAlgorithmMode.REPLAY,
            )
        }
        val stockSnapshot = stock.snapshot()

        listOf(
            SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR,
            SibionicsAlgorithmSelection.BALANCED_TRACKER,
            SibionicsAlgorithmSelection.STATE_MODEL,
        ).forEach { selection ->
            val target = SibionicsAlgorithmContext("target-${selection.name}").apply {
                configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
            }
            assertTrue("selection=$selection", target.restore(stockSnapshot))
            val output = target.processPreparedMeasurement(
                stockMmol = 6.4f,
                measurementMmol = 6.4f,
                rawMmol = 6.4f,
                temperatureC = 34f,
                index = 1,
                impedance = 2_900f,
                eventTimeMs = 60_000L,
                chemicalSignal = SibionicsChemicalSignal(6.4f, 0),
            )
            assertTrue("selection=$selection output=$output", output.isFinite() && output > 0f)
        }
    }

    @Test
    fun preparedCalibrationMeasurementDoesNotReapplyLegacyAnchorMapper() {
        val algorithm = SibionicsAlgorithmContext("prepared")
        algorithm.configure(
            "46HU804EBJ4",
            1.4f,
            SibionicsConstants.Variant.CHINESE,
            SibionicsAlgorithmSelection.STOCK_CALIBRATED,
        )
        val stock = algorithm.processStock(8f, 34f, 1, SibionicsAlgorithmMode.REPLAY)

        val output = algorithm.processPreparedMeasurement(
            stockMmol = stock,
            measurementMmol = 5.7f,
            rawMmol = 8f,
            temperatureC = 34f,
            index = 1,
            impedance = 100f,
            eventTimeMs = 60_000L,
        )

        assertEquals(5.7f, output, 0.001f)
    }

    @Test
    fun customModelUsesPreVendorChemicalMotionInsteadOfFilteringStock() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        repeat(20) { offset ->
            val index = 120 + offset
            context.process(
                stockMmol = 6f,
                rawMmol = 6f,
                temperatureC = 34f,
                impedance = 2_900f,
                index = index,
                eventTimeMs = index * 60_000L,
                anchors = emptyList(),
                chemicalMmol = 6f,
                vendorStockMmol = 6f,
            )
        }

        var output = 6f
        repeat(8) { offset ->
            val index = 140 + offset
            output = context.process(
                stockMmol = 6f,
                rawMmol = 6f,
                temperatureC = 34f,
                impedance = 2_900f,
                index = index,
                eventTimeMs = index * 60_000L,
                anchors = emptyList(),
                chemicalMmol = 6f + (offset + 1) * 0.18f,
                vendorStockMmol = 6f,
            )
        }

        assertTrue("output=$output", output > 6.5f)
    }

    @Test
    fun stateModelReducesKnownFirstOrderChemicalLag() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        var chemical = 6f
        repeat(30) { offset ->
            val index = 120 + offset
            context.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = 6f,
            )
        }

        var chemicalError = 0f
        var modelError = 0f
        repeat(20) { offset ->
            val truth = 6f + (offset + 1) * 0.12f
            chemical += (truth - chemical) / 6f
            val index = 150 + offset
            val output = context.process(
                truth, truth, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = chemical,
                vendorStockMmol = truth,
            )
            chemicalError += abs(chemical - truth)
            modelError += abs(output - truth)
        }

        assertTrue("chemicalError=$chemicalError modelError=$modelError", modelError < chemicalError)
    }

    @Test
    fun isolatedChemicalArtifactIsRobustlyDownWeighted() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        repeat(25) { offset ->
            val index = 120 + offset
            context.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = 6f,
                vendorStockMmol = 6f,
            )
        }
        val artifact = context.process(
            6f, 12f, 34f, 2_900f, 145, 145 * 60_000L, emptyList(),
            chemicalMmol = 12f,
            vendorStockMmol = 6f,
        )

        assertTrue("artifact=$artifact", artifact in 5.5f..6.7f)
    }

    @Test
    fun calibrationCorrectionShiftsEstimatorStateImmediately() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        repeat(25) { offset ->
            val index = 120 + offset
            context.process(
                6f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = 6f,
                vendorStockMmol = 6f,
            )
        }
        val calibrated = context.process(
            stockMmol = 5f,
            rawMmol = 6f,
            temperatureC = 34f,
            impedance = 2_900f,
            index = 145,
            eventTimeMs = 145 * 60_000L,
            anchors = emptyList(),
            chemicalMmol = 6f,
            vendorStockMmol = 6f,
        )

        assertTrue("calibrated=$calibrated", calibrated in 4.8f..5.2f)
    }

    @Test
    fun invalidMeasurementDoesNotInventOrAdvanceAReading() {
        val context = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        context.process(
            6f, 6f, 34f, 2_900f, 120, 120 * 60_000L, emptyList(),
            chemicalMmol = 6f,
            vendorStockMmol = 6f,
        )

        val missing = context.process(
            Float.NaN, 6f, 34f, 2_900f, 121, 121 * 60_000L, emptyList(),
            chemicalMmol = 6f,
            vendorStockMmol = 6f,
        )

        assertTrue(missing.isNaN())
    }

    @Test
    fun snapshotRestoresDriftAndMotionState() {
        val original = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        repeat(30) { offset ->
            val index = 120 + offset
            original.process(
                6f + offset * 0.04f, 6f, 34f, 2_900f, index, index * 60_000L, emptyList(),
                chemicalMmol = 6f + offset * 0.03f,
                vendorStockMmol = 6f + offset * 0.04f,
            )
        }
        val restored = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        assertTrue(restored.restore(original.snapshot()))

        val expected = original.process(
            7.3f, 7f, 34f, 2_900f, 150, 150 * 60_000L, emptyList(),
            chemicalMmol = 7f,
            vendorStockMmol = 7.3f,
        )
        val actual = restored.process(
            7.3f, 7f, 34f, 2_900f, 150, 150 * 60_000L, emptyList(),
            chemicalMmol = 7f,
            vendorStockMmol = 7.3f,
        )
        assertEquals(expected, actual, 0.001f)
    }

    @Test
    fun discardedLegacyStateIsNotReportedAsRestored() {
        val original = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        original.process(
            6f, 6f, 34f, 2_900f, 120, 120 * 60_000L, emptyList(),
            chemicalMmol = 6f,
            vendorStockMmol = 6f,
        )
        val incompatible = original.snapshot().also { bytes ->
            java.nio.ByteBuffer.wrap(bytes, Int.SIZE_BYTES, Int.SIZE_BYTES)
                .putInt(4)
        }

        val restored = SibionicsAdaptiveAlgorithmContext().apply { configure(1.4f) }
        assertFalse(restored.restore(incompatible))
        assertEquals(null, restored.continuationIndex())
    }

    @Test
    fun wrapperSnapshotsRequireExactCustomContinuationIndex() {
        val selections = listOf(
            SibionicsAlgorithmSelection.STATE_MODEL_CALIBRATED,
            SibionicsAlgorithmSelection.BALANCED_TRACKER_CALIBRATED,
            SibionicsAlgorithmSelection.RESPONSIVE_ESTIMATOR_CALIBRATED,
        )
        selections.forEach { selection ->
            val context = SibionicsAlgorithmContext("continuation-${selection.storageId}").apply {
                configure(
                    "46HU804EBJ4",
                    1.4f,
                    SibionicsConstants.Variant.CHINESE,
                    selection,
                )
            }
            repeat(140) { offset ->
                val index = offset + 1
                context.process(
                    rawMmol = 5.5f + (offset % 7) * 0.05f,
                    temperatureC = 34f,
                    index = index,
                    mode = SibionicsAlgorithmMode.LIVE,
                    impedance = 2_900f,
                    eventTimeMs = index * 60_000L,
                )
            }

            val restored = SibionicsAlgorithmContext("restored-${selection.storageId}").apply {
                configure(
                    "46HU804EBJ4",
                    1.4f,
                    SibionicsConstants.Variant.CHINESE,
                    selection,
                )
            }
            assertTrue(restored.restore(context.snapshot()))
            assertEquals(140, restored.customContinuationIndex())
            assertTrue(restored.hasExactContinuation(141))
            assertFalse(restored.hasExactContinuation(140))
            assertFalse(restored.hasExactContinuation(142))
        }
    }

    @Test
    fun lifetimesMatchEachSibionicsSeries() {
        val day = SibionicsConstants.DAY_MS
        assertEquals(14L * day, SibionicsConstants.Variant.EU.officialLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.HEMATONIX.officialLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.CHINESE.officialLifetimeMs)
        assertEquals(22L * day, SibionicsConstants.Variant.SIBIONICS2.officialLifetimeMs)
        assertEquals(23L * day, SibionicsConstants.Variant.SIBIONICS2.expectedLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.GS3.officialLifetimeMs)
        assertEquals(14L * day + 36L * 60L * 60L * 1000L, SibionicsConstants.Variant.GS3.expectedLifetimeMs)
    }

    @Test
    fun gs1ResetUsesObservedFf32Payload() {
        val expected = byteArrayOf(0x24, 0xE7.toByte(), 0x6F, 0x34)
        assertArrayEquals(
            expected,
            SibionicsProtocol.buildGs1ResetPacket(),
        )
        assertArrayEquals(
            expected,
            SibionicsProtocol.buildMaintenanceResetPacket(SibionicsConstants.Variant.EU),
        )
        assertArrayEquals(
            expected,
            SibionicsProtocol.buildMaintenanceResetPacket(SibionicsConstants.Variant.HEMATONIX),
        )
    }

    @Test
    fun chineseResetUsesObservedAa55Payload() {
        val expected = byteArrayOf(0xAA.toByte(), 0x55, 0x10, 0xF1.toByte())
        assertArrayEquals(expected, SibionicsProtocol.buildChineseResetPacket())
        assertArrayEquals(
            expected,
            SibionicsProtocol.buildMaintenanceResetPacket(SibionicsConstants.Variant.CHINESE),
        )
    }

    @Test
    fun sibionics2ResetKeepsEncryptedV120Command() {
        assertArrayEquals(
            SibionicsProtocol.buildResetPacket(0),
            SibionicsProtocol.buildMaintenanceResetPacket(SibionicsConstants.Variant.SIBIONICS2),
        )
    }

    @Test
    fun chineseHistoryProgressCountsRecordsRatherThanPacketIndex() {
        val first = chineseEntry(index = 700, unreceived = 12_340)
        assertEquals(12_341, SibionicsProtocol.estimateChineseHistoryTotal(0, 1, listOf(first)))

        val batch = (0 until 10).map { offset ->
            chineseEntry(index = 701 + offset, unreceived = 12_339 - offset)
        }
        assertEquals(12_341, SibionicsProtocol.estimateChineseHistoryTotal(12_341, 11, batch))
    }

    @Test
    fun chinesePositiveAddTimeCannotCreateFutureReading() {
        val now = 1_784_473_517_000L
        val live = chineseEntry(index = 8_364, unreceived = 0, addTimeSeconds = 116)
        val delayedHistory = chineseEntry(index = 8_363, unreceived = 2, addTimeSeconds = 4)

        assertEquals(now, live.eventTimeMs(now))
        assertEquals(now - 116_000L, delayedHistory.eventTimeMs(now))
    }

    private fun chineseEntry(
        index: Int,
        unreceived: Int,
        addTimeSeconds: Int = 0,
    ) = SibionicsProtocol.ChineseEntry(
        index = index,
        rawTemperature = 0,
        rawImpedance = 0,
        rawGlucose = 0,
        status = 0,
        numOfUnreceived = unreceived,
        addTimeSeconds = addTimeSeconds,
    )
}
