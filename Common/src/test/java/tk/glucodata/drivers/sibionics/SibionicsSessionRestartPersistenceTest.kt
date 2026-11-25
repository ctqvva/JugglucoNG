package tk.glucodata.drivers.sibionics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSessionRestartPersistenceTest {
    private val sensor = "SIBI:P225043JMV"

    @Test
    fun aRestartIsPersistedAsANewlyAddedSensorInOneCommittedWrite() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        // The day-16 session the 2026-09-24 capture restored.
        SibionicsRegistry.saveAlgorithmCheckpoint(context, sensor, 23_437, byteArrayOf(1, 2, 3))
        SibionicsRegistry.saveStartTimeMs(context, sensor, 1_788_785_280_000L)
        SibionicsRegistry.saveLastReading(context, sensor, 1_790_200_380_000L, 66f, 12f)
        SibionicsRegistry.saveShortCode(context, sensor, "0P225043")
        val commitsBefore = prefs.commits

        assertTrue(SibionicsRegistry.saveSessionRestart(context, sensor, byteArrayOf(9)))

        assertEquals("one synchronous write", commitsBefore + 1, prefs.commits)
        assertEquals(0, SibionicsRegistry.loadLastIndex(context, sensor))
        assertEquals(0L, SibionicsRegistry.loadStartTimeMs(context, sensor))
        assertEquals(0L, SibionicsRegistry.loadLastReading(context, sensor).first)
        assertEquals(listOf<Byte>(9), SibionicsRegistry.loadAlgorithmState(context, sensor)?.toList())
        // What identifies the sensor is not session state and survives.
        assertEquals("0P225043", SibionicsRegistry.loadShortCode(context, sensor))
    }

    @Test
    fun anUnsuccessfulCommitIsReported() {
        val prefs = FakePreferences(commitSucceeds = false)
        assertFalse(SibionicsRegistry.saveSessionRestart(PrefsContext(prefs), sensor, byteArrayOf(9)))
        assertTrue("nothing lands from a failed commit", prefs.values.isEmpty())
    }

    @Test
    fun gs3CheckpointRestoresTheLiveIndexAndRejectsItsReplayAfterRestart() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val anchor = 1_790_200_000_000L
        val time = anchor + 20 * SibionicsGs3Protocol.RECORD_INTERVAL_MS
        val trend = SibionicsProtocol.Trend.NOT_DETERMINED
        val reading = SibionicsGs3Reading(20, time, 108f, trend)
        // The cursor tracks the page's first index, which can be behind the live index.
        assertTrue(SibionicsRegistry.saveGs3Page(context, sensor, 17, anchor, reading))
        assertEquals(1, prefs.commits)

        val restoredContext = PrefsContext(prefs)
        val restoredAnchor = SibionicsRegistry.loadStartTimeMs(restoredContext, sensor)
        val (restoredTime, restoredGlucose, _) = SibionicsRegistry.loadLastReading(restoredContext, sensor)
        val savedIndex = SibionicsRegistry.loadGs3LastLiveIndex(restoredContext, sensor)
        assertEquals(17, SibionicsRegistry.loadLastIndex(restoredContext, sensor))
        assertEquals(time, restoredTime)
        assertEquals(108f, restoredGlucose, 0f)
        assertEquals(20, savedIndex)

        val replay = SibionicsGs3Protocol.Record(20, 60, trend, 0, byteArrayOf())
        val now = time + 5 * SibionicsGs3Protocol.RECORD_INTERVAL_MS
        // Also cover the timestamp fallback for preferences written by early test builds.
        for (index in listOf(savedIndex, -1)) {
            val restoredIndex = SibionicsGs3ReadingTime.restoreLastLiveIndex(index, restoredAnchor, restoredTime)
            assertEquals(20, restoredIndex)
            assertFalse(SibionicsGs3ReadingTime.isNewLiveRecord(replay, restoredIndex))
            assertEquals(anchor, SibionicsGs3ReadingTime.anchor(restoredAnchor, replay, restoredIndex, now))

            val fresh = replay.copy(index = 21)
            assertTrue(SibionicsGs3ReadingTime.isNewLiveRecord(fresh, restoredIndex))
            val shifted = SibionicsGs3ReadingTime.anchor(restoredAnchor, fresh, restoredIndex, now)
            assertEquals(now, shifted + fresh.index * SibionicsGs3Protocol.RECORD_INTERVAL_MS)
        }
    }

    @Test
    fun theAutoResetBackoffIsCommittedAndClearedByTheRestartItWaitedFor() {
        val prefs = FakePreferences()
        val context = PrefsContext(prefs)
        val commitsBefore = prefs.commits

        SibionicsRegistry.saveAutoResetNotBeforeMs(context, sensor, 1_790_221_980_000L)

        assertEquals("committed: the case it guards is a process that dies seconds later", commitsBefore + 1, prefs.commits)
        assertEquals(1_790_221_980_000L, SibionicsRegistry.loadAutoResetNotBeforeMs(context, sensor))

        assertTrue(SibionicsRegistry.saveSessionRestart(context, sensor, byteArrayOf(9)))
        assertEquals(0L, SibionicsRegistry.loadAutoResetNotBeforeMs(context, sensor))
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
