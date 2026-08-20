package tk.glucodata

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the keep rules whose loss is silent.
 *
 * Twice now a missing rule has disabled a whole mechanism in release builds with no crash and no
 * log line: 713631f9 (HistoryRepository lookup by name -> "no rows" -> the Ottai driver re-pulled
 * the sensor's entire history on every reconnect) and the hidden onConnectionUpdated callback,
 * which R8 deleted outright so that a 4h jamming storm produced 210 framework invocations and
 * zero app log lines. Neither shows up in a debug build, and nothing else in the tree checks that
 * the rules survive an edit of proguard-rules.my.
 */
class ProguardKeepRulesTests {

    private fun proguardRulesMy(): File {
        // Gradle runs unit tests with the module directory as the working directory, but walk up
        // anyway so the test does not depend on that.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "Common/proguard-rules.my")
            if (candidate.isFile) return candidate
            val here = File(dir, "proguard-rules.my")
            if (here.isFile) return here
            dir = dir.parentFile
        }
        throw AssertionError("proguard-rules.my not found from ${System.getProperty("user.dir")}")
    }

    private fun rulesText(): String = proguardRulesMy().readText()

    /**
     * Comment lines stripped before matching: the premise of this class is that a rule which does
     * not apply is as good as no rule, and a rule pasted into a `#` line is exactly that.
     */
    private fun activeRules(): String =
        rulesText().lines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")

    @Test
    fun theHiddenConnectionParamsCallbackIsKept() {
        val text = activeRules()
        assertTrue(
            "proguard-rules.my must keep BluetoothGattCallback#onConnectionUpdated; without it R8 " +
                "deletes SuperGattCallback's declaration and the driver never sees a link-parameter " +
                "update again",
            text.contains(
                "-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback { " +
                    "public void onConnectionUpdated(android.bluetooth.BluetoothGatt, int, int, int, int); }",
            ),
        )
    }

    @Test
    fun theHistoryRepositoryLookupNamesAreKept() {
        // The 713631f9 case: this one method was the omission, and its absence cost a full history
        // re-pull per reconnect.
        val text = activeRules()
        assertTrue(text.contains("-keepnames class tk.glucodata.data.HistoryRepository"))
        assertTrue(text.contains("getHistoryTimestampsForSensorBlocking"))
    }

    @Test
    fun theLibreviewJournalBridgeIsKept() {
        // Two name-based hops with nothing R8 can see: journalentries.cpp does FindClass on
        // LibreviewJournal, and LibreviewJournal does Class.forName on LibreviewJournalEntries.
        // Losing either sends LibreView an empty food/insulin array and says nothing.
        val text = activeRules()
        assertTrue(text.contains("-keep class tk.glucodata.LibreviewJournal { *; }"))
        assertTrue(text.contains("-keepnames class tk.glucodata.data.journal.LibreviewJournalEntries"))
        listOf("prepare(boolean)", "foodEntries()", "insulinEntries()", "noteEntries()", "commit()", "discard()")
            .forEach { assertTrue("proguard-rules.my must keep $it", text.contains(it)) }
    }

    @Test
    fun theKeptCallbackIsStillDeclaredWithThatExactSignature() {
        // A rule that no longer matches the declaration is as good as no rule, and R8 does not
        // warn about a keep rule matching nothing.
        var dir: File? = proguardRulesMy().parentFile
        var source: File? = null
        while (dir != null && source == null) {
            val candidate = File(dir, "src/main/java/tk/glucodata/SuperGattCallback.java")
            if (candidate.isFile) source = candidate
            dir = dir.parentFile
        }
        assertNotNull("SuperGattCallback.java not found next to proguard-rules.my", source)
        val text = source!!.readText().replace(Regex("\\s+"), " ")
        assertTrue(
            "SuperGattCallback must declare onConnectionUpdated(BluetoothGatt,int,int,int,int) " +
                "with @Keep for runtime dispatch to bind it",
            text.contains(
                "@Keep public void onConnectionUpdated(BluetoothGatt gatt, int interval, " +
                    "int latency, int timeout, int status)",
            ),
        )
    }
}
