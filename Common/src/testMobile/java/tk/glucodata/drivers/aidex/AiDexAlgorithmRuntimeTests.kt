package tk.glucodata.drivers.aidex

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.net.Uri
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.*
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.aidex.native.ble.AiDexBleManager
import tk.glucodata.drivers.aidex.native.protocol.AiDexKeyExchange
import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import tk.glucodata.data.calibration.CalibrationDatabase
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.SettingsExporter
import tk.glucodata.data.ExportPackageExporter
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.calibration.MobileCalibrationProvider
import org.json.JSONArray
import org.json.JSONObject

/** Actual Android runtime/manager; JNI, crypto and final side effects/history Room are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AiDexAlgorithmRuntimeTests.TestApplication::class,
    shadows = [AiDexAlgorithmRuntimeTests.NativeInputs::class,
        AiDexAlgorithmRuntimeTests.PublicationSink::class, AiDexAlgorithmRuntimeTests.RefreshSink::class,
        AiDexAlgorithmRuntimeTests.DecryptedFrames::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AiDexAlgorithmRuntimeTests {
    class TestApplication : Applic() { override fun onCreate() = Unit }

    @Implements(AiDexKeyExchange::class)
    class DecryptedFrames {
        companion object {
            var complete = false
            val encryptedOpcodes = ArrayList<Int>()
        }
        @Implementation fun decrypt(data: ByteArray): ByteArray = data.copyOf()
        @Implementation fun isComplete(): Boolean = complete
        @Implementation fun encrypt(data: ByteArray): ByteArray? {
            encryptedOpcodes += data[0].toInt() and 0xFF
            return null // Observe command attempts without real keys or a GATT queue.
        }
    }

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
            var startMs = 0L
            var current: CurrentGlucoseSource.Snapshot? = null
            @JvmStatic @Implementation
            fun processExternalCurrentReading(serial: String, reading: LiveReadingLanes, rate: Float, time: Long, generation: Int) {
                processExternalCurrentReading(serial, reading, rate, time, generation, 0L)
            }
            @JvmStatic @Implementation
            fun processExternalCurrentReading(serial: String, reading: LiveReadingLanes, rate: Float, time: Long, generation: Int, start: Long) {
                this.rate = rate
                startMs = start
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
    private var roomAttempts = 0
    private var seriesTransform: ((FloatArray, LongArray) -> FloatArray)? = null
    private val sourceHistoryRequests = ArrayList<Long?>()
    private var beforeRoomWrite: () -> Unit = {}
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
        DecryptedFrames.complete = false
        DecryptedFrames.encryptedOpcodes.clear()
        Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE).edit().clear().commit()
        Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        AiDexSourceJournalBackup.file(Applic.app.filesDir, serial).parentFile!!.deleteRecursively()
        NativeInputs.start = System.currentTimeMillis() - 180_000L
        SensorBluetooth.gattcallbacks.clear()
        ManagedCurrentSensor.clear()
        Applic.app.getSharedPreferences("calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { CalibrationDatabase.getInstance(Applic.app).calibrationDao().deleteAll() }
        CalibrationManager.init(Applic.app)
        runBlocking { CalibrationManager.loadCalibrations() }
        clearIntegratedFitCaches()
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
                    roomAttempts++
                    beforeRoomWrite()
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
                seriesTransform?.invoke(values, timestamps) ?: FloatArray(values.size) { values[it] + 40f / if (Applic.unit == 1) 18.0182f else 1f }
            override fun notifyExternalCalibrationPipelineChanged() {
                completionNotifications++
                runtime.calibrationChanged(++revision)
            }
        })
        installRuntime()
        PublicationSink.rate = Float.NaN
        PublicationSink.current = null
    }

    private fun installRuntime() {
        executor = QueuedExecutor()
        val store = AiDexBleManager::class.java.getDeclaredMethod("storeAlgorithmLive", AiDexAlgorithmReading::class.java,
            AiDexAlgorithmReading::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val publish = AiDexBleManager::class.java.getDeclaredMethod("publishAlgorithmReading", AiDexAlgorithmReading::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        runtime = AiDexAlgorithmRuntime(Applic.app, serial, { 0L }, { NativeInputs.start }, { false },
            storeLive = { row, previous -> store.invoke(driver, row, previous, Natives.AIDEX_TREND_UNKNOWN) as AiDexAlgorithmReading },
            publish = { row, live -> publish.invoke(driver, row, live); Unit }, worker = executor,
            requestSourceHistory = { sourceHistoryRequests += it })
        AiDexBleManager::class.java.getDeclaredField("algorithmRuntime\$delegate").apply { isAccessible = true }
            .set(driver, lazyOf(runtime))
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

    @Test fun stockReleaseGapIsRefetchedBeforePendingStockRestoration() {
        runtime.record(listOf(sample(1), sample(2)))
        choose(9)
        val missed = sample(3, 105f).copy(origin = AiDexSourceSample.HISTORY)
        runtime.close()
        room[missed.timestampMs] = 110f // Stock release wrote Auto without our journal.
        installRuntime()
        executor.runNext()
        assertEquals(listOf(missed.timestampMs), sourceHistoryRequests)
        assertNull(runtime.controller.journal.atMinute(missed.minute))
        assertEquals(1, roomWrites)
        assertTrue(runtime.isRebuilding())
        assertFalse(runtime.failed)

        // Live/UI triggers neither recompute/commit history nor repeat downloads.
        val incoming = sample(4, 115f)
        assertTrue(runtime.live(incoming))
        assertEquals(155f, room[incoming.timestampMs]!!, 0f)
        executor.runNext()
        choose(0)
        assertEquals(1, sourceHistoryRequests.size)
        assertEquals(2, roomWrites)
        assertTrue(driver.integratesUserCalibration(false))

        runtime.record(listOf(missed)) // Authentic 0x23/0x24 recovery.
        runtime.historyComplete()
        executor.runNext()
        assertEquals(listOf(missed.timestampMs, null), sourceHistoryRequests)
        assertEquals(105f, room[missed.timestampMs]!!, 0f)
        assertEquals(115f, room[incoming.timestampMs]!!, 0f)
        assertEquals(AiDexAlgorithmSelection.STOCK, runtime.controller.appliedSelection)
        assertEquals(incoming.timestampMs, runtime.latestPublished!!.source.timestampMs)
        assertFalse(driver.integratesUserCalibration(false))
        assertFalse(runtime.isRebuilding())
        assertFalse(runtime.failed)
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun incompleteRecoveryStopsRetryingAndRestartRequestsOriginalsAgain() {
        runtime.record(listOf(sample(1)))
        choose(9)
        val missed = sample(2, 110f)
        runtime.close()
        room[missed.timestampMs] = 110f
        installRuntime()
        executor.runNext()
        runtime.historyComplete() // Timeout/expired raw page did not fill the hole.
        executor.runNext()
        assertTrue(runtime.failed)
        assertFalse(runtime.isRebuilding())
        repeat(3) { runtime.requestReplay(); executor.runNext() }
        assertEquals(listOf(missed.timestampMs), sourceHistoryRequests)
        assertEquals(1, roomWrites)
        assertTrue(executor.pending.isEmpty())

        runtime.close()
        installRuntime()
        executor.runNext()
        assertEquals(listOf(missed.timestampMs, missed.timestampMs), sourceHistoryRequests)
        runtime.sourceHistoryUnavailable(missed.timestampMs)
        assertTrue(runtime.failed)
        assertFalse(runtime.isRebuilding())
        runtime.record(listOf(missed.copy(origin = AiDexSourceSample.HISTORY)))
        runtime.historyComplete()
        executor.runNext()
        assertFalse(runtime.failed)
        assertEquals(150f, room[missed.timestampMs]!!, 0f)
        assertTrue(driver.integratesUserCalibration(false))
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun emptyJournalAfterRestoreAlsoRequestsAuthenticFactoryHistory() {
        runtime.record(listOf(sample(1)))
        choose(9)
        val original = runtime.controller.journal.first()!!
        runtime.close()
        AiDexSourceJournalBackup.file(Applic.app.filesDir, serial).delete()
        installRuntime()
        executor.runNext()
        assertEquals(listOf(original.timestampMs), sourceHistoryRequests)
        assertTrue(runtime.controller.journal.snapshot().isEmpty())
        assertEquals(140f, room[original.timestampMs]!!, 0f)
        assertEquals(1, roomWrites)
    }

    @Test fun managerRepairSurvivesInFlightRangeAndRewindsBothDownloadCursors() {
        fun field(name: String) = AiDexBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        val request = AiDexBleManager::class.java.getDeclaredMethod("requestOriginalHistoryRecovery", Long::class.javaObjectType)
            .apply { isAccessible = true }
        val range = AiDexBleManager::class.java.getDeclaredMethod("handleHistoryRangeResponse", ByteArray::class.java)
            .apply { isAccessible = true }
        val complete = AiDexBleManager::class.java.getDeclaredMethod("onHistoryDownloadComplete").apply { isAccessible = true }
        val handler = field("handler").get(driver) as Handler
        field("hasAuthoritativeSessionStart").setBoolean(driver, true)
        field("historyRawNextIndex").setInt(driver, 2)
        field("historyBriefNextIndex").setInt(driver, 2)
        field("phase").set(driver, AiDexBleManager.Phase.STREAMING)
        DecryptedFrames.complete = true
        val bytes = byteArrayOf(AiDexOpcodes.GET_HISTORY_RANGE.toByte(), 0, 1, 0, 1, 0, 2, 0)
        range.invoke(driver, bytes) // Ordinary download was planned before recovery.
        val missingTime = sample(1).timestampMs
        request.invoke(driver, missingTime)
        Shadows.shadowOf(handler.looper).idle()
        assertEquals(missingTime, field("sourceHistoryRepairTime").get(driver))
        assertNull(field("activeHistoryRepairTime").get(driver))
        assertEquals(0, DecryptedFrames.encryptedOpcodes.count { it == AiDexOpcodes.GET_HISTORY_RANGE })
        complete.invoke(driver)
        Shadows.shadowOf(handler.looper).idle()
        assertEquals(1, DecryptedFrames.encryptedOpcodes.count { it == AiDexOpcodes.GET_HISTORY_RANGE })
        range.invoke(driver, bytes) // Next range/reconnect incorporates the retained repair.
        assertEquals(1, field("historyRawNextIndex").getInt(driver))
        assertEquals(1, field("historyBriefNextIndex").getInt(driver))
        assertEquals(missingTime, field("activeHistoryRepairTime").get(driver))
        request.invoke(driver, null)
        Shadows.shadowOf(handler.looper).idle()
        assertNull(field("sourceHistoryRepairTime").get(driver))
        runtime.close()
        request.invoke(driver, missingTime)
        Shadows.shadowOf(handler.looper).idle()
        assertNull(field("sourceHistoryRepairTime").get(driver))
    }

    @Test fun pendingRepairDoesNotRetryQuarantinedPreResetHistory() {
        fun field(name: String) = AiDexBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("sourceHistoryRepairTime").set(driver, sample(1).timestampMs)
        field("postResetRequestedAtMs").setLong(driver, System.currentTimeMillis())
        field("phase").set(driver, AiDexBleManager.Phase.STREAMING)
        DecryptedFrames.complete = true
        AiDexBleManager::class.java.getDeclaredMethod("handleHistoryRangeResponse", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(driver,
                byteArrayOf(AiDexOpcodes.GET_HISTORY_RANGE.toByte(), 0, 1, 0, 1, 0, 40, 0))
        val handler = field("handler").get(driver) as Handler
        Shadows.shadowOf(handler.looper).idle()
        assertEquals(0, DecryptedFrames.encryptedOpcodes.count { it == AiDexOpcodes.GET_HISTORY_RANGE })
        assertEquals(41, field("historyRawNextIndex").getInt(driver))
    }

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

    @Test fun genericNativeSyncIsDisabledForLiveAndRetainedAiDexInEveryMode() {
        assertFalse(driver.shouldUseNativeHistorySync())
        assertFalse(SensorIdentity.shouldUseNativeHistorySync(serial))
        runtime.record(listOf(sample(1)))
        choose(9)
        assertFalse(SensorIdentity.shouldUseNativeHistorySync(serial))
        choose(0)
        assertFalse(SensorIdentity.shouldUseNativeHistorySync(serial))
        removeKeepingHistory()
        assertFalse(SensorIdentity.shouldUseNativeHistorySync(serial))
        assertFalse(SensorIdentity.shouldUseNativeHistorySync(serial.removePrefix("X-")))
        assertEquals(serial, SensorIdentity.resolveRoomStorageSensorId(serial.removePrefix("X-")))
    }

    @Test fun irrecoverableOriginalMinuteNeverStopsNewFactoryReadingsOrPublication() {
        runtime.record(listOf(sample(1)))
        choose(9)
        val lost = sample(2)
        room[lost.timestampMs] = 100f
        runtime.close()
        installRuntime()
        executor.runNext()
        runtime.sourceHistoryUnavailable(lost.timestampMs)
        choose(0) // Historical ownership stays calibrated while Stock is pending.
        assertTrue(runtime.failed)
        for (index in 3..5) {
            val source = sample(index, 110f + index)
            assertTrue(runtime.live(source))
            executor.runNext()
            assertEquals(source.timestampMs, runtime.latestPublished!!.source.timestampMs)
            assertEquals(source.factoryMgDl + 40f, room[source.timestampMs]!!, 0f)
            assertNull(runtime.latestPublished!!.uncertainty)
            assertTrue(driver.integratesUserCalibration(false))
        }
        assertTrue(executor.pending.isEmpty())
        assertNull(runtime.controller.journal.atMinute(lost.minute))
    }

    @Test fun stockRestartOwnsMissingFactoryHistoryBeforeFirstAdaptiveSelection() {
        runtime.record(listOf(sample(1)))
        Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("algorithmSourceBootstrapped_$serial", true).commit()
        val collectedByStockRelease = sample(2, 110f)
        room[collectedByStockRelease.timestampMs] = 110f
        runtime.close()
        installRuntime()
        executor.runNext()
        assertEquals(110f, runtime.controller.journal.atMinute(collectedByStockRelease.minute)!!.factoryMgDl, 0f)
        assertEquals(100f, room[sample(1).timestampMs]!!, 0f)
        choose(9)
        assertEquals(150f, room[collectedByStockRelease.timestampMs]!!, 0f)
    }

    private fun stockSettingsPreferences() = JSONObject().put("AiDexNativePrefs", JSONObject().put("values",
        JSONObject().put("algorithmMode_$serial", JSONObject().put("type", "int").put("value", 0))
            .put("algorithmAppliedMode_$serial", JSONObject().put("type", "int").put("value", 0))
            .put("algorithmEverAdaptive_$serial", JSONObject().put("type", "boolean").put("value", false))))

    @Test fun stockSettingsImportPreservesRetainedInternallyCalibratedHistoryOwnership() {
        saveRealAutoReference()
        runtime.record(listOf(sample(1)))
        choose(9)
        val last = room.entries.last()
        removeKeepingHistory()
        SettingsExporter.importSharedPreferences(Applic.app, stockSettingsPreferences())
        val prefs = Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
        assertEquals(0, prefs.getInt("algorithmMode_$serial", -1))
        assertEquals(9, prefs.getInt("algorithmAppliedMode_$serial", -1))
        assertTrue(prefs.getBoolean("algorithmEverAdaptive_$serial", false))
        assertTrue(ManagedSensorRuntime.integratesUserCalibration(serial, false))
        assertEquals(140f, CalibrationManager.getCalibratedValue(last.value, last.key, false, false, serial), 0f)
        assertEquals(140f, room[last.key]!!, 0f)
    }

    @Test fun historyRestoringImportCanRestoreMatchingOwnershipFromBackup() {
        runtime.record(listOf(sample(1)))
        choose(9)
        runtime.close()
        SettingsExporter.importSharedPreferences(Applic.app, stockSettingsPreferences(), preserveAiDexHistoryState = false)
        assertEquals(0, Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
            .getInt("algorithmAppliedMode_$serial", -1))
    }

    private fun packageSettings(preferences: JSONObject) = JSONObject()
        .put("schema", SettingsExporter.SCHEMA).put("schemaVersion", 1)
        .put("sharedPreferences", preferences).put("nativeSettingsFiles", JSONObject())

    private fun importPackage(settings: JSONObject, readings: JSONArray?) = runBlocking {
        val payload = JSONObject().put("schema", ExportPackageExporter.SCHEMA).put("schemaVersion", 1)
            .put("settings", settings)
        if (readings != null) payload.put("history", JSONObject().put("readings", readings)
            .put("journalEntries", JSONArray()).put("journalInsulinPresets", JSONArray()).put("journalFoods", JSONArray()))
        val file = java.io.File(Applic.app.cacheDir, "aidex-restore.json").apply { writeText(payload.toString()) }
        try { ExportPackageExporter.importFromJson(Applic.app, Uri.fromFile(file)).getOrThrow() }
        finally { file.delete() }
    }

    @Test fun streamedFullBackupRestoresAppliedOwnershipOnACleanDevice() {
        val time = sample(1).timestampMs
        runtime.close()
        SensorBluetooth.gattcallbacks.clear()
        val preferences = stockSettingsPreferences()
        val values = preferences.getJSONObject("AiDexNativePrefs").getJSONObject("values")
        values.getJSONObject("algorithmMode_$serial").put("value", 9)
        values.getJSONObject("algorithmAppliedMode_$serial").put("value", 9)
        values.getJSONObject("algorithmEverAdaptive_$serial").put("value", true)
        val readings = JSONArray().put(JSONObject().put("timestamp", time).put("sensorSerial", serial)
            .put("valueMgDl", 140f).put("rawValueMgDl", 100f))
        val summary = importPackage(packageSettings(preferences), readings)
        assertEquals(1, summary.historyReadings)
        assertEquals(9, Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
            .getInt("algorithmAppliedMode_$serial", -1))
        val restored = runBlocking { HistoryDatabase.getInstance(Applic.app).historyDao()
            .getSensorReadingsAtTimestamps(serial, listOf(time)).single() }
        saveRealAutoReference()
        assertTrue(ManagedSensorRuntime.integratesUserCalibration(serial, false))
        assertEquals(140f, restored.value, 0f)
        assertEquals(restored.value, CalibrationManager.getCalibratedValue(restored.value, time, false, false, serial), 0f)
    }

    @Test fun packageWithNoGlucoseReadingsPreservesLocalAppliedOwnership() {
        runtime.record(listOf(sample(1)))
        choose(9)
        removeKeepingHistory()
        for (readings in listOf(null, JSONArray())) {
            val summary = importPackage(packageSettings(stockSettingsPreferences()), readings)
            assertEquals(0, summary.historyReadings)
            val prefs = Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
            assertEquals(0, prefs.getInt("algorithmMode_$serial", -1))
            assertEquals(9, prefs.getInt("algorithmAppliedMode_$serial", -1))
            assertTrue(prefs.getBoolean("algorithmEverAdaptive_$serial", false))
        }
    }

    private fun clearIntegratedFitCaches() {
        for (name in listOf("integratedBaselineCache", "integratedContextCache")) {
            val cache = CalibrationManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as MutableMap<*, *>
            synchronized(cache) { cache.clear() }
        }
    }

    @Test fun blockedRestartSeedsAvailableFactoryAnchorsForRealIntegratedFallbackCalibration() {
        val anchor = sample(0)
        runtime.record(listOf(anchor))
        choose(9)
        val lost = sample(5)
        room[lost.timestampMs] = 100f
        runtime.close()
        runBlocking { CalibrationDatabase.getInstance(Applic.app).calibrationDao().insert(
            CalibrationEntity(timestamp = anchor.timestampMs, sensorId = serial,
                sensorValue = 200f, sensorValueRaw = 100f, userValue = 140f, sensorValueStock = 0f)) }
        CalibrationAccess.register(MobileCalibrationProvider)
        runBlocking { CalibrationManager.loadCalibrations() }
        assertTrue(CalibrationManager.hasCalibrationPointsForMode(false, serial))
        val fresh = sample(20, 110f)
        clearIntegratedFitCaches()
        CalibrationManager.seedIntegratedCalibrationBaseline(floatArrayOf(100f), longArrayOf(anchor.timestampMs), false, serial)
        val expected = CalibrationManager.getIntegratedCalibratedSeries(floatArrayOf(110f), longArrayOf(fresh.timestampMs), false, serial).single()
        clearIntegratedFitCaches()
        val wrong = CalibrationManager.getIntegratedCalibratedSeries(floatArrayOf(110f), longArrayOf(fresh.timestampMs), false, serial).single()
        assertTrue("Unknown Stock must materially change this fit", kotlin.math.abs(expected - wrong) > 1f)
        clearIntegratedFitCaches() // Simulate process recreation with only the journal available.
        installRuntime()
        executor.runNext()
        runtime.sourceHistoryUnavailable(lost.timestampMs)
        choose(0) // Retained calibrated ownership stays active while Stock is pending.
        assertTrue(runtime.failed)
        assertTrue(runtime.live(fresh))
        assertEquals(expected, room[fresh.timestampMs]!!, 0.001f)
        assertEquals(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED, runtime.controller.appliedSelection)
        assertNull(runtime.controller.journal.atMinute(lost.minute))
        assertNull(runtime.latestPublished!!.uncertainty)
        executor.runNext()
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun failedStockLiveRoomWriteIsRepairedFromItsJournalWithoutGenericSync() {
        beforeRoomWrite = { if (roomAttempts == 1) error("busy database") }
        val source = sample(1)
        assertTrue(runtime.live(source))
        assertFalse(room.containsKey(source.timestampMs))
        assertNotNull(runtime.controller.journal.atMinute(source.minute))
        assertEquals(source.timestampMs, runtime.latestPublished!!.source.timestampMs)
        assertEquals(1, executor.pending.size)
        executor.runNext()
        assertEquals(100f, room[source.timestampMs]!!, 0f)
        assertEquals(2, roomAttempts)
        assertEquals(AiDexAlgorithmSelection.STOCK, runtime.controller.appliedSelection)
        assertFalse(driver.shouldUseNativeHistorySync())
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun persistentRoomFailureRetriesThreeTimesThenWaitsForAnotherLiveEvent() {
        beforeRoomWrite = { error("database unavailable") }
        val first = sample(1)
        assertTrue(runtime.live(first))
        executor.runNext()
        assertEquals(4, roomAttempts) // One live attempt, at most three owned replays.
        assertTrue(runtime.failed)
        assertTrue(executor.pending.isEmpty())
        assertEquals(first.timestampMs, runtime.latestPublished!!.source.timestampMs)
        beforeRoomWrite = {}
        val second = sample(2, 110f)
        assertTrue(runtime.live(second))
        executor.runNext()
        assertEquals(100f, room[first.timestampMs]!!, 0f)
        assertEquals(110f, room[second.timestampMs]!!, 0f)
        assertFalse(runtime.failed)
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun queuedRoomRepairUsesALaterStockChoiceInsteadOfAnOldAdaptiveValue() {
        runtime.record(listOf(sample(0)))
        choose(9)
        beforeRoomWrite = { if (roomAttempts == 2) error("live write failed") }
        val live = sample(1)
        assertTrue(runtime.live(live))
        assertEquals(140f, runtime.latestPublished!!.glucoseMgDl, 0f)
        choose(0) // The queued repair observes the current selection under the same lock.
        assertEquals(100f, room[live.timestampMs]!!, 0f)
        assertEquals(AiDexAlgorithmSelection.STOCK, runtime.controller.appliedSelection)
        assertFalse(driver.integratesUserCalibration(false))
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun failedDuplicateLiveWriteAfterReplayCommitDoesNotStartARepairLoop() {
        runtime.record(listOf(sample(0, raw = 100f)))
        choose(8)
        val original = sample(1, raw = 100f)
        assertTrue(runtime.live(original))
        beforeRoomWrite = { if (roomAttempts == 4) error("duplicate live write failed") }
        assertFalse(runtime.live(original.copy(rawMgDl = 110f)))
        executor.runNext()
        assertEquals(4, roomAttempts) // Initial, live, replay batch, redundant deferred live.
        assertEquals(runtime.latestPublished!!.glucoseMgDl, room[original.timestampMs]!!, 0f)
        assertFalse(runtime.failed)
        assertFalse(runtime.controller.needsReplay())
        assertTrue(executor.pending.isEmpty())
    }

    @Test fun calibratedReplayFallbackFitUsesFactoryMinutesWithUsableRawToo() {
        val anchor = sample(1, 100f, 100f)
        val estimated = AiDexAlgorithmContext(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED).process(anchor, emptyList())
        assertNotNull(estimated.uncertainty)
        val fallbacks = listOf(sample(10, 120f), sample(11, 130f)).map { AiDexAlgorithmReading(it, it.factoryMgDl) }
        // A legacy reference stored Adaptive=200, unknown Stock, user=140.
        // Its usable-raw factory minute must be available to rebase the fit.
        seriesTransform = { values, times ->
            val position = times.indexOf(anchor.timestampMs)
            val stock = if (position >= 0) values[position] else 200f
            FloatArray(values.size) { values[it] * 140f / stock }
        }
        val method = AiDexAlgorithmRuntime::class.java.getDeclaredMethod("calibrateFallbacks", List::class.java,
            AiDexAlgorithmSelection::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(runtime, listOf(estimated) + fallbacks,
            AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED) as List<AiDexAlgorithmReading>
        assertEquals(estimated.glucoseMgDl, rows[0].glucoseMgDl, 0f)
        assertEquals(168f, rows[1].glucoseMgDl, 0f)
        assertEquals(182f, rows[2].glucoseMgDl, 0f)
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
            assertEquals(NativeInputs.start, PublicationSink.startMs)
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

    private fun removeKeepingHistory() {
        driver.terminateManagedSensor(false)
        driver.removeManagedPersistence(Applic.app)
        SensorBluetooth.gattcallbacks.remove(driver)
        SensorIdentity.invalidateCaches()
    }

    private fun saveRealAutoReference() = runBlocking {
        CalibrationDatabase.getInstance(Applic.app).calibrationDao().insert(
            CalibrationEntity(timestamp = NativeInputs.start, sensorId = serial,
                sensorValue = 100f, sensorValueRaw = 100f, userValue = 140f))
        CalibrationManager.loadCalibrations()
    }

    @Test fun removedCalibratedSensorKeepsAutoOwnershipForSingleAndSeriesCalibration() {
        saveRealAutoReference()
        runtime.record(listOf(sample(0), sample(1)))
        choose(9)
        val last = room.entries.last()
        assertEquals(140f, last.value, 0f)
        runtime.resetRequested()
        choose(0) // Requested Stock is still pending; retained values remain owned by mode 9.
        assertEquals(AiDexAlgorithmSelection.ADAPTIVE_V2_CALIBRATED, runtime.controller.appliedSelection)
        removeKeepingHistory()
        for (alias in listOf(serial, serial.removePrefix("X-"), serial.lowercase())) {
            assertTrue(ManagedSensorRuntime.integratesUserCalibration(alias, false))
            assertFalse(ManagedSensorRuntime.integratesUserCalibration(alias, true))
            assertEquals(last.value, CalibrationManager.getCalibratedValue(last.value, last.key, false, false, alias), 0f)
            assertNull(CalibrationManager.seriesCalibrator(false, alias))
        }
        assertEquals(last.value, room[last.key]!!, 0f)
    }

    @Test fun restoredStockHistoryRemainsEligibleForOneGenericCorrectionAfterRemoval() {
        saveRealAutoReference()
        runtime.record(listOf(sample(0), sample(1)))
        choose(9)
        choose(0)
        val last = room.entries.last()
        removeKeepingHistory()
        assertFalse(ManagedSensorRuntime.integratesUserCalibration(serial, false))
        assertEquals(100f, last.value, 0f)
        assertEquals(140f, CalibrationManager.getCalibratedValue(last.value, last.key, false, false, serial), 0.001f)
        assertNotNull(CalibrationManager.seriesCalibrator(false, serial))
    }

    private fun currentGatt() = ShadowBluetoothGatt.newInstance(
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55")).also {
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }.set(driver, it)
    }

    private fun dataFrame() = ByteArray(17).also { data ->
        data[0] = 1
        data[4] = 2 // Native start + two minutes
        data[6] = 100 // Factory mg/dL
        data[8] = 0xE8.toByte(); data[9] = 3 // i1 = 10, raw proxy = 100 mg/dL
        val crc = Crc16CcittFalse.checksum(data.copyOfRange(0, 15))
        data[15] = crc.toByte(); data[16] = (crc shr 8).toByte()
    }

    private fun drainCallbackPosts() {
        val handler = AiDexBleManager::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(driver) as Handler
        Shadows.shadowOf(handler.looper).idle()
    }

    @Test fun gattCallbackReturnsWhileRoomIsBusyAndOwnedWorkerProcessesItsCopiedFrameLater() {
        val enteredRoom = CountDownLatch(1)
        val releaseRoom = CountDownLatch(1)
        beforeRoomWrite = { enteredRoom.countDown(); check(releaseRoom.await(5, TimeUnit.SECONDS)) }
        runtime.enqueue { runtime.live(sample(0)) }
        val writer = Thread { executor.runNext() }.apply { start() }
        val callbacks = Executors.newSingleThreadExecutor()
        val frame = dataFrame()
        val characteristic = BluetoothGattCharacteristic(AiDexBleManager.CHAR_F003, 0, 0).apply { value = frame }
        val gatt = currentGatt()
        try {
            assertTrue(enteredRoom.await(2, TimeUnit.SECONDS))
            callbacks.submit { driver.onCharacteristicChanged(gatt, characteristic) }.get(1, TimeUnit.SECONDS)
            // Reuse/mutation of Android's buffer must not alter the queued frame.
            frame[6] = 200.toByte()
            drainCallbackPosts()
            assertEquals(1, executor.pending.size)
            assertEquals(0, roomWrites)
        } finally {
            releaseRoom.countDown()
            writer.join(3000)
            callbacks.shutdownNow()
        }
        executor.runNext()
        val expectedTime = NativeInputs.start + 120_000L
        assertEquals(100f, room[expectedTime]!!, 0f)
        assertEquals(expectedTime, requireNotNull(PublicationSink.current).timeMillis)
    }

    @Test fun queuedGattNotificationRechecksTheTransportBeforeProcessing() {
        val gatt = currentGatt()
        val characteristic = BluetoothGattCharacteristic(AiDexBleManager.CHAR_F003, 0, 0).apply { value = dataFrame() }
        driver.onCharacteristicChanged(gatt, characteristic)
        drainCallbackPosts()
        assertEquals(1, executor.pending.size)
        currentGatt() // Replace the transport after enqueueing, before execution.
        executor.runNext()
        assertEquals(0, roomWrites)
        assertTrue(runtime.controller.journal.snapshot().isEmpty())
        assertNull(PublicationSink.current)
    }

    @Test fun dequeuedWorkCannotWriteAfterRemovalClosesOwnership() {
        runtime.enqueue { runtime.live(sample(0)) }
        val held = executor.pending.removeFirst()
        runtime.close()
        held.run()
        assertEquals(0, roomWrites)
        assertTrue(runtime.controller.journal.snapshot().isEmpty())
        assertFalse(runtime.enqueue { error("must not execute") })
    }
}
