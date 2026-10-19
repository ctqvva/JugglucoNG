package tk.glucodata.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicBoolean
import tk.glucodata.Log
import tk.glucodata.NotificationMultiSensorSource
import tk.glucodata.SensorIdentity

// The preference keys this registry owns. Declared here once; readers reference these instead of
// repeating the string, and WearPrefsSync mirrors exactly the definitions whose scope is MIRRORED.
const val KEY_SMOOTHING_MINUTES = "dashboard_chart_smoothing_minutes"
const val KEY_SMOOTHING_GRAPH_ONLY = "dashboard_data_smoothing_graph_only"
const val KEY_SMOOTHING_COLLAPSE_CHUNKS = "dashboard_data_smoothing_collapse_chunks"
const val KEY_SMOOTHING_EXCHANGE_OUTPUTS_ONLY = "dashboard_data_smoothing_exchange_outputs_only"
const val KEY_PREDICTION_ENABLED = "dashboard_predictive_simulation_enabled"
const val KEY_PREDICTION_TREND_MOMENTUM = "dashboard_prediction_trend_momentum_enabled"
const val KEY_PREDICTION_CARB_RATIO = "dashboard_prediction_carb_ratio_g_per_u"
const val KEY_PREDICTION_INSULIN_SENSITIVITY = "dashboard_prediction_insulin_sensitivity_mgdl_per_u"
const val KEY_PREDICTION_CARB_ABSORPTION = "dashboard_prediction_carb_absorption_g_per_h"
const val KEY_PREDICTION_HORIZON = "dashboard_prediction_horizon_minutes"
const val KEY_SENSOR_SELECTION = "dashboard_multi_sensor_selection_order"
const val KEY_SENSOR_COLORS = "sensor_color_overrides_argb"

// The defaults, declared once (plan §2.4). Before this, prediction-horizon 120 lived in four files
// and carb-absorption 35 in five, all agreeing only by hand.
const val DEFAULT_SMOOTHING_MINUTES = 0
const val DEFAULT_SMOOTHING_GRAPH_ONLY = false
const val DEFAULT_SMOOTHING_COLLAPSE_CHUNKS = false
const val DEFAULT_SMOOTHING_EXCHANGE_OUTPUTS_ONLY = false
const val DEFAULT_PREDICTION_ENABLED = true
const val DEFAULT_PREDICTION_TREND_MOMENTUM = true
const val DEFAULT_PREDICTION_CARB_RATIO_G_PER_U = 10f
const val DEFAULT_PREDICTION_INSULIN_SENSITIVITY_MGDL_PER_U = 54f
const val DEFAULT_PREDICTION_CARB_ABSORPTION_G_PER_H = 35f
const val DEFAULT_PREDICTION_HORIZON_MINUTES = 120

/** Where a setting belongs and who may write it. */
enum class SettingScope { PHONE, WATCH, MIRRORED }

/**
 * Whether the value travels with a backup/export — and, for [SECRET], that it does not travel to
 * the watch either. The export half is inert until O5 builds that feature; the watch half is live
 * ([SettingsRegistry.mirrored]) and is the reason a secret is a rule rather than a comment.
 */
enum class SettingBackup { INCLUDED, EXCLUDED, SECRET }

/** The stored type, and the wire tag [tk.glucodata.WearPrefsSync] has always used for it. */
enum class SettingType(val wire: String) { INT("i"), BOOL("b"), FLOAT("f"), STRING("s") }

/**
 * One user setting, declared once (plan §2.4): file, key, type, default, scope, backup policy and
 * an optional range. Reads go through the typed getters, so a value an old install stored as
 * another type falls back to the default and logs once instead of escaping as a ClassCastException
 * at the call site.
 */
