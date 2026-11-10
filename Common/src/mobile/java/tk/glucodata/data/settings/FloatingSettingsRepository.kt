package tk.glucodata.data.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import tk.glucodata.ui.overlay.OverlayPosition

// Using SharedPreferences for simplicity as I don't want to introduce DataStore dependency if not already prevalent/configured for this specific prefs file
// Actually, using SharedPreferences 'tk.glucodata_preferences' is standard in this app.

class FloatingSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    
    // Keys
    companion object {
        const val KEY_ENABLED = "floating_glucose_enabled"
        const val KEY_TRANSPARENT = "floating_transparent" // true = transparent, false = filled
        const val KEY_SHOW_SECONDARY = "floating_show_secondary"
        const val KEY_FONT_SOURCE = "floating_font_source" // "APP" or "SYSTEM"
        const val KEY_FONT_SIZE = "floating_font_size" // Float
        const val KEY_FONT_WEIGHT = "floating_font_weight" // "LIGHT", "REGULAR", "MEDIUM"
        const val KEY_SHOW_ARROW = "floating_show_arrow"
        const val KEY_X = "floating_x" // legacy pixels, superseded by KEY_POSITION_*
        const val KEY_Y = "floating_y"
        const val KEY_POSITION_X = "floating_position_x" // Float 0..1, see OverlayPosition
        const val KEY_POSITION_Y = "floating_position_y"
        const val KEY_CORNER_RADIUS = "floating_corner_radius" // Float dp
        const val KEY_OPACITY = "floating_opacity" // Float 0..1
        const val KEY_DYNAMIC_ISLAND = "floating_dynamic_island"
        const val KEY_ISLAND_VERTICAL_OFFSET = "floating_island_vertical_offset"
        const val KEY_ISLAND_GAP = "floating_island_gap"
        const val KEY_NOTIFICATION_DOT = "floating_notification_dot"
        const val KEY_SUBTLE_OUTLINE = "floating_subtle_outline"

        const val DEFAULT_FONT_SIZE = 14f
        const val DEFAULT_BACKGROUND_OPACITY = 1.0f
        const val DEFAULT_ISLAND_VERTICAL_OFFSET = 8f
    }

    // Modern Flow wrappers for SharedPreferences using a helper or manual callbackChannel would be best,
    // but for now, we'll expose a callback-based flow or simple StateFlow in ViewModel.
    // Let's make a simple Flow implementation.
    
    // Note: In a real app we'd use DataStore. Here we'll rely on the ViewModel to push updates 
    // or standard SharedPreferences listeners if needed. 
    // For the Service, it needs to observe changes.
    
    // Implementation: Flow that emits on preference change
    val isEnabled: Flow<Boolean> = prefFlow(KEY_ENABLED, false)
    val isTransparent: Flow<Boolean> = prefFlow(KEY_TRANSPARENT, false)
    val showSecondary: Flow<Boolean> = prefFlow(KEY_SHOW_SECONDARY, false)
    val fontSource: Flow<String> = prefFlow(KEY_FONT_SOURCE, "APP")
    val fontSize: Flow<Float> = prefFlow(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    val fontWeight: Flow<String> = prefFlow(KEY_FONT_WEIGHT, "REGULAR")
    val showArrow: Flow<Boolean> = prefFlow(KEY_SHOW_ARROW, true)
    val cornerRadius: Flow<Float> = prefFlow(KEY_CORNER_RADIUS, 28f)
    val backgroundOpacity: Flow<Float> = prefFlow(KEY_OPACITY, DEFAULT_BACKGROUND_OPACITY)
    val isDynamicIslandEnabled: Flow<Boolean> = prefFlow(KEY_DYNAMIC_ISLAND, false)
    val islandVerticalOffset: Flow<Float> = prefFlow(KEY_ISLAND_VERTICAL_OFFSET, DEFAULT_ISLAND_VERTICAL_OFFSET)
    val islandGap: Flow<Float> = prefFlow(KEY_ISLAND_GAP, 0f) // 0 implies auto/default
    val showNotificationDot: Flow<Boolean> = prefFlow(KEY_NOTIFICATION_DOT, true)
    val useSubtleOutline: Flow<Boolean> = prefFlow(KEY_SUBTLE_OUTLINE, false)



    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    fun setTransparent(transparent: Boolean) = prefs.edit().putBoolean(KEY_TRANSPARENT, transparent).apply()
    fun setDynamicIslandEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND, enabled).apply()
    fun setIslandVerticalOffset(offset: Float) = prefs.edit().putFloat(KEY_ISLAND_VERTICAL_OFFSET, offset).apply()
    fun setIslandGap(gap: Float) = prefs.edit().putFloat(KEY_ISLAND_GAP, gap).apply()
    fun setShowNotificationDot(show: Boolean) = prefs.edit().putBoolean(KEY_NOTIFICATION_DOT, show).apply()
    fun setUseSubtleOutline(use: Boolean) = prefs.edit().putBoolean(KEY_SUBTLE_OUTLINE, use).apply()
    fun setShowSecondary(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_SECONDARY, show).apply()
    fun setFontSource(source: String) = prefs.edit().putString(KEY_FONT_SOURCE, source).apply()
    fun setFontSize(size: Float) = prefs.edit().putFloat(KEY_FONT_SIZE, size).apply()
    fun setFontWeight(weight: String) = prefs.edit().putString(KEY_FONT_WEIGHT, weight).apply()
    fun setShowArrow(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_ARROW, show).apply()
    fun setCornerRadius(radius: Float) = prefs.edit().putFloat(KEY_CORNER_RADIUS, radius).apply()
    fun setBackgroundOpacity(opacity: Float) = prefs.edit().putFloat(KEY_OPACITY, opacity).apply()
    
    fun savePosition(position: OverlayPosition) {
        prefs.edit()
            .putFloat(KEY_POSITION_X, position.x)
            .putFloat(KEY_POSITION_Y, position.y)
            .apply()
    }

    fun resetPosition() = savePosition(OverlayPosition.CENTRE)

    /** Null until a position has been saved in the screen-relative form. */
    fun getPosition(): OverlayPosition? {
        if (!prefs.contains(KEY_POSITION_X) || !prefs.contains(KEY_POSITION_Y)) return null
        return OverlayPosition(prefs.getFloat(KEY_POSITION_X, 0f), prefs.getFloat(KEY_POSITION_Y, 0f))
    }

    /** Pixel position saved by versions before #685; only read once, to migrate it. */
    fun getLegacyPixelPosition(): Pair<Int, Int>? {
        if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y)) return null
        return prefs.getInt(KEY_X, 0) to prefs.getInt(KEY_Y, 0)
    }

    val position: Flow<OverlayPosition?> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == KEY_POSITION_X || k == KEY_POSITION_Y || k == null) {
                trySend(getPosition())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getPosition())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun <T> prefFlow(key: String, default: T): Flow<T> = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, k ->
            if (k == key || k == null) {
                trySend(getValue(sharedPreferences, key, default))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getValue(prefs, key, default)) // Initial value
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getValue(prefs: android.content.SharedPreferences, key: String, default: T): T {
        return when (default) {
            is Boolean -> prefs.getBoolean(key, default) as T
            is String -> prefs.getString(key, default) as T
            is Float -> prefs.getFloat(key, default) as T
            is Int -> prefs.getInt(key, default) as T
            else -> default // Should not happen
        }
    }
}
