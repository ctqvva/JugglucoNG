package tk.glucodata.combined

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import tk.glucodata.*
import tk.glucodata.alerts.AlertVolumeTestApplication
import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.alerts.DualSensorRuntime
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.DisplayValueResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class,
    shadows = [CombinedSensorIntegrationTests.NativeSink::class, CombinedSensorIntegrationTests.PublicationSink::class])
class CombinedSensorIntegrationTests {
    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink {
        companion object {
            var current = "a"
            var saved: strGlucose? = null
            var healthEnabled = false
            var allowShell = true
            var start = 0L
            val ids = linkedSetOf("a", "b")
            val nativeValues = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Float>>()
            @JvmStatic @Implementation fun gethealthConnect() = healthEnabled
            @JvmStatic @Implementation fun getsensorptr(ptr: Long) = 1L
            @JvmStatic @Implementation fun lastglucoseForSensor(id: String): strGlucose? =
                saved?.takeIf { id == "CO${it.sensorid}" || id == it.sensorid.takeLast(8) }
            @JvmStatic @Implementation fun lastglucose(): strGlucose? = null
            @JvmStatic @Implementation fun lastsensorname() = current
            @JvmStatic @Implementation fun activeSensors() = ids.toTypedArray()
            @JvmStatic @Implementation fun setcurrentsensor(id: String) { current = id }
            @JvmStatic @Implementation fun ensureSensorShellWithCapacity(id: String, time: Long, capacity: Int): Long {
                if (!allowShell) return 0
                ids.add(id); start = time; return 1
            }
            @JvmStatic @Implementation fun str2sensorptr(id: String): Long = if (id in ids) 1 else 0
            @JvmStatic @Implementation fun getSensorStartmsecFromSensorptr(ptr: Long) = start * 1000
            @JvmStatic @Implementation fun addGlucoseStreamBatchWithRawTemp(times: LongArray, values: FloatArray,
                raw: FloatArray, temperatures: FloatArray, id: String): Int {
                values.forEach { nativeValues += id to it }
                return times.size
            }
        }
    }
    @Implements(SuperGattCallback::class)
    class PublicationSink {
        data class Published(val id: String?, val value: Float, val resolved: Boolean, val time: Long, val rate: Float)
        companion object {
            val outputs = java.util.concurrent.CopyOnWriteArrayList<Published>()
            @JvmStatic @Implementation fun processExternalCurrentReading(id: String?, reading: LiveReadingLanes,
                rate: Float, time: Long, generation: Int) {
                outputs += Published(id, reading.resolvedValue, reading.isResolved, time, rate)
            }
        }
    }
    @Before fun setup() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        Applic.unit = 0
        Applic.app.getSharedPreferences("combined_trend_boundaries", 0).edit().clear().commit()
        SuperGattCallback.previousglucose = null
        CalibrationAccess.unregisterForTests()
        NativeSink.saved = null
        NativeSink.healthEnabled = false
        Applic.app.getSharedPreferences("tk.glucodata_preferences", 0).edit().clear().commit()
        CombinedSensorRegistry::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .set(CombinedSensorRegistry, null)
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.invalidate()
        NativeSink.current = "a"; NativeSink.ids.clear(); NativeSink.ids.addAll(listOf("a", "b"))
        NativeSink.allowShell = true
        PublicationSink.outputs.clear()
        NativeSink.nativeValues.clear()
        Log.doLog = false
    }
    @After fun cleanup() {
        CombinedSensorRegistry.setEnabled(false, emptyList())
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.invalidate()
        CalibrationAccess.unregisterForTests()
        HealthConnectAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HealthConnectAccess, null)
    }
    private fun drain(manager: CombinedSensorManager) {
        val executor = CombinedSensorManager::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(manager) as ScheduledExecutorService
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }
    private fun learn(manager: CombinedSensorManager, now: Long, a: Float = 100f, b: Float = 110f) {
        for (minute in 0..2) {
            val time = now - (2 - minute) * 60_000L
            manager.observe(input("a", time, a))
            manager.observe(input("b", time, b))
        }
        drain(manager)
    }
    private fun input(id: String, time: Long, mgdl: Float): CurrentDisplaySource.Snapshot {
        val value = if (Applic.unit == 1) mgdl / 18.0182f else mgdl
        val display = DisplayValueResolver.resolve(value, Float.NaN, 0, Applic.unit == 1)
        return CurrentDisplaySource.Snapshot(time, 0f, id, 0, 0, 0, "test", value, Float.NaN,
            value, mgdl.toInt(), Applic.unit == 1, display)
    }
    @Test fun enablingCreatesAndSelectsADerivedSourceAndDisablingRestoresThePhysicalSource() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val config = CombinedSensorRegistry.config()
        assertTrue(config.enabled)
        assertEquals(config.sensorId, SensorIdentity.resolveMainSensor())
        assertEquals(listOf(config.sensorId), MultiSensorSelection.selectedOrder())
        val old = CombinedSensorRegistry.manager!!
        assertFalse(CombinedSensorRegistry.hasFreshOutput())
        CombinedSensorRegistry.setEnabled(false, emptyList())
        assertTrue(old.isStopped)
        assertEquals("a", SensorIdentity.resolveMainSensor())
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        assertNotSame(old, CombinedSensorRegistry.manager)
        var obsoletePublished = false
        CombinedSensorRegistry.publishIfCurrent(old) { obsoletePublished = true }
        assertFalse(obsoletePublished)
        MultiSensorSelection.setSelectedOrder(listOf("a"))
        assertEquals(listOf("a", "b"), CombinedSensorRegistry.config().inputs)
    }
    @Test fun normalPublicationReceivesTheCombinedResolvedPrimaryInEitherDisplayUnit() {
        for (unit in listOf(0, 1)) {
            Applic.unit = unit
            CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
            val manager = CombinedSensorRegistry.manager!!
            val now = System.currentTimeMillis()
            for (minute in 0..2) {
                val time = now - (2 - minute) * 60_000L
                manager.observe(input("a", time, 100f))
                manager.observe(input("b", time, 110f))
            }
            drain(manager)
            val published = PublicationSink.outputs.last()
            assertEquals(CombinedSensorRegistry.config().sensorId, published.id)
            assertTrue(published.resolved)
            assertEquals(if (unit == 1) 105f / 18.0182f else 105f, published.value, 0.001f)
            assertEquals(now, published.time)
            assertTrue(CombinedSensorRegistry.hasFreshOutput())
            assertEquals(10.5f, NativeSink.nativeValues.last().second, 0.001f)
            assertTrue(NativeSink.nativeValues.all { CombinedSensorRegistry.isCombined(it.first) })
            assertTrue(PublicationSink.outputs.all { CombinedSensorRegistry.isCombined(it.id) })
            CombinedSensorRegistry.setEnabled(false, emptyList())
            PublicationSink.outputs.clear()
        }
    }
    @Test fun calibrationReferencesInfluenceTheSameBlendInBothDisplayUnits() {
        val values = mutableListOf<Float>()
        for (unit in listOf(0, 1)) {
            Applic.unit = unit
            val now = System.currentTimeMillis()
            CalibrationAccess.register(object : CalibrationProvider {
                override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = false
                override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean, emitDiagnostics: Boolean, sensorId: String?) = value
                override fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean) =
                    if (sensorId == "a") doubleArrayOf(100.0, (100f / if (unit == 1) 18.0182f else 1f).toDouble(), (now - 120_000L).toDouble()) else doubleArrayOf()
            })
            CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
            val manager = CombinedSensorRegistry.manager!!
            learn(manager, now)
            values += manager.result.estimateMgdl!!
            CombinedSensorRegistry.setEnabled(false, emptyList())
        }
        assertTrue(values[0] < 104f)
        assertEquals(values[0], values[1], 0.001f)
    }

    @Test fun restartRestoresTheNativeShortAliasEvenWhenAPhysicalSensorWasGloballyNewest() {
        val now = System.currentTimeMillis() / 1000 * 1000
        val id = CombinedSensorRegistry.idFor(listOf("a", "b"))
        NativeSink.saved = strGlucose(now / 1000, "105", id.takeLast(11), 0f, 0, 0)
        assertTrue(SensorIdentity.matches(id, id.takeLast(11)))
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        manager.observe(input("a", now, 100f))
        manager.observe(input("b", now, 110f))
        drain(manager)
        assertTrue(PublicationSink.outputs.isEmpty())
        assertTrue(NativeSink.nativeValues.isEmpty())
        assertEquals(105f, manager.getManagedCurrentSnapshot(360_000L)!!.glucoseValue, 0.001f)
        assertEquals(id, SensorIdentity.resolveAppSensorId(id.takeLast(11)))
        assertEquals(id, SensorIdentity.resolveAppSensorId(id.takeLast(8)))
    }

    @Test fun unknownTrendSurvivesPublicationAndHistoryDoesNotCrossACompositionBoundary() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        val now = System.currentTimeMillis()
        learn(manager, now - 60_000L)
        assertTrue(PublicationSink.outputs.last().rate.isNaN()) // learning -> blend
        manager.observe(input("a", now, 100f))
        manager.observe(input("b", now, 160f))
        drain(manager)
        assertTrue(PublicationSink.outputs.last().rate.isNaN()) // blend -> fallback
        val id = manager.SerialNumber
        val history = listOf(GlucosePoint(now - 60_000L, 105f, 0f), GlucosePoint(now, 100f, 0f))
        assertEquals(listOf(now), CombinedTrendHistory.filter(history, id).map { it.timestamp })
        val snapshot = CurrentDisplaySource.resolveSnapshot(
            CurrentGlucoseSource.Snapshot(now, "100", 100f, Float.NaN, 100f, Float.NaN, id, 0, 0, "test"),
            history, now - 300_000L, 0, false, CurrentDisplaySource.SmoothingMode(false, 0, false), id)!!
        assertTrue(snapshot.rate.isNaN())
        assertTrue(DisplayTrendSource.resolveArrowRate(history, snapshot, 0, false, Float.NaN).isNaN())
        // The cutoff is persisted, rather than owned by the disposable manager.
        manager.shutdown()
        assertEquals(now / 1000L * 1000L, CombinedTrendHistory.startTime(id.takeLast(11)))
        assertEquals(history, CombinedTrendHistory.filter(history, "a"))
        val uiHistory = history.map { tk.glucodata.ui.GlucosePoint(it.value, "", it.timestamp, sensorSerial = id) }
        assertTrue(tk.glucodata.logic.TrendEngine.calculateTrend(uiHistory, isMmol = false).velocity.isNaN())
        assertNull(tk.glucodata.ui.readingDeltas(listOf(now), uiHistory, false, 1).single())
        history.forEach { it.sensorSerial = id }
        assertTrue(TrendAccess.calculateVelocity(history, false, false).isNaN())
    }

    @Test fun watchPromotionExitsCombineThroughTheSharedPrimarySelectionPath() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        WearSensorSelectionSync.onCommand("primary:b".toByteArray())
        assertFalse(CombinedSensorRegistry.config().enabled)
        assertEquals("b", SensorIdentity.resolveMainSensor())
        assertEquals("b", MultiSensorSelection.selectedOrder().first())
    }

    @Test fun stoppedOrExpiredAssessmentCannotKeepAWarningOrWaitAlive() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        val now = System.currentTimeMillis()
        for (minute in 0..5) {
            manager.observe(input("a", now - (5 - minute) * 60_000L, 100f))
            manager.observe(input("b", now - (5 - minute) * 60_000L, 160f))
        }
        drain(manager)
        assertTrue(CombinedSensorRegistry.result(now)!!.warningDue)
        val config = AlertDefaults.defaultConfig(AlertType.SENSOR_DISAGREEMENT, false).copy(enabled = true)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, DualSensorRuntime.evaluate(config, now + 360_001L).status)
        val revision = CombinedSensorRegistry.revision.value
        manager.observe(input("a", now + 60_000L, 100f))
        drain(manager)
        // A future sample is unavailable, and status alone must invalidate the control.
        assertTrue(CombinedSensorRegistry.revision.value > revision)
        manager.shutdown()
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, DualSensorRuntime.evaluate(config, now).status)
        assertFalse(CombinedSensorRegistry.hasFreshOutput())
    }

    @Test fun waitingStatusNotifiesTheControlWithoutPublishingAnotherReading() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        val now = System.currentTimeMillis()
        learn(manager, now - 60_000L)
        val revision = CombinedSensorRegistry.revision.value
        val dataRevision = UiRefreshBus.revision.value
        manager.observe(input("a", now, 100f))
        drain(manager)
        assertEquals(DualSensorConsensus.Status.WAITING_FOR_PEER, CombinedSensorRegistry.result(now)!!.status)
        assertTrue(CombinedSensorRegistry.revision.value > revision)
        assertEquals(dataRevision, UiRefreshBus.revision.value)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, CombinedSensorRegistry.result(now + 360_001L)!!.status)
    }

    @Test fun outputExpiryNotifiesTheControlEvenIfAssessmentRemainsUnavailable() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        CombinedSensorRegistry.manager!!.shutdown()
        val time = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val manager = CombinedSensorManager(CombinedSensorRegistry.config().sensorId, listOf("a", "b"), time::get)
        SensorBluetooth.gattcallbacks.add(manager)
        manager.connectDevice(0)
        val now = time.get()
        learn(manager, now - 60_000L)
        manager.observe(input("a", now, Float.NaN))
        manager.observe(input("b", now, Float.NaN))
        drain(manager)
        assertTrue(CombinedSensorRegistry.hasFreshOutput())
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, manager.result.status)
        val revision = CombinedSensorRegistry.revision.value
        time.addAndGet(7 * 60_000L)
        val executor = CombinedSensorManager::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(manager) as ScheduledExecutorService
        executor.submit { CombinedSensorManager::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(manager) }.get(10, TimeUnit.SECONDS)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, manager.result.status)
        assertFalse(CombinedSensorRegistry.hasFreshOutput())
        assertTrue(CombinedSensorRegistry.revision.value > revision)
    }

    @Test fun healthConnectReceivesTheDerivedNativeRecordAndPhysicalExportsResumeWhenDisabled() {
        val exported = java.util.concurrent.CopyOnWriteArrayList<String>()
        HealthConnectAccess.register(object : HealthConnect {
            override fun start(activity: MainActivity) {}
            override fun stop() {}
            override fun writeAll(sensorPtr: Long, sensorName: String) { assertTrue(sensorPtr != 0L); exported += sensorName }
        })
        NativeSink.healthEnabled = true
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        learn(manager, System.currentTimeMillis())
        assertTrue(exported.isNotEmpty())
        assertTrue(exported.all { it == manager.SerialNumber })
        val physical = object : SuperGattCallback("a", 0, 0) {}
        val export = SuperGattCallback::class.java.getDeclaredMethod("exportToHealthConnect").apply { isAccessible = true }
        val before = exported.size
        export.invoke(physical)
        assertEquals(before, exported.size)
        CombinedSensorRegistry.setEnabled(false, emptyList())
        export.invoke(physical)
        assertEquals("a", exported.last())
    }

    @Test fun threeSelectedSensorsPublishOneGroupEstimate() {
        NativeSink.ids += "c"
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b", "c"))
        val manager = CombinedSensorRegistry.manager!!
        val now = System.currentTimeMillis()
        for (minute in 0..2) for ((id, value) in listOf("a" to 100f, "b" to 110f, "c" to 120f))
            manager.observe(input(id, now - (2 - minute) * 60_000L, value))
        drain(manager)
        assertEquals(110f, PublicationSink.outputs.last().value, 0.001f)
        assertEquals(listOf("a", "b", "c"), CombinedSensorRegistry.config().inputs)
    }

    @Test fun failingToCreateNativeHistoryDoesNotEnableOrSelectTheMode() {
        NativeSink.allowShell = false
        assertThrows(IllegalStateException::class.java) { CombinedSensorRegistry.setEnabled(true, listOf("a", "b")) }
        assertFalse(CombinedSensorRegistry.config().enabled)
        assertEquals("a", NativeSink.current)
    }
    @Test fun identityIsOrderIndependentButRejectsRecursiveOrDuplicateInputs() {
        assertEquals(CombinedSensorRegistry.idFor(listOf("a", "b")), CombinedSensorRegistry.idFor(listOf("b", "a")))
        for (invalid in listOf(listOf("a", "a"), listOf("a", "COMB-123"), listOf("a"), listOf("a", ""))) {
            assertThrows(IllegalArgumentException::class.java) { CombinedSensorRegistry.setEnabled(true, invalid) }
        }
    }
    @Test fun veryLowCalibratedFallbackPublishesInBothUnitsWithoutBlending() {
        for (unit in listOf(0, 1)) {
            Applic.unit = unit
            CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
            val manager = CombinedSensorRegistry.manager!!
            learn(manager, System.currentTimeMillis(), 15f, 100f)
            assertNull(manager.result.estimateMgdl)
            assertEquals(if (unit == 1) 15f / 18.0182f else 15f, PublicationSink.outputs.last().value, 0.001f)
            assertEquals(1.5f, NativeSink.nativeValues.last().second, 0.001f)
            CombinedSensorRegistry.setEnabled(false, emptyList())
        }
    }

    @Test fun historicalRowsKeepDeltasAndTrendsInsideTheirOwnSegments() {
        val id = CombinedSensorRegistry.idFor(listOf("a", "b"))
        // A previous day, then a later blend/fallback transition, then today's segment.
        val base = System.currentTimeMillis() / 1000 * 1000 - 2 * 24 * 60 * 60_000L
        val minutes = (0..2).toList() + (1440..1442).toList() + (2880..2882).toList()
        for (minute in minutes) CombinedTrendHistory.recordOutput(id, base + minute * 60_000L, minute % 1440 == 0)
        val points = minutes.map { minute -> tk.glucodata.ui.GlucosePoint(
            if (minute < 1440) 105f else if (minute < 2880) 100f else 110f, "", base + minute * 60_000L, sensorSerial = id) }
        val index = tk.glucodata.ui.RowDeltaIndex(points, SensorIdentity::matches)
        val deltas = index.deltasFor(points.asReversed(), false, 1)
        for (minute in listOf(2, 1442, 2882)) {
            val point = points.first { it.timestamp == base + minute * 60_000L }
            assertNotNull("missing historical delta at $minute", deltas[point])
            val segment = CombinedTrendHistory.filterUi(points, id, point.timestamp)
            assertEquals(3, segment.size)
            assertEquals(0f, tk.glucodata.logic.TrendEngine.calculateTrend(segment, isMmol = false).velocity, 0.001f)
        }
        assertNull(deltas[points.first { it.timestamp == base + 1440 * 60_000L }])
        assertNull(deltas[points.first { it.timestamp == base + 2880 * 60_000L }])
    }

    @Test fun enablingShowsOnlyTheCombinedSourceWhileKeepingAllContributorsActive() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        val id = manager.SerialNumber
        learn(manager, System.currentTimeMillis())
        assertEquals(listOf(id), MultiSensorSelection.selectedOrder())
        assertEquals(listOf(id), NotificationMultiSensorSource.selectedSensorIds(id))
        assertEquals(listOf("a", "b"), CombinedSensorRegistry.config().inputs)
        assertTrue(NativeSink.ids.containsAll(listOf("a", "b")))
        val current = CurrentDisplaySource.resolveCurrent(360_000L, id)!!
        assertEquals(null, current.secondaryStr)
        assertEquals(null, current.tertiaryStr)
        assertFalse(CurrentDisplaySource.setViewModeForSensor(id, 2))
        assertEquals(0, CurrentDisplaySource.resolveViewModeForSensor(id))
        CombinedSensorRegistry.setEnabled(false, emptyList())
        assertEquals(listOf("a", "b"), MultiSensorSelection.selectedOrder())
    }

    @Test fun oldAutomaticPeerSelectionMigratesOnceWithoutOverridingLaterExplicitChoices() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val manager = CombinedSensorRegistry.manager!!
        drain(manager)
        MultiSensorSelection.setSelectedOrder(listOf(manager.SerialNumber, "a", "b"))
        Applic.app.getSharedPreferences("tk.glucodata_preferences", 0).edit().remove("combined_sensor_display_version").commit()
        CombinedSensorRegistry.migrateDisplaySelection(manager)
        assertEquals(listOf(manager.SerialNumber), MultiSensorSelection.selectedOrder())
        MultiSensorSelection.setSelectedOrder(listOf(manager.SerialNumber, "a"))
        CombinedSensorRegistry.migrateDisplaySelection(manager)
        assertEquals(listOf(manager.SerialNumber, "a"), MultiSensorSelection.selectedOrder())
        assertEquals(listOf("a", "b"), CombinedSensorRegistry.config().inputs)
    }

    @Test fun combinedReadingRowsHaveOneValueEvenWithASealedValueOrObsoleteViewMode() {
        val id = CombinedSensorRegistry.idFor(listOf("a", "b"))
        for (unit in listOf(0, 1)) for (mode in 0..3) for (sealed in listOf(false, true)) {
            val divisor = if (unit == 1) 18.0182f else 1f
            val value = 119f / divisor
            val point = tk.glucodata.ui.GlucosePoint(value, "", System.currentTimeMillis(), value,
                sensorSerial = id, source = GlucoseReadingSource.COMBINED,
                sealedDisplayValue = if (sealed) value else null, sealedDisplayViewMode = if (sealed) 0 else null)
            val display = tk.glucodata.ui.getDisplayValues(point, mode, if (unit == 1) "mmol/L" else "mg/dL", 200f / divisor)
            assertEquals(value, display.primaryValue, 0.001f)
            assertNull(display.secondaryStr)
            assertNull(display.tertiaryStr)
        }
    }

    @Test fun dashboardHeroUsesCombinedRateInsteadOfThePreviousPhysicalHistory() {
        val id = CombinedSensorRegistry.idFor(listOf("a", "b"))
        val now = System.currentTimeMillis()
        val display = DisplayValueResolver.resolve(6.6f, Float.NaN, 0, true)
        val snapshot = CurrentDisplaySource.Snapshot(now, 0f, id, 0, 0, 0, "combined", 6.6f, Float.NaN,
            6.6f, 119, true, display)
        val previousPhysical = (0..8).map { minute -> tk.glucodata.ui.GlucosePoint(
            20f - minute, "", now - (9 - minute) * 60_000L, sensorSerial = "a") }
        val latest = tk.glucodata.ui.GlucosePoint(6.6f, "", now, sensorSerial = id, source = GlucoseReadingSource.COMBINED)
        val trend = tk.glucodata.ui.resolveDashboardHeroTrend(previousPhysical, latest, snapshot, id, 0, true)
        assertEquals(0f, trend.velocity)
        assertEquals(tk.glucodata.logic.TrendEngine.TrendState.Flat, trend.state)
        val unknown = tk.glucodata.ui.resolveDashboardHeroTrend(previousPhysical, latest, snapshot.copy(rate = Float.NaN), id, 0, true)
        assertTrue(unknown.velocity.isNaN())
        assertEquals(tk.glucodata.logic.TrendEngine.TrendState.Unknown, unknown.state)
        val tagged = DisplayTrendSource.augmentHistory(emptyList(), snapshot, id, 0L).single()
        assertEquals(id, tagged.sensorSerial)
    }

    @Test fun defaultCombinedChartAndScrubberShowOneLineAndExplicitPeersRemainSeparate() {
        CombinedSensorRegistry.setEnabled(true, listOf("a", "b"))
        val id = CombinedSensorRegistry.config().sensorId
        val now = System.currentTimeMillis()
        for (mmol in listOf(false, true)) {
            val divisor = if (mmol) 18.0182f else 1f
            val unit = if (mmol) "mmol/L" else "mg/dL"
            val combined = (0..8).map { minute -> tk.glucodata.ui.GlucosePoint(
                (if (minute == 4) 140f else 119f) / divisor, "", now - (8 - minute) * 60_000L,
                sensorSerial = id, source = GlucoseReadingSource.COMBINED) }
            fun resolve(peers: tk.glucodata.ui.MultiSensorDisplayData) = tk.glucodata.ui.resolveDashboardChart(
                tk.glucodata.ui.ChartResolutionInputs(combined, peers, 5, false,
                    tk.glucodata.chart.MainSensorOwnership.NONE, 0L, 3, true, false, id, 0))
            val single = resolve(tk.glucodata.ui.MultiSensorDisplayData.EMPTY)
            assertEquals(listOf(id), MultiSensorSelection.selectedOrder())
            assertEquals(1, single.chartModel.series.size)
            val primary = single.chartModel.primary!!
            assertTrue(primary.secondaryLanes.isEmpty())
            val point = combined[4]
            val drawn = primary.cursorAt(point.timestamp)!!.line!!.value
            assertNotEquals(point.value, drawn)
            val scrub = tk.glucodata.ui.resolveChartScrubValues(point, 3, unit, drawn, true)
            assertEquals(drawn, scrub.primaryValue, 0.001f)
            assertNull(scrub.secondaryStr)
            assertNull(scrub.tertiaryStr)

            // Only an explicitly selected peer adds its independent trace and row.
            MultiSensorSelection.setSelectedOrder(listOf(id, "b"))
            val physical = combined.map { it.copy(value = 141f / divisor, sensorSerial = "b", source = GlucoseReadingSource.SENSOR) }
            val peers = tk.glucodata.ui.MultiSensorDisplay.buildDisplayData(physical, listOf("b"), mapOf("b" to 0), listOf(id, "b"))
            val withPeer = resolve(peers).chartModel
            assertEquals(2, withPeer.series.size)
            assertEquals(listOf("b"), withPeer.peers.map { it.sensorId })
            assertTrue(withPeer.primary!!.secondaryLanes.isEmpty())
            assertEquals(141f / divisor, withPeer.peers.single().cursorAt(point.timestamp)!!.line!!.value, 0.001f)
            MultiSensorSelection.setSelectedOrder(listOf(id))
        }
    }

    @Test fun physicalScrubberRetainsItsCalibrationAndRawLanes() {
        val point = tk.glucodata.ui.GlucosePoint(100f, "", 1L, rawValue = 90f, sensorSerial = "a")
        val calibration = tk.glucodata.data.calibration.CalibrationManager
        val previous = calibration.shouldHideInitialWhenCalibrated()
        try {
            for (hide in listOf(false, true)) {
                calibration.setHideInitialWhenCalibrated(hide)
                val display = tk.glucodata.ui.resolveChartScrubValues(point, 2, "mg/dL", 105f, true)
                assertEquals(105f, display.primaryValue)
                assertEquals(if (hide) "90" else "100", display.secondaryStr)
                assertEquals(if (hide) null else "90", display.tertiaryStr)
            }
        } finally { calibration.setHideInitialWhenCalibrated(previous) }
    }

}
