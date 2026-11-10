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
}
