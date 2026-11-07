package tk.glucodata.share.lumiflex

import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LumiflexCloudTestApplication::class)
class LumiflexCloudSyncWorkerTests {
    @Test fun startupRecoversInterruptedSyncWithoutResettingAnInProcessSync() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context).apply { clear(); startSession("account", "token", "user"); enabled = true }
        settings.recordSync(settings.session(), LumiflexCloudSettings.SyncState.SYNCING, 7)
        LumiflexCloudSyncWorker.initializeAfterProcessStart(context)
        assertEquals(LumiflexCloudSettings.SyncState.QUEUED, settings.syncStatus().state)
        assertEquals(7, settings.syncStatus().pending)
        settings.recordSync(settings.session(), LumiflexCloudSettings.SyncState.SYNCING, 7)
        LumiflexCloudSyncWorker.initialize(context)
        assertEquals(LumiflexCloudSettings.SyncState.SYNCING, settings.syncStatus().state)
        settings.enabled = false; LumiflexCloudSyncWorker.initialize(context)
    }

    @Test fun anAlarmPersistsNetworkConstrainedWorkInsteadOfStartingAnUnownedThread() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context).apply { clear(); startSession("account", "token", "user"); enabled = true }
        context.sendBroadcast(android.content.Intent(context, LumiflexFollowerPollReceiver::class.java)
            .setAction(LumiflexFollowerPollReceiver.ACTION))
        ShadowLooper.idleMainLooper()
        val active = await(WorkManager.getInstance(context).getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME))
            .single { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, active.constraints.requiredNetworkType)
        settings.enabled = false; LumiflexCloudSyncWorker.initialize(context)
    }
    /** WorkManager startup posts main-loop callbacks; a blocking get on Robolectric's main thread starves them. */
    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!future.isDone && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun processStartupRestoresUniqueNetworkConstrainedWorkAndDisablingCancelsRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context)
        settings.clear()
        LumiflexCloudQueue(context).clear()
        settings.startSession("account", "token", "user")
        settings.enabled = true
        LumiflexCloudSyncWorker.initialize(context)
        val manager = WorkManager.getInstance(context)
        val first = await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME))
            .single { !it.state.isFinished }
        LumiflexCloudSyncWorker.initialize(context)
        val kept = await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME))
            .single { !it.state.isFinished }
        assertEquals(first.id, kept.id)
        val periodic = await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.PERIODIC_NAME))
        assertEquals(1, periodic.count { !it.state.isFinished })
        val work = periodic.single { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertNotNull(work.periodicityInfo)
        assertTrue(await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME)).isNotEmpty())
        settings.enabled = false
        LumiflexCloudSyncWorker.initialize(context)
        val cancelled = await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.PERIODIC_NAME))
        assertTrue(cancelled.isNotEmpty())
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.CANCELLED, await(manager.getWorkInfoById(first.id))!!.state)
        assertTrue(settings.isSignedIn())
    }
    @Test
    fun explicitSyncReplacesWorkWhileStartupKeepsIt() {
        val context = RuntimeEnvironment.getApplication()
        val settings = LumiflexCloudSettings(context).apply {
            clear(); startSession("account", "token", "user"); enabled = true
        }
        val manager = WorkManager.getInstance(context)
        LumiflexCloudSyncWorker.initialize(context)
        val first = await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME))
            .single { !it.state.isFinished }
        LumiflexCloudSyncWorker.initialize(context, replace = true)
        val active = await(manager.getWorkInfosForUniqueWork(LumiflexCloudSyncWorker.WORK_NAME))
            .single { !it.state.isFinished }
        assertNotEquals(first.id, active.id)
        val replaced = await(manager.getWorkInfoById(first.id))
        assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
        settings.enabled = false
        LumiflexCloudSyncWorker.initialize(context)
    }

}
