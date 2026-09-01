package tk.glucodata.drivers.aidex

import android.os.Build
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import org.json.JSONObject
import tk.glucodata.Log

internal data class AiDexCnResult<T>(
    val value: T? = null,
    val error: String = "",
    val code: Int? = null,
) {
    val isSuccess: Boolean get() = value != null && error.isBlank()
}

internal data class AiDexProvisionedKeys(
    val secret: ByteArray,
    val iv: ByteArray,
)

/**
 * Minimal client for the official AiDEX CN account and sensor-specific key endpoint.
 *
 * The vendor API encrypts every JSON request and response with app-embedded RSA keys. Credentials,
 * tokens, and returned pairing material are deliberately excluded from logs.
 */
internal object AiDexCnCloudClient {
    private const val TAG = "AiDexCnCloud"
    private const val BASE_URL = "https://china.pancares.com"
    internal const val SEND_PHONE_CODE = "/backend/aidex-x/user/sendRegisterPhoneVerificationCode"
    private const val LOGIN_WITH_CODE = "/backend/aidex-x/user/loginOrRegisterByVerificationCodeWithPhone"
    private const val LOGIN_WITH_PASSWORD = "/backend/aidex-x/user/loginByPassword"
    private const val GET_SN_CONFIG = "/backend/aidex-x/cgmDevice/getSnConfig"
    private const val TIMEOUT_MS = 20_000

    /**
     * The sensor-key endpoint mirrors the official app's China-only availability check. The
     * backend also accepts this compatibility routing header, which is required outside China.
     */
    private const val CN_ROUTING_IP = "220.181.38.148"

    fun normalizeCnPhone(raw: String): String? {
        var digits = raw.filter(Char::isDigit)
        if (digits.startsWith("0086") && digits.length == 15) digits = digits.drop(4)
        if (digits.startsWith("86") && digits.length == 13) digits = digits.drop(2)
        return digits.takeIf { it.length == 11 && it[0] == '1' && it[1] in '3'..'9' }
    }

    fun requestLoginCode(phone: String): AiDexCnResult<Unit> {
        val normalized = normalizeCnPhone(phone)
            ?: return AiDexCnResult(error = "Invalid mainland China phone number")
        // The vendor's SMS sign-in is a combined login-or-register flow. Its AccountViewModel
        // requests the registration code before calling loginOrRegisterByVerificationCodeWithPhone;
        // the separately declared sendLoginPhoneVerificationCode endpoint is not used there and
        // currently answers with business code 500.
        val response = post(SEND_PHONE_CODE, JSONObject().put("phone", normalized))
        return if (response.value != null) {
            AiDexCnResult(Unit, code = response.code)
        } else {
            AiDexCnResult(error = response.error, code = response.code)
        }
    }

    fun loginWithCode(phone: String, code: String): AiDexCnResult<String> {
        val normalized = normalizeCnPhone(phone)
            ?: return AiDexCnResult(error = "Invalid mainland China phone number")
        if (code.isBlank()) return AiDexCnResult(error = "Verification code is required")
        return login(
            LOGIN_WITH_CODE,
            JSONObject().put("phone", normalized).put("code", code.trim()),
        )
    }

    fun loginWithPassword(phone: String, password: String): AiDexCnResult<String> {
        val normalized = normalizeCnPhone(phone)
            ?: return AiDexCnResult(error = "Invalid mainland China phone number")
        if (password.isBlank()) return AiDexCnResult(error = "Password is required")
        return login(
            LOGIN_WITH_PASSWORD,
            JSONObject()
                .put("userName", normalized)
                .put("password", AiDexCnProtocol.md5Hex(password)),
        )
    }

    fun getProvisionedKeys(serial: String, token: String): AiDexCnResult<AiDexProvisionedKeys> {
        val bareSerial = AiDexSerialIdentity.bareSerial(serial)
        if (bareSerial.isBlank()) return AiDexCnResult(error = "Invalid sensor serial")
        if (token.isBlank()) return AiDexCnResult(error = "Sign in is required")

        val random = ByteArray(8).also(SecureRandom()::nextBytes).toHex()
        val timestamp = System.currentTimeMillis().toString()
        val body = AiDexCnProtocol.signedSnConfigBody(bareSerial, random, timestamp)
        val response = post(GET_SN_CONFIG, body, token, useCnRouting = true)
        val data = response.value ?: return AiDexCnResult(error = response.error, code = response.code)
        val responseSerial = data.optString("deviceSn")
        if (responseSerial.isNotBlank() &&
            !AiDexSerialIdentity.bareSerial(responseSerial).equals(bareSerial, ignoreCase = true)
        ) {
            return AiDexCnResult(error = "Server returned pairing material for another sensor")
        }
        val secret = AiDexCnProtocol.decodeMaterial(data.optString("publicKey"))
        val iv = AiDexCnProtocol.decodeMaterial(data.optString("communicationKey"))
        if (secret == null || iv == null) {
            return AiDexCnResult(error = "Server returned invalid pairing material")
        }
        return AiDexCnResult(AiDexProvisionedKeys(secret, iv), code = response.code)
    }

