package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SensorBluetooth needs JNI and a Context, so its leftover wiring cannot run in a unit test. These
 * checks read the source instead, the way ProguardKeepRulesTests guards the keep rules. The
 * decision itself (row first, then the live callback) is a pure function unit-tested in
 * AiDexManagedSensorIdentityAdapterTests; what is pinned here is that SensorBluetooth feeds it the
 * stored row and that the shared loops go through it.
 *
 * The regression behind them: shared SensorBluetooth loops once asked a shape-only AiDex predicate
 * about every driver's ids. Ottai keys sensors by the bare 12-hex BLE MAC, so its sensors were
 * skipped at restore and torn down on every device sync — a constant reconnect loop.
 */
class SensorBluetoothLeftoverWiringTests {

    private val code: String by lazy { blankCommentsAndLiterals(readSource()) }

    private fun readSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "src/main/java/tk/glucodata/SensorBluetooth.java"
        while (dir != null) {
            listOf(File(dir, "Common/$relative"), File(dir, relative))
                .firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("SensorBluetooth.java not found from ${System.getProperty("user.dir")}")
    }

    /**
     * Comments become spaces and the contents of string and char literals become spaces, in one
     * left-to-right pass, so neither a commented-out call nor text inside a literal can satisfy or
     * break a check, and braces inside literals do not unbalance a body.
     */
    private fun blankCommentsAndLiterals(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else Char(0)
            when {
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && next == '*' -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    while (i < end) { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                }
                c == '"' || c == '\'' -> {
                    out.append(c); i++
                    while (i < src.length && src[i] != c && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Body of the method declared by [signature], by brace matching. Whitespace is collapsed and
     * dropped around dots, so a call split over lines reads as one expression.
     */
    private fun body(signature: String): String {
        val at = code.indexOf(signature)
        assertTrue("'$signature' not found - check this test", at >= 0)
        val open = code.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        return code.substring(open, i + 1)
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ?\\. ?"), ".")
    }

    @Test
    fun theLeftoverHelperFeedsTheStoredRowVerdictAndScansOnlyInsideTheFallback() {
        val b = body("private static boolean isAiDexMacFallbackLeftover(Context context, String sensorId)")
        val rowRead = "final Boolean persisted = context == null ? null : " +
            "tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE.persistedLeftoverVerdict(context, sensorId);"
        val decision = "return tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.INSTANCE.leftoverVerdict(persisted, () -> {"
        assertTrue("the helper no longer reads the stored row verdict as-is", b.contains(rowRead))
        assertEquals("the row verdict must not be reassigned or filtered before it decides", 1, b.split("persisted =").size - 1)
        val call = b.indexOf(decision)
        assertTrue("the helper no longer hands the row verdict to leftoverVerdict with a lazy live check", call > b.indexOf(rowRead))
        val scan = b.indexOf("synchronized (gattcallbacks)")
        assertTrue("the gattcallbacks scan must run only inside the live-check lambda", scan > call)
        assertEquals("one scan only", scan, b.lastIndexOf("synchronized (gattcallbacks)"))
    }

    @Test
    fun sharedLoopsAskOnlyTheOwnershipHelper_neverTheRawPredicate() {
        for (signature in listOf(
            "private void setDevices(String[] names)",
            "void addPersistedManagedCallbacks()",
            "boolean updateDevicers()",
        )) {
            val b = body(signature)
            assertFalse("$signature calls isMacFallbackSerial directly; use isAiDexMacFallbackLeftover", b.contains("isMacFallbackSerial("))
            assertTrue("$signature no longer checks for AiDex leftovers - check this test", b.contains("isAiDexMacFallbackLeftover("))
        }
    }

    @Test
    fun addAiDexSensor_sortsLeftoverOwnersRowFirst() {
        val b = body("public static boolean addAiDexSensor(Context context, String name, String address)")
        val add = b.indexOf("leftoverOwners.add(cb)")
        assertTrue("leftoverOwners.add(cb) not found - check this test", add >= 0)
        assertEquals(
            "leftover owners must be exactly the callbacks the row-first helper calls leftovers",
            "if (isAiDexMacFallbackLeftover(context, cb.SerialNumber)) { ",
            b.substring(b.lastIndexOf("if (", add), add),
        )
    }

    @Test
    fun updateDevicers_decidesEachVictimOnceAndDropsIt() {
        val b = body("boolean updateDevicers()")
        val loop = b.indexOf("for (int el = rem.size() - 1")
        val after = b.indexOf("int index = gattcallbacks.size()", loop)
        assertTrue("victim loop not found - check this test", loop >= 0 && after > loop)
        val classify = b.indexOf(
            "if (isAiDexMacFallbackLeftover(Applic.app, was)) { rem.add(gatt); leftoverVictims.add(gatt); " +
                "if (was != null) { droppedLeftovers.add(was); } continue; }"
        )
        assertTrue("the first loop must record the leftover victim and its id when it classifies it", classify in 0 until loop)
        val victimLoop = b.substring(loop, after)
        assertFalse(
            "the victim loop asks again; a concurrent drop can flip that answer and re-create the leftover",
            victimLoop.contains("isAiDexMacFallbackLeftover("),
        )
        assertTrue(victimLoop.contains("final boolean leftover = leftoverVictims.contains(victim);"))
        // Recorded in the loop, dropped after the list's lock with the other post-lock steps (the drop
        // family is banned under that lock by name, since its two-argument form frees a live callback;
        // see GattMonitorOrderTests).
        assertTrue(
            "a leftover victim must have its row and native record dropped, not only its callback freed",
            victimLoop.contains("if (leftover) { removedLeftovers.add(removedSerial); }") &&
                b.substring(after).contains(
                    "for (String serial : removedLeftovers) { try { dropAiDexLeftoverPersistAndNative(Applic.app, serial, false); }"
                ),
        )
    }
}
