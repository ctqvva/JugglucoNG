package tk.glucodata.webserver

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Collections
import tk.glucodata.Log

/**
 * The web server's TLS material: where it lives, how to generate it, and what to
 * tell the user about what is currently installed.
 *
 * The native server reads `fullchain.pem` and `privkey.pem` from the app's files
 * directory (`sslserver.cpp` resolves them against `globalbasedir`, which
 * `settings.cpp` sets to the files dir). It does not care where they came from,
 * so a generated pair and an imported one are interchangeable as far as it is
 * concerned — the difference is only ever presented in the UI.
 */
object WebServerCertificate {

    private const val TAG = "WebServerCert"
    private const val CERTIFICATE_FILE = "fullchain.pem"
    private const val PRIVATE_KEY_FILE = "privkey.pem"

    /** Marks a pair this app wrote, so the screen can tell it apart from an import. */
    private const val MARKER_FILE = "selfsigned.marker"

    private const val COMMON_NAME = "JugglucoNG"
    private const val KEY_SIZE_BITS = 2048

    /** Names the export files carry. They match the two import buttons. */
    private const val EXPORT_CERTIFICATE_NAME = "jugglucong-fullchain.pem"
    private const val EXPORT_PRIVATE_KEY_NAME = "jugglucong-privkey.pem"
    private const val EXPORT_DIRECTORY = "exports"

    /** What the settings screen needs to describe the installed certificate. */
    data class Installed(
        val selfSigned: Boolean,
        val subject: String,
        val hostnames: List<String>,
        val notAfterMillis: Long,
    ) {
        fun isExpired(nowMillis: Long): Boolean = notAfterMillis in 1 until nowMillis

        /** True when [host] is one of the names a browser will accept for this certificate. */
        fun covers(host: String): Boolean = hostnames.any { it.equals(host, ignoreCase = true) }
    }

    /** What the screen should say, and which action it should offer first. */
    enum class Status {
        /** Nothing installed. HTTPS cannot start until something is. */
        MISSING,

        /** Installed and answering for the address people are using. */
        READY,

        /**
         * Ours, still valid, but the device has moved to an address it was not
         * issued for. Worth offering to reissue, not worth doing silently: the
         * fingerprint would change under a certificate the user may already have
         * exported and trusted elsewhere.
         */
        ADDRESS_CHANGED,

        /** Past its notAfter. Browsers refuse it outright. */
        EXPIRED,
    }

    /**
     * [lanAddress] is the address the screen is telling people to visit, or null
     * when the server is loopback-only and no LAN name matters.
     *
     * A certificate the user imported is deliberately never reported as
     * ADDRESS_CHANGED: it may be issued to a domain name that has nothing to do
     * with whatever IP the phone holds today, and offering to replace someone's
     * CA-issued certificate because their DHCP lease moved would be wrong.
     */
    fun statusOf(installed: Installed?, lanAddress: String?, nowMillis: Long): Status = when {
        installed == null -> Status.MISSING
        installed.isExpired(nowMillis) -> Status.EXPIRED
        !installed.selfSigned -> Status.READY
        lanAddress != null && !installed.covers(lanAddress) -> Status.ADDRESS_CHANGED
        else -> Status.READY
    }

    fun certificateFile(context: Context): File = File(filesDir(context), CERTIFICATE_FILE)

    fun privateKeyFile(context: Context): File = File(filesDir(context), PRIVATE_KEY_FILE)

    fun hasCertificate(context: Context): Boolean =
        certificateFile(context).canRead() && privateKeyFile(context).canRead()

    /**
     * Reads back whatever is installed. Returns null when nothing is installed or
     * the files cannot be parsed — an imported certificate the app cannot read is
     * still perfectly usable by the server, so this only drives what is shown.
     */
    fun installed(context: Context): Installed? {
        if (!hasCertificate(context)) return null
        return runCatching {
            val certificate = certificateFile(context).inputStream().use { stream ->
                CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
            }
            Installed(
                selfSigned = File(filesDir(context), MARKER_FILE).exists(),
                subject = commonNameOf(certificate) ?: certificate.subjectX500Principal.name,
                hostnames = hostnamesOf(certificate),
                notAfterMillis = certificate.notAfter?.time ?: 0L,
            )
        }.onFailure { Log.stack(TAG, "installed", it) }.getOrNull()
    }

