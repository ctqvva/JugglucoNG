package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the connect-mode lever and the measurement that would justify ever pulling it.
 *
 * SuperGattCallback.autoconnect is a single application-wide static that the SensorBluetooth
 * constructor overwrites from Natives.getAndroid13(); while the connectGatt call sites read it
 * directly no driver could choose its own mode, and the 2026-08-01 jamming storm accordingly
 * logged 103 connect attempts (52 + 51, two Ottai sensors) at autoConnect=true and none at false.
 * useAutoConnect() makes a per-driver choice expressible, and the 2026-09-09 CT5 trace is the
 * first measurement that decides it for one peripheral: after a lowPower frame the transmitter is
 * only connectable near its 3-minute push, and three direct connects in a row reported their first
 * callback at 30016ms, 30027ms and 30038ms — Android's connect timer expiring, status 147 — for 96
 * seconds of dead air. That is the number noteFirstGattCallback exists to collect, so the Anytime
 * driver may answer it; every other driver still has nothing in the dataset deciding between the
 * modes (a modelled 1071s saved to 280s lost) and keeps inheriting the application-wide setting.
 * These are source scans because the classes involved need the Android runtime and the native
 * library to be constructed at all.
 */
class ConnectModeLeverTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun superGattCallback(): String =
        File(repoRoot(), "Common/src/main/java/tk/glucodata/SuperGattCallback.java").readText()

    private fun sources(vararg roots: String): List<File> =
        roots.flatMap { root ->
            File(repoRoot(), root).walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .toList()
        }

    @Test
    fun everyConnectGattCallSiteAsksTheDriver() {
        val text = superGattCallback().replace(Regex("\\s+"), " ")
        val callSites = Regex("connectGatt\\(Applic\\.app, ([A-Za-z.()]+),").findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "SuperGattCallback must keep exactly the three connectGatt call sites",
            3,
            callSites.size,
        )
        assertTrue(
            "every connectGatt call site must read the connect mode through useAutoConnect(), not " +
                "the application-wide static: $callSites",
            callSites.all { it == "cb.useAutoConnect()" },
        )
    }

    @Test
    fun noUnmeasuredDriverOverridesTheDefault() {
        // R2(a) is a lever, not a change of behaviour: an override appearing here means some
        // driver now connects in a mode the 2026-08-01 dataset never measured. The Anytime
        // files are listed because the 2026-09-09 CT5 trace did measure it — see the next test
        // for what that override is still held to.
        val measured = setOf(
            "AnytimeBleManager.kt",
            "AnytimeConnectRetryPolicy.kt",
            "AnytimeConnectRetryPolicyTests.kt",
        )
        val overriders = sources("Common/src")
            .filter { it.name != "SuperGattCallback.java" && it.name != "ConnectModeLeverTests.kt" }
            .filter { it.readText().contains("useAutoConnect") }
            .map { it.name }
        assertTrue(
            "no driver may override useAutoConnect() without field measurements of the other " +
                "mode; found ${overriders - measured}",
            (overriders - measured).isEmpty(),
        )
    }

    @Test
    fun theMeasuredOverrideStaysGatedOnAnObservedTimeout() {
        // The measurement licenses reacting to a direct connect that demonstrably failed, not
        // switching modes generally: a session that connects keeps the user's setting.
        val text = File(
            repoRoot(),
            "Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeBleManager.kt",
        ).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            "the Anytime override must start from the application-wide setting and go " +
                "through the measured-timeout policy",
            text.contains(
                "override fun useAutoConnect(): Boolean = " +
                    "connectMode.useAutoConnect(super.useAutoConnect())"
            ),
        )
        val policy = File(
            repoRoot(),
            "Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeConnectRetryPolicy.kt",
        ).readText()
        assertEquals(
            "the mode may only be changed where an observed timeout and a successful " +
                "direct connect decide it; AnytimeConnectModeState is that one place",
            2,
            Regex("directConnectUnreachable = (true|false)").findAll(policy).count(),
        )
    }

    @Test
    fun everyGattDriverRecordsItsFirstCallbackLatency() {
        // The base class cannot funnel this: onConnectionStateChange is in practice the first
        // callback of an attempt and no driver except AiDex chains to super, so a driver that
        // declares it and forgets the call contributes nothing to the comparison.
        // Every source set, not src/main alone: Libre3 and Si are the app's primary sensors and
        // both live in flavour source sets, so a src/main scan stayed green while the measurement
        // was absent for most users. GlucoseMeterGatt is a plain BluetoothGattCallback and has no
        // connectGatt of ours to time, hence the subclass filter.
        val missing = sources("Common/src")
            .filterNot { it.path.replace('\\', '/').contains("/Common/src/test/") }
            .filter { it.name != "SuperGattCallback.java" }
            .filter { file ->
                val text = file.readText()
                (text.contains("extends SuperGattCallback") || text.contains(": SuperGattCallback("))
                    && (text.contains("override fun onConnectionStateChange(")
                        || text.contains("public void onConnectionStateChange("))
                    && !text.contains("noteFirstGattCallback(")
            }
            .map { it.name }
        assertTrue(
            "every SuperGattCallback subclass must report how long connectGatt took to produce " +
                "its first callback; missing in $missing",
            missing.isEmpty(),
        )
    }
}