class SettingDefinition(
    val key: String,
    val type: SettingType,
    val defaultValue: Any,
    val scope: SettingScope,
    val backup: SettingBackup = SettingBackup.EXCLUDED,
    val file: String = SettingsRegistry.FILE,
    val minValue: Float? = null,
    val maxValue: Float? = null,
    /**
     * A key whose stored form is not the effective value on the sending side (the sensor
     * selection: what travels is the list the phone actually draws, not the raw preference).
     */
    val effectiveString: (() -> String?)? = null,
) {
    private val loggedMismatch = AtomicBoolean(false)

    init {
        val matches = when (type) {
            SettingType.INT -> defaultValue is Int
            SettingType.BOOL -> defaultValue is Boolean
            SettingType.FLOAT -> defaultValue is Float
            SettingType.STRING -> defaultValue is String
        }
        require(matches) { "$key: default ${defaultValue::class.simpleName} does not match $type" }
    }

    fun prefs(context: Context?): SharedPreferences? =
        context?.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun readInt(prefs: SharedPreferences?): Int {
        val fallback = defaultValue as Int
        return guarded(prefs, fallback) { it.getInt(key, fallback) }
    }

    fun readBool(prefs: SharedPreferences?): Boolean {
        val fallback = defaultValue as Boolean
        return guarded(prefs, fallback) { it.getBoolean(key, fallback) }
    }

    fun readFloat(prefs: SharedPreferences?): Float {
        val fallback = defaultValue as Float
        return guarded(prefs, fallback) { it.getFloat(key, fallback) }
    }

    fun readString(prefs: SharedPreferences?): String {
        val fallback = defaultValue as String
        return guarded(prefs, fallback) { it.getString(key, fallback).orEmpty() }
    }

    fun readInt(context: Context?): Int = readInt(prefs(context))
    fun readBool(context: Context?): Boolean = readBool(prefs(context))
    fun readFloat(context: Context?): Float = readFloat(prefs(context))
    fun readString(context: Context?): String = readString(prefs(context))

    /** The declared range, applied the way every previous reader did with coerceIn. */
    fun readIntClamped(prefs: SharedPreferences?): Int = clamp(readInt(prefs).toFloat()).toInt()
    fun readIntClamped(context: Context?): Int = readIntClamped(prefs(context))
    fun readFloatClamped(prefs: SharedPreferences?): Float = clamp(readFloat(prefs))
    fun readFloatClamped(context: Context?): Float = clamp(readFloat(prefs(context)))

    private fun clamp(value: Float): Float {
        var result = value
        minValue?.let { result = maxOf(result, it) }
        maxValue?.let { result = minOf(result, it) }
        return result
    }

    private fun <T> guarded(prefs: SharedPreferences?, fallback: T, read: (SharedPreferences) -> T): T {
        if (prefs == null) return fallback
        return try {
            read(prefs)
        } catch (t: ClassCastException) {
            if (loggedMismatch.compareAndSet(false, true)) {
                Log.stack(TAG, "$key holds a value of another type; using the default", t)
            }
            fallback
        }
    }

    private companion object {
        const val TAG = "SettingsRegistry"
    }
}

/**
 * Every user setting declared once (plan §2.4). The pilot is the set [WearPrefsSync] already
 * mirrors; [WearPrefsSync] is generated from [mirrored] rather than keeping its own list, so a
 * default cannot disagree with itself.
 */
object SettingsRegistry {
    const val FILE = "tk.glucodata_preferences"
    private const val SELECTION_SEPARATOR = ","

