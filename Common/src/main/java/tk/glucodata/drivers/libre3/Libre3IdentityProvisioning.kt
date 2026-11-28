// Libre3IdentityProvisioning.kt — how app identities reach this phone.
//
// Same shape as xDrip4iOS's Libre3IdentityStore: an identity set is an immutable, versioned JSON
// file (RoundWhiteDiscKit format) pinned by its SHA-256 digest. A source names the version, the
// digest and one or more mirror URLs; whatever a mirror serves is accepted only if it hashes to the
// pinned digest. Installed versions stay on the device (sealed, see Libre3Persistence) so pairing
// works offline, and a sensor keeps authorizing with the version it paired with even after a newer
// set is selected.
//
// The default download metadata matches xDrip4iOS Libre3IdentityStore.swift at f7447f1c.
// Only metadata ships in the APK. HTTPS downloads may be plain JSON or XZ-compressed JSON;
// both are bounded, and the uncompressed bytes must match the pinned digest before parsing.

package tk.glucodata.drivers.libre3

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A pinned identity set: `version|sha256hex|url [url…]`. */
data class Libre3IdentitySource(val version: String, val sha256: String, val urls: List<String>) {

    fun toSpec(): String = "$version|$sha256|${urls.joinToString(" ")}"

    companion object {
        private val VERSION = Regex("[A-Za-z0-9._-]{1,64}")
        private val DIGEST = Regex("[0-9a-f]{64}")

        /** Null for a blank or malformed spec; only https URLs are accepted. */
        fun parse(spec: String?): Libre3IdentitySource? {
            val parts = spec?.trim()?.split('|') ?: return null
            if (parts.size != 3) return null
            val version = parts[0].trim()
            val digest = parts[1].trim().lowercase()
            val urls = parts[2].split(' ', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (!VERSION.matches(version) || !DIGEST.matches(digest) || urls.isEmpty()) return null
            if (urls.any { !it.startsWith("https://") }) return null
            return Libre3IdentitySource(version, digest, urls)
        }

        /** A user-entered single URL + digest; the version is derived from the digest. */
        fun custom(url: String, sha256: String): Libre3IdentitySource? {
            val digest = sha256.trim().lowercase()
            return parse("custom-${digest.take(12)}|$digest|${url.trim()}")
        }
    }
}

object Libre3IdentityProvisioning {
    val defaultSource = Libre3IdentitySource(
        "app-identities-1",
        "ab18ce8bcb978526e102cc5e16440f06d2aee19426c26a15dd0013d66e9995ed",
        listOf(
            "https://arweave.net/qWbA3dXIHU2sU51oYtdYlouSfkCx-GczN2swLQb0upk",
            "https://turbo-gateway.com/qWbA3dXIHU2sU51oYtdYlouSfkCx-GczN2swLQb0upk",
        ),
    )

    /** Apple Compression's LZMA format is an XZ container, not a raw .lzma stream. */
    fun decodeDownload(bytes: ByteArray): ByteArray {
        if (bytes.size > MAX_FILE_BYTES) throw ProvisioningException("download too large")
        val xzMagic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)
        return if (bytes.size >= xzMagic.size && bytes.copyOfRange(0, xzMagic.size).contentEquals(xzMagic)) {
            org.tukaani.xz.XZInputStream(bytes.inputStream(), 80 * 1024).use { readBounded(it) }
        } else bytes
    }

    /** An identity file is a few hundred bytes per identity; anything larger is not one. */
    const val MAX_FILE_BYTES = 64 * 1024

    class ProvisioningException(message: String) : Exception(message)

    fun sha256Hex(bytes: ByteArray): String = Libre3Bytes.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Reads at most [max] bytes and refuses a longer stream before buffering it. */
    fun readBounded(input: InputStream, max: Int = MAX_FILE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw ProvisioningException("file is larger than ${max / 1024} KiB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Digest first, then structure: a file that does not hash to the pin is never parsed. */
    fun validate(bytes: ByteArray, sha256: String): List<Libre3AppIdentity> {
        val actual = sha256Hex(bytes)
        if (!actual.equals(sha256, ignoreCase = true)) {
            throw ProvisioningException("digest mismatch (got ${actual.take(12)}…)")
        }
        return Libre3AppIdentity.parseJson(String(bytes, Charsets.UTF_8))
    }

    /** Tries each mirror in order; the first one serving the pinned bytes wins. */
    fun fetch(source: Libre3IdentitySource, get: (String) -> ByteArray = ::httpsGet): ByteArray {
        val errors = ArrayList<String>()
        for (url in source.urls) {
            val bytes = try {
                decodeDownload(get(url))
            } catch (e: Exception) {
                errors += "${hostOf(url)}: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            try {
                validate(bytes, source.sha256)
                return bytes
            } catch (e: Exception) {
                errors += "${hostOf(url)}: ${e.message}"
            }
        }
        throw ProvisioningException(errors.joinToString("; ").ifEmpty { "no mirror answered" })
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    internal fun httpsRedirect(base: URL, location: String): URL = URL(base, location).also {
        if (it.protocol != "https") throw ProvisioningException("redirected off https")
    }

    fun httpsGet(url: String): ByteArray {
        var target = URL(url)
        repeat(5) {
            if (target.protocol != "https") throw ProvisioningException("source is not https")
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json, application/x-xz, */*")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw ProvisioningException("redirect without location")
                    target = httpsRedirect(target, location)
                    return@repeat
                }
                if (code !in 200..299) throw ProvisioningException("HTTP $code")
                val declared = connection.contentLengthLong
                if (declared > MAX_FILE_BYTES) throw ProvisioningException("file is larger than ${MAX_FILE_BYTES / 1024} KiB")
                return connection.inputStream.use { readBounded(it) }
            } finally {
                connection.disconnect()
            }
        }
        throw ProvisioningException("too many redirects")
    }
}
