package tk.glucodata.drivers.ottai

import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * A Context whose only working call is getSharedPreferences, backed by an in-memory map, so
 * OttaiRegistry's prefs code runs on the JVM. ContextWrapper(null) is safe here because unit tests
 * link against AGP's mockable android.jar (returnDefaultValues = true), whose constructor does
 * nothing; any other Context call returns a default instead of reaching a real device.
 */
internal class FakePrefsContext : ContextWrapper(null) {
    val prefs = FakeSharedPreferences()
    val requestedNames = mutableSetOf<String>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        requestedNames += name.orEmpty()
        return prefs
    }
}

/**
 * Map-backed SharedPreferences with the platform's edit semantics: nothing is visible before
 * apply/commit, putString(key, null) removes, and a read with the wrong type throws
 * ClassCastException as the real one does.
 */
internal class FakeSharedPreferences : SharedPreferences {
    val values = HashMap<String, Any>()

    private fun raw(key: String?): Any? = values[requireNotNull(key)]

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?): String? = raw(key) as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        raw(key) as MutableSet<String>? ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (raw(key) ?: return defValue) as Int
    override fun getLong(key: String?, defValue: Long): Long = (raw(key) ?: return defValue) as Long
    override fun getFloat(key: String?, defValue: Float): Float = (raw(key) ?: return defValue) as Float
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (raw(key) ?: return defValue) as Boolean
    override fun contains(key: String?): Boolean = raw(key) != null
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        // null marks a removal; the last write to a key wins, as on the platform.
        private val pending = LinkedHashMap<String, Any?>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[requireNotNull(key)] = value
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toMutableSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            for ((key, value) in pending) if (value == null) values.remove(key) else values[key] = value
            pending.clear()
            clearAll = false
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
