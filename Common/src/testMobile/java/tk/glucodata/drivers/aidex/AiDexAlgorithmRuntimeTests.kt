package tk.glucodata.drivers.aidex

import android.content.Context
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.*
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.aidex.native.ble.AiDexBleManager

/** Actual Android runtime/manager; only JNI, final side effects and Room are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AiDexAlgorithmRuntimeTests.TestApplication::class,
    shadows = [AiDexAlgorithmRuntimeTests.NativeInputs::class,
        AiDexAlgorithmRuntimeTests.PublicationSink::class, AiDexAlgorithmRuntimeTests.RefreshSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AiDexAlgorithmRuntimeTests {
    class TestApplication : Applic() { override fun onCreate() = Unit }

    @Implements(Natives::class)
    class NativeInputs {
        companion object {
            var start = 0L
            @JvmStatic @Implementation fun getDeviceAddress(pointer: Long, fresh: Boolean): String? = null
            @JvmStatic @Implementation fun getSensorStartmsec(pointer: Long): Long = start
            @JvmStatic @Implementation fun getSensorUiSnapshot(serial: String): LongArray? = null
        }
    }

    @Implements(UiRefreshBus::class)
    class RefreshSink {
        companion object {
            @JvmStatic @Implementation fun requestDataRefresh() = Unit
            @JvmStatic @Implementation fun requestStatusRefresh() = Unit
        }
    }

    @Implements(SuperGattCallback::class)
    class PublicationSink {
        @Implementation fun completeManagedLiveReading(time: Long) = Unit
        @Implementation fun markLocalReadingAccepted(time: Long) = Unit
        companion object {
            var rate = Float.NaN
            var current: CurrentGlucoseSource.Snapshot? = null
            @JvmStatic @Implementation
            fun processExternalCurrentReading(serial: String, reading: LiveReadingLanes, rate: Float, time: Long, generation: Int) {
                this.rate = rate
                // Exercise the actual managed-before-callback preference used by exports.
                current = CurrentGlucoseSource.getFresh(300_000L, serial)
            }
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        val pending = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); pending.addLast(command) }
        fun runNext() { check(pending.isNotEmpty()); pending.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return pending.toMutableList().also { pending.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && pending.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private val serial = "X-222227QA01"
    private lateinit var driver: AiDexBleManager
    private lateinit var runtime: AiDexAlgorithmRuntime
    private lateinit var executor: QueuedExecutor
    private val room = sortedMapOf<Long, Float>()
    private val queuedRoomWrites = ArrayList<() -> Unit>()
    private var references = DoubleArray(0)
    private var rawFit = 1L
    private var autoFit = 1L
    private var revision = 0L
    private var completionNotifications = 0
    private var roomWrites = 0
    private var previousApp: Applic? = null
    private var previousUnit = 0
    private var previousHistoryBridge: Any? = null
    private var previousCalibrationProvider: Any? = null
    private val historyField = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }
    private val calibrationField = CalibrationAccess::class.java.getDeclaredField("provider").apply { isAccessible = true }

    @Before fun setUp() {
        previousApp = Applic.app
        previousUnit = Applic.unit
        previousHistoryBridge = historyField.get(null)
        previousCalibrationProvider = calibrationField.get(null)
        Applic.app = RuntimeEnvironment.getApplication() as TestApplication
        Applic.unit = 0
        Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE).edit().clear().commit()
        Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        AiDexSourceJournalBackup.file(Applic.app.filesDir, serial).parentFile!!.deleteRecursively()
        NativeInputs.start = System.currentTimeMillis() - 180_000L
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.clear()
        driver = AiDexBleManager(serial, 0L, 4)
        SensorBluetooth.gattcallbacks.add(driver)
        SensorIdentity.invalidateCaches()
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(HistoryRepositoryBridge::class.java.classLoader,
            arrayOf(HistoryRepositoryBridge::class.java)) { _, method, args ->
            when (method.name) {
                "getHistoryForNotificationForSensor" -> room.map { (time, value) ->
                    GlucosePoint(time, value / if (args!![2] as Boolean) 18.0182f else 1f, 0f)
                }
                "getHistoryTimestampsForSensorBlocking" -> room.keys.toLongArray()
                "storeHistoryBatchBlocking" -> {
                    val times = args!![1] as LongArray
                    val values = args[2] as FloatArray
                    times.indices.forEach { room[times[it]] = values[it] }
                    roomWrites++
                    true
                }
                "storeCurrentReadingAsync" -> {
                    val time = args!![0] as Long
                    val value = args[1] as Float
                    // Deliberately delayed until after a Stock replay, reproducing
                    // the old unowned IO coroutine's harmful execution order.
                    queuedRoomWrites += { room[time] = value }
                    null
                }
                else -> error("Unexpected history operation: ${method.name}")
            }
        } as HistoryRepositoryBridge)
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = references.isNotEmpty()
            override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean, emitDiagnostics: Boolean, sensorId: String?) = value + 40f
            override fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean) = if (isRawMode) DoubleArray(0) else references
            override fun getIntegratedCalibrationFingerprint(sensorId: String?, isRawMode: Boolean) = if (isRawMode) rawFit else autoFit
            override fun getIntegratedCalibratedSeries(values: FloatArray, timestamps: LongArray, isRawMode: Boolean, sensorId: String?) =
                FloatArray(values.size) { values[it] + 40f / if (Applic.unit == 1) 18.0182f else 1f }
            override fun notifyExternalCalibrationPipelineChanged() {
                completionNotifications++
                runtime.calibrationChanged(++revision)
            }
        })
        executor = QueuedExecutor()
        val store = AiDexBleManager::class.java.getDeclaredMethod("storeAlgorithmLive", AiDexAlgorithmReading::class.java,
            AiDexAlgorithmReading::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val publish = AiDexBleManager::class.java.getDeclaredMethod("publishAlgorithmReading", AiDexAlgorithmReading::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        runtime = AiDexAlgorithmRuntime(Applic.app, serial, { 0L }, { NativeInputs.start }, { false },
            storeLive = { row, previous -> store.invoke(driver, row, previous, Natives.AIDEX_TREND_UNKNOWN) as AiDexAlgorithmReading },
            publish = { row, live -> publish.invoke(driver, row, live); Unit }, worker = executor)
        AiDexBleManager::class.java.getDeclaredField("algorithmRuntime\$delegate").apply { isAccessible = true }
            .set(driver, lazyOf(runtime))
        PublicationSink.rate = Float.NaN
        PublicationSink.current = null
    }

    @After fun tearDown() {
        if (::runtime.isInitialized) runtime.close()
        historyField.set(null, previousHistoryBridge)
        calibrationField.set(null, previousCalibrationProvider)
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.clear()
        SensorIdentity.invalidateCaches()
        Applic.unit = previousUnit
        Applic.app = previousApp
    }

    private fun sample(index: Int, factory: Float = 100f, raw: Float = Float.NaN) =
        runtime.source(NativeInputs.start + index * 60_000L, factory, raw, Float.NaN, AiDexSourceSample.LIVE)

    private fun choose(mode: Int) { assertTrue(runtime.select(mode)); executor.runNext() }

    @Test fun completionRevisionDoesNotScheduleAnotherCalibratedReplay() {
        runtime.record(listOf(sample(0), sample(1)))
        choose(9)
        assertEquals(1, completionNotifications)
        assertEquals(1, roomWrites)
        assertFalse(runtime.controller.needsReplay())
        assertFalse(runtime.isRebuilding())
        assertTrue(executor.pending.isEmpty())
        runtime.calibrationChanged(++revision) // another cache-only invalidation
        assertTrue(executor.pending.isEmpty())
        assertEquals(1, roomWrites)
    }

    @Test fun changedReferencesAndFallbackFitEachScheduleOneReplay() {
        runtime.record(listOf(sample(0)))
        choose(9)
        references = doubleArrayOf(100.0, 130.0, NativeInputs.start.toDouble())
        runtime.calibrationChanged(++revision)
        executor.runNext()
        assertTrue(executor.pending.isEmpty())
        autoFit++
        runtime.calibrationChanged(++revision)
        executor.runNext()
        assertEquals(3, completionNotifications)
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun liveRoomWriteCannotOverwriteLaterStockRestoration() {
        runtime.record(listOf(sample(0)))
        choose(9)
        val live = sample(1)
        assertTrue(runtime.live(live))
        assertEquals(140f, room[live.timestampMs]!!, 0f)
        choose(0)
        queuedRoomWrites.forEach { it() }
        assertTrue("AiDex must not leave a queued live Room writer", queuedRoomWrites.isEmpty())
        assertEquals(100f, room[live.timestampMs]!!, 0f)
        assertFalse(driver.integratesUserCalibration(false))
        assertEquals("Stock receives the generic correction exactly once", 140f,
            CalibrationAccess.getCalibratedValue(room[live.timestampMs]!!, live.timestampMs, false, false, serial), 0f)
    }

    @Test fun publicationSeesTheIncomingManagedMinuteInsteadOfThePreviousMinute() {
        for (index in 0..2) {
            val sample = sample(index, 100f + index * 20)
            assertTrue(runtime.live(sample))
            val current = requireNotNull(PublicationSink.current)
            assertEquals("managed", current.source)
            assertEquals(sample.timestampMs, current.timeMillis)
            assertEquals(sample.factoryMgDl, current.numericValue, 0f)
        }
    }

    @Test fun firstBroadcastRateStaysMgdlPerMinuteInBothDisplayUnits() {
        val store = AiDexBleManager::class.java.getDeclaredMethod("storeAlgorithmLive", AiDexAlgorithmReading::class.java,
            AiDexAlgorithmReading::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val sample = sample(1, 180.182f)
        assertTrue(runtime.live(sample) { row, previous -> store.invoke(driver, row, previous, 20) as AiDexAlgorithmReading })
        for (isMmol in listOf(false, true)) {
            Applic.unit = if (isMmol) 1 else 0
            val snapshot = requireNotNull(driver.getManagedCurrentSnapshot(300_000L))
            assertEquals(if (isMmol) 10f else 180.182f, snapshot.glucoseValue, 0.001f)
            assertEquals(2f, snapshot.rate, 0f)
            val publisher = AiDexBleManager::class.java.getDeclaredMethod("publishAlgorithmReading", AiDexAlgorithmReading::class.java,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            publisher.invoke(driver, runtime.latestPublished, true)
            assertEquals(2f, PublicationSink.rate, 0f)
            assertEquals(2f, requireNotNull(PublicationSink.current).rate, 0f)
        }
    }

    @Test fun rejectedClearStorageReleasesPendingStockChoiceWithoutChangingStart() {
        runtime.record(listOf(sample(0), sample(1)))
        choose(9)
        val originalStart = NativeInputs.start
        runtime.resetRequested()
        choose(0) // queued replay exits while the reset is pending
        assertEquals(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED, runtime.controller.appliedSelection)
        AiDexBleManager::class.java.getDeclaredMethod("handleClearStorageResponse", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(driver, byteArrayOf(0xF3.toByte(), 0))
        executor.runNext()
        assertEquals(originalStart, NativeInputs.start)
        assertEquals(AiDexAlgorithmSelection.STOCK, runtime.controller.appliedSelection)
        assertFalse(runtime.controller.needsReplay())
        assertTrue(executor.pending.isEmpty())
        choose(8)
        assertEquals(AiDexAlgorithmSelection.ADAPTIVE_V2, runtime.controller.appliedSelection)
    }
}
