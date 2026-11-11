package tk.glucodata

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.drivers.sibionics.SibionicsConstants
import tk.glucodata.drivers.sibionics.SibionicsRegistry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NativeHistoryTestApplication::class,
    shadows = [AliasDiagnosticNativeNames::class])
class NativeAliasHistoryReportTests {
    private lateinit var context: Context
    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        Applic.app = context as NativeHistoryTestApplication
        context.getSharedPreferences("tk.glucodata_preferences", 0).edit().clear().commit()
        AliasDiagnosticNativeNames.names.clear()
        AliasDiagnosticNativeNames.lookups.clear()
        SensorIdentity.invalidateCaches()
    }

    @Test fun exportReportInspectsRegisteredAliasesAndExplicitlyRefusesAutoMigration() {
        val legacy = "0111801JGS9"
        val fullLegacy = "240600111801JGS9"
        val record = SibionicsRegistry.ensureSensorRecord(context, "SIBI:$legacy", null,
            legacy, SibionicsConstants.Variant.SIBIONICS2)
        SibionicsRegistry.bindLegacyNativeName(context, record.sensorId, legacy)
        AliasDiagnosticNativeNames.names[record.sensorId] = arrayOf(record.sensorId)
        AliasDiagnosticNativeNames.names[legacy] = arrayOf(fullLegacy, record.sensorId)
        val second = System.currentTimeMillis() / 1000L - 60
        val directories = listOf(record.sensorId, fullLegacy).map { name ->
            File(context.filesDir, "sensors/$name").apply { mkdirs() }
        }
        try {
            directories.forEach { directory ->
                File(directory, "polls.dat").writeBytes(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(second.toInt()).putInt(1).putInt(123).putInt(0).putFloat(0f).array())
            }
            val before = directories.associate { it.name to File(it, "polls.dat").readBytes() }
            val text = NativeAliasHistoryDiagnostics.report(context)
            assertTrue(text, text.contains("classification=SPLIT_POLL_HISTORY"))
            assertTrue(text, text.contains("auto_algorithm=UNKNOWN"))
            assertTrue(text, text.contains("historical_calibration=UNKNOWN"))
            assertTrue(text, text.contains("merge_allowed=false"))
            assertEquals(listOf(record.sensorId, legacy), AliasDiagnosticNativeNames.lookups)
            directories.forEach { directory ->
                assertArrayEquals(before.getValue(directory.name), File(directory, "polls.dat").readBytes())
                assertEquals(listOf("polls.dat"), directory.listFiles()!!.map { it.name })
            }
        } finally { directories.forEach { it.deleteRecursively() } }
    }

    @Test fun noRegisteredLegacyRecordMeansNoNativeLookupOrReport() {
        assertEquals("", NativeAliasHistoryDiagnostics.report(context))
        assertTrue(AliasDiagnosticNativeNames.lookups.isEmpty())
    }
}

@Implements(value = Natives::class, callThroughByDefault = false)
class AliasDiagnosticNativeNames {
    companion object {
        val names = mutableMapOf<String, Array<String>>()
        val lookups = mutableListOf<String>()
        @JvmStatic @Implementation fun __staticInitializer__() {}
        @JvmStatic @Implementation fun getNativeHistoryRecordNames(id: String): Array<String>? {
            lookups += id
            return names[id]
        }
        @JvmStatic @Implementation fun getGlucoseHistoryForSensor(id: String, start: Long): LongArray? =
            error("diagnostics must never open/repair a native history mapping")
        @JvmStatic @Implementation fun ensureSensorShell(id: String, start: Long): Long =
            error("diagnostics must never create a sensor shell")
    }
}
