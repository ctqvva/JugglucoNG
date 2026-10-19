package tk.glucodata.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The typed reads of a [SettingDefinition] (plan §2.4): a value an old install stored as another
 * type falls back to the default instead of throwing at the call site, and a declared range is
 * applied the way the previous readers did with `coerceIn`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Keep Conscrypt from becoming the JVM-wide top JCA provider; see HistoryMigrationTest.
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsRegistryPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aStoredValueIsRead() {
        SettingsRegistry.PREDICTION_HORIZON.prefs(context)!!.edit()
            .putInt(KEY_PREDICTION_HORIZON, 240).commit()

        assertEquals(240, SettingsRegistry.PREDICTION_HORIZON.readInt(context))
    }

    @Test
    fun aValueStoredAsAnotherTypeFallsBackToTheDefault() {
        SettingsRegistry.PREDICTION_CARB_ABSORPTION.prefs(context)!!.edit()
            .putString(KEY_PREDICTION_CARB_ABSORPTION, "not a number").commit()

        assertEquals(
            DEFAULT_PREDICTION_CARB_ABSORPTION_G_PER_H,
            SettingsRegistry.PREDICTION_CARB_ABSORPTION.readFloat(context),
            0.0f,
        )
    }

    @Test
    fun theDeclaredRangeIsApplied() {
        SettingsRegistry.PREDICTION_HORIZON.prefs(context)!!.edit()
            .putInt(KEY_PREDICTION_HORIZON, 10_000).commit()

        assertEquals(360, SettingsRegistry.PREDICTION_HORIZON.readIntClamped(context))
    }
}
