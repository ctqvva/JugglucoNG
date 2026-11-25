package tk.glucodata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Old/new combinations for the versioned display-prefs message (direction.md §6 Q2). The old
 * parser skips a line with no `=`, so a new phone's version line is invisible to an old watch; a
 * new watch reads a legacy payload as version 1; and a payload from a newer protocol is ignored
 * rather than applied partially.
 *
 * The accept policy itself is pinned without Android in [WearProtocolTests]; applying a payload
 * writes settings and raises UiRefreshBus, which needs the native library, so it is not exercised
 * here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Keep Conscrypt from becoming the JVM-wide top JCA provider; see HistoryMigrationTest.
@ConscryptMode(ConscryptMode.Mode.OFF)
class WearPrefsVersionTests {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theEncodedPayloadCarriesTheVersionFirst() {
        val text = WearPrefsSync.encode(context).toString(Charsets.UTF_8)
        assertTrue("first line is the version", text.startsWith("${WearProtocol.versionLine()}\n"))
        // The version line has no '=', which is exactly why an old receiver skips it.
        assertTrue("no '=' in the version line", !WearProtocol.versionLine().contains('='))
    }

    @Test
    fun theVersionLineAloneWritesNothing() {
        val onlyVersion = "${WearProtocol.versionLine()}\n".toByteArray(Charsets.UTF_8)

        assertEquals(0, WearPrefsSync.apply(context, onlyVersion))
    }

    @Test
    fun sensorDisplayNamesApplyAndClearThroughWearPrefsSync() {
        val prefs = context.getSharedPreferences(
            tk.glucodata.settings.SettingsRegistry.FILE,
            Context.MODE_PRIVATE,
        )
        val wire = WearPrefsSync.escapeLine(
            SensorDisplayName.encodeStoredMap(
                linkedMapOf(
                    "1111122222333331" to "DXCMAA",
                    "NSF-AABBCCDDEEFF" to "Nightscout follower",
                ),
            ),
        )
        val payload = "${WearProtocol.versionLine()}\ns:${tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES}=$wire\n"
            .toByteArray(Charsets.UTF_8)

        var namesChanged = false
        assertEquals(
            1,
            WearPrefsSync.applyToPrefs(context, payload) { _, _, displayNamesChanged ->
                namesChanged = displayNamesChanged
                if (displayNamesChanged) SensorDisplayName.invalidateCache()
            },
        )
        assertTrue("first write flags display-name change", namesChanged)
        assertEquals(
            mapOf(
                "1111122222333331" to "DXCMAA",
                "NSF-AABBCCDDEEFF" to "Nightscout follower",
            ),
            SensorDisplayName.decodeStoredMap(
                prefs.getString(tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES, "").orEmpty(),
            ),
        )

        // Re-applying identical names does not flag a change.
        namesChanged = true
        assertEquals(
            1,
            WearPrefsSync.applyToPrefs(context, payload) { _, _, displayNamesChanged ->
                namesChanged = displayNamesChanged
            },
        )
        assertTrue("identical payload does not re-flag change", !namesChanged)

        // Empty value clears stale mirrored names and flags change for cache invalidation.
        val emptyPayload = "${WearProtocol.versionLine()}\ns:${tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES}=\n"
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            1,
            WearPrefsSync.applyToPrefs(context, emptyPayload) { _, _, displayNamesChanged ->
                namesChanged = displayNamesChanged
                if (displayNamesChanged) SensorDisplayName.invalidateCache()
            },
        )
        assertTrue("empty payload flags display-name change", namesChanged)
        assertEquals(
            emptyMap<String, String>(),
            SensorDisplayName.decodeStoredMap(
                prefs.getString(tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES, "").orEmpty(),
            ),
        )
    }

    @Test
    fun mirroredNameCacheDoesNotPoisonWhenPrefsAreUnavailableOrThrow() {
        val prefs = context.getSharedPreferences(
            tk.glucodata.settings.SettingsRegistry.FILE,
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString(tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES, "1111122222333331|DXCMAA")
            .apply()
        SensorDisplayName.invalidateCache()

        // Early boot / locked storage: null or throwing provider must not poison the cache.
        assertEquals(emptyMap<String, String>(), SensorDisplayName.loadMirroredNames { null })
        assertEquals(
            emptyMap<String, String>(),
            SensorDisplayName.loadMirroredNames { throw IllegalStateException("locked") },
        )

        // Once prefs become available, the next call loads and caches the persisted map.
        assertEquals(
            mapOf("1111122222333331" to "DXCMAA"),
            SensorDisplayName.loadMirroredNames { prefs },
        )

        // Cached value is served without re-reading until invalidateCache() is called.
        prefs.edit()
            .putString(tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES, "1111122222333331|DXCMBB")
            .apply()
        assertEquals(
            mapOf("1111122222333331" to "DXCMAA"),
            SensorDisplayName.loadMirroredNames { prefs },
        )
        SensorDisplayName.invalidateCache()
        assertEquals(
            mapOf("1111122222333331" to "DXCMBB"),
            SensorDisplayName.loadMirroredNames { prefs },
        )
    }
}
