package tk.glucodata.drivers.libre3

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.bluetooth.BluetoothGatt
import android.os.Handler
import java.time.Duration
import org.robolectric.shadow.api.Shadow
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowSystemClock
import tk.glucodata.*
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

/** Exercises the actual persistence, manager and bridge with software stand-ins for JNI and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Libre3ReviewApplication::class, shadows = [Libre3ReviewNatives::class, Libre3PublicationShadow::class])
class Libre3ReviewRegressionTest {
    private lateinit var context: Context
    private lateinit var manager: Libre3BleManager
    private var oldBridge: Any? = null
    private var oldHealth: Any? = null
    private val healthWrites = mutableListOf<Pair<Long, String>>()
    private var timestamps: LongArray? = longArrayOf()
    private var storeSucceeds = true
    private val roomWrites = mutableListOf<Long>()
    private val sensor = "0D1234ABC"
    private val now = 1_793_000_000_000L
    private fun field(name: String) = Libre3BleManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(manager)
    private fun set(name: String, value: Any?) = field(name).set(manager, value)
    private fun invoke(name: String, vararg args: Any?): Any? = Libre3BleManager::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(manager, *args)
    private fun kind(name: String): Any = Class.forName("tk.glucodata.drivers.libre3.Libre3BleManager\$ControlKind")
        .enumConstants.single { it.toString() == name }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as Applic
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(Libre3Persistence.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        oldHealth = HealthConnectAccess.get()
        HealthConnectAccess.register(object : HealthConnect {
            override fun start(activity: MainActivity) {}
            override fun stop() {}
            override fun writeAll(sensorPtr: Long, sensorName: String) { healthWrites += sensorPtr to sensorName }
        })
        Libre3ReviewNatives.reset()
        manager = Libre3BleManager(sensor)
        // Existing native shell in this fixture. Window geometry is tested separately.
        set("nativeWindowChecked", true)
        oldBridge = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.get(HistoryRepositoryAccess)
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(
            HistoryRepositoryBridge::class.java.classLoader, arrayOf(HistoryRepositoryBridge::class.java),
        ) { _, method, args -> when (method.name) {
            "getHistoryTimestampsForSensorBlocking" -> timestamps
            "storeHistoryBatchWithSourceBlocking" -> storeSucceeds.also { ok ->
                if (ok) roomWrites.addAll((args!![1] as LongArray).toList())
            }
            "getHistoryForNotificationForSensor" -> emptyList<GlucosePoint>()
            else -> null
        } } as HistoryRepositoryBridge)
    }

    @After fun cleanup() {
        manager.setPause(true)
        manager.close()
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HistoryRepositoryAccess, oldBridge)
        HealthConnectAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HealthConnectAccess, oldHealth)
        Applic.app = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun minutes() = get("minuteBackfill") as MutableList<VirtualGlucoseSensorBridge.Reading>
    @Suppress("UNCHECKED_CAST")
    private fun history() = get("historicBackfill") as MutableList<VirtualGlucoseSensorBridge.Reading>
    private fun reading(time: Long = System.currentTimeMillis() - 60_000L) = VirtualGlucoseSensorBridge.Reading(time, 123f)

    @Test fun failingLinkStepDropsTheLinkInsteadOfCrashingTheApp() {
        val handler = get("liveHandler") as Handler
        var ranAfter = false
        // Uncaught on the driver thread, this would end the process and repeat on every start.
        handler.dispatchMessage(android.os.Message.obtain(handler) { error("synthetic link failure") })
        handler.dispatchMessage(android.os.Message.obtain(handler) { ranAfter = true })
        assertTrue(ranAfter)
    }

    @Test fun clinicalReplayUsesStockNativeSlotsEvenWhenRoomAlreadyHasTheMinute() {
        val r = reading()
        timestamps = longArrayOf(r.timestampMs)
        minutes().add(r)
        invoke("flushBackfill")
        assertEquals(1, Libre3ReviewNatives.stockWrites)
        assertEquals(0, Libre3ReviewNatives.resolvedWrites)
        assertTrue(minutes().isEmpty())
    }

    @Test fun failedRoomStoreRetainsBatchAndDoesNotPersistProgress() {
        minutes().add(reading())
        set("lastLifeCount", 101)
        storeSucceeds = false
        invoke("flushBackfill")
        assertEquals(0, Libre3Persistence.getLastLifeCount(context, sensor))
        assertEquals(1, minutes().size)
        storeSucceeds = true
        invoke("flushBackfill")
        assertEquals(101, Libre3Persistence.getLastLifeCount(context, sensor))
        assertTrue(minutes().isEmpty())
    }

    @Test fun lateLiveFrameAndNextRecoveredMinuteUseTheSameGrid() {
        val lifeCount = 100
        // The live frame is 20 seconds late. The recovered minute is only 40
        // seconds after receipt, but a full minute after the sensor's live sample.
        val liveAt = System.currentTimeMillis() - 20_000L
        val anchor = liveAt - lifeCount * 60_000L
        set("anchorStartMs", anchor)
        set("lastLifeCount", lifeCount - 1)
        invoke("onRealtime", Libre3RealtimeReading(lifeCount, 123, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        invoke("flushBackfill")
        assertEquals(listOf(liveAt), roomWrites)
        assertEquals(liveAt, manager.getManagedCurrentSnapshot(0)!!.timeMillis)
        timestamps = roomWrites.toLongArray()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        invoke("onClinical", Libre3ClinicalRecord(lifeCount + 1, 124, 0))
        invoke("flushBackfill")
        assertEquals(listOf(liveAt, liveAt + 60_000L), roomWrites)
        assertEquals(lifeCount + 1, Libre3Persistence.getLastLifeCount(context, sensor))
    }

    @Test fun aPreviewReceiptTimestampDoesNotHideTheAdjacentRecoveredMinute() {
        val nextMinute = System.currentTimeMillis() - 30_000L
        set("anchorStartMs", nextMinute - 101 * 60_000L)
        timestamps = longArrayOf(nextMinute - 40_000L)
        invoke("onClinical", Libre3ClinicalRecord(101, 124, 0))
        invoke("flushBackfill")
        assertEquals(listOf(nextMinute), roomWrites)
    }

    @Test fun takeoverWithoutActivationTimeLearnsItsGridOnlyOnce() {
        Libre3Persistence.saveSensor(context, pairing().copy(startTimeMs = 0))
        set("record", Libre3Persistence.loadSensor(context, sensor))
        invoke("onClinical", Libre3ClinicalRecord(101, 124, 0))
        assertTrue(minutes().isEmpty())
        invoke("onRealtime", Libre3RealtimeReading(100, 123, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val learned = Libre3Persistence.getAnchor(context, sensor)
        val first = manager.getManagedCurrentSnapshot(0)!!.timeMillis
        assertEquals(first - 100 * 60_000L, learned)
        assertEquals(learned, manager.getManagedUiSnapshot(sensor)!!.startTimeMs)
        invoke("flushBackfill")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(80))
        invoke("onRealtime", Libre3RealtimeReading(101, 124, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        invoke("flushBackfill")
        assertEquals(listOf(first, first + 60_000L), roomWrites)
        assertEquals(learned, Libre3Persistence.getAnchor(context, sensor))
    }

    private fun awaitDriverHandler() {
        val delivered = CountDownLatch(1)
        (get("liveHandler") as Handler).post { delivered.countDown() }
        assertTrue("driver did not process the posted lifecycle change", delivered.await(5, TimeUnit.SECONDS))
    }

    @Test fun pauseAndResumeKeepBackfillAndCommitProgressOnlyAfterWriting() {
        set("record", pairing())
        val minute = reading()
        val filler = reading(minute.timestampMs - 300_000L)
        minutes().add(minute)
        history().add(filler)
        set("lastLifeCount", 101)
        set("lastHistoricLifeCount", 100)
        assertTrue(manager.getManagedUiSnapshot(sensor)!!.isUiEnabled)
        manager.softDisconnect()
        awaitDriverHandler()
        assertFalse(manager.getManagedUiSnapshot(sensor)!!.isUiEnabled)
        assertEquals(listOf(minute), minutes())
        assertEquals(listOf(filler), history())
        assertTrue(roomWrites.isEmpty())
        assertEquals(0, Libre3Persistence.getLastLifeCount(context, sensor))
        assertEquals(0, Libre3Persistence.getLastHistoricLifeCount(context, sensor))

        manager.softReconnect()
        awaitDriverHandler()
        assertTrue(manager.getManagedUiSnapshot(sensor)!!.isUiEnabled)
        assertEquals(listOf(minute.timestampMs, filler.timestampMs), roomWrites)
        assertEquals(101, Libre3Persistence.getLastLifeCount(context, sensor))
        assertEquals(100, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
        assertTrue(minutes().isEmpty() && history().isEmpty())
        assertEquals(listOf(minute.timestampMs), Libre3ReviewNatives.wakes)
    }

    @Test fun watchWithoutRoomStoresBackfillInTheNativeRecordAndSettles() {
        // Wear registers no Room bridge; the native record is the watch's history.
        withoutRoom()
        val base = (System.currentTimeMillis() / 60_000L - 30L) * 60_000L
        val minute = reading(base)
        minutes().add(minute)
        history().add(VirtualGlucoseSensorBridge.Reading(base, 110f)) // same slot as the minute
        history().add(VirtualGlucoseSensorBridge.Reading(base + 10 * 60_000L, 130f)) // a hole
        set("lastLifeCount", 101)
        set("lastHistoricLifeCount", 100)
        invoke("flushBackfill")
        assertTrue(minutes().isEmpty() && history().isEmpty())
        assertEquals(101, Libre3Persistence.getLastLifeCount(context, sensor))
        assertEquals(100, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
        assertEquals(setOf(base / 1000L, base / 1000L + 600L), Libre3ReviewNatives.nativeSeconds)
        assertTrue(roomWrites.isEmpty())

        // The next replay of the same page finds both slots stored natively.
        history().add(VirtualGlucoseSensorBridge.Reading(base + 10 * 60_000L, 130f))
        val before = Libre3ReviewNatives.stockWrites
        invoke("flushBackfill")
        assertTrue(history().isEmpty())
        assertEquals(before, Libre3ReviewNatives.stockWrites)
    }

    private fun withoutRoom() =
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }.set(HistoryRepositoryAccess, null)

    @Test fun watchKeepsReadingsAndProgressUntilEveryNativeWriteIsAccepted() {
        withoutRoom()
        val base = (System.currentTimeMillis() / 60_000L - 60L) * 60_000L + 345L
        val hole = base + 20 * 60_000L
        minutes().add(reading(base))
        minutes().add(reading(base + 60_000L))
        history().add(VirtualGlucoseSensorBridge.Reading(hole, 130f))
        set("lastLifeCount", 101)
        set("lastHistoricLifeCount", 100)
        // Only the five-minute point is refused, then one of the two minutes.
        for (refused in listOf(hole / 1000L, base / 1000L + 60L)) {
            Libre3ReviewNatives.refusedSeconds = setOf(refused)
            invoke("flushBackfill")
            assertEquals(0, Libre3Persistence.getLastLifeCount(context, sensor))
            assertEquals(0, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
            assertEquals(2, minutes().size)
            assertEquals(1, history().size)
        }
        Libre3ReviewNatives.refusedSeconds = emptySet()
        invoke("flushBackfill")
        assertTrue(minutes().isEmpty() && history().isEmpty())
        assertEquals(setOf(base / 1000L, base / 1000L + 60L, hole / 1000L), Libre3ReviewNatives.nativeSeconds)
        assertEquals(101, Libre3Persistence.getLastLifeCount(context, sensor))
        assertEquals(100, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
    }

    @Test fun watchSettlesAReadingItsNativeWindowCanNeverHold() {
        withoutRoom()
        // The fixture's window opens one day ago; a retry could not store this either.
        minutes().add(reading(System.currentTimeMillis() - 2 * 86_400_000L))
        set("lastLifeCount", 101)
        invoke("flushBackfill")
        assertTrue(minutes().isEmpty())
        assertEquals(101, Libre3Persistence.getLastLifeCount(context, sensor))
    }

    @Test fun watchReplacesAFillInOnlyOnceItsMinuteIsStored() {
        withoutRoom()
        val at = (System.currentTimeMillis() / 60_000L - 60L) * 60_000L + 345L
        history().add(VirtualGlucoseSensorBridge.Reading(at, 130f))
        invoke("flushBackfill")
        @Suppress("UNCHECKED_CAST") val fillers = get("historyFillers") as Set<Long>
        assertEquals(setOf(at), fillers)
        minutes().add(reading(at))
        Libre3ReviewNatives.refusedSeconds = setOf(at / 1000L)
        invoke("flushBackfill")
        assertEquals(setOf(at), fillers)
        Libre3ReviewNatives.refusedSeconds = emptySet()
        invoke("flushBackfill")
        assertTrue(fillers.isEmpty() && minutes().isEmpty())
        assertEquals(setOf(at / 1000L), Libre3ReviewNatives.nativeSeconds)
    }

    @Test fun failingStorageRetriesWithAGrowingDelay() {
        withoutRoom()
        val at = System.currentTimeMillis() - 60_000L
        Libre3ReviewNatives.refusedSeconds = setOf(at / 1000L)
        minutes().add(reading(at))
        val runnable = get("flushRunnable") as Runnable
        val handler = get("liveHandler") as Handler
        val delays = List(10) {
            invoke("flushBackfill")
            assertTrue(handler.hasCallbacks(runnable))
            org.robolectric.Shadows.shadowOf(handler.looper).nextScheduledTaskTime.toMillis() -
                android.os.SystemClock.uptimeMillis()
        }
        assertEquals(2_000L, delays.first())
        assertEquals(4_000L, delays[1])
        assertEquals(300_000L, delays.last())
        assertEquals(1, minutes().size)
    }

    @Test fun failedTimestampLookupDoesNotOverwriteNativeMinuteSlots() {
        history().add(reading())
        set("lastHistoricLifeCount", 100)
        timestamps = null
        invoke("flushBackfill")
        assertEquals(0, Libre3ReviewNatives.stockWrites)
        assertEquals(0, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
        assertEquals(1, history().size)
    }

    @Test fun timeoutAbandonsQueuedHistoricRequestSoLateCompletionCannotSettleIt() {
        set("lastLifeCount", 70)
        set("lastHistoricLifeCount", 50)
        set("controlInFlight", kind("CLINICAL"))
        set("requestedHistoricCommitted", 100)
        @Suppress("UNCHECKED_CAST")
        val queue = get("controlQueue") as ArrayDeque<Pair<Any, ByteArray>>
        queue.addLast(kind("HISTORIC") to Libre3ControlCommand.historicalFrom(50))
        (get("controlTimeoutRunnable") as Runnable).run()
        assertNull(get("controlInFlight"))
        assertTrue(queue.isEmpty())
        invoke("onControlResponse") // late/duplicate completion with no active request
        assertEquals(50, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
        assertEquals(70, Libre3Persistence.getLastLifeCount(context, sensor))
    }

    @Test fun postedOldDisconnectDoesNotClearTheReplacementLink() {
        val old = Shadow.newInstanceOf(BluetoothGatt::class.java)
        val replacement = Shadow.newInstanceOf(BluetoothGatt::class.java)
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }.set(manager, replacement)
        set("linkUp", true)
        set("controlInFlight", kind("HISTORIC"))
        invoke("onLinkDisconnected", old, 19)
        assertTrue(get("linkUp") as Boolean)
        assertEquals("HISTORIC", get("controlInFlight").toString())
    }

    @Test fun certificateRejectionStopsAutomaticConnectionAttempts() {
        invoke("onHandshakeFailed", Libre3Handshake.Action.Failed(Libre3Handshake.FailureKind.SENSOR_CERTIFICATE, "unknown signer"))
        assertTrue(get("deviceRejected") as Boolean)
        assertFalse(manager.connectDevice(0))
        assertEquals(Libre3BleManager.Phase.IDLE, manager.phase)
    }

    @Test fun nativeLifecycleUsesTheFullFifteenDayWearTime() {
        set("record", Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now, 60, 21600, 1, "test", 1))
        set("nativeWindowChecked", false)
        invoke("ensureNativeWindow")
        assertEquals(15, Libre3ReviewNatives.wearDays)
        assertTrue(get("nativeWindowChecked") as Boolean)
    }

    @Test fun libreViewNumbersHistoryFromTheDriversLifeCountOrigin() {
        // LibreView record numbers are sensor lifeCounts, so native needs the origin the driver uses.
        set("record", Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now, 60, 21600, 1, "test", 1))
        set("anchorStartMs", now + 30_000L)
        set("nativeWindowChecked", false)
        invoke("ensureNativeWindow")
        assertEquals((now + 30_000L) / 1000L, Libre3ReviewNatives.activationSec)

        // An NFC rescan that moves the activation hands the new origin over too.
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now - 600_000L, 60, 21600, 1, "test", 1))
        manager.onNfcRecordUpdated()
        invoke("ensureNativeWindow")
        assertEquals((now - 600_000L) / 1000L, Libre3ReviewNatives.activationSec)
    }

    @Test fun watchHandoffCarriesTheSensorItsCredentialsAndCachedKey() {
        java.security.Security.insertProviderAt(FakeAndroidKeyStore.Provider(), 1)
        val savedManaged = ManagedCurrentSensor.get()
        try {
            val identity = Libre3AppIdentity.create("app", ByteArray(30) + byteArrayOf(0x44, 0x44),
                Libre3Bytes.parseHex(Libre3GattSessionSimulationTest.CERT_HEX)!!)
            val json = Libre3AppIdentity.toJson(listOf(identity)).toByteArray()
            Libre3Persistence.installIdentities(context, "v1", Libre3IdentityProvisioning.sha256Hex(json), json, "test")
            val record = Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", byteArrayOf(1, 2, 3, 4), now, 60, 21600, 77, "1.4.2.30", 1)
            Libre3Persistence.saveSensor(context, record)
            Libre3Persistence.setBackfillMarks(context, sensor, 700, 690)
            Libre3Persistence.setAnchor(context, sensor, now + 5_000)
            val kAuth = ByteArray(16) { 7 }
            assertTrue(Libre3Persistence.saveCachedAuth(context, sensor, kAuth, "v1/app", Libre3Persistence.credentialRevision(context)))
            ManagedCurrentSensor.set(sensor)
            SensorIdentity.invalidateCaches()
            val payload = ManagedSensorHandoff.createOutgoingPayload(context, refresh = true)
            assertTrue(String(payload).contains("\"libre3\""))

            // The watch: none of the phone's preferences or Keystore keys.
            context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            FakeAndroidKeyStore.keys.clear()
            ManagedCurrentSensor.clear()
            SensorIdentity.invalidateCaches()
            assertTrue(ManagedSensorHandoff.applyIncoming(context, payload))

            assertEquals(record, Libre3Persistence.loadSensor(context, sensor))
            assertEquals(700, Libre3Persistence.getLastLifeCount(context, sensor))
            assertEquals(690, Libre3Persistence.getLastHistoricLifeCount(context, sensor))
            assertEquals(now + 5_000, Libre3Persistence.getAnchor(context, sensor))
            val cached = Libre3Persistence.loadCachedAuth(context, sensor)!!
            assertArrayEquals(kAuth, cached.first)
            assertEquals("v1/app", cached.second)
            assertEquals(listOf("v1/app"), Libre3Persistence.loadIdentitiesForSensor(context, sensor).map { it.label })
            assertEquals(sensor, ManagedCurrentSensor.get())
        } finally {
            ManagedCurrentSensor.set(savedManaged)
            java.security.Security.removeProvider("AndroidKeyStore")
            FakeAndroidKeyStore.keys.clear()
        }
    }


    @Test fun liveMirrorWakesNightscoutAndCompletesHealthExport() {
        val at = System.currentTimeMillis()
        invoke("publishCurrent", at, 123f, 0f, 101)
        assertEquals(1, Libre3ReviewNatives.stockWrites)
        assertEquals(listOf(at), Libre3ReviewNatives.wakes)
        if (!Applic.isWearable) assertEquals(listOf(1L to sensor), healthWrites)
        else assertTrue(healthWrites.isEmpty())
    }

    @Test fun oldBackfillCannotClaimHealthConnectFromTheCurrentSensor() {
        val oldBlue = SensorBluetooth.blueone
        val oldCallbacks = SensorBluetooth.gattcallbacks.toList()
        val current = Libre3BleManager("CURRENT")
        val stopField = SuperGattCallback::class.java.getDeclaredField("stopHealth").apply { isAccessible = true }
        val liveExport = SuperGattCallback::class.java.getDeclaredMethod("exportToHealthConnect").apply { isAccessible = true }
        try {
            SensorBluetooth.blueone = SensorBluetooth()
            SensorBluetooth.gattcallbacks.clear()
            SensorBluetooth.gattcallbacks.add(manager)
            SensorBluetooth.gattcallbacks.add(current)
            // Opening Sensors has reset both flags. Old history arrives first.
            stopField.setBoolean(manager, false)
            stopField.setBoolean(current, false)
            minutes().add(reading())
            invoke("flushBackfill")
            assertFalse(stopField.getBoolean(current))
            assertFalse(stopField.getBoolean(manager))
            if (!Applic.isWearable) {
                assertEquals(1, healthWrites.size)
                liveExport.invoke(current)
                assertEquals("CURRENT", healthWrites.last().second)
                // Only the current LIVE export owns the health callback now.
                assertTrue(stopField.getBoolean(manager))
                assertFalse(stopField.getBoolean(current))
            }
        } finally {
            current.setPause(true)
            current.close()
            SensorBluetooth.gattcallbacks.clear()
            SensorBluetooth.gattcallbacks.addAll(oldCallbacks)
            SensorBluetooth.blueone = oldBlue
        }
    }

    @Test fun setupTimeoutDoesNotRejectAnIdentity() {
        val hs = Libre3Handshake(Libre3Handshake.Mode.FULL, ByteArray(4), null, null)
        Libre3Handshake::class.java.getDeclaredField("stage").apply { isAccessible = true }.set(hs, Libre3Handshake.Stage.AWAIT_CERT_ACCEPTED)
        set("handshake", hs)
        set("phase", Libre3BleManager.Phase.AUTHENTICATING)
        (get("setupDeadlineRunnable") as Runnable).run()
        assertFalse(get("identityRejected") as Boolean)
        assertEquals(0, get("identityRejections"))
    }

    @Test fun mixedCaseRemovalDeletesMetadataAndSecretsTogether() {
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now, 60, 21600, 1, "test", 1))
        val prefs = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$sensor.kauth", "sealed").commit()
        Libre3Persistence.removeSensor(context, sensor.lowercase())
        assertFalse(Libre3Persistence.hasSensor(context, sensor))
        assertFalse(prefs.all.keys.any { it.startsWith("$sensor.", ignoreCase = true) })
    }

    @Test fun receiverIdIsNotReturnedWhenDurableWriteFails() {
        val original = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name != Libre3Persistence.PREFS_NAME) super.getSharedPreferences(name, mode) else object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = original.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun putLong(key: String, value: Long): SharedPreferences.Editor { delegate.putLong(key, value); return this }
                            override fun commit(): Boolean = false
                        }
                    }
                }
        }
        assertTrue(runCatching { Libre3Persistence.ownReceiverId(failing) }.isFailure)
        assertEquals(0L, original.getLong("receiver_id", 0L))
    }

    @Test fun cachedReceiverIdStillRequiresACheckedDurableWrite() {
        val original = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        original.edit().putLong("receiver_id", 123L).commit()
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name != Libre3Persistence.PREFS_NAME) super.getSharedPreferences(name, mode) else object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = original.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun putLong(key: String, value: Long): SharedPreferences.Editor { delegate.putLong(key, value); return this }
                            override fun commit(): Boolean = false
                        }
                    }
                }
        }
        assertTrue(runCatching { Libre3Persistence.ownReceiverId(failing) }.isFailure)
    }

    @Test fun nonSecretSettingsMigrateToExportablePreferences() {
        val local = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val source = Libre3IdentitySource.custom("https://example.test/keys", "a".repeat(64))!!
        local.edit().putInt("receiver_source", Libre3Persistence.RECEIVER_OWN)
            .putString("identity_custom_source", source.toSpec()).putLong("receiver_id", 123L).commit()
        assertEquals(Libre3Persistence.RECEIVER_OWN, Libre3Persistence.getReceiverSource(context))
        assertEquals(source, Libre3Persistence.effectiveIdentitySource(context))
        val exportable = context.getSharedPreferences(Libre3Persistence.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(setOf("receiver_source", "identity_custom_source"), exportable.all.keys)
        assertFalse(local.contains("receiver_source"))
        assertTrue(local.contains("receiver_id"))
    }

    @Test fun removalDisablesAutomaticProvisioningButFreshSetupHasADefaultSource() {
        assertNotNull(Libre3Persistence.effectiveIdentitySource(context))
        assertFalse(Libre3Persistence.autoProvisionBlocked(context))
        Libre3Persistence.clearIdentities(context)
        assertTrue(Libre3Persistence.autoProvisionBlocked(context))
        assertFalse(Libre3Persistence.ensureIdentities(context))
    }

    private fun failedPreferenceWrites(file: String, shouldFail: () -> Boolean): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val original = super.getSharedPreferences(name, mode)
            if (name != file) return original
            return object : SharedPreferences by original {
                override fun edit(): SharedPreferences.Editor {
                    val delegate = original.edit()
                    return object : SharedPreferences.Editor by delegate {
                        override fun commit(): Boolean {
                            // Android updates in-memory preferences before reporting disk failure.
                            val saved = delegate.commit()
                            return saved && !shouldFail()
                        }
                    }
                }
            }
        }
    }

    @Test fun failedMigrationKeepsChoicesUsableAndRetriesBeforeExport() {
        val local = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val source = Libre3IdentitySource.custom("https://example.test/keys", "a".repeat(64))!!
        local.edit().putInt("receiver_source", Libre3Persistence.RECEIVER_OWN)
            .putString("identity_custom_source", source.toSpec()).putLong("receiver_id", 123L).commit()
        var failWrites = true
        val failing = failedPreferenceWrites(Libre3Persistence.SETTINGS_PREFS_NAME) { failWrites }
        assertEquals(Libre3Persistence.RECEIVER_OWN, Libre3Persistence.getReceiverSource(failing))
        assertEquals(source, Libre3Persistence.effectiveIdentitySource(failing))
        assertTrue(local.contains("receiver_source"))
        assertTrue(runCatching { Libre3Persistence.migrateSettingsForExport(failing) }.isFailure)
        // Edits made while migration is pending must win over stale legacy values.
        Libre3Persistence.setReceiverSource(failing, Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT)
        Libre3Persistence.setCustomIdentitySource(failing, null)
        assertEquals(Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT, Libre3Persistence.getReceiverSource(failing))
        assertNull(Libre3Persistence.customIdentitySource(failing))
        failWrites = false
        Libre3Persistence.migrateSettingsForExport(failing)
        assertEquals(Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT, Libre3Persistence.getReceiverSource(context))
        assertNull(Libre3Persistence.customIdentitySource(context))
        assertFalse(local.contains("receiver_source"))
        assertEquals(123L, local.getLong("receiver_id", 0L))
    }

    private fun withHandoverRoster(run: (SuperGattCallback, String) -> Unit) {
        val savedCallbacks = SensorBluetooth.gattcallbacks.toList()
        val savedBlue = SensorBluetooth.blueone
        val savedManaged = ManagedCurrentSensor.get()
        val legacyName = Libre3IdentityAdapter.legacyShortName(sensor)
        val legacy = object : SuperGattCallback(legacyName, 0L, 3) {}
        try {
            SensorBluetooth.blueone = SensorBluetooth()
            SensorBluetooth.gattcallbacks.clear()
            ManagedCurrentSensor.clear()
            SensorIdentity.invalidateCaches()
            Libre3ReviewNatives.activeNames = arrayOf("OTHER", legacyName)
            run(legacy, legacyName)
        } finally {
            SensorBluetooth.gattcallbacks.clear()
            SensorBluetooth.gattcallbacks.addAll(savedCallbacks)
            SensorBluetooth.blueone = savedBlue
            ManagedCurrentSensor.set(savedManaged)
            SensorIdentity.invalidateCaches()
        }
    }

    private fun pairing() = Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now, 60, 21_600, 123, "test", 1)

    @Test fun selectedLegacyTakeoverTransfersToKotlinEvenWithAnotherSensorActive() = withHandoverRoster { legacy, legacyName ->
        Libre3ReviewNatives.selectedName = legacyName
        SensorBluetooth.gattcallbacks.add(legacy)
        // An existing successor takes the refresh path, avoiding any real Bluetooth connection.
        SensorBluetooth.gattcallbacks.add(manager)
        assertEquals(legacyName, SensorIdentity.resolveMainSensor())
        Libre3Registry.addSensor(context, pairing())
        assertFalse(SensorBluetooth.gattcallbacks.contains(legacy))
        assertEquals(sensor, ManagedCurrentSensor.get())
        assertEquals(sensor, SensorIdentity.resolveMainSensor())
    }

    @Test fun sensorOfTheRemovedProprietaryDriverMovesWithoutARescan() = withHandoverRoster { _, legacyName ->
        Libre3ReviewNatives.selectedName = legacyName
        Libre3ReviewNatives.accountNumber = 0x1_6f0d8378L
        Libre3ReviewNatives.legacyAddress = "cc:22:df:b8:f9:58"
        Libre3ReviewNatives.legacyPin = byteArrayOf(0x32, 0x25, 0xec.toByte(), 0x72)
        Libre3ReviewNatives.legacyStartMs = now
        Libre3ReviewNatives.legacyEndMs = now + 15L * 24 * 60 * 60_000
        val successor = Libre3Registry.adoptLegacySensor(context, legacyName, 41L)
        assertTrue(successor is Libre3BleManager)
        assertEquals(sensor, successor.SerialNumber)
        val record = Libre3Persistence.loadSensor(context, sensor)!!
        assertEquals("CC:22:DF:B8:F9:58", record.bleAddress)
        assertEquals("3225ec72", Libre3Bytes.hex(record.blePin))
        assertEquals(now, record.startTimeMs)
        assertEquals(15 * 24 * 60, record.wearMinutes)
        assertEquals(0x6f0d8378L, record.receiverId) // the ID the removed driver sent in A0/A8
        // The legacy record is finished (its history stays) and its stream pointer released.
        assertTrue(Libre3ReviewNatives.sensorFinishedById("E07A-$legacyName"))
        assertEquals(listOf(41L), Libre3ReviewNatives.freed)
        // The main role moves with it; SensorBluetooth puts the returned driver in the roster.
        assertEquals(sensor, ManagedCurrentSensor.get())
        SensorBluetooth.gattcallbacks.add(successor)
        SensorIdentity.invalidateCaches()
        assertEquals(sensor, SensorIdentity.resolveMainSensor())
        successor.close()
    }

    @Test fun legacySensorWithoutStoredPairingWaitsForAScan() = withHandoverRoster { _, legacyName ->
        Libre3ReviewNatives.legacyAddress = "CC:22:DF:B8:F9:58"
        val successor = Libre3Registry.adoptLegacySensor(context, legacyName, 41L)
        assertFalse(Libre3Persistence.hasSensor(context, sensor))
        assertEquals(sensor, successor.SerialNumber)
        successor.close()
    }

    @Test fun unrelatedSelectionSurvivesLegacyTakeover() = withHandoverRoster { legacy, _ ->
        Libre3ReviewNatives.selectedName = "OTHER"
        SensorBluetooth.gattcallbacks.add(legacy)
        SensorBluetooth.gattcallbacks.add(manager)
        Libre3Registry.addSensor(context, pairing())
        assertFalse(SensorBluetooth.gattcallbacks.contains(legacy))
        assertNull(ManagedCurrentSensor.get())
        assertEquals("OTHER", SensorIdentity.resolveMainSensor())
    }

    @Test fun failedMigrationCleanupDoesNotCrashOrOverwriteDestinationChoices() {
        val local = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        local.edit().putInt("receiver_source", Libre3Persistence.RECEIVER_OWN).putLong("receiver_id", 123L).commit()
        val failing = failedPreferenceWrites(Libre3Persistence.PREFS_NAME) { true }
        assertEquals(Libre3Persistence.RECEIVER_OWN, Libre3Persistence.getReceiverSource(failing))
        Libre3Persistence.setReceiverSource(failing, Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT)
        // Simulate legacy keys returning from disk when a failed cleanup is reopened.
        local.edit().putInt("receiver_source", Libre3Persistence.RECEIVER_OWN).commit()
        assertEquals(Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT, Libre3Persistence.getReceiverSource(context))
        assertFalse(local.contains("receiver_source"))
        assertEquals(123L, local.getLong("receiver_id", 0L))
    }

    @Test fun removalClearsAllCachedKeysAndRejectsWorkStartedBeforeRemoval() {
        val revision = Libre3Persistence.credentialRevision(context)
        context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("identity_payload.old", "sealed")
            .putString("$sensor.kauth", "sealed")
            .putString("other.kauth", "sealed").commit()
        Libre3Persistence.clearIdentities(context)
        val keys = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE).all.keys
        assertFalse(keys.any { it.endsWith(".kauth") || it.startsWith("identity_payload.") })
        assertFalse(Libre3Persistence.saveCachedAuth(context, sensor, ByteArray(16), "old/test", revision))
        val result = runCatching { Libre3Persistence.installIdentities(context, "old", "0".repeat(64), byteArrayOf(), "test", revision) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(Libre3Persistence.hasIdentities(context))
    }
}

class Libre3ReviewApplication : Applic() { override fun onCreate() {} }

@Implements(value = Natives::class, callThroughByDefault = false)
class Libre3ReviewNatives {
    companion object {
        val wakes = mutableListOf<Long>()
        var accountNumber = 0L
        var wearDays = 0
        var activationSec = 0L
        var stockWrites = 0
        var resolvedWrites = 0
        val nativeSeconds = sortedSetOf<Long>()
        var refusedSeconds = emptySet<Long>()
        var activeNames = emptyArray<String>()
        var selectedName: String? = null
        val finishedNames = mutableSetOf<String>()
        fun reset() {
            stockWrites = 0; resolvedWrites = 0; nativeSeconds.clear(); refusedSeconds = emptySet(); wearDays = 0; activationSec = 0L; wakes.clear(); accountNumber = 0L
            activeNames = emptyArray(); selectedName = null; finishedNames.clear()
            legacyAddress = null; legacyPin = null; legacyStartMs = 0L; legacyEndMs = 0L; freed.clear()
        }
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun setSensorWearDays(serial: String, days: Int) { wearDays = days }
        @JvmStatic @Implementation fun setManagedLibre3Activation(serial: String, seconds: Long) { activationSec = seconds }
        @JvmStatic @Implementation fun getlibreAccountIDnumber(): Long = accountNumber
        @JvmStatic @Implementation fun setlibreAccountIDNative(account: String) { accountNumber = 0x11223344L }
        @JvmStatic @Implementation fun setlibreAccountIDnumberNative(value: Long) { accountNumber = value }
        @JvmStatic @Implementation fun setlibreAccountID(account: String) {
            Shadow.directlyOn<Any?, Natives>(Natives::class.java, "setlibreAccountID", org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, account))
        }
        @JvmStatic @Implementation fun setlibreAccountIDnumber(value: Long) {
            Shadow.directlyOn<Any?, Natives>(Natives::class.java, "setlibreAccountIDnumber", org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, value))
        }
        // A legacy native Libre 3 record, as the removed proprietary driver left it.
        var legacyAddress: String? = null
        var legacyPin: ByteArray? = null
        var legacyStartMs = 0L
        var legacyEndMs = 0L
        val freed = mutableListOf<Long>()
        @JvmStatic @Implementation fun resolveFullSensorName(name: String): String? =
            if (name.startsWith("XX")) "E07A-$name" else null
        @JvmStatic @Implementation fun getDeviceAddress(dataptr: Long, getnew: Boolean): String? = legacyAddress
        @JvmStatic @Implementation fun getsensorptr(dataptr: Long): Long = if (dataptr != 0L) dataptr + 1 else 0L
        @JvmStatic @Implementation fun getpin(sensorptr: Long): ByteArray? = legacyPin
        @JvmStatic @Implementation fun getSensorStartmsec(dataptr: Long): Long = legacyStartMs
        @JvmStatic @Implementation fun getSensorEndTime(dataptr: Long, official: Boolean): Long = legacyEndMs
        @JvmStatic @Implementation fun getSensorWarmupMinutesByName(name: String): Int = 60
        @JvmStatic @Implementation fun freedataptr(dataptr: Long) { freed += dataptr }
        @JvmStatic @Implementation fun getuseuploader(): Boolean = true
        @JvmStatic @Implementation fun gethealthConnect(): Boolean = true
        @JvmStatic @Implementation fun wakeNightscoutForLiveReading(source: String, at: Long): Boolean { wakes += at; return true }
        @JvmStatic @Implementation fun getSensorStreamCapacity(serial: String): Int = 32 * 24 * 60
        @JvmStatic @Implementation fun str2sensorptr(serial: String): Long = 1L
        @JvmStatic @Implementation fun activeSensors(): Array<String> = activeNames.filterNot(::sensorFinishedById).toTypedArray()
        @JvmStatic @Implementation fun lastsensorname(): String? = selectedName
        @JvmStatic @Implementation fun currentsensorname(): String? = selectedName
        @JvmStatic @Implementation fun setcurrentsensor(name: String) { selectedName = name.ifEmpty { null } }
        @JvmStatic @Implementation fun finishSensorById(name: String): Boolean = finishedNames.add(name.removePrefix("E07A-"))
        @JvmStatic @Implementation fun sensorFinishedById(name: String): Boolean = name.removePrefix("E07A-") in finishedNames
        @JvmStatic @Implementation fun getSensorStartmsecFromSensorptr(ptr: Long): Long = System.currentTimeMillis() - 86_400_000L
        @JvmStatic @Implementation fun addGlucoseStreamBatchWithRawTemp(t: LongArray, g: FloatArray, r: FloatArray, temp: FloatArray, serial: String): Int {
            val accepted = t.filterNot { it in refusedSeconds }
            stockWrites += accepted.size; nativeSeconds.addAll(accepted); return accepted.size
        }
        @JvmStatic @Implementation fun getGlucoseHistoryForSensor(serial: String, startSec: Long): LongArray? =
            nativeSeconds.filter { it > startSec }.flatMap { listOf(it, 1230L, 0L) }.takeIf { it.isNotEmpty() }?.toLongArray()
        @JvmStatic @Implementation fun addResolvedGlucoseStreamBatchWithRawTemp(t: LongArray, g: FloatArray, r: FloatArray, temp: FloatArray, serial: String): Int {
            resolvedWrites += t.size; return t.size
        }
    }
}

/** Keep notification/alarm publication outside the export boundary test; shared completion still runs. */
@Implements(value = SuperGattCallback::class, callThroughByDefault = true)
class Libre3PublicationShadow {
    companion object {
        @JvmStatic @Implementation
        fun processExternalCurrentReading(sensor: String, lanes: LiveReadingLanes, rate: Float, time: Long, gen: Int, start: Long) {}
    }
}
