package tk.glucodata.share.lumiflex

import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudSyncWorkerTests {
    @Test
    fun processStartupRestoresUniqueNetworkConstrainedWorkAndDisablingCancelsRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context)
        settings.clear()
        LumiflexCloudQueue(context).clear()
        settings.startSession("account", "token", "user")
        settings.enabled = true
        LumiflexCloudSyncWorker.initialize(context)
        LumiflexCloudSyncWorker.initialize(context)
        val manager = WorkManager.getInstance(context)
        val periodic = manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
        assertEquals(1, periodic.count { !it.state.isFinished })
        val work = periodic.single { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertNotNull(work.periodicityInfo)
        assertTrue(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME).get(10, TimeUnit.SECONDS).isNotEmpty())
        settings.enabled = false
        LumiflexCloudSyncWorker.initialize(context)
        runBlocking {
            withTimeout(10_000) {
                manager.getWorkInfosForUniqueWorkFlow(LumiflexCloudSyncWorker.PERIODIC_NAME)
                    .first { it.isNotEmpty() && it.all { row -> row.state == WorkInfo.State.CANCELLED } }
            }
        }
        assertTrue(settings.isSignedIn())
    }
}
