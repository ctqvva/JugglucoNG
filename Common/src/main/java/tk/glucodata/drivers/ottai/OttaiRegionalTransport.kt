package tk.glucodata.drivers.ottai

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Regional gateways use webDeviceId for the cipher owner, independently of account identity. */
internal class OttaiRegionalTransport(
    private val route: Route,
    headers: Map<String, String>,
    private val send: (String, String, String?, Map<String, String>) -> OttaiNetSecurity.Reply,
) {
    private val identity = if (route.web) UUID.randomUUID().toString() else headers.getValue("deviceId")
    private val common = if (route.cn) headers + mapOf("versionName" to "1.63.0", "versionCode" to "264122") else headers + mapOf(
        // Current official Syai web client's secure-API compatibility version. Older values
        // leave cipher parameters undecoded, even when key exchange itself succeeds.
        "versionCode" to "10000000",
        "webDeviceId" to identity,
    ) + if (route.web) mapOf("deviceId" to headers.getValue("deviceFingerprinting")) else emptyMap()
    private val codec = OttaiNetSecurity(
        identity,
        exchange = { body -> send("POST", route.exchangeUrl, body.toString(),
            common + ("Content-Type" to "application/json;charset=UTF-8")) },
        send = { method, path, cipherHeaders, query, body ->
            val qs = query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            send(method, route.secureBase + path + if (qs.isEmpty()) "" else "?$qs", body,
                common + cipherHeaders + if (body == null) emptyMap() else
                    mapOf("Content-Type" to "application/json;charset=UTF-8"))
        },
        identityHeader = if (route.cn) "cipherUserId" else "webDeviceId",
        randomNonceId = route.web,
    )

    fun request(method: String, body: String?): OttaiNetSecurity.Reply =
        codec.requestReply(method, route.path, route.query, body)

    fun close() = codec.close()

    internal data class Route(
        val secureBase: String,
        val exchangeUrl: String,
        val path: String,
        val query: Map<String, String>,
        val web: Boolean,
        val cn: Boolean = false,
    )

    companion object {
        fun route(url: String, headers: Map<String, String> = emptyMap()): Route? {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) return null
            val web = uri.host == "www.syai.com" && uri.path.startsWith("/api/cgm/web/")
            val cn = uri.host == "api.ottai.com" && headers["appName"] == "ottai" &&
                headers["packageName"] == "com.ottai.tag"
            val mobile = (cn || uri.host in setOf("api.syai.com", "seas.ottai.com")) &&
                uri.path.startsWith(OttaiConstants.PREFIX + "/")
            if (!web && !mobile) return null
            val origin = "https://${uri.host}"
            val secureBase = origin + if (web) "/api/cgm/security/web" else "/cgm/security/app/server"
            val exchangeUrl = if (web) "$origin/api/cgm/security/official/server/security/exchangeKey"
                else "$secureBase/security/exchangeKey"
            val path = uri.path.removePrefix(if (web) "/api/cgm/web" else OttaiConstants.PREFIX)
            require(!path.contains(".."))
            val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.split('&')?.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }.orEmpty()
            return Route(secureBase, exchangeUrl, path, query, web, cn)
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
