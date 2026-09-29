package tk.glucodata.drivers.ottai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing an Ottai must retire its native shell, or updateDevices() turns the orphan into a
 * Libre2 callback (2026-09-22: a sensor needed two Disconnect taps). The JNI calls are
 * injected so the decision logic runs on the JVM; the wiring is pinned by source checks, as in
 * ManagedSensorCloneOwnershipTests, because OttaiRegistry.removeSensor needs Android prefs.
 */
class OttaiRemovalTests {
    private class FakeNatives(
        private val ptrs: Map<String, Long>,
        private val throwOnFinish: Boolean = false,
    ) {
        // Two threads write to it in the lock tests.
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        fun getdataptr(name: String): Long { calls += "get:$name"; return ptrs[name] ?: 0L }
        fun finish(ptr: Long) { calls += "finish:$ptr"; if (throwOnFinish) error("jni") }
        fun free(ptr: Long) { calls += "free:$ptr" }
    }

    private fun finish(sensorId: String, active: Array<String>?, natives: FakeNatives) =
        OttaiRegistry.finishNativeMirror(sensorId, active, natives::getdataptr, natives::finish, natives::free)

    private val record = OttaiRegistry.SensorRecord("0123456789AB", "", "Ottai")

    private fun finishRemoved(canonical: String, records: List<OttaiRegistry.SensorRecord>, natives: FakeNatives) =
        OttaiRegistry.finishRemovedNativeMirror(
            canonical, records, { arrayOf("56789AB") }, natives::getdataptr, natives::finish, natives::free,
        )

    @Test
    fun activeShortAliasIsFinishedByFullNameAndFreed() {
        val natives = FakeNatives(mapOf("0123456789AB" to 42L))
        assertTrue(finish("0123456789AB", arrayOf("56789CD", "56789AB"), natives))
        assertEquals(listOf("get:0123456789AB", "finish:42", "free:42"), natives.calls)
    }

    @Test
    fun activeFullNameAndLowerCaseShortNameBothCount() {
        val natives = FakeNatives(mapOf("0123456789AB" to 42L))
        assertTrue(finish("0123456789AB", arrayOf("0123456789AB"), natives))
        assertTrue(finish("0123456789AB", arrayOf("56789ab"), natives))
        assertEquals(
            listOf("get:0123456789AB", "finish:42", "free:42", "get:0123456789AB", "finish:42", "free:42"),
            natives.calls,
        )
    }

    @Test
    fun anIdWithNoShortNameNeverMatchesAnEmptyEntry() {
        val natives = FakeNatives(mapOf("ABCDE" to 42L))
        assertFalse(finish("ABCDE", arrayOf(""), natives))
        assertTrue(natives.calls.isEmpty())
    }

    @Test
    fun sensorWithoutActiveShellIsNeverOpened() {
        val natives = FakeNatives(mapOf("0123456789AB" to 42L))
        assertFalse(finish("0123456789AB", arrayOf("56789CD"), natives))
        assertFalse(finish("0123456789AB", null, natives))
        assertTrue(natives.calls.isEmpty())
    }

    @Test
    fun unopenableShellIsLeftAlone() {
        val natives = FakeNatives(emptyMap())
        assertFalse(finish("0123456789AB", arrayOf("56789AB"), natives))
        assertEquals(listOf("get:0123456789AB"), natives.calls)
    }

    @Test
    fun streamIsFreedEvenWhenFinishThrows() {
        val natives = FakeNatives(mapOf("0123456789AB" to 42L), throwOnFinish = true)
        assertFalse(finish("0123456789AB", arrayOf("56789AB"), natives))
        assertEquals(listOf("get:0123456789AB", "finish:42", "free:42"), natives.calls)
    }

    @Test
    fun onlyARemovalThatDropsARecordFinishesTheShell() {
        val natives = FakeNatives(mapOf("0123456789AB" to 42L, "56789AB" to 7L))
        // Names the same shell but drops no record: the record, and its shell, stay.
        assertFalse(finishRemoved("56789AB", listOf(record), natives))
        assertFalse(finishRemoved("0123456789AB", emptyList(), natives))
        assertTrue(natives.calls.isEmpty())
        assertTrue(finishRemoved("0123456789AB", listOf(record), natives))
        assertEquals(listOf("get:0123456789AB", "finish:42", "free:42"), natives.calls)
    }

