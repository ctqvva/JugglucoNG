package tk.glucodata

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.drivers.sibionics.SibionicsConstants
import tk.glucodata.drivers.sibionics.SibionicsRegistry

/** Real identity/ownership policy and history fallback; native reads are shadowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [NativeHistoryNames::class])
class NativeHistoryOwnershipTests {
    @Before
    fun setUp() {
        Applic.app = RuntimeEnvironment.getApplication() as NativeHistoryTestApplication
        Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SensorBluetooth.gattcallbacks.clear()
        NativeHistoryNames.fullNames.clear()
        NativeHistoryNames.histories.clear()
        NativeHistoryNames.historyReads.clear()
        SensorIdentity.invalidateCaches()
    }

    @After
    fun tearDown() {
        SensorBluetooth.gattcallbacks.clear()
        NativeHistoryNames.fullNames.clear()
        NativeHistoryNames.histories.clear()
        NativeHistoryNames.historyReads.clear()
        SensorIdentity.invalidateCaches()
    }

    @Test
    fun localApiAndNightscoutMirrorsKeepRoomOwnershipThroughNativeShortNames() {
        for (fullName in listOf("API-123456789ABC", "NSF-23456789ABCD")) {
            // sensor::shortsensorname_chars() removes the first five characters.
            val alias = fullName.drop(5)
            NativeHistoryNames.fullNames[alias] = fullName
            NativeHistoryNames.fullNames[fullName] = fullName

            assertEquals(fullName, SensorIdentity.canonicalSensorId(alias))
            // Wear has no Room, so its policy deliberately allows native history.
            assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync(fullName))
            assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync(alias))
        }
    }

    @Test
    fun cloneOwnershipOverridesFollowerRoomPolicyForFullAndShortNames() {
        for ((index, fullName) in listOf("API-123456789ABC", "NSF-23456789ABCD").withIndex()) {
            val alias = fullName.drop(5)
            NativeHistoryNames.fullNames[alias] = fullName
            NativeHistoryNames.fullNames[fullName] = fullName
            // Resolve/cache the alias before Clone registers the arriving shell.
            assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync(alias))
            val route = if (index == 0) CloneTransport.LOCAL_ICE else CloneTransport.TURN
            assertTrue(CloneSensorRegistry.markCloneSensor(if (index == 0) fullName else alias, route.code, "history-test"))

            assertTrue(SensorIdentity.shouldUseNativeHistorySync(fullName))
            assertTrue(SensorIdentity.shouldUseNativeHistorySync(alias))
        }
        // Owning one mirror does not change the policy for another local follower.
        assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync("API-ABCDEF123456"))
        // Ownership changes must take effect even while native aliases remain cached.
        Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            .edit().remove("clone_source_sensor_ids_v1").commit()
        for (fullName in listOf("API-123456789ABC", "NSF-23456789ABCD")) {
            assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync(fullName))
            assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync(fullName.drop(5)))
        }
    }

    @Test
    fun cloneNotificationFallbackLoadsAndAdvancesWithoutRoomHistory() {
        val fullName = "API-123456789ABC"
        val alias = fullName.drop(5)
        NativeHistoryNames.fullNames[alias] = fullName
        NativeHistoryNames.fullNames[fullName] = fullName
        val firstTime = 1_760_000_000L
        NativeHistoryNames.histories[alias] = longArrayOf(firstTime, 1150L, 0L)
        assertEquals(if (Applic.isWearable) 1 else 0,
            NotificationHistorySource.getRawHistory(0L, alias).size)
        NativeHistoryNames.historyReads.clear()
        assertTrue(CloneSensorRegistry.markCloneSensor(fullName, CloneTransport.TURN.code, "history-test"))

        val first = NotificationHistorySource.getRawHistory(0L, alias).single()
        assertEquals(firstTime * 1000L, first.timestamp)
        assertEquals(115f, first.value, 0f)
        NativeHistoryNames.histories[alias] = longArrayOf(
            firstTime, 1150L, 0L, firstTime + 60L, 1300L, 0L,
        )
        val updated = NotificationHistorySource.getRawHistory(0L, alias)
        assertEquals(2, updated.size)
        assertEquals((firstTime + 60L) * 1000L, updated.last().timestamp)
        assertEquals(130f, updated.last().value, 0f)
        assertEquals(listOf(alias, alias), NativeHistoryNames.historyReads)
    }

    @Test
    fun legacyNativeSensorStillImportsHistoryThroughItsShortName() {
        NativeHistoryNames.fullNames["1YL08230BFY"] = "240601YL08230BFY"

        assertTrue(SensorIdentity.shouldUseNativeHistorySync("1YL08230BFY"))
    }

    @Test
    fun unavailableNativeNameStillUsesTheLogicalSensorPolicy() {
        assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync("API-123456789ABC"))
        assertTrue(SensorIdentity.shouldUseNativeHistorySync("unknown-sensor"))
    }

    private fun migratedSibionics(): Pair<String, String> {
        val legacy = "0111801JGS9"
        val record = SibionicsRegistry.ensureSensorRecord(
            Applic.app, "SIBI:$legacy", null, legacy, SibionicsConstants.Variant.SIBIONICS2,
        )
        SibionicsRegistry.bindLegacyNativeName(Applic.app, record.sensorId, legacy)
        SensorIdentity.invalidateCaches()
        return record.sensorId to legacy
    }

    @Test
    fun wearHistoryFindsRegisteredLegacyRecordBehindAnEmptyManagedShell() {
        if (!Applic.isWearable) return // Phone managed history remains Room-owned.
        val (managed, legacy) = migratedSibionics()
        NativeHistoryNames.histories[managed] = longArrayOf(1_760_000_000L, 0L, 0L)
        NativeHistoryNames.histories[legacy] = longArrayOf(1_760_000_060L, 1230L, 1170L)

        for (id in listOf(managed, legacy)) {
            NativeHistoryNames.historyReads.clear()
            val raw = NotificationHistorySource.getRawHistory(0L, id).single()
            assertEquals(1_760_000_060_000L, raw.timestamp)
            assertEquals(123f, raw.value, 0f)
            assertEquals(117f, raw.rawValue, 0f)
            assertEquals(listOf(managed, legacy), NativeHistoryNames.historyReads)
            val mmol = NotificationHistorySource.getDisplayHistory(0L, true, id).single()
            assertEquals(123f / 18.0182f, mmol.value, 0.0001f)
        }
    }

    @Test
    fun usableManagedHistoryWinsOverLegacyAlgorithmValues() {
        if (!Applic.isWearable) return
        val (managed, legacy) = migratedSibionics()
        NativeHistoryNames.histories[managed] = longArrayOf(1_760_000_060L, 1230L, 1170L)
        NativeHistoryNames.histories[legacy] = longArrayOf(1_760_000_060L, 1800L, 1700L)

        val point = NotificationHistorySource.getRawHistory(0L, legacy).single()
        assertEquals(123f, point.value, 0f)
        assertEquals(listOf(managed), NativeHistoryNames.historyReads)
    }

    @Test
    fun unregisteredSensorCannotReadAnUnrelatedSuffixRecord() {
        val managed = "SIBI:0111801JGS9"
        NativeHistoryNames.histories["0111801JGS9"] = longArrayOf(1_760_000_060L, 1230L, 1170L)

        assertTrue(NotificationHistorySource.getRawHistory(0L, managed).isEmpty())
        assertTrue("0111801JGS9" !in NativeHistoryNames.historyReads)
    }
}

class NativeHistoryTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class NativeHistoryNames {
    companion object {
        val fullNames = mutableMapOf<String, String>()
        val histories = mutableMapOf<String, LongArray>()
        val historyReads = mutableListOf<String>()

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun resolveFullSensorName(id: String): String? = fullNames[id]
        @JvmStatic @Implementation fun getGlucoseHistoryForSensor(id: String, start: Long): LongArray? {
            historyReads += id
            return histories[id]
        }
    }
}
