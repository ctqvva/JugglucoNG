package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health Connect export must never hand native a 0 sensorptr.
 *
 * A driver that keeps no dataptr (iCan) gets 0 from getsensorptr(dataptr) on
 * every reading, and healthConnectfromSensorptr dereferenced it: a SIGSEGV every
 * three minutes once the UI had initialised HealthConnection. These are source
 * checks because the natives live in libg.so, which a local JVM test cannot load.
 */
class HealthConnectNullSensorptrTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }

    private fun flattened(relative: String): String =
        File(repoRoot(), relative).readText().replace(Regex("\\s+"), " ")

    /** A JNI native's source, from its fromjava(name) up to the next one. */
    private fun nativeBody(jni: String, name: String): String {
        val start = jni.indexOf("fromjava($name)(")
        assertTrue("fromjava($name) not found in g.cpp", start >= 0)
        val next = jni.indexOf("fromjava(", start + 1)
        return jni.substring(start, if (next < 0) jni.length else next)
    }

    @Test
    fun sensorptrNativesReturnOnZeroBeforeDereferencing() {
        val jni = flattened("Common/src/main/cpp/g.cpp")
        // The guards upstream Juggluco added in 10.9.3, for the natives that exist here.
        for (name in listOf(
            "streamfromSensorptr",
            "setHidefromSensorptr",
            "getHidefromSensorptr",
            "healthConnectfromSensorptr",
            "healthConnectWritten",
        )) {
            val body = nativeBody(jni, name)
            val guard = body.indexOf("if (!sensorptr)")
            val deref = body.indexOf("reinterpret_cast<")
            assertTrue("$name must return on a 0 sensorptr", guard >= 0)
            assertTrue("$name must check sensorptr before it dereferences it", guard < deref)
        }
    }

    @Test
    fun exportChecksSensorptrBeforeClaimingHealthConnect() {
        val callback = flattened("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        assertEquals(
            "every export in SuperGattCallback goes through exportToHealthConnect()",
            1,
            Regex("HealthConnection\\.Companion\\.writeAll\\(").findAll(callback).count(),
        )
        val export = callback.substring(callback.indexOf("private void exportToHealthConnect()"))
            .substringBefore("protected void handleGlucoseResult(")
        val check = export.indexOf("sensorptr == 0L")
        val claim = export.indexOf("dohealth(this)")
        assertTrue("exportToHealthConnect must check sensorptr and call dohealth", check >= 0 && claim >= 0)
        assertTrue(
            "dohealth() sets stopHealth on every other callback, so a sensor with nothing to export must not reach it",
            check < claim,
        )
        assertTrue(export.contains("HealthConnection.Companion.writeAll(sensorptr, SerialNumber)"))
    }

    @Test
    fun writeAllReturnsOnZeroBeforeItStartsAnExport() {
        val health = flattened("Common/src/mobile/java/tk/glucodata/HealthConnection.kt")
        val entry = health.substring(health.indexOf("private fun writeAllIns(")).substringBefore("scope.launch")
        val check = entry.indexOf("sensorptr == 0L")
        val claim = entry.indexOf("active.getAndSet(true)")
        assertTrue("writeAllIns must check sensorptr and take the active flag", check >= 0 && claim >= 0)
        assertTrue("a 0 sensorptr must not take the active flag or start a coroutine", check < claim)
    }
}
