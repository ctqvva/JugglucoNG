package tk.glucodata.alerts

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SignalRestoredReadingPreferencesTests {
    /** Separate volatile memory from disk, so a restart drops pending asynchronous writes. */
    private class PreferenceDisk {
        val durable = mutableMapOf<String, Any?>()
        var commits = 0
        var applies = 0

        fun newProcessContext(): Context {
            val memory = durable.toMutableMap()
            val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString", "getLong" -> memory[args!![0]] ?: args[1]
                    "edit" -> {
                        val staged = mutableMapOf<String, Any?>()
                        lateinit var editor: SharedPreferences.Editor
                        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                            arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                            when (editMethod.name) {
                                "putString", "putLong" -> {
                                    staged[editArgs!![0] as String] = editArgs[1]
                                    editor
                                }
                                "commit" -> {
                                    memory.putAll(staged)
                                    durable.putAll(staged)
                                    commits++
                                    true
                                }
                                "apply" -> {
                                    memory.putAll(staged)
                                    applies++
                                    null
                                }
                                else -> error("Unexpected editor operation: ${editMethod.name}")
                            }
                        } as SharedPreferences.Editor
                        editor
                    }
                    else -> error("Unexpected preference operation: ${method.name}")
                }
            } as SharedPreferences
            return object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String, mode: Int) = prefs
            }
        }
    }

    @Test fun saveReturnsOnlyAfterTheCompleteCheckpointIsAvailableToAColdProcess() {
        val disk = PreferenceDisk()
        val store = SignalRestoredReadingPreferences(disk.newProcessContext())
        val first = SignalRestoredReading("sensor-A", 1_800_000_000_000L)
        store.save(first)
        assertEquals(first, SignalRestoredReadingPreferences(disk.newProcessContext()).load())

        val replacement = SignalRestoredReading("sensor-B", first.readingMs + 60_000L)
        store.save(replacement)
        assertEquals(replacement, SignalRestoredReadingPreferences(disk.newProcessContext()).load())
        assertEquals(2, disk.commits)
        assertEquals(0, disk.applies)
    }
}