    /**
     * A removal that starts while a driver's native call is inside unlessReleased waits for it and
     * touches no native until it returns: the write lands before the finish, never after it (the
     * ghost a reading in flight would bring back).
     */
    @Test
    fun theFinishWaitsForAWriteAlreadyPastTheReleasedCheck() {
        val natives = FakeNatives(mapOf("0123456789AB" to 42L))
        val inside = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            OttaiRegistry.unlessReleased({ false }, false) {
                inside.countDown()
                proceed.await()
                natives.calls += "write"
                true
            }
        }
        writer.isDaemon = true
        writer.start()
        // Released in finally: a failed assertion must not leave the process-wide lock held for
        // every later test that takes it.
        try {
            assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val remover = Thread { finishRemoved("0123456789AB", listOf(record), natives) }
            remover.isDaemon = true
            remover.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (remover.state != Thread.State.BLOCKED && remover.isAlive && System.nanoTime() < deadline) Thread.yield()
            // The removal waits on the write's lock instead of finishing under it.
            assertEquals(Thread.State.BLOCKED, remover.state)
            assertTrue(natives.calls.isEmpty())
            proceed.countDown()
            writer.join(5_000)
            remover.join(5_000)
        } finally {
            proceed.countDown()
        }
        assertEquals(listOf("write", "get:0123456789AB", "finish:42", "free:42"), natives.calls)
    }

    /**
     * The removal waits on the lock but does not hold it while it opens and finishes the shell:
     * getdataptr can call back into the identity adapters, which take gattcallbacks, while
     * updateDevicers holds gattcallbacks and restores an Ottai manager that needs this lock.
     */
    @Test
    fun theFinishHoldsNoLockWhileItOpensTheShell() {
        val held = mutableListOf<Boolean>()
        val finished = OttaiRegistry.finishRemovedNativeMirror(
            "0123456789AB",
            listOf(record),
            { held += Thread.holdsLock(OttaiRegistry.nativeShellLock); arrayOf("56789AB") },
            { held += Thread.holdsLock(OttaiRegistry.nativeShellLock); 42L },
            { held += Thread.holdsLock(OttaiRegistry.nativeShellLock) },
            { held += Thread.holdsLock(OttaiRegistry.nativeShellLock) },
        )
        assertTrue(finished)
        assertEquals(listOf(false, false, false, false), held)
    }

    /** released is read once the lock is held: a call queued behind it skips if the driver was freed meanwhile. */
    @Test
    fun releasedIsReadAfterTheLockIsTaken() {
        assertEquals(0, OttaiRegistry.unlessReleased({ true }, 0) { throw AssertionError("ran after release") })
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val inside = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { OttaiRegistry.unlessReleased({ false }, Unit) { inside.countDown(); proceed.await() } }
        holder.isDaemon = true
        holder.start()
        var result = "" // read after writer.join(), which makes the write visible
        try {
            assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val writer = Thread { result = OttaiRegistry.unlessReleased({ released.get() }, "skipped") { "wrote" } }
            writer.isDaemon = true
            writer.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (writer.state != Thread.State.BLOCKED && writer.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, writer.state)
            released.set(true) // free() -> onTerminalFree while the write waits
            proceed.countDown()
            holder.join(5_000)
            writer.join(5_000)
        } finally {
            proceed.countDown()
        }
        assertEquals("skipped", result)
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/java/tk/glucodata/drivers/ottai/OttaiRegistry.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    // Source without comments, whitespace-flattened: a comment word ("stop", a quoted call) can
    // neither satisfy nor break a pin. No string literal in these files holds "//" or "/*" and no
    // block comment nests; a URL or MIME literal added later would need a real lexer here.
    private fun code(relative: String): String =
        File(repoRoot(), relative).readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")

    private val stopWord = Regex("\\bstop\\b")

    private fun section(source: String, start: String, end: String): String {
        val at = source.indexOf(start)
        assertTrue("missing: $start", at >= 0)
        return source.substring(at).substringBefore(end)
    }

    @Test
    fun removeSensorFinishesTheNativeMirrorBeforeDroppingTheRecord() {
        val registry = code("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiRegistry.kt")
        // The public overload wires the real native step: the records it reads are those before the
        // drop, because the internal overload runs it first.
        val wiring = section(
            registry,
            "fun removeSensor(context: Context, sensorId: String?) {",
            "internal fun removeSensor(",
        )
        assertTrue("removeSensor must finish the native mirror",
            wiring.contains("finishRemovedNativeMirror( canonical, persistedRecords(context), { Natives.activeSensors() },"))
        assertTrue("finished via finishSensor", wiring.contains("Natives.finishSensor("))
        assertFalse("a removed-by-user shell is never revived on re-add", wiring.contains("removeSensorById"))
        // The internal overload runs that step before it drops the record.
        val body = section(
            registry,
            "internal fun removeSensor(context: Context, sensorId: String?, finishMirror",
            "saveProvisionalActiveTime(context, canonical, recoveredStartMs)",
        )
        val finish = body.indexOf("runCatching { finishMirror(canonical) }")
        val drop = body.indexOf("writeRecords(")
        assertTrue(finish >= 0 && drop >= 0)
        assertTrue("and do it before the record is dropped", finish < drop)
        assertFalse(body.contains("removeSensorById"))
    }

    @Test
    fun aReleasedManagerNeverWritesNative() {
        val manager = code("Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt")
        // Terminal-only: setPause() also sets stop, and a paused manager must keep writing. So the
        // one assignment is the one in onTerminalFree, and no guarded body consults stop.
        assertTrue(section(manager, "override fun onTerminalFree() {", "}").contains("released = true"))
        assertEquals(2, Regex("\\breleased = [^=]").findAll(manager).count())
        // The check and the call are one step under the lock removeSensor finishes under.
        val guard = "OttaiRegistry.unlessReleased({ released },"
        val mirror = section(manager, "private val nativeGlucoseMirror = OttaiNativeGlucoseMirror(", "private val abnormalDropAtMs")
        assertTrue(mirror.contains("sensorId -> $guard false) { ensureNativeShellCapacity(sensorId) Natives.addGlucoseStreamWithTemp("))
        assertTrue(mirror.contains("sensorId -> $guard 0) { ensureNativeShellCapacity(sensorId) Natives.addGlucoseStreamBatchWithTemp("))
        assertFalse(stopWord.containsMatchIn(mirror))
        for (header in listOf(
            "private fun ensureNativePresenceShell(reason: String) {",
            "private fun applyActivatedWearToNative(id: String) {",
            "private fun repairSecondsUnitNativeStart(id: String) {",
        )) {
            assertTrue(header, section(manager, header, "runCatching").startsWith("$header $guard Unit) {"))
            assertFalse(header, stopWord.containsMatchIn(section(manager, header, ".onFailure")))
        }
        // Sizing revives a finished shell too; it runs only inside the guarded bodies above (the two
        // lambdas, ensureNativePresenceShell, applyActivatedWearToNative) plus its own definition.
        assertEquals(5, Regex("\\bensureNativeShellCapacity\\(").findAll(manager).count())
    }
}