    val SMOOTHING_MINUTES = SettingDefinition(
        KEY_SMOOTHING_MINUTES, SettingType.INT, DEFAULT_SMOOTHING_MINUTES,
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )
    val SMOOTHING_GRAPH_ONLY = SettingDefinition(
        KEY_SMOOTHING_GRAPH_ONLY, SettingType.BOOL, DEFAULT_SMOOTHING_GRAPH_ONLY,
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )
    val SMOOTHING_COLLAPSE_CHUNKS = SettingDefinition(
        KEY_SMOOTHING_COLLAPSE_CHUNKS, SettingType.BOOL, DEFAULT_SMOOTHING_COLLAPSE_CHUNKS,
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )
    val SMOOTHING_EXCHANGE_OUTPUTS_ONLY = SettingDefinition(
        KEY_SMOOTHING_EXCHANGE_OUTPUTS_ONLY, SettingType.BOOL, DEFAULT_SMOOTHING_EXCHANGE_OUTPUTS_ONLY,
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )
    val PREDICTION_ENABLED = SettingDefinition(
        KEY_PREDICTION_ENABLED, SettingType.BOOL, DEFAULT_PREDICTION_ENABLED,
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )
    val PREDICTION_TREND_MOMENTUM = SettingDefinition(
        KEY_PREDICTION_TREND_MOMENTUM, SettingType.BOOL, DEFAULT_PREDICTION_TREND_MOMENTUM,
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )
    val PREDICTION_CARB_RATIO = SettingDefinition(
        KEY_PREDICTION_CARB_RATIO, SettingType.FLOAT, DEFAULT_PREDICTION_CARB_RATIO_G_PER_U,
        SettingScope.MIRRORED, SettingBackup.INCLUDED, minValue = 3f, maxValue = 30f,
    )
    val PREDICTION_INSULIN_SENSITIVITY = SettingDefinition(
        KEY_PREDICTION_INSULIN_SENSITIVITY, SettingType.FLOAT, DEFAULT_PREDICTION_INSULIN_SENSITIVITY_MGDL_PER_U,
        SettingScope.MIRRORED, SettingBackup.INCLUDED, minValue = 10f, maxValue = 180f,
    )
    val PREDICTION_CARB_ABSORPTION = SettingDefinition(
        KEY_PREDICTION_CARB_ABSORPTION, SettingType.FLOAT, DEFAULT_PREDICTION_CARB_ABSORPTION_G_PER_H,
        SettingScope.MIRRORED, SettingBackup.INCLUDED, minValue = 10f, maxValue = 90f,
    )
    val PREDICTION_HORIZON = SettingDefinition(
        KEY_PREDICTION_HORIZON, SettingType.INT, DEFAULT_PREDICTION_HORIZON_MINUTES,
        SettingScope.MIRRORED, SettingBackup.INCLUDED, minValue = 30f, maxValue = 360f,
    )
    val SENSOR_SELECTION = SettingDefinition(
        KEY_SENSOR_SELECTION, SettingType.STRING, "",
        SettingScope.MIRRORED, SettingBackup.INCLUDED, effectiveString = ::effectiveSensorSelection,
    )
    val SENSOR_COLORS = SettingDefinition(
        KEY_SENSOR_COLORS, SettingType.STRING, "",
        SettingScope.MIRRORED, SettingBackup.INCLUDED,
    )

    val definitions: List<SettingDefinition> = listOf(
        SMOOTHING_MINUTES,
        SMOOTHING_GRAPH_ONLY,
        SMOOTHING_COLLAPSE_CHUNKS,
        SMOOTHING_EXCHANGE_OUTPUTS_ONLY,
        PREDICTION_ENABLED,
        PREDICTION_TREND_MOMENTUM,
        PREDICTION_CARB_RATIO,
        PREDICTION_INSULIN_SENSITIVITY,
        PREDICTION_CARB_ABSORPTION,
        PREDICTION_HORIZON,
        SENSOR_SELECTION,
        SENSOR_COLORS,
    )

    /**
     * Every setting that travels phone→watch.
     *
     * Scope says a setting belongs on both devices; this filter is what keeps a **secret** off the
     * watch. It is a property of the code rather than of the list, because a `MIRRORED` + `SECRET`
     * definition is exactly what the first D1 watch feature wants to declare (a Nightscout URL, an
     * API token — #498) and today nothing in the registry stops it from going out on the wire.
     *
     * Per #498 the watch may hold a credential only for an output the user switched on there,
     * so a secret reaches it through that output's own opt-in path, never through this generic
     * mirror. Today no definition declares [SettingBackup.SECRET], so this changes nothing yet.
     */
    val mirrored: List<SettingDefinition> = mirroredFrom(definitions)

    /**
     * What [definitions] sends to the watch, as a function of the list rather than of this
     * object, so a test can hand it a definition that does not exist yet.
     */
    internal fun mirroredFrom(definitions: List<SettingDefinition>): List<SettingDefinition> =
        definitions.filter { travelsToWatch(it.scope, it.backup) }

    /** `MIRRORED` scope, and not a secret: what the watch is allowed to see. */
    fun travelsToWatch(scope: SettingScope, backup: SettingBackup): Boolean =
        scope == SettingScope.MIRRORED && backup != SettingBackup.SECRET

    private val byKey = definitions.associateBy { it.key }

    fun find(key: String): SettingDefinition? = byKey[key]

    /** Phone: the sensors it displays, primary first. */
    private fun effectiveSensorSelection(): String? = runCatching {
        val primary = SensorIdentity.resolveMainSensor()
        selectionToWire(NotificationMultiSensorSource.selectedSensorIds(primary))
    }.getOrNull()

    /**
     * The payload is line-based and `MultiSensorSelection` stores its list one id per line, so the
     * list travels with its own separator instead.
     */
    fun selectionToWire(sensorIds: List<String>): String =
        sensorIds.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(SELECTION_SEPARATOR)

    fun selectionFromWire(raw: String): List<String> =
        raw.split(SELECTION_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
}
