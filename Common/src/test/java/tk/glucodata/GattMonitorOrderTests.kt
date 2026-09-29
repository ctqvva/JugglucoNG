package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream's connect deadline made SuperGattCallback take the callback's monitor in free(),
 * discard(), setPause() and closeGattTransport(), and around the whole connect runnable. Two rules
 * keep that from deadlocking: the drivers' terminal hook runs outside the monitor (AiDex waits for
 * its handler, whose tasks close through closeGattTransport()), and nothing that takes a
 * callback's monitor runs under the list's lock (code holding that monitor reaches mygatts(), e.g.
 * a connect checking CloneSensorRegistry). These read the source, as ConnectModeLeverTests does;
 * comments are stripped.
 */
class GattMonitorOrderTests {

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

    @Test
    fun theTerminalHookRunsOutsideTheCallbacksMonitor() {
        val base = read("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        assertFalse(base.contains("synchronized void free()"))
        assertFalse(base.contains("synchronized void discard()"))
        assertTrue(
            base.contains(
                "onTerminalFree(); synchronized (this) { close(); Natives.freedataptr(dataptr); dataptr = 0L; }"
            )
        )
        assertTrue(base.contains("onTerminalFree(); synchronized (this) { close(); dataptr = 0L; }"))
    }

    /**
     * Source reduced to code: comments dropped, string, char and raw-string literals emptied (Kotlin
     * `${...}` templates skipped with their own nesting, quotes and lines), in one pass so none can
     * hide another ("https://" in a string is not a comment, a brace in a string is not a brace).
     * No regex: Java's is recursive per repetition and overflows the stack on long literals.
     */
    private class CodeOnly(private val src: String, private val kotlin: Boolean) {
        /** Copies code from [start] into [out] (null: skip it) to the end, or past the '}' closing a template. */
        fun code(start: Int, out: StringBuilder?, template: Boolean): Int {
            var i = start
            var depth = 0
            while (i < src.length) {
                when {
                    src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    src.startsWith("/*", i) -> {
                        i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                        out?.append(' ')
                    }
                    src.startsWith("\"\"\"", i) -> {
                        i = literal(i + 3, "\"\"\"", raw = true)
                        out?.append("\"\"")
                    }
                    src[i] == '"' -> {
                        i = literal(i + 1, "\"", raw = false)
                        out?.append("\"\"")
                    }
                    src[i] == '\'' -> {
                        var j = i + 1
                        while (j < src.length && src[j] != '\'' && src[j] != '\n') {
                            if (src[j] == '\\') j++
                            j++
                        }
                        i = j + 1
                        out?.append("''")
                    }
                    template && src[i] == '{' -> {
                        depth++
                        i++
                    }
                    template && src[i] == '}' -> {
                        if (depth == 0) return i + 1
                        depth--
                        i++
                    }
                    else -> out?.append(src[i++]) ?: i++
                }
            }
            return i
        }

        /** Index just past the literal whose body starts at [start]. */
        private fun literal(start: Int, close: String, raw: Boolean): Int {
            var i = start
            while (i < src.length) {
                when {
                    !raw && src[i] == '\\' -> i += 2
                    src.startsWith(close, i) -> return i + close.length
                    kotlin && src.startsWith("\${", i) -> i = code(i + 2, null, template = true)
                    !raw && src[i] == '\n' -> return i
                    else -> i++
                }
            }
            return i
        }
    }

    private fun codeOnly(file: File): String {
        val out = StringBuilder()
        CodeOnly(file.readText(), file.extension == "kt").code(0, out, template = false)
        return out.toString().replace(Regex("\\s+"), " ")
    }

    /**
     * Each block locking the callback list, whatever the receiver is spelled as (`gattcallbacks`,
     * `blueone.gattcallbacks`, `SensorBluetooth.gattcallbacks`, Java or Kotlin), by brace matching.
     */
    private fun listLockBlocks(code: String, name: String): List<String> {
        val opener = Regex("synchronized\\s*\\(\\s*(?:\\w+\\.)*gattcallbacks\\s*\\)\\s*\\{")
        return opener.findAll(code).map { match ->
            var depth = 0
            var end = match.range.last
            while (true) {
                assertTrue("unbalanced braces after a list lock in $name", end < code.length)
                when (code[end]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth == 0) break
                end++
            }
            code.substring(match.range.first, end + 1)
        }.toList()
    }

    @Test
    fun nothingTakesACallbacksMonitorUnderTheListsLock() {
        // Direct calls by name, with or without a receiver, and as member references.
        val direct = listOf(
            Regex("(?<![\\w$])(?:free|discard|close|disconnect|closeGattTransport)\\s*\\(\\s*\\)"),
            Regex("(?<![\\w$])(?:setPause|connectDevice|reconnect|shouldreconnect)\\s*\\("),
            Regex("::(?:free|discard|close|disconnect|closeGattTransport|setPause|connectDevice|reconnect|shouldreconnect)\\b"),
        )
        // Known wrappers that reach a callback's monitor, by name: resetdataptr() closes, the connect
        // helpers reach connectDevice(), the teardowns free or close, and SensorBluetooth's entry
        // points end in one of those. A source scan cannot follow arbitrary helpers: a new entry point
        // that reaches free/close/setPause/connectDevice belongs in this list.
        val wrappers = listOf(
            Regex(
                "(?<![\\w$])(?:resetdataptr|connectDevices|connectToActiveDevice|connectToAllActiveDevices|" +
                    "checkandconnect|goscan|addDevice|addAiDexSensor|removeDevices?|updateDevices|updateDevicers|" +
                    "sensorEnded|resetDevice(?:s|r|OrFree|Ptr)?|destruct|destructor|" +
                    "retireCloneSensor|blockLocalCloneConnection|dropAiDexLeftoverPersistAndNative|" +
                    "teardownLeftoverAiDex|teardownLeftoverAiDexOwners|forgetVendor|rollbackUnpairedAiDexSensor|" +
                    "ensurePersistedManagedCallbacks|addPersistedManagedCallbacks|syncNativeDevicesNoBluetooth|" +
                    "setDevices|startDevices|initializeBluetooth|connectNamedDevice|reconnectall|othersworking)\\s*\\("
            ),
            // start(boolean) by its receiver: a bare start( would also hit Thread.start().
            Regex("(?:SensorBluetooth|blueone)\\s*\\.\\s*start\\s*\\("),
        )
        // The most direct way to take a callback's monitor: any other lock taken inside a list lock.
        // Not a label: Kotlin's return@synchronized (value) takes no lock.
        val nestedLock = Regex("(?<![\\w@$])synchronized\\s*\\(\\s*([^)]*)\\)")
        val listLock = Regex("(?:\\w+\\.)*gattcallbacks")
        val files = File(repoRoot(), "Common/src").walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .filterNot { it.path.replace('\\', '/').contains("/Common/src/test") }
            .filter { it.readText().contains("gattcallbacks") }
            .toList()
        var checked = 0
        var inSensorBluetooth = 0
        for (file in files) {
            val code = codeOnly(file)
            // A literal the scanner misread would leak or swallow a brace and move a block's end.
            assertEquals("unbalanced braces after scanning ${file.name}", code.count { it == '{' }, code.count { it == '}' })
            val blocks = listLockBlocks(code, file.name)
            if (file.name == "SensorBluetooth.java") inSensorBluetooth = blocks.size
            checked += blocks.size
            for (block in blocks) {
                for (call in direct) {
                    assertFalse("${call.find(block)?.value} under the list's lock in ${file.name}: ${block.take(160)}", call.containsMatchIn(block))
                }
                for (call in wrappers) {
                    assertFalse("${call.find(block)?.value} under the list's lock in ${file.name}: ${block.take(160)}", call.containsMatchIn(block))
                }
                for (lock in nestedLock.findAll(block)) {
                    val operand = lock.groupValues[1].trim()
                    assertTrue("synchronized ($operand) under the list's lock in ${file.name}", listLock.matches(operand))
                }
            }
        }
        // SensorBluetooth alone: the roster pass, the Clone paths and the five blueone.gattcallbacks blocks.
        assertTrue("list-lock blocks found in SensorBluetooth: $inSensorBluetooth", inSensorBluetooth >= 20)
        assertTrue("list-lock blocks found elsewhere: ${checked - inSensorBluetooth}", checked - inSensorBluetooth >= 5)
    }

    @Test
    fun theRosterPassStillFreesWhatItRemoved() {
        val source = read("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        // The try opens before the roster lock and closes after the add loop, so every claim is covered.
        assertTrue(
            source.contains(
                "ArrayList<String> allDevs = null; try { synchronized (gattcallbacks) { " +
                    "String[] nativeDevs = filterActiveSensorNames(Natives.activeSensors());"
            )
        )
        assertTrue(source.contains("} finally { for (SuperGattCallback victim : claimedVictims) { try { victim.free();"))
        // Re-homing runs outside the list's lock, so its walk must be over a snapshot.
        assertTrue(
            source.contains(
                "for (SuperGattCallback cb : mygatts()) { if (cb == null) continue; " +
                    "addReplacementCandidate(candidates, seen, cb.SerialNumber, removedSerial);"
            )
        )
        assertTrue(
            source.contains(
                "if (claimed) { claimedVictims.add(victim); } if (leftover) { removedLeftovers.add(removedSerial); } " +
                    "removedSerials.add(removedSerial);"
            )
        )
        // Each step guarded on its own, so one failure neither hides the add loop's exception nor
        // skips the steps after it.
        assertTrue(
            source.contains(
                "for (SuperGattCallback victim : claimedVictims) { try { victim.free(); } catch (Throwable t) { " +
                    "Log.stack(LOG_ID, \"updateDevicers free\", t); } } for (String serial : removedLeftovers) { " +
                    "try { dropAiDexLeftoverPersistAndNative(Applic.app, serial, false); } catch (Throwable t) { " +
                    "Log.stack(LOG_ID, \"updateDevicers drop removed \" + serial, t); } } for (String serial : removedSerials) { " +
                    "try { rehomeCurrentSensorAfterRemoval(serial, allDevs); } catch (Throwable t) { " +
                    "Log.stack(LOG_ID, \"updateDevicers rehome \" + serial, t); } } for (String dev : lateLeftovers) { " +
                    "try { dropAiDexLeftoverPersistAndNative(Applic.app, dev); } catch (Throwable t) { " +
                    "Log.stack(LOG_ID, \"updateDevicers drop late \" + dev, t); } }"
            )
        )
    }

    @Test
    fun anNfcRescanCannotReviveACallbackRemovedMeanwhile() {
        val source = read("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        assertTrue(
            source.contains(
                "cb.resetdataptr(); cb.sensorstartmsec = Natives.getSensorStartmsec(cb.dataptr); cb.setPause(false); " +
                    "if (!isListed(cb)) { cb.setPause(true); }"
            )
        )
        // The re-check is only sound under the list's lock, the lock every remover takes it off under.
        assertTrue(
            source.contains(
                "private static boolean isListed(SuperGattCallback callback) { synchronized (gattcallbacks) { " +
                    "for (Object cb : gattcallbacks.toArray()) { if (cb == callback) return true; } return false; } }"
            )
        )
        // Every remover takes callbacks off the list before freeing them, removeDevices included.
        assertTrue(
            source.contains(
                "for (int pass = 0; pass < 8; pass++) { final ArrayList<SuperGattCallback> removed; " +
                    "synchronized (gattcallbacks) { if (gattcallbacks.isEmpty()) break; " +
                    "removed = new ArrayList<>(gattcallbacks); gattcallbacks.clear(); } " +
                    "for (SuperGattCallback cb : removed) { try { cb.free(); }"
            )
        )
    }

    @Test
    fun theClonePathsLetGoOfTheListBeforeTouchingACallback() {
        val source = read("Common/src/main/java/tk/glucodata/SensorBluetooth.java")
        assertTrue(
            source.contains(
                "synchronized (gattcallbacks) { for (SuperGattCallback callback : gattcallbacks) { " +
                    "if (SensorIdentity.matches(callback.SerialNumber, sensorId)) { matched.add(callback); } } } " +
                    "for (SuperGattCallback callback : matched) { callback.setPause(true); callback.closeGattTransport(); }"
            )
        )
        assertTrue(
            source.contains(
                "if (SensorIdentity.matches(callback.SerialNumber, sensorId)) { retired.add(callback); " +
                    "gattcallbacks.remove(index); } } } for (SuperGattCallback callback : retired) { callback.free(); }"
            )
        )
    }
}
