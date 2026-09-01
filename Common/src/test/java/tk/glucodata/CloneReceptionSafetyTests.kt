package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the receiver's hard-off boundary across Kotlin and native ICE.
 *
 * These are source checks because the native mirror, Android preferences, and
 * sensor callback roster cannot be constructed in a local JVM test. Each check
 * names a separate layer deliberately: losing any one reopens the race where a
 * late authenticated packet restores a Clone sensor after the user turned the
 * receiver off.
 */
class CloneReceptionSafetyTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    @Test
    fun disablingCloneClearsPersistedAndTransientReceiverState() {
        val registry = source("Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt")
            .replace(Regex("\\s+"), " ")

        assertTrue(registry.contains("remove(KEY_SENSOR_IDS)"))
        assertTrue(registry.contains("remove(KEY_SENSOR_CONNECTIONS)"))
        assertTrue(registry.contains("CloneIobSnapshot.clear()"))
        assertTrue(registry.contains("getBoolean(KEY_RECEPTION_ENABLED, true)"))
        assertTrue(registry.contains("fun markCloneSensor(") && registry.contains("): Boolean"))
    }

    @Test
    fun disabledIceCannotReconnectOrInterpretCommands() {
        val ice = source("Common/src/main/cpp/net/ICE/ICE.cpp")
            .replace(Regex("\\s+"), " ")
        val commands = source("Common/src/main/cpp/net/getcommand.cpp")
            .replace(Regex("\\s+"), " ")
        val host = source("Common/src/main/cpp/datbackup.hpp")
            .replace(Regex("\\s+"), " ")

        assertTrue(ice.contains("if(host.deactivated)"))
        assertTrue(ice.contains("if (!host.ICE || host.deactivated)"))
        assertTrue(commands.contains("std::lock_guard<std::mutex> commandLock(receiverCommandsMutex)"))
        assertTrue(commands.contains("host->deactivated || !receiverCommandsEnabled.load"))
        val closeGate = host.indexOf("connection->setReceiverCommandsEnabled(false)")
        val quiesce = host.indexOf("connection->waitForReceiverCommandsIdle()")
        assertTrue(closeGate >= 0 && quiesce > closeGate)
        assertTrue(host.contains("connection->endConnection()"))
        assertTrue(host.contains("if (host.ICE) startReceiverThread(index)"))
        assertTrue(ice.contains("startReceiverThread(%d): already running"))
        assertTrue(ice.contains("con->wakeReceiver=true"))
    }

    @Test
    fun lateJniSensorSyncRequiresTheLocalReceptionGate() {
        val bridge = source("Common/src/main/cpp/curve/javacurve.cpp")
            .replace(Regex("\\s+"), " ")
        val access = source("Common/src/main/java/tk/glucodata/HistorySyncAccess.kt")
            .replace(Regex("\\s+"), " ")

        assertTrue(bridge.contains("(Ljava/lang/String;ILjava/lang/String;)Z"))
        assertTrue(bridge.contains("if (!accepted)"))
        assertTrue(access.contains("CloneSensorRegistry.whileReceptionEnabled"))
    }

    @Test
    fun cloneRetirementAndRosterRebuildShareOneMonitor() {
        val bluetooth = source("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
            .replace(Regex("\\s+"), " ")
        val retireStart = bluetooth.indexOf("public static void retireCloneSensor")
        val rosterStart = bluetooth.indexOf("boolean updateDevicers()")
        assertTrue(retireStart >= 0 && rosterStart >= 0)
        assertTrue(bluetooth.substring(retireStart).contains("synchronized (gattcallbacks)"))
        val roster = bluetooth.substring(rosterStart)
        val rosterLock = roster.indexOf("synchronized (gattcallbacks)")
        val activeSnapshot = roster.indexOf("Natives.activeSensors()")
        assertTrue(rosterLock >= 0 && activeSnapshot > rosterLock)
    }

    @Test
    fun reconcileAndIobImportCommitUnderTheReceiverGate() {
        val registry = source("Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt")
            .replace(Regex("\\s+"), " ")
        val access = source("Common/src/main/java/tk/glucodata/HistorySyncAccess.kt")
            .replace(Regex("\\s+"), " ")
        val snapshots = source("Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt")
            .replace(Regex("\\s+"), " ")

        val reconcile = registry.substring(registry.indexOf("fun reconcilePrimaryCloneSensor"))
        assertTrue(reconcile.contains("whileReceptionEnabled"))
        assertTrue(access.contains("return CloneSensorRegistry.whileReceptionEnabled"))

        val importStart = snapshots.indexOf("fun importCloneIobSnapshot")
        val importEnd = snapshots.indexOf("fun importFromJson", importStart)
        assertTrue(importStart >= 0 && importEnd > importStart)
        val importBody = snapshots.substring(importStart, importEnd)
        assertTrue(importBody.contains("CloneIobSnapshot.update(remote)"))
        assertTrue(importBody.contains("scheduleCloneIobRefresh(remote.timestampMillis)"))
        assertFalse(importBody.contains("broadcastIobSnapshot("))
        assertFalse(importBody.contains("JournalIobAccess.pushWatchserver("))
        assertFalse(importBody.contains("Notify.showoldglucose()"))
        assertTrue(snapshots.contains("cloneIobRefreshJob = journalChangedScope.launch"))

        val journalImport = snapshots.substring(
            snapshots.indexOf("fun importCloneJournalSnapshot"),
            snapshots.indexOf("fun importFromJson"),
        )
        assertTrue(journalImport.contains("cloneJournalImportMutex.withLock"))
        assertTrue(snapshots.contains("CloneSensorRegistry.whileReceptionEnabled"))
        val nativeSender = source("Common/src/main/cpp/datbackup.cpp")
        assertTrue(nativeSender.contains("senderConnectionGeneration()"))
        assertFalse(nativeSender.contains("lastConnectionIdentity"))
        val commandLine = source("Common/src/main/cpp/cmdline/main.cpp")
        assertTrue(commandLine.contains("javaExportCloneJournalSnapshot()"))
        assertTrue(commandLine.contains("javaImportCloneJournalSnapshot(const char *, int)"))

    }

    @Test
    fun cloneDisableClosesTheNativeGateBeforeWorkerTeardown() {
        val screen = source("Common/src/mobile/java/tk/glucodata/ui/MirrorSettingsScreen.kt")
            .replace(Regex("\\s+"), " ")

        val prepare = screen.indexOf("Natives::prepareHostDeactivation")
        val worker = screen.indexOf("executor.execute")
        assertTrue(prepare >= 0 && worker > prepare)
        assertTrue(screen.contains("Natives.setHostDeactivated(index, deactivated)"))
        assertTrue(screen.contains("enabled = cloneConnections.isNotEmpty() && !CloneHostTransitionRunner.isRunning()"))
    }

    @Test
    fun turnRetransmissionNeverEntersLibjuiceWithTheAckMutexHeld() {
        val transport = source("Common/src/main/cpp/net/ICE/ICE_data.cpp")
            .replace(Regex("\\s+"), " ")

        assertTrue(transport.contains("std::lock_guard<std::mutex> sendLock(sendMutex)"))
        assertTrue(transport.contains("lck.unlock(); rel_msec= sendpacket"))
        assertTrue(transport.contains("rel_msec= sendpacket(agent, trans_id,data,len, index, starttime2); lck.lock();"))
        assertTrue(transport.contains("lck.unlock(); con->endConnection();"))
    }

    @Test
    fun rendezvousRequestsAreBoundedAndCancelledWithTheirIceGeneration() {
        val https = source("Common/src/main/cpp/net/ICE/ContextHTTPS.cpp")
            .replace(Regex("\\s+"), " ")
        val ice = source("Common/src/main/cpp/net/ICE/ICE.cpp")
            .replace(Regex("\\s+"), " ")
        val connection = source("Common/src/main/cpp/net/ICE/ICEConnect.hpp")
            .replace(Regex("\\s+"), " ")
        val retryWait = ice.substring(
            ice.indexOf("static bool waitForCurrentAgent"),
            ice.indexOf("static HTTPSRequestOptions")
        )

        assertTrue(https.contains("O_NONBLOCK"))
        assertTrue(https.contains("deadline.pollMilliseconds()"))
        assertTrue(https.contains("cancelled->load(std::memory_order_acquire)"))
        assertTrue(https.contains("elapsedRealtimeMilliseconds()"))
        assertTrue(ice.contains(".timeoutMilliseconds=10000"))
        assertTrue(ice.contains("waitForCurrentAgent(con,agent,5)"))
        assertTrue(retryWait.contains("std::min<int64_t>(remaining,250)"))
        assertTrue(!retryWait.contains("sleep(1)"))
        assertTrue(connection.contains("cancelRendezvous();"))
    }

    @Test
    fun rejectedRendezvousGenerationRestartsWithoutWaitingForPacTimeout() {
        val ice = source("Common/src/main/cpp/net/ICE/ICE.cpp")
            .replace(Regex("\\s+"), " ")
        val connection = source("Common/src/main/cpp/net/ICE/ICEConnect.hpp")
            .replace(Regex("\\s+"), " ")

        assertTrue(ice.contains("restartRejectedNegotiation(con,agent,generation,allindex"))
        assertTrue(ice.contains("local candidate rejected"))
        assertTrue(ice.contains("remote candidate stream unavailable"))
        assertTrue(ice.contains("generation rejected"))
        assertTrue(ice.contains("if(con->endConnect.load()||!stillworking(allindex))"))
        assertTrue(connection.contains("requestReconnectIfCurrent"))
        assertTrue(connection.contains("endConnect.compare_exchange_strong(expected,true)"))
    }

    @Test
    fun backgroundLivenessIsOptionalAndReceiverScoped() {
        val policy = source("Common/src/main/java/tk/glucodata/CloneBackgroundLiveness.kt")
            .replace(Regex("\\s+"), " ")
        val registry = source("Common/src/main/java/tk/glucodata/CloneSensorRegistry.kt")
            .replace(Regex("\\s+"), " ")
        val service = source("Common/src/main/java/tk/glucodata/keeprunning.java")
            .replace(Regex("\\s+"), " ")

        assertTrue(policy.contains("getBoolean(KEY_ENABLED, false)"))
        assertTrue(policy.contains("enabled && receptionEnabled && hasReceiver"))
        assertTrue(policy.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(policy.contains("(Natives.getbackuphostreceive(index) and 2) != 0"))
        assertTrue(registry.contains("CloneBackgroundLiveness.sync()"))
        assertTrue(service.contains("CloneBackgroundLiveness.sync();"))
        assertTrue(service.contains("CloneBackgroundLiveness.release();"))

        assertFalse(CloneBackgroundLiveness.shouldHold(false, receptionEnabled = true, hasReceiver = true))
        assertFalse(CloneBackgroundLiveness.shouldHold(true, receptionEnabled = false, hasReceiver = true))
        assertFalse(CloneBackgroundLiveness.shouldHold(true, receptionEnabled = true, hasReceiver = false))
        assertTrue(CloneBackgroundLiveness.shouldHold(true, receptionEnabled = true, hasReceiver = true))
    }

    @Test
    fun hybridQrAppliesItsIceServicesBeforeStartingTheConnection() {
        val generator = source("Common/src/main/cpp/hostJson.cpp")
            .replace(Regex("\\s+"), " ")
        val screen = source("Common/src/mobile/java/tk/glucodata/ui/MirrorSettingsScreen.kt")
            .replace(Regex("\\s+"), " ")
        val startup = source("Common/src/main/java/tk/glucodata/Applic.java")
            .replace(Regex("\\s+"), " ")
        val configStore = source("Common/src/main/java/tk/glucodata/CloneIceNetworkConfig.kt")
            .replace(Regex("\\s+"), " ")
        val ice = source("Common/src/main/cpp/net/ICE/ICE.cpp")
            .replace(Regex("\\s+"), " ")

        assertTrue(generator.contains("const auto iceConfig=currentICEConfig()"))
        assertTrue(generator.contains("insertbool(inserter,\"stun\""))
        assertTrue(generator.contains("insertbool(inserter,\"cv\",false)"))
        assertTrue(generator.contains("R\"(,\"rv\":"))

        val saveConfig = screen.indexOf("CloneIceNetworkConfigStore.save")
        val createHost = screen.indexOf("val pos = Natives.changebackuphost")
        assertTrue(saveConfig >= 0 && createHost > saveConfig)
        assertTrue(screen.contains("rollbackNetworkConfiguration()"))

        val prepareConfig = startup.indexOf("CloneIceNetworkConfigStore.prepareForNativeStartup(this)")
        val loadNative = startup.indexOf("numio.setlibrary(this)")
        val validateConfig = startup.indexOf("CloneIceNetworkConfigStore.initialize(this)")
        assertTrue(prepareConfig >= 0 && loadNative > prepareConfig && validateConfig > loadNative)
        val prepareBody = configStore.substring(
            configStore.indexOf("fun prepareForNativeStartup"),
            configStore.indexOf("fun initialize"),
        )
        assertTrue(prepareBody.contains("applyToNative(load(context))"))
        assertFalse(prepareBody.contains("TurnServerNR"))
        assertTrue(ice.contains("const auto networkConfig=currentICEConfig()"))
        assertTrue(ice.contains("networkConfig.useTurnForStun&&has_configured_turn"))
        assertTrue(ice.contains("hostname,rendezvousPort"))
    }

}
