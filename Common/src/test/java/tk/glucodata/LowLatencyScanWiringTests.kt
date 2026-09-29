package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-duty scanning is one driver's short, bounded request, never a default. SensorBluetooth and
 * SuperGattCallback need the Android runtime and the native library, so these read the source,
 * as ConnectModeLeverTests does; the Ottai gate itself is unit-tested in
 * OttaiActivationScanModeTests. Comments are stripped before flattening.
 */
class LowLatencyScanWiringTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    // Block comments first, then line comments, then whitespace: a pin matches code only, so
    // comment wording can neither satisfy nor break one. None of the files read here has "//" or
    // "/*" inside a string literal; if one ever does, code is cut and a pin fails, never passes.
    private fun read(path: String): String =
        File(repoRoot(), path).readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")

    private fun sensorBluetooth() = read("Common/src/main/java/tk/glucodata/SensorBluetooth.java")

    private fun section(source: String, start: String, end: String): String {
        val at = source.indexOf(start)
        assertTrue("missing: $start", at >= 0)
        val stop = source.indexOf(end, at + start.length)
        assertTrue("missing: $end", stop >= 0)
        return source.substring(at, stop)
    }

    @Test
    fun everyDriverDefaultsToTheLowPowerScan() {
        assertTrue(
            read("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
                .contains("public boolean wantsLowLatencyScan() { return false; }"),
        )
        val overriders = File(repoRoot(), "Common/src").walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            // src/test, src/testMobile and src/testWear: a test that names the method is not a driver.
            .filterNot { it.path.replace('\\', '/').contains("/Common/src/test") }
            .filter { it.name != "SuperGattCallback.java" && it.name != "SensorBluetooth.java" }
            .filter { it.readText().contains("wantsLowLatencyScan") }
            .map { it.name }
            .toSet()
        assertEquals(setOf("OttaiBleManager.kt"), overriders)
    }

    @Test
    fun ottaiAsksOnlyThroughTheBoundedGateWithoutItsLock() {
        val ottai = read("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        // The whole expression: the three volatile reads the bounded gate needs, and nothing else —
        // the next declaration follows at once, so no operand can be appended after the call.
        assertTrue(
            ottai.contains(
                "override fun wantsLowLatencyScan(): Boolean = !stop && wantsLowLatencyActivationScan( " +
                    "awaitingFreshActivationAdvertisement, activationDiscoveryStartedAtMs, " +
                    "System.currentTimeMillis(), ) override fun onScanResult(",
            ),
        )
        // Called under SensorBluetooth's lock; the Ottai monitor here would invert the lock order.
        assertFalse(ottai.contains("@Synchronized override fun wantsLowLatencyScan"))
    }

    @Test
    fun sharedScanPicksTheModePerStartAndCapsFullDuty() {
        val sb = sensorBluetooth()
        assertEquals(1, Regex("SCAN_MODE_LOW_LATENCY").findAll(sb).count())
        assertTrue(sb.contains("lowLatency ? mLowLatencyScanSettings : mScanSettings"))
        assertTrue(sb.contains("scanLowLatency ? lowlatencyscantimeout : scantimeout"))
        val cap = Regex("lowlatencyscantimeout = (\\d+);").find(sb)!!.groupValues[1].toInt()
        assertTrue("full duty must stay capped at the Ottai window", cap in 1..120_000)
        assertTrue(sb.contains("this.mScanning = false; scanLowLatency = false;"))
    }

    @Test
    fun modeWalkTakesNoLockAndFallsBackToTheDefault() {
        // No synchronized(gattcallbacks) or mygatts(): this runs under scanStarter's lock, often for a
        // driver holding its monitor, so the list's lock here would order the two locks against any
        // path that takes a driver's monitor under the list's lock. A list changed mid-walk answers
        // the default instead of throwing. An unfiltered scan never runs at full duty.
        assertTrue(sensorBluetooth().contains(
            "private static boolean lowLatencyScanWanted() { try { boolean wanted = false; " +
                "for (SuperGattCallback cb : gattcallbacks) { if (cb.getService() == null) return false; " +
                "if (cb.wantsLowLatencyScan()) wanted = true; } return wanted; } " +
                "catch (RuntimeException e) { Log.stack(LOG_ID, \"lowLatencyScanWanted\", e); return false; } }",
        ))
    }

    @Test
    fun startPicksFullDutyOnlyForTheFiltersItBuiltAndRecordsIt() {
        val sb = sensorBluetooth()
        assertTrue(sb.contains("final boolean lowLatency = mScanFilters != null && lowLatencyScanWanted();"))
        // Recorded once startScan has returned: a flag left false under a full-duty scan gives it the
        // 390 s timeout and makes every scanStarter call restart it.
        assertTrue(sb.contains(
            "this.mBluetoothLeScanner.startScan(mScanFilters, lowLatency ? mLowLatencyScanSettings : mScanSettings, mScanCallback); " +
                "} catch (Throwable e) { Log.stack(LOG_ID, e); if (Build.VERSION.SDK_INT > 30 && !Applic.mayscan()) " +
                "Applic.missingScanPermission(); return false; } scanLowLatency = lowLatency; return true;",
        ))
        assertEquals(1, Regex("scanLowLatency = lowLatency;").findAll(sb).count())
    }

    @Test
    fun aRunningScanIsRestartedOnlyToGoUpToFullDuty() {
        val sb = sensorBluetooth()
        assertTrue(sb.contains("if (mScanning && !scanLowLatency && lowLatencyScanWanted()) {"))
        // Down is the cap's job; a restart per flip could spend Android's five starts per 30 s.
        assertFalse(sb.contains("scanLowLatency != lowLatencyScanWanted()"))
    }

    @Test
    fun timeoutActsOnlyOnItsRunningScanUnderTheStarterLock() {
        // Unlocked, a timeout already running when scanStarter restarts the scan at full duty would
        // cancel that restart; stopScan(true) at the cap would leave a gap of a minute or more.
        assertTrue(sensorBluetooth().contains(
            "scantimeouttime = System.currentTimeMillis(); synchronized (SensorBluetooth.this) { " +
                "if (!mScanning) return; if (scanLowLatency) { SensorBluetooth.this.stopScan(false); " +
                "if (bluetoothIsEnabled()) SensorBluetooth.this.scanStarter(0L); return; } " +
                "SensorBluetooth.this.stopScan(true); } };",
        ))
    }

    @Test
    fun aStartPublishesItsTimeoutUnderTheStarterLock() {
        val sb = sensorBluetooth()
        assertTrue(sb.contains(
            "if (scanner.start()) { synchronized (SensorBluetooth.this) { mScanning = true; " +
                "final long timeout = scanLowLatency ? lowlatencyscantimeout : scantimeout; " +
                "if (scanOnUI) { Applic.app.getHandler().postDelayed(mScanTimeoutRunnable, timeout); } " +
                "else { timeoutFuture = Applic.scheduler.schedule(mScanTimeoutRunnable, timeout, TimeUnit.MILLISECONDS); } } ",
        ))
        assertEquals(1, Regex("mScanning = true;").findAll(sb).count())
    }

    @Test
    fun ottaiBoundsItsWaitBeforeAskingForTheScan() {
        val ottai = read("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        val body = section(ottai, "fun awaitFreshActivationAdvertisement(): Boolean {",
            "private fun abandonFreshActivationAdvertisement(")
        val remove = body.indexOf("handler.removeCallbacks(freshActivationAdvertisementTimeoutRunnable)")
        val post = body.indexOf("handler.postDelayed( freshActivationAdvertisementTimeoutRunnable, " +
            "FRESH_ACTIVATION_ADVERTISEMENT_TIMEOUT_MS, )")
        val restart = body.indexOf(
            "val blue = SensorBluetooth.blueone if (blue != null && SensorBluetooth.scanActiveOrPending()) { " +
                "Log.i(TAG, \"restarting managed scan for activation advertisement\") blue.stopScan(false) }",
        )
        val scan = body.indexOf("SensorBluetooth.blueone?.scanStarter(0L)")
        // A throw out of scanStarter must not leave the wait without the timeout that abandons it.
        assertTrue(remove >= 0 && post > remove)
        assertTrue("scanStarter must follow the posted timeout", scan > post)
        // A scan already flagged active is not delivering to a callback added after it started.
        // scanStarter skips that case; stopping first is what lets this wait hear an advertisement.
        assertTrue("activation wait must restart a scan that is already flagged active", restart > post && scan > restart)
    }

    @Test
    fun ottaiSetupScanYieldsTheRadioBeforeConnect() {
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
        assertTrue(wizard.contains(
            "if (SensorBluetooth.gattcallbacks.isNotEmpty() && !SensorBluetooth.scanActiveOrPending()) { " +
                "SensorBluetooth.blueone?.scanStarter(0L) }",
        ))
        val scanEffect = section(
            wizard,
            "DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, restartKey)",
            "LaunchedEffect(scanPermissionGranted",
        )
        assertFalse(scanEffect.substring(scanEffect.lastIndexOf("onDispose {")).contains("scanStarter"))
        assertTrue(wizard.contains(
            "DisposableEffect(Unit) { onDispose { " +
                "if (SensorBluetooth.gattcallbacks.isNotEmpty() && !SensorBluetooth.scanActiveOrPending()) { " +
                "SensorBluetooth.blueone?.scanStarter(0L) } } }",
        ))
    }
}
