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

/** Real identity adapters and history policy; only native name resolution is shadowed. */
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
        SensorIdentity.invalidateCaches()
    }

    @After
    fun tearDown() {
        SensorBluetooth.gattcallbacks.clear()
        NativeHistoryNames.fullNames.clear()
        SensorIdentity.invalidateCaches()
    }

    @Test
    fun apiAndNightscoutMirrorsKeepRoomOwnershipThroughNativeShortNames() {
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
    fun legacyNativeSensorStillImportsHistoryThroughItsShortName() {
        NativeHistoryNames.fullNames["1YL08230BFY"] = "240601YL08230BFY"

        assertTrue(SensorIdentity.shouldUseNativeHistorySync("1YL08230BFY"))
    }

    @Test
    fun unavailableNativeNameStillUsesTheLogicalSensorPolicy() {
        assertEquals(Applic.isWearable, SensorIdentity.shouldUseNativeHistorySync("API-123456789ABC"))
        assertTrue(SensorIdentity.shouldUseNativeHistorySync("unknown-sensor"))
    }
}

class NativeHistoryTestApplication : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class NativeHistoryNames {
    companion object {
        val fullNames = mutableMapOf<String, String>()

        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun resolveFullSensorName(id: String): String? = fullNames[id]
    }
}
