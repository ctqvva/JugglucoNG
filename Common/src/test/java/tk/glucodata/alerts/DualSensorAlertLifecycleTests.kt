package tk.glucodata.alerts

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
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AlertVolumeTestApplication::class,
    shadows = [DualSensorAlertLifecycleTests.DeliverySink::class, DualSensorAlertLifecycleTests.NativeSink::class])
class DualSensorAlertLifecycleTests {
    @Implements(Notify::class)
    class DeliverySink {
        companion object {
            val cancelled = mutableListOf<Pair<Int, String>>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun cancelRetrySession(kind: Int, reason: String) {
                cancelled += kind to reason
            }
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    private val type = AlertType.SENSOR_DISAGREEMENT
    private val config = AlertDefaults.defaultConfig(type, false).copy(enabled = true)
    private var logging = false
    private val handleResult = AlertRuntimeManager::class.java.getDeclaredMethod(
        "handleSensorDisagreementLocked", AlertConfig::class.java, DualSensorConsensus.Result::class.java
    ).apply { isAccessible = true }

    @Before fun setup() {
        Applic.app = RuntimeEnvironment.getApplication() as AlertVolumeTestApplication
        DeliverySink.cancelled.clear()
        logging = Log.doLog
        Log.doLog = false
        AlertStateTracker.resetState(type)
        AlertStateTracker.onAlertTriggered(type)
        AlertStateTracker.onAlertDismissed(type)
    }

    @After fun cleanup() {
        AlertStateTracker.resetState(type)
        Log.doLog = logging
    }

    @Test fun waitingForPeerPreservesTheAcknowledgedDisagreementEpisode() {
        repeat(2) {
            handleResult.invoke(AlertRuntimeManager, config,
                DualSensorConsensus.Result(status = DualSensorConsensus.Status.WAITING_FOR_PEER))
            assertTrue(AlertStateTracker.isEpisodeActive(type))
            assertTrue(AlertStateTracker.isDismissed(type))
        }
        assertTrue("Waiting must not cancel the ongoing episode's queued delivery or retries",
            DeliverySink.cancelled.isEmpty())
    }

    @Test fun actualUnavailabilityOrDisablingStillClearsTheEpisode() {
        handleResult.invoke(AlertRuntimeManager, config, DualSensorConsensus.Result())
        assertFalse(AlertStateTracker.isEpisodeActive(type))
        assertFalse(AlertStateTracker.isDismissed(type))
        AlertStateTracker.onAlertTriggered(type)
        AlertStateTracker.onAlertDismissed(type)
        handleResult.invoke(AlertRuntimeManager, config.copy(enabled = false),
            DualSensorConsensus.Result(status = DualSensorConsensus.Status.WAITING_FOR_PEER))
        assertFalse(AlertStateTracker.isEpisodeActive(type))
        assertFalse(AlertStateTracker.isDismissed(type))
    }
}
