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

    @Test fun watchTakeoverIsRefusedForPersistedLibre3Sensors() {
        Libre3Persistence.saveSensor(context, Libre3SensorRecord(sensor, "AA:BB:CC:DD:EE:FF", ByteArray(4), now, 60, 21600, 1, "test", 1))
        assertFalse(ManagedSensorHandoff.supportsSensor(sensor))
        assertTrue(ManagedSensorHandoff.supportsSensor("unrelated"))
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
        local.edit().putBoolean("enabled", true).putInt("receiver_source", Libre3Persistence.RECEIVER_OWN)
            .putString("identity_custom_source", source.toSpec()).putLong("receiver_id", 123L).commit()
        assertTrue(Libre3Persistence.isEnabled(context))
        assertEquals(Libre3Persistence.RECEIVER_OWN, Libre3Persistence.getReceiverSource(context))
        assertEquals(source, Libre3Persistence.effectiveIdentitySource(context))
        val exportable = context.getSharedPreferences(Libre3Persistence.SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(setOf("enabled", "receiver_source", "identity_custom_source"), exportable.all.keys)
        assertFalse(local.contains("enabled"))
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
        local.edit().putBoolean("enabled", true).putInt("receiver_source", Libre3Persistence.RECEIVER_OWN)
            .putString("identity_custom_source", source.toSpec()).putLong("receiver_id", 123L).commit()
        var failWrites = true
        val failing = failedPreferenceWrites(Libre3Persistence.SETTINGS_PREFS_NAME) { failWrites }
        assertTrue(Libre3Persistence.isEnabled(failing))
        assertEquals(Libre3Persistence.RECEIVER_OWN, Libre3Persistence.getReceiverSource(failing))
        assertEquals(source, Libre3Persistence.effectiveIdentitySource(failing))
        assertTrue(local.getBoolean("enabled", false))
        assertTrue(runCatching { Libre3Persistence.migrateSettingsForExport(failing) }.isFailure)
        // Edits made while migration is pending must win over stale legacy values.
        Libre3Persistence.setEnabled(failing, false)
        Libre3Persistence.setCustomIdentitySource(failing, null)
        assertFalse(Libre3Persistence.isEnabled(failing))
        assertNull(Libre3Persistence.customIdentitySource(failing))
        failWrites = false
        Libre3Persistence.migrateSettingsForExport(failing)
        assertFalse(Libre3Persistence.isEnabled(context))
        assertNull(Libre3Persistence.customIdentitySource(context))
        assertFalse(local.contains("enabled"))
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

    @Test fun unrelatedSelectionSurvivesLegacyTakeover() = withHandoverRoster { legacy, _ ->
        Libre3ReviewNatives.selectedName = "OTHER"
        SensorBluetooth.gattcallbacks.add(legacy)
        SensorBluetooth.gattcallbacks.add(manager)
        Libre3Registry.addSensor(context, pairing())
        assertFalse(SensorBluetooth.gattcallbacks.contains(legacy))
        assertNull(ManagedCurrentSensor.get())
        assertEquals("OTHER", SensorIdentity.resolveMainSensor())
    }

    @Test fun selectedKotlinSensorTransfersBackToLegacyAfterItsPairingIsRemoved() = withHandoverRoster { _, legacyName ->
        Libre3Persistence.saveSensor(context, pairing())
        ManagedCurrentSensor.set(sensor)
        Libre3ReviewNatives.selectedName = "OTHER"
        SensorBluetooth.gattcallbacks.add(manager)
        assertEquals(sensor, SensorIdentity.resolveMainSensor())
        Libre3Registry.retireManagedForLegacyScan(context, legacyName)
        assertFalse(SensorBluetooth.gattcallbacks.contains(manager))
        assertFalse(Libre3Persistence.hasSensor(context, sensor))
        assertNull(ManagedCurrentSensor.get())
        assertEquals(legacyName, SensorIdentity.resolveMainSensor())
    }

    @Test fun failedMigrationCleanupDoesNotCrashOrOverwriteDestinationChoices() {
        val local = context.getSharedPreferences(Libre3Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        local.edit().putBoolean("enabled", true).putLong("receiver_id", 123L).commit()
        val failing = failedPreferenceWrites(Libre3Persistence.PREFS_NAME) { true }
        assertTrue(Libre3Persistence.isEnabled(failing))
        Libre3Persistence.setEnabled(failing, false)
        // Simulate legacy keys returning from disk when a failed cleanup is reopened.
        local.edit().putBoolean("enabled", true).commit()
        assertFalse(Libre3Persistence.isEnabled(context))
        assertFalse(local.contains("enabled"))
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
        var stockWrites = 0
        var resolvedWrites = 0
        var activeNames = emptyArray<String>()
        var selectedName: String? = null
        val finishedNames = mutableSetOf<String>()
        fun reset() {
            stockWrites = 0; resolvedWrites = 0; wearDays = 0; wakes.clear(); accountNumber = 0L
            activeNames = emptyArray(); selectedName = null; finishedNames.clear()
        }
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun setSensorWearDays(serial: String, days: Int) { wearDays = days }
        @JvmStatic @Implementation fun getlibreAccountIDnumber(): Long = accountNumber
        @JvmStatic @Implementation fun setlibreAccountIDNative(account: String) { accountNumber = 0x11223344L }
        @JvmStatic @Implementation fun setlibreAccountIDnumberNative(value: Long) { accountNumber = value }
        @JvmStatic @Implementation fun setlibreAccountID(account: String) {
            Shadow.directlyOn<Any?, Natives>(Natives::class.java, "setlibreAccountID", org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, account))
        }
        @JvmStatic @Implementation fun setlibreAccountIDnumber(value: Long) {
            Shadow.directlyOn<Any?, Natives>(Natives::class.java, "setlibreAccountIDnumber", org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, value))
        }
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
            stockWrites += t.size; return t.size
        }
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
