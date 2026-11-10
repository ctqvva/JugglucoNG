package tk.glucodata.ui

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.UiRefreshBus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UncertaintyDisplayPreferenceTests {
    @Test fun displayToggleDoesNotPublishADataChangeOrRebuildHistory() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = mutableListOf<UiRefreshBus.Event>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { UiRefreshBus.events.collect { events.add(it) } }
        val revision = UiRefreshBus.revision.value
        try {
            val context = RuntimeEnvironment.getApplication()
            GlucoseUncertaintyDisplay.setRibbonEnabled(context, false)
            assertFalse(GlucoseUncertaintyDisplay.isRibbonEnabled(context))
            GlucoseUncertaintyDisplay.setRibbonEnabled(context, true)
            assertTrue(GlucoseUncertaintyDisplay.isRibbonEnabled(context))
            assertEquals(revision, UiRefreshBus.revision.value)
            assertEquals(listOf(UiRefreshBus.Event.StatusOnly, UiRefreshBus.Event.StatusOnly), events)
        } finally {
            scope.cancel()
        }
    }
}
