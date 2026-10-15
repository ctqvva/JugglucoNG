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
        assertEquals(listOf(config.sensorId, "a", "b"), MultiSensorSelection.selectedOrder())
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
        assertEquals(now, CombinedTrendHistory.startTime(id.takeLast(11)))
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
}
