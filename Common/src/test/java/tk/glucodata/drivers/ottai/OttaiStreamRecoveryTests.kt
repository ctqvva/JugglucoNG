package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import tk.glucodata.Applic
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.HistoryRepositoryBridge
import tk.glucodata.SuperGattCallback

/** Replays the time-anchor and empty-store state from the Oct 29 field trace through the manager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OttaiRemovalTestApplication::class,
    shadows = [OttaiRemovalNatives::class, OttaiRecoveryGatt::class])
class OttaiStreamRecoveryTests {
    private val sensor = "70D07EDCB416"
    private lateinit var manager: OttaiBleManager
    private var oldBridge: Any? = null
    private var queries = 0
    private var stored = longArrayOf()
    private val oldStart = 1_793_223_480_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Applic
        Applic.app = context
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        OttaiRemovalNatives.reset()
        OttaiRecoveryGatt.requests.clear()
        manager = OttaiBleManager(sensor, 0L)
        set("materials", OttaiRegistry.DeviceMaterials("", "x", "", oldStart, "E1.1.4", 0))
        val bridgeField = HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }
        oldBridge = bridgeField.get(HistoryRepositoryAccess)
        HistoryRepositoryAccess.register(Proxy.newProxyInstance(
            HistoryRepositoryBridge::class.java.classLoader, arrayOf(HistoryRepositoryBridge::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getHistoryTimestampsForSensorBlocking" -> { queries++; stored }
                "getHistoryForNotificationForSensor" -> emptyList<tk.glucodata.GlucosePoint>()
                else -> null
            }
        } as HistoryRepositoryBridge)
    }

    @After
    fun tearDown() {
        HistoryRepositoryAccess::class.java.getDeclaredField("bridge").apply { isAccessible = true }
            .set(HistoryRepositoryAccess, oldBridge)
        SuperGattCallback::class.java.getDeclaredField("stop").apply { isAccessible = true }.setBoolean(manager, true)
        manager.close()
    }

    @Test
    fun historyEstimateCannotTrapLiveReadingsSeventySixMinutesInThePast() {
        set("streamStartTimeMs", oldStart)
        set("streamStartReliable", false)
        val arrival = 1_793_228_632_000L
        val sample = invoke("resolveSampleTimeMs", reading(11), true, arrival) as Long
        assertEquals(arrival, sample)
        assertTrue(OttaiBleManager.isFreshLiveSample(arrival, sample))
        assertTrue(get("streamStartReliable") as Boolean)
        val r = reading(11)
        val emitted = invoke("emitReading", r, true, arrival, listOf(r), 0, false)!!
        assertTrue(emitted.javaClass.getDeclaredField("publishCurrent").apply { isAccessible = true }.getBoolean(emitted))
        assertTrue(emitted.javaClass.getDeclaredField("persist").apply { isAccessible = true }.getBoolean(emitted))
    }

    @Test
    fun failedHistoryIssueRetriesTheDiffOnTheNextLiveCounter() {
        set("streamStartTimeMs", 1_793_227_980_000L)
        invoke("requestRoomBackfillAfterLive", 22, 22, true)
        assertEquals(1, queries)
        assertTrue(get("historyDiffRetryPending") as Boolean)
        set("phase", enumValue("Phase", "STREAMING"))
        set("sessionKeyHex", "00".repeat(16))
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, Shadow.newInstanceOf(BluetoothGatt::class.java))
        invoke("requestRoomBackfillAfterLive", 23, 22, true)
        assertEquals(2, queries)
        assertEquals(listOf(0 to 23), OttaiRecoveryGatt.requests)
    }

    @Test
    fun newActivationDiscardsThePreviousCloudStartAndHistoryEstimate() {
        set("streamStartTimeMs", oldStart)
        val r = reading(11)
        invoke("emitReading", r, true, r.monitorTimeMs, listOf(r), 0, false)
        invoke("markActivationCommandSent")
        val context = RuntimeEnvironment.getApplication() as Applic
        assertEquals(0L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
        assertEquals(0L, OttaiRegistry.loadMaterials(context, sensor).activeTimeMs)
        assertTrue(OttaiRegistry.loadProvisionalActiveTime(context, sensor) > 0L)
        assertEquals(0L, get("streamStartTimeMs"))
        assertEquals(0, invoke("estimatedNewestDataNo"))
        val arrival = System.currentTimeMillis()
        assertEquals(arrival, invoke("resolveSampleTimeMs", r.copy(monitorTimeMs = 0L), true, arrival))
    }

    @Test
    fun restartWithDecodedCounterButEmptyRoomRequestsTheMissingHistory() {
        val start = 1_793_227_980_000L
        set("streamStartTimeMs", start)
        set("streamStartReliable", true)
        set("lastDataNo", 22)
        set("phase", enumValue("Phase", "STREAMING"))
        set("sessionKeyHex", "00".repeat(16))
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, Shadow.newInstanceOf(BluetoothGatt::class.java))
        invoke("requestRoomBackfillAfterLive", 22, 22, true)
        assertEquals(1, queries)
        assertEquals(listOf(0 to 22), OttaiRecoveryGatt.requests)
        assertTrue(get("roomBackfillChecked") as Boolean)
    }

    @Test
    fun reconnectDiffRequestsOnlyTheMissingWindow() {
        val start = 1_793_227_980_000L
        stored = (0 until 22).filter { it !in 11..15 }.map { start + it * 60_000L }.toLongArray()
        set("streamStartTimeMs", start)
        set("phase", enumValue("Phase", "STREAMING"))
        set("sessionKeyHex", "00".repeat(16))
        SuperGattCallback::class.java.getDeclaredField("mBluetoothGatt").apply { isAccessible = true }
            .set(manager, Shadow.newInstanceOf(BluetoothGatt::class.java))
        invoke("requestRoomBackfillAfterLive", 22, 22, true)
        assertEquals(listOf(11 to 5), OttaiRecoveryGatt.requests)
    }

    @Test
    fun oldMinuteFlooredRowsStillCoverHistoryAfterSecondsArePreserved() {
        val minuteStart = 1_793_227_980_000L
        stored = (0 until 22).map { minuteStart + it * 60_000L }.toLongArray()
        set("streamStartTimeMs", minuteStart + 53_000L)
        invoke("requestRoomBackfillAfterLive", 22, 22, true)
        assertEquals(1, queries)
        assertTrue(get("roomBackfillChecked") as Boolean)
        assertTrue(OttaiRecoveryGatt.requests.isEmpty())
    }

    @Test
    fun unconfirmedAnchorDoesNotRefreshARepeatedPolledCounter() {
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = 0L))
        val arrival = System.currentTimeMillis()
        val r = reading(23).copy(monitorTimeMs = 0L)
        assertEquals(arrival, invoke("resolveSampleTimeMs", r, true, arrival))
        assertEquals(arrival, invoke("resolveSampleTimeMs", r, true, arrival + 8_000L))
        assertEquals(0L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
        // The next independent counter still confirms activation with the stable stream anchor.
        invoke("resolveSampleTimeMs", reading(24).copy(monitorTimeMs = 0L), true, arrival + 60_000L)
        assertEquals(arrival - 23 * 60_000L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
    }

    @Test
    fun acceptedNotifyPreservesSecondsAndRepeatedPollKeepsItsOriginalAge() {
        val arrival = 1_793_229_351_000L
        val start = 1_793_227_920_000L
        set("streamStartTimeMs", start)
        set("streamStartReliable", true)
        set("lastDataNo", 22)
        val r = reading(23)
        val emitted = invoke("emitReading", r, true, arrival, listOf(r), 0, true)!!
        val sample = emitted.javaClass.getDeclaredField("sampleMs").apply { isAccessible = true }.getLong(emitted)
        assertEquals(arrival, sample)
        // Same counter read eight seconds later must keep the notify's date, not be rejuvenated.
        assertEquals(arrival, invoke("resolveSampleTimeMs", r, true, arrival + 8_000L))
        assertEquals(arrival, manager.getCurrentSnapshot(Long.MAX_VALUE)?.timeMillis)
    }

    @Test
    fun firstNotifyCannotBypassTheBoundedCorrectionWithACounterFourAhead() {
        val arrival = 1_793_229_351_000L
        val start = arrival - 23 * 60_000L
        set("streamStartTimeMs", start)
        set("streamStartReliable", true)
        set("lastDataNo", 22)
        val r = reading(27)
        invoke("emitReading", r, true, arrival, listOf(r), 0, true)
        assertEquals(start, get("streamStartTimeMs"))
    }

    @Test
    fun repeatedCounterKeepsItsCappedTimeBeforeAndAfterArrivalPassesTheLateAnchor() {
        val arrival = System.currentTimeMillis()
        val genuineStart = arrival - 11 * 60_000L
        // A poll of counter 10 arrived twenty seconds late. Counter 11 is prompt, so its
        // anchored date is ahead of arrival and must be capped, but only once for this counter.
        val delayedPollAt = arrival - 60_000L + 20_000L
        invoke("seedStreamTimeAnchor", 10, delayedPollAt, "live", true, delayedPollAt)
        set("lastDataNo", 10)
        val r = reading(11)
        val first = invoke("emitReading", r, true, arrival, listOf(r), 0, false)!!
        assertEquals(arrival, emittedLong(first, "sampleMs"))
        assertTrue(emittedBoolean(first, "publishCurrent"))
        for (delay in listOf(8_000L, 25_000L)) {
            val replay = invoke("emitReading", r, true, arrival + delay, listOf(r), 0, false)!!
            assertEquals(arrival, emittedLong(replay, "sampleMs"))
            assertFalse(emittedBoolean(replay, "publishCurrent"))
            assertFalse(emittedBoolean(replay, "persist"))
            assertEquals(arrival, manager.getCurrentSnapshot(Long.MAX_VALUE)?.timeMillis)
        }
        assertEquals(genuineStart + 20_000L, get("streamStartTimeMs"))
        // Distinct counters must still publish and corroborate the bounded anchor correction.
        val next = reading(12)
        val emitted = invoke("emitReading", next, true, arrival + 60_000L, listOf(next), 0, false)!!
        assertTrue(emittedBoolean(emitted, "publishCurrent"))
        val following = reading(13)
        invoke("emitReading", following, true, arrival + 120_000L, listOf(following), 0, false)
        assertEquals(genuineStart, get("streamStartTimeMs"))
        val replay = invoke("emitReading", next, true, arrival + 128_000L, listOf(next), 0, false)!!
        assertEquals(arrival + 60_000L, emittedLong(replay, "sampleMs"))
        assertFalse(emittedBoolean(replay, "publishCurrent"))
    }

    @Test
    fun genuineLowerCountersReplaceACorruptHighActivationCandidateAndPersistTheStart() {
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = 0L))
        val arrival = System.currentTimeMillis()
        val start = arrival - 20 * 60_000L
        fun resolve(n: Int, delay: Long) = invoke("resolveSampleTimeMs",
            reading(n).copy(monitorTimeMs = 0L), true, arrival + delay)
        resolve(20, 0L)
        resolve(2_000, 60_000L)
        assertEquals(2_000, get("pendingConfirmedDataNo"))
        resolve(21, 60_000L)
        assertEquals(21, get("pendingConfirmedDataNo"))
        assertEquals(0L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
        resolve(21, 68_000L) // replay cannot confirm the replacement candidate
        assertEquals(0L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
        resolve(22, 120_000L)
        assertEquals(start, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
        assertEquals(start, OttaiRegistry.loadMaterials(RuntimeEnvironment.getApplication(), sensor).activeTimeMs)
    }

    @Test
    fun sameCounterCannotReplaceOrConfirmACandidateAfterALongDelay() {
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = 0L))
        val start = System.currentTimeMillis() - 20 * 60_000L
        invoke("offerConfirmedActiveTime", start, 20)
        invoke("offerConfirmedActiveTime", start + 5 * 60_000L, 20)
        assertEquals(start, get("pendingConfirmedStartMs"))
        assertEquals(0L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
    }

    private fun emittedLong(reading: Any, name: String): Long =
        reading.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(reading)
    private fun emittedBoolean(reading: Any, name: String): Boolean =
        reading.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(reading)

    @Test
    fun repeatedCounterCappedByALateAnchorKeepsItsTimestampAndPublicationClaim() {
        val arrival = System.currentTimeMillis()
        val start = arrival - 101 * 60_000L
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = start))
        val lateSeedArrival = arrival - 60_000L + 20_000L
        invoke("seedStreamTimeAnchor", 100, lateSeedArrival, "live", true, lateSeedArrival)
        val r = reading(101)

        assertEquals(Triple(arrival, true, true), liveEmission(r, arrival))
        // Both while the anchor is ahead, and after arrival has overtaken it, this is one sample.
        for (delay in listOf(8_000L, 25_000L, 60_000L, 181_000L)) {
            assertEquals(Triple(arrival, false, false), liveEmission(r, arrival + delay))
            assertEquals(arrival, manager.getCurrentSnapshot(Long.MAX_VALUE)?.timeMillis)
        }
        assertEquals(start + 20_000L, get("streamStartTimeMs"))
        assertEquals(Triple(arrival + 240_000L, true, true), liveEmission(reading(105), arrival + 240_000L))
    }

    @Test
    fun rejectedCappedReadingDoesNotPinItsTimestamp() {
        val arrival = System.currentTimeMillis()
        val start = arrival - 101 * 60_000L
        set("streamStartTimeMs", start + 20_000L)
        set("streamStartReliable", true)
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = arrival - 60_000L))
        val r = reading(101)
        assertNull(invoke("emitReading", r, true, arrival, listOf(r), 0, false))
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = start))
        assertEquals(arrival + 8_000L, invoke("resolveSampleTimeMs", r, true, arrival + 8_000L))
    }

    @Test
    fun agreeingLowerCounterAndRepeatedCounterCannotCorroborateOrRearmActivation() {
        set("materials", (get("materials") as OttaiRegistry.DeviceMaterials).copy(activeTimeMs = 0L))
        val start = System.currentTimeMillis() - 23 * 60_000L
        invoke("offerConfirmedActiveTime", start, 23)
        invoke("offerConfirmedActiveTime", start + 60_000L, 22)
        // Even a much later replay of the same counter is not a different activation candidate.
        invoke("offerConfirmedActiveTime", start + 180_000L, 23)
        assertEquals(start, get("pendingConfirmedStartMs"))
        assertEquals(23, get("pendingConfirmedDataNo"))
        assertEquals(0L, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
        invoke("offerConfirmedActiveTime", start, 24)
        assertEquals(start, (get("materials") as OttaiRegistry.DeviceMaterials).activeTimeMs)
    }

    private fun liveEmission(r: OttaiReading, arrival: Long): Triple<Long, Boolean, Boolean> {
        val emitted = invoke("emitReading", r, true, arrival, listOf(r), 0, false)!!
        fun field(name: String) = emitted.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(emitted)
        return Triple(field("sampleMs") as Long, field("publishCurrent") as Boolean, field("persist") as Boolean)
    }

    private fun reading(n: Int) = OttaiReading(
        OttaiRecord(n, 0, n * 60, 13_411, 33.8, ByteArray(12)), 7.2,
        oldStart + n * 60_000L, true,
    )

    private fun enumValue(type: String, value: String): Any =
        OttaiBleManager::class.java.declaredClasses.single { it.simpleName == type }.enumConstants!!.single { it.toString() == value }
    private fun invoke(name: String, vararg args: Any): Any? =
        OttaiBleManager::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(manager, *args)
    private fun set(name: String, value: Any) = OttaiBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    private fun get(name: String): Any? = OttaiBleManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)
}

@Implements(value = BluetoothGatt::class, callThroughByDefault = false)
class OttaiRecoveryGatt {
    @Implementation
    fun getService(uuid: UUID): BluetoothGattService? = if (uuid == OttaiConstants.SERVICE_CGM) {
        BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(OttaiConstants.CHAR_HISTORY_REQUEST,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        }
    } else null

    @Implementation
    fun writeCharacteristic(c: BluetoothGattCharacteristic): Boolean {
        val v = c.value
        fun le(i: Int) = (v[i].toInt() and 255) or ((v[i + 1].toInt() and 255) shl 8)
        requests += le(0) to le(2)
        return true
    }

    companion object { val requests = mutableListOf<Pair<Int, Int>>() }
}
