package tk.glucodata.drivers.ottai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native poll geometry is fixed when a shell is first created, and every native call on an
 * unknown id creates one. setSensorWearDays and the stream writes create it at the 15-day
 * default, so each must be preceded by the sized create, or a 28-30 day Ottai stops reaching
 * Nightscout on day 15 (2026-09-22: a 28-day sensor's shell created at 21600 records). The size itself is
 * pure and runs here; JNI cannot, so the call order is pinned in the source, like
 * ManagedSensorFamilyTests.
 */
class OttaiNativeShellCapacityTests {
    private val day = 86_400_000L

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun source(path: String) = File(repoRoot(), path).readText()

    private val manager by lazy {
        source("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
    }

    // Comments out, all whitespace out: pins match code only.
    private fun flat(code: String) = code
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")
        .replace(Regex("\\s+"), "")

    private fun functionBody(name: String): String {
        val start = manager.indexOf("private fun $name(")
        assertTrue("$name not found", start >= 0)
        val end = Regex("\\r?\\n    }\\r?\\n").find(manager, start)?.range?.first ?: -1
        assertTrue("end of $name not found", end > start)
        return manager.substring(start, end)
    }

    @Test
    fun shellSizeCoversTheLongestKnownLifetimePlusADay() {
        assertEquals("unknown lifetime: 30 d floor + 1 d", 31 * 1440, OttaiConstants.nativeShellRecords(0L, 0L))
        assertEquals(31 * 1440, OttaiConstants.nativeShellRecords(28 * day, 0L))
        assertEquals(31 * 1440, OttaiConstants.nativeShellRecords(30 * day, 0L))
        assertEquals(31 * 1440, OttaiConstants.nativeShellRecords(15 * day + 1_800_000L, 15 * day + 1_800_000L))
        assertEquals("a part day rounds up", 32 * 1440, OttaiConstants.nativeShellRecords(30 * day + 60_000L, 0L))
        assertEquals(46 * 1440, OttaiConstants.nativeShellRecords(45 * day, 0L))
        assertEquals("the cloud rating counts before the readback", 46 * 1440, OttaiConstants.nativeShellRecords(0L, 45 * day))
        assertEquals("implausible values fall back to the floor", 31 * 1440,
            OttaiConstants.nativeShellRecords(1_700_000_280_000L, 1_700_000_280_000L))
        for (lifetime in listOf(15 * day + 1_800_000L, 28 * day, 30 * day, 45 * day)) {
            // The native start is the creation instant and only moves earlier; records are dated from
            // the confirmed start, so the last record's poll index can exceed its dataNo.
            assertTrue("no margin for $lifetime",
                OttaiConstants.nativeShellRecords(lifetime, 0L) >= OttaiConstants.nativeLifetimeRecords(lifetime) + 2)
        }
    }

    @Test
    fun lifetimeRecordsAreTheFirmwareMinutesOrZeroWhenUnknown() {
        assertEquals(40320, OttaiConstants.nativeLifetimeRecords(28 * day))
        assertEquals(43200, OttaiConstants.nativeLifetimeRecords(30 * day))
        assertEquals(64800, OttaiConstants.nativeLifetimeRecords(45 * day))
        assertEquals(21630, OttaiConstants.nativeLifetimeRecords(15 * day + 1_800_000L))
        assertEquals(0, OttaiConstants.nativeLifetimeRecords(0L))
        assertEquals(0, OttaiConstants.nativeLifetimeRecords(9 * day))
        assertEquals(0, OttaiConstants.nativeLifetimeRecords(46 * day))
    }

    @Test
    fun wearDaysNeverCreateTheShellFirst() {
        val body = functionBody("applyActivatedWearToNative")
        val wear = body.indexOf("Natives.setSensorWearDays(")
        assertTrue("setSensorWearDays call not found", wear >= 0)
        val sized = body.indexOf("ensureNativeShellCapacity(")
        assertTrue(
            "ensureNativeShellCapacity must run before setSensorWearDays, which creates a missing shell at the 15-day default",
            sized in 0 until wear,
        )
    }

    @Test
    fun streamWritesNeverCreateTheShellFirst() {
        val start = manager.indexOf("OttaiNativeGlucoseMirror(")
        assertTrue(start >= 0)
        val end = Regex("\\r?\\n    \\)\\r?\\n").find(manager, start)!!.range.first
        val block = manager.substring(start, end)
        for (call in listOf("Natives.addGlucoseStreamWithTemp(", "Natives.addGlucoseStreamBatchWithTemp(")) {
            val at = block.indexOf(call)
            assertTrue("$call not found in the mirror wiring", at >= 0)
            val lambda = block.substring(block.lastIndexOf("->", at), at)
            assertTrue(
                "$call creates a missing shell at the 15-day default; size it first",
                lambda.contains("ensureNativeShellCapacity(sensorId)"),
            )
        }
    }

    @Test
    fun everySizingSiteAsksForTheLifetimeAwareSize() {
        for (name in listOf("ensureNativeShellCapacity", "repairSecondsUnitNativeStart")) {
            assertTrue("$name must size through nativeShellRecords", flat(functionBody(name)).contains(
                "valminimumRecords=OttaiConstants.nativeShellRecords(activatedMaxActiveMs,materials.activeExpireTimeMs)"))
        }
        assertEquals("a new sizing site must go through ensureNativeShellCapacity",
            2, Regex(Regex.escape("Natives.ensureSensorShellWithCapacity(")).findAll(flat(manager)).count())
        assertFalse("a fixed 30-day size leaves no margin", flat(manager).contains("EXTENDED_LIFETIME_DAYS*24*60"))
    }

    @Test
    fun presenceSizesOnceThenAppliesTheStartPlainly() {
        val body = flat(functionBody("ensureNativePresenceShell"))
        assertTrue(body.contains("if(!ensureNativeShellCapacity(id,startSec)){Natives.ensureSensorShell(id,startSec)}"))
        assertFalse(body.contains("ensureSensorShellWithCapacity("))
    }

    @Test
    fun capacityWarningJudgesTheLifetimeAndWaitsForIt() {
        val body = flat(functionBody("ensureNativePresenceShell"))
        assertTrue(body.contains("vallifetimeRecords=OttaiConstants.nativeLifetimeRecords(activatedMaxActiveMs)"))
        assertTrue("a lifetime corrected upward is judged again", body.contains("valcheckKey=\"\$id/\$lifetimeRecords\""))
        assertTrue("no verdict before the lifetime is known", body.contains("if(lifetimeRecords>0&&nativeCapacityCheckedFor!=checkKey)"))
        assertTrue(body.contains("Natives.hasSensorStreamCapacity(id,lifetimeRecords)"))
    }

    @Test
    fun theSizedMemoLatchesOnlyOnceNativeReturnsAShell() {
        assertTrue(flat(functionBody("ensureNativeShellCapacity")).contains(
            "valshell=Natives.ensureSensorShellWithCapacity(id,startSec,minimumRecords)if(shell!=0L){nativeShellSizedFor=id}"))
        assertEquals("nativeShellSizedFor must be assigned nowhere else",
            1, Regex("nativeShellSizedFor=(?!=)").findAll(flat(manager)).count())
    }

    @Test
    fun theSizingCallOffersNoStartByDefault() {
        assertTrue(flat(functionBody("ensureNativeShellCapacity")).contains(
            "privatefunensureNativeShellCapacity(id:String,startSec:Long=0L):Boolean"))
        // Start 0 is only safe because both native layers on this path apply a start above zero
        // only; a call that merely sizes a shell must never move its start.
        val jni = source("Common/src/main/cpp/g.cpp")
        val from = jni.indexOf("static jlong ensureSensorShellInternal(")
        val to = jni.indexOf("fromjava(ensureSensorShell)", from)
        assertTrue(from >= 0 && to > from)
        assertEquals(2, Regex("startTimeSec\\s*>\\s*0\\s*&&").findAll(jni.substring(from, to)).count())
        val sensoren = source("Common/src/main/cpp/sensoren.hpp")
        val shell = sensoren.indexOf("ensureDirectStreamShell(")
        assertTrue("Sensoren::ensureDirectStreamShell not found", shell >= 0)
        val guard = Regex("if\\s*\\(\\s*starttime\\s*>\\s*0\\s*\\)").find(sensoren, shell)
        val write = sensoren.indexOf("info->starttime = starttime", shell)
        assertTrue("ensureDirectStreamShell must write starttime only under a start > 0 guard",
            guard != null && write > guard.range.first)
    }
}
