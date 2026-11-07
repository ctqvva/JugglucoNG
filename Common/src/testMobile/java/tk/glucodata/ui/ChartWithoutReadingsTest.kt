package tk.glucodata.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import tk.glucodata.Applic
import tk.glucodata.Natives

/** Lets the chart's native lookups answer null instead of failing to link. */
@Implements(Natives::class)
class ChartTestNatives

/**
 * The chart has to be there before there are readings to put on it: while a new sensor's
 * history is still being fetched, and with no sensor at all. It used to compose only once it
 * had data, which left a blank screen with no range picker for as long as the fetch took.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en", shadows = [ChartTestNatives::class])
class ChartWithoutReadingsTest {
    @get:Rule
    val compose = createComposeRule()

    private var previousApplic: Applic? = null

    @Before
    fun provideApplic() {
        previousApplic = Applic.app
        val applic = Applic()
        ReflectionHelpers.callInstanceMethod<Any>(
            applic,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication())
        )
        Applic.app = applic
    }

    @After
    fun restoreApplic() {
        Applic.app = previousApplic
    }

    @Test
    fun theChartAndItsRangePickerShowWithNoHistory() {
        compose.setContent {
            DashboardChartSection(
                modifier = Modifier.fillMaxWidth().height(480.dp),
                glucoseHistory = emptyList(),
                graphLow = 2f,
                graphHigh = 12f,
                targetLow = 3.9f,
                targetHigh = 10f,
                unit = "mmol/L",
                viewMode = 0,
                onTimeRangeSelected = {},
                selectedTimeRange = TimeRange.H3,
            )
        }
        compose.onNodeWithTag(GLUCOSE_CHART_PLOT_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("3H", useUnmergedTree = true).assertExists()
    }
}
