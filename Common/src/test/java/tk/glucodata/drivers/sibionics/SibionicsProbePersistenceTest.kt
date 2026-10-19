package tk.glucodata.drivers.sibionics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class SibionicsProbePersistenceTest {
    @Test
    fun activeBleAliasResolvesCalibrationAfterQrReplacesItsRecord() {
        val context = PrefsContext(FakePreferences())
        val variant = SibionicsConstants.Variant.SIBIONICS2
        val ble = "P225043JMV"
        val active = SibionicsRegistry.ensureSensorRecord(context, ble, null, ble, variant)
        assertNull(SibionicsRegistry.loadProbeCode(context, active.sensorId))
        val qr = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val scanned = SibionicsRegistry.ensureSensorRecord(context, qr, null, null, variant, bleNameOverride = ble)
        assertNotEquals(active.sensorId, scanned.sensorId)
        assertEquals(1, SibionicsRegistry.persistedRecords(context).size)
        val probe = SibionicsRegistry.loadProbeCode(context, active.sensorId)
        assertEquals("EU2VCZUQPSHD5Q", probe)
        assertEquals(1.73f, SibionicsSensitivity.sensitivityFor(active.shortCode, variant, probe), 0.00001f)
    }

    @Test
    fun scannedCalibrationSurvivesAddressWritesAndSessionRestart() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val variant = SibionicsConstants.Variant.SIBIONICS2
        val qr = "\u001D0106972831641476112602081727080710LT46260201C\u001D21EU2VCZUQPSHD5Q"
        val record = SibionicsRegistry.ensureSensorRecord(context, qr, null, null, variant)
        assertEquals("SIBI:VCZUQPSHD5Q", record.sensorId)
        assertEquals("VCZUQPSH", record.shortCode)
        SibionicsRegistry.ensureSensorRecord(context, record.displayName, "AA:BB:CC:DD:EE:FF",
            record.displayName, variant, shortCodeOverride = record.shortCode)
        SibionicsRegistry.saveSessionRestart(context, record.sensorId, byteArrayOf(9))
        val restored = SibionicsRegistry.findRecord(context, record.sensorId)!!
        val probe = SibionicsRegistry.loadProbeCode(context, restored.sensorId)
        assertEquals("EU2VCZUQPSHD5Q", probe)
        assertEquals(1.73f, SibionicsSensitivity.sensitivityFor(restored.shortCode, restored.variant, probe), 0.00001f)
        assertNull(SibionicsRegistry.loadProbeCode(context, "SIBI:OTHER"))
    }

    private class PrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    /** In-memory SharedPreferences; an editor's changes land together, on apply or commit. */
    private class FakePreferences(private val commitSucceeds: Boolean = true) : SharedPreferences {
        val values = HashMap<String, Any?>()
        var commits = 0

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            private var clear = false
            private fun put(key: String?, value: Any?) = apply { puts[key!!] = value; removes.remove(key) }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = apply { removes += key!!; puts.remove(key) }
            override fun clear() = apply { clear = true }
            private fun land() {
                if (clear) values.clear()
                removes.forEach(values::remove)
                values.putAll(puts)
            }
            override fun commit(): Boolean {
                commits++
                if (!commitSucceeds) return false
                land()
                return true
            }
            override fun apply() = land()
        }
    }
}
