package tk.glucodata

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Nightscout's `/api/v2/authorization/request/<access token>` hands back, as far as the
 * app reads it: the JWT, when it stops being valid, and the permissions it grants. The
 * permissions travel inside the token, so a role changed on the server is invisible until
 * the next exchange -- which is why the settings screen can force one and show this.
 */
data class NightscoutTokenGrant(
    val token: String,
    val expiresAtMillis: Long,
    val permissions: List<String>
) {
    companion object {
        /** Null when the body carries no token: a refusal, or not Nightscout at all. */
        @JvmStatic
        fun parse(body: String): NightscoutTokenGrant? {
            val obj = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
            val token = obj.optString("token").trim()
            if (token.isEmpty()) return null
            // Seconds since the epoch on the wire; 0 when absent, so an odd server answers
            // "expired" rather than "valid forever".
            val exp = obj.optLong("exp", 0L)
            return NightscoutTokenGrant(
                token = token,
                expiresAtMillis = exp * 1000L,
                permissions = permissionsOf(obj.opt("permissionGroups"))
            )
        }

        /**
         * `permissionGroups` is a list of lists (one per role), flattened here in order and
         * without repeats. A bare "*" is what an admin token says, and it stays a "*".
         */
        internal fun permissionsOf(groups: Any?): List<String> {
            val array = groups as? JSONArray ?: return emptyList()
            val out = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                when (val group = array.opt(i)) {
                    is JSONArray -> for (j in 0 until group.length()) {
                        group.optString(j).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                    }
                    is String -> group.trim().takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
            return out.toList()
        }

        /** The server's own words for a refusal, or the start of whatever it sent instead. */
        @JvmStatic
        fun refusalMessage(body: String): String {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return trimmed.take(160)
            val message = runCatching {
                JSONObject(trimmed).let { obj ->
                    sequenceOf("message", "description")
                        .map { obj.optString(it).trim() }
                        .firstOrNull { it.isNotEmpty() }
                }
            }.getOrNull()
            return message ?: trimmed.take(160)
        }
    }
}