    private fun login(path: String, body: JSONObject): AiDexCnResult<String> {
        val response = post(path, body)
        val token = response.value?.optString("token").orEmpty()
        return if (token.isNotBlank()) {
            AiDexCnResult(token, code = response.code)
        } else {
            AiDexCnResult(error = response.error.ifBlank { "The server did not return a session" }, code = response.code)
        }
    }

    private fun post(
        path: String,
        body: JSONObject,
        token: String = "",
        useCnRouting: Boolean = false,
    ): AiDexCnResult<JSONObject> {
        var connection: HttpURLConnection? = null
        return try {
            val envelope = AiDexCnProtocol.encryptEnvelope(body.toString())
            val bytes = envelope.toByteArray(Charsets.UTF_8)
            connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                setRequestProperty("Content-Length", bytes.size.toString())
                setRequestProperty("encryption", "enabled")
                setRequestProperty("X-Key-version", "v2")
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                setRequestProperty(
                    "User-Agent",
                    "android${Build.VERSION.SDK_INT}-${Build.BRAND}-${Build.MODEL},com.microtech.aidexx,1.15.1",
                )
                // This compatibility header belongs only to getSnConfig's China availability
                // gate. Account requests should match the official client and use their real IP.
                if (useCnRouting) setRequestProperty("X-Forwarded-For", CN_ROUTING_IP)
                if (token.isNotBlank()) setRequestProperty("x-token", token)
                outputStream.use { it.write(bytes) }
            }
            val httpCode = connection.responseCode
            val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val decoded = AiDexCnProtocol.decryptEnvelope(responseText)
            val json = decoded?.let(::JSONObject)
                ?: return AiDexCnResult(error = "Invalid server response")
            val businessCode = json.optInt("code", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
            val ok = httpCode in 200..299 && businessCode == 200
            if (!ok) {
                val message = json.optString("msg").ifBlank { json.optString("message") }
                Log.w(TAG, "$path -> http=$httpCode biz=${businessCode ?: "?"}")
                AiDexCnResult(
                    error = "http=$httpCode biz=${businessCode ?: "?"} ${message.take(120)}".trim(),
                    code = businessCode,
                )
            } else {
                Log.i(TAG, "$path -> http=$httpCode")
                AiDexCnResult(json.optJSONObject("data") ?: JSONObject(), code = businessCode)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$path request failed: ${t.message}")
            AiDexCnResult(error = "Network error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }
}

/** Pure request signing and vendor-envelope crypto, kept testable without Android state. */
internal object AiDexCnProtocol {
    private const val APP_SECRET = "4ed6aa2b43a04251b6f78db47f870906"
    private const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxMh1zL8SIgJ+3Cpi5k2DNB9O3/a5XqDhX6d9JxMuQ9PJwYDLU4Qvv4AnAHB3mmiQVaGCqgSjUxBGgH5eG738ofh9rj8779+sKUPmRtp8pv8NsQsVXWH90ZiRl5RAi5NtGe4ztp05f98T9C8K8de3c40uNRmJ3VE08agm6L9is/QbGV2KO/7nStYILiSqm0xfgv80KaFVTpm8rDLP+R2mGpW5giET4ePyMcq7JikSbxZLC1cqqr1fobxuWo8KqN5X8GN4V+TPA4944gzcQDPTynrndJlb9hTpoBTkfHAJnYLJAbI3NP/bsvWTqPpCnqHuEihAdJNTP7vACiSJOk24JwIDAQAB"
    private const val PRIVATE_KEY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDl/TtBd/zpLD8XohNE43D43tivCT1d8poBqlX7ZjZe0z+MJPY9ildJoMziU8EOxL14iCpcjPYCBW013XJKEuMRZF6Co9c20TZAxfEJTqtKXoZ/wmva3CO1M8sogvHygDMOrqVdkrRDIfONkqPFZcLKkhrQaQUshrTFN7ljEgeD4FHK/FeHRcawq7oZZ3ekjEKToFNvcXmkeqI1Ie2JtQu52a9NXpWtGqFQoNdHAIsQGKQlQ3ZIQEp81XDip1D5DvMWPJq8dfIlmx0toi/8rZ0msNRkPHBEj3WtJCxW7zBLwOgGf2ehDwz9sNIAoWK50ixwfPRtjn8yuyDd6tj91wgFAgMBAAECggEADkN4x9QptspcNVqDaGvUp1KmzKA9kpSHsZ+/SmKp75KZZ9cpAGMSi8nxuv2nxQM1nwKO4gOKBFES11rfCjIGrB7175M6tzcdbsH383RSEkAvhj/oEYBCpKvMJzyLxdbmk7wIHHgLPvidksOr7YOko4yRJijFPeAy2OJeWIMXPTnvrg4m2CCCIKDMhRADkKM4LbbVsLJU6osg4/hdmuNNqOgoaQoL/YG+DriGC2g5s70j44+6emNBCvgUo7j/sBS/7jtXmXMhi+pwQ4ATpbmrBo2mDNOrzHwXXoF+6MQ2QfPw67T1D53cFlPvKncx/ZW0yeIBw63L8yuM/D5aEzsBoQKBgQD3vVkwaSzAC4hFSesE/j4RPgglz9I/WcbatpkD3pX1wx+UQZNLFMhZimmoQ/kQ1gpGxj9n1Uoe5nop60dYKT0BEcpA1wlhh9HJltq4cAQfCojtzU2YDhvJqA3uAx8TFEFRD/3KqPsMI07/e0Pg7+hMhblNRoL29aLYkP5XnuiSEQKBgQDtqF55g9u549gWDylE2bbaU1uP0G75fR5STj28dhAXrr9QOVWifH/IU+TQmkTpbY5HKA35V4x8oljokKuPDlYF2834+fFF8gdq9cUIZP5ZJ9wnz7sgYgtAyGjyglnL5MGCxGDgO1Gm9mioE2dd5seOYM9Tx+9OnCtzjV37rmmitQKBgQCpB4UR9cC1q2intd5ngryAcS2H9vrBhJSb55gRPs5cZ2xlcDR6NszX4wth5jbKmO9cCKl8Q9eVq7VQYppD+acI0sWHZfCdndiyTX4f9zWopDx22+wEQiQNe989NN3/24MRNvL3UkIvruuYftb6Y1XA2EBtDB4RN9mLH6qZx+9wsQKBgEopZ0neJtwMSKshkgxFMDVTG9h1/5tlOugOOF+uK/ln85VyPtHUrf9yho2+BlEdee+khS/Q7SsbckkolBCxNZDgdZcDUBI2o6/x/8lN0r05ng7iWQ+S6NYPdAhxOtpQiT5oT57JhAJpFGGWpIP99znr5ebMFlZej4SgkbpvQ9GdAoGAfQ7Joz29e8C1c0mnF6QUSf4XcIx/rP0gtFmepSOpOTF8i+w4OAo3+iko+j3tcBHH1XoZwV7ga6oTO+NizsjJoiKzulS9RoPwU268dXPq8C4ZZ7YaK8JemfiEuLI7XqKrNjogvHrD0xkuuzMK3W7Fz7vzJNMTkSiCfqIkOx84tO4="

    fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    fun integritySign(deviceSn: String, randomStr: String, timestamp: String): String {
        val fields = sortedMapOf(
            "deviceSn" to deviceSn,
            "randomStr" to randomStr,
            "timestamp" to timestamp,
        )
        val canonical = fields.entries.joinToString("&") { "${it.key}=${it.value}" }
        return md5Hex("$canonical&key=$APP_SECRET").uppercase(Locale.ROOT)
    }

    fun signedSnConfigBody(deviceSn: String, randomStr: String, timestamp: String): JSONObject =
        JSONObject()
            .put("body", JSONObject().put("deviceSn", deviceSn))
            .put(
                "integrityData",
                JSONObject()
                    .put("randomStr", randomStr)
                    .put("timestamp", timestamp)
                    .put("sign", integritySign(deviceSn, randomStr, timestamp)),
            )

    fun decodeMaterial(encoded: String): ByteArray? = runCatching {
        val decoded = if (encoded.length == 32 && encoded.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            Base64.getDecoder().decode(encoded)
        }
        decoded.takeIf { it.size == 16 }
    }.getOrNull()

    fun encryptEnvelope(plainJson: String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)),
        ) as RSAPublicKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val input = plainJson.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        input.asList().chunked(key.modulus.bitLength() / 8 - 11).forEach { chunk ->
            output.write(cipher.doFinal(chunk.toByteArray()))
        }
        return JSONObject().put("encryptData", Base64.getEncoder().encodeToString(output.toByteArray())).toString()
    }

    fun decryptEnvelope(response: String): String? = runCatching {
        val outer = JSONObject(response)
        val encrypted = outer.optString("encryptData")
        if (encrypted.isBlank()) return@runCatching response
        val key = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY)),
        ) as RSAPrivateKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val input = Base64.getDecoder().decode(encrypted)
        val blockSize = key.modulus.bitLength() / 8
        require(input.size % blockSize == 0)
        val output = ByteArrayOutputStream()
        input.asList().chunked(blockSize).forEach { chunk ->
            output.write(cipher.doFinal(chunk.toByteArray()))
        }
        output.toString(Charsets.UTF_8.name())
    }.getOrNull()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