    /**
     * Generates a fresh key pair and self-signed certificate covering localhost
     * plus every address this device currently holds, and writes both PEM files.
     * Returns null on success or a message to show the user.
     *
     * Slow enough to be worth keeping off the main thread: RSA key generation
     * runs to a second or two on a phone.
     */
    fun generate(context: Context, nowMillis: Long = System.currentTimeMillis()): String? {
        return runCatching {
            val keyPair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(KEY_SIZE_BITS) }
                .generateKeyPair()

            val der = SelfSignedCertificate.create(
                keyPair = keyPair,
                commonName = COMMON_NAME,
                subjectAltNames = localSubjectAltNames(),
                notBeforeMillis = nowMillis,
            )

            // Write the key first and the certificate second: the server checks
            // for the certificate, so a half-written pair should not look ready.
            writeAtomically(privateKeyFile(context), pem("PRIVATE KEY", keyPair.private.encoded))
            writeAtomically(certificateFile(context), pem("CERTIFICATE", der))
            File(filesDir(context), MARKER_FILE).writeText(nowMillis.toString())
            null
        }.onFailure { Log.stack(TAG, "generate", it) }
            .getOrElse { it.message ?: "Could not generate a certificate" }
    }

    /**
     * Copies the installed certificate — and, when [includePrivateKey], the key
     * beside it — into the directory the app's FileProvider serves, and returns
     * the files to hand to a share intent.
     *
     * Staged rather than shared in place because the PEMs live in the app's
     * private files directory, which the provider does not expose; only
     * `cacheDir/exports` is declared in export_file_paths.xml. Previous staged
     * copies are cleared first so a private key does not sit in the cache longer
     * than the export that asked for it.
     */
    fun stageForExport(context: Context, includePrivateKey: Boolean): List<File> {
        val certificate = certificateFile(context)
        if (!certificate.canRead()) return emptyList()
        return runCatching {
            val directory = File(context.applicationContext.cacheDir, EXPORT_DIRECTORY)
            directory.mkdirs()
            directory.listFiles { file -> file.name.startsWith("jugglucong-") }
                ?.forEach { it.delete() }

            val staged = mutableListOf<File>()
            staged += File(directory, EXPORT_CERTIFICATE_NAME).also { certificate.copyTo(it, overwrite = true) }
            if (includePrivateKey) {
                val key = privateKeyFile(context)
                if (!key.canRead()) return emptyList()
                staged += File(directory, EXPORT_PRIVATE_KEY_NAME).also { key.copyTo(it, overwrite = true) }
            }
            staged
        }.onFailure { Log.stack(TAG, "stageForExport", it) }.getOrDefault(emptyList())
    }

    /** Called after an imported certificate lands, so it stops claiming to be ours. */
    fun clearSelfSignedMarker(context: Context) {
        runCatching { File(filesDir(context), MARKER_FILE).delete() }
    }

    /**
     * localhost and 127.0.0.1 always, plus whatever addresses the device holds
     * right now so LAN access does not add a name mismatch on top of the
     * untrusted-issuer warning. DHCP can move the phone, which is what the
     * settings screen's "does not cover this address" hint is for.
     */
    internal fun localSubjectAltNames(): SelfSignedCertificate.SubjectAltNames {
        val addresses = linkedMapOf<String, ByteArray>()
        addresses["127.0.0.1"] = byteArrayOf(127, 0, 0, 1)
        addresses["::1"] = ByteArray(16).also { it[15] = 1 }
        localAddresses().forEach { address ->
            address.hostAddress?.substringBefore('%')?.let { addresses[it] = address.address }
        }
        return SelfSignedCertificate.SubjectAltNames(
            dnsNames = listOf("localhost"),
            ipAddresses = addresses.values.toList(),
        )
    }

    private fun localAddresses(): List<InetAddress> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filter { it is Inet4Address || (it is Inet6Address && !it.isLinkLocalAddress) }
            .filterNot { it.isAnyLocalAddress || it.isMulticastAddress }
            .toList()
    }.getOrDefault(emptyList())

    private fun filesDir(context: Context): File = context.applicationContext.filesDir

    private fun commonNameOf(certificate: X509Certificate): String? =
        certificate.subjectX500Principal.name
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substring(3)

    /**
     * SAN entries as plain strings for display. `getSubjectAlternativeNames`
     * returns pairs of (type, value) where 2 is dNSName and 7 is iPAddress.
     */
    private fun hostnamesOf(certificate: X509Certificate): List<String> = runCatching {
        certificate.subjectAlternativeNames
            ?.mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int ?: return@mapNotNull null
                val value = entry.getOrNull(1) as? String ?: return@mapNotNull null
                if (type == 2 || type == 7) value else null
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun pem(label: String, der: ByteArray): String {
        val body = Base64.encodeToString(der, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    /**
     * A torn PEM file is worse than no PEM file: the server would fail to start
     * with an error about the key rather than about the missing certificate.
     */
    private fun writeAtomically(target: File, contents: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(contents)
        if (!temporary.renameTo(target)) {
            target.writeText(contents)
            temporary.delete()
        }
        target.setReadable(true, true)
    }
}
