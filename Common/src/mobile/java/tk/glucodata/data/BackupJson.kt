package tk.glucodata.data

import org.json.JSONObject

// Android's JSONObject getters can coerce invalid field types. Defaults apply only to fields
// omitted by older backups, so malformed values cannot silently turn into different records.
private inline fun <reified T : Any> JSONObject.backupValue(name: String): T =
    requireNotNull(get(name) as? T) { "Invalid backup field: $name" }

internal fun JSONObject.backupLong(name: String, default: Long? = null): Long {
    if (!has(name) && default != null) return default
    return requireNotNull(backupValue<Number>(name).toString().toLongOrNull()) { "Invalid backup integer: $name" }
}

internal fun JSONObject.backupInt(name: String, default: Int? = null): Int {
    val value = backupLong(name, default?.toLong())
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid backup integer: $name" }
    return value.toInt()
}

internal fun JSONObject.backupFloat(name: String, default: Float? = null): Float {
    if (!has(name) && default != null) return default
    val value = backupValue<Number>(name).toFloat()
    require(value.isFinite()) { "Invalid backup number: $name" }
    return value
}

internal fun JSONObject.backupBoolean(name: String, default: Boolean): Boolean =
    if (has(name)) backupValue(name) else default

internal fun JSONObject.backupString(name: String, default: String? = null): String =
    if (!has(name) && default != null) default else backupValue(name)
