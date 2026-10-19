package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup panels and a delete-with-unbind hand the one BLE scanner back and forth. These paths
 * need the Android runtime, so the tests read the source, as ConnectModeLeverTests does.
 * Comments are stripped before flattening, so a pin matches code only.
 */
class SetupScanHandoffWiringTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun read(path: String): String =
        File(repoRoot(), path).readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")

    private fun section(source: String, start: String, end: String): String {
        val at = source.indexOf(start)
        assertTrue("missing: $start", at >= 0)
        val stop = source.indexOf(end, at + start.length)
        assertTrue("missing: $end", stop >= 0)
        return source.substring(at, stop)
    }

    @Test
    fun ottaiActivationWaitRestartsAScanThatIsAlreadyFlaggedActive() {
        val body = section(
            read("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt"),
            "fun awaitFreshActivationAdvertisement(): Boolean {",
            "UiRefreshBus.requestStatusRefresh()",
        )
        val post = body.indexOf("handler.postDelayed( freshActivationAdvertisementTimeoutRunnable, " +
            "FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS, )")
        val restart = body.indexOf(
            "val blue = SensorBluetooth.blueone if (blue != null && SensorBluetooth.scanActiveOrPending()) { " +
                "Log.i(TAG, \"restarting managed scan for activation advertisement\") blue.stopScan(false) }",
        )
        val starter = "SensorBluetooth.blueone?.scanStarter(0L)"
        val scan = body.indexOf(starter)
        // A throw out of scanStarter must not leave the wait without the timeout that abandons it.
        assertTrue(post >= 0 && restart > post && scan > restart)
        assertTrue("one start only", body.indexOf("scanStarter(0L)", scan + starter.length) < 0)
    }

    @Test
    fun ottaiSetupScanYieldsTheRadioAndHandsItBackOnLeave() {
        val wizard = read("Common/src/mobile/java/tk/glucodata/ui/setup/OttaiSetupWizard.kt")
        assertTrue(wizard.contains(
            "if (SensorBluetooth.scanActiveOrPending()) { " +
                "Log.i(OTTAI_SCAN_LOG, \"stopping managed scan before the setup scanner\") " +
                "SensorBluetooth.blueone?.stopScan(false) }",
        ))
        val connect = wizard.indexOf("private fun connectOttaiSensor(")
        val stop = wizard.indexOf("OttaiSetupScanHold.stopPanelScans()", connect)
        val add = wizard.indexOf("OttaiRegistry.addSensorForUserConnect(", connect)
        assertTrue(connect >= 0 && stop > connect && add > stop)
        val scanEffect = section(
            wizard,
            "DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, restartKey)",
            "LaunchedEffect(scanPermissionGranted",
        )
        // Connect arms the managed scan before the panel leaves. Only leaving hands the radio back.
        assertFalse(scanEffect.substring(scanEffect.lastIndexOf("onDispose {")).contains("scanStarter"))
        assertTrue(wizard.contains(
            "DisposableEffect(Unit) { onDispose { " +
                "scanner.stopScan() OttaiSetupScanHold.untrack(scanner) " +
                "if (SensorBluetooth.gattcallbacks.isNotEmpty() && !SensorBluetooth.scanActiveOrPending()) { " +
                "SensorBluetooth.blueone?.scanStarter(0L) } } }",
        ))
    }

    @Test
    fun aidexRemovalUnpairDoesNotStartTheBroadcastScan() {
        val manager = read("Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt")
        assertTrue(manager.contains(
            "val startPostUnpairScan = synchronized(postUnpairScanLock) { " +
                "reconnect.isBroadcastOnlyMode = true !postUnpairBroadcastScanSuppressed } " +
                "UiRefreshBus.requestStatusRefresh() " +
                "if (startPostUnpairScan) { handler.post { startBroadcastScan(\"post-unpair\") } } else { " +
                "Log.i(TAG, \"post-unpair broadcast scan suppressed — sensor is being removed\") }",
        ))
        assertTrue(manager.contains(
            "private fun startBroadcastScan(reason: String, continuous: Boolean = shouldContinueBroadcastScanning()) { " +
                "if (postUnpairBroadcastScanSuppressed) return",
        ))
        assertTrue(manager.contains(
            "val cmd = commandBuilder.deleteBond() ?: run { clearPostUnpairSuppressionUnlessBroadcastOnly()",
        ))
        assertTrue(manager.contains(
            "synchronized(postUnpairScanLock) { if (!reconnect.isBroadcastOnlyMode) { postUnpairBroadcastScanSuppressed = false } }",
        ))
        assertTrue(manager.contains(
            "val startNow = synchronized(postUnpairScanLock) { " +
                "if (!postUnpairBroadcastScanSuppressed) return@synchronized false " +
                "postUnpairBroadcastScanSuppressed = false " +
                "reconnect.isBroadcastOnlyMode } " +
                "if (!startNow) return handler.post { " +
                "if (postUnpairBroadcastScanSuppressed || !broadcastOnlyConnection) return@post " +
                "startBroadcastScan(\"delete-unbind-cancelled\") }",
        ))
    }

    @Test
    fun aidexDeleteWithUnbindSuppressesFirstAndReleasesWhenCancelled() {
        val vm = read("Common/src/mobile/java/tk/glucodata/ui/viewmodel/SensorViewModel.kt")
        val disconnect = vm.substring(vm.indexOf("fun disconnectAiDexSensor("))
        val suppress = disconnect.indexOf("gatt.suppressPostUnpairBroadcastScan()")
        val unpair = disconnect.indexOf("gatt.unpairSensor()")
        // The first terminateSensor is the plain disconnect without unpair.
        val terminate = disconnect.lastIndexOf("terminateSensor(serial)")
        val release = disconnect.indexOf("if (!removed) gatt.releasePostUnpairBroadcastScanSuppression()")
        assertTrue(suppress in 0 until unpair)
        assertTrue(terminate in unpair until release)
        // Cancellation, not a finished removal, is what releases the flag.
        assertTrue(disconnect.contains(
            "gatt.suppressPostUnpairBroadcastScan() var removed = false try {",
        ))
        assertTrue(disconnect.contains(
            "terminateSensor(serial) } removed = true } finally { " +
                "if (!removed) gatt.releasePostUnpairBroadcastScanSuppression() }",
        ))
        // A rejected unpair is still followed by that removal, so the ACK keeps the flag.
        val manager = read("Common/src/main/java/tk/glucodata/drivers/aidex/native/ble/AiDexBleManager.kt")
        assertTrue(manager.contains("} else if (pendingUnpairDisconnect) { pendingUnpairDisconnect = false isUnpaired = false"))
    }
}
