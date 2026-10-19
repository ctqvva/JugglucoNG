package tk.glucodata.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import tk.glucodata.Log
import java.io.File
import java.security.MessageDigest

/**
 * Local checks run on a downloaded APK *before* it is handed to the installer.
 *
 * The SHA-256 in `update-manifest.json` travels over the same connection as the APK URL, so on
 * its own it proves nothing against a compromised release: whoever can swap the APK can swap the
 * digest with it. The check that does not share that fate is the signing certificate — an APK
 * signed with a different key cannot update this install no matter what the metadata claims.
 * Android enforces that at commit time anyway; doing it here turns a baffling
 * "App not installed" dialog into a specific warning, before the user is asked to confirm
 * anything.
 */
object ApkVerifier {

    private const val LOG_TAG = "AppUpdate"

    /** Null when the file is safe to install; otherwise the reason it is not. */
    fun verify(context: Context, file: File, update: AvailableUpdate): UpdateError? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: run {
            Log.w(LOG_TAG, "downloaded update is not a readable APK")
            return UpdateError.PACKAGE_MISMATCH
        }

        if (archive.packageName != context.packageName) {
            Log.w(LOG_TAG, "downloaded update is for ${archive.packageName}")
            return UpdateError.PACKAGE_MISMATCH
        }

        val installed = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val installedCode = installed?.let { versionCodeOf(it) } ?: 0L
        val candidateCode = versionCodeOf(archive)
        // Equal is rejected too: reinstalling the same build is never what the card offered, and
        // it would mean a metadata bug pointed us at the running version.
        if (candidateCode <= installedCode) {
            Log.w(LOG_TAG, "refusing downgrade: $candidateCode <= $installedCode")
            return UpdateError.DOWNGRADE
        }
        update.versionCode?.let { declared ->
            if (declared.toLong() != candidateCode) {
                Log.w(LOG_TAG, "APK version code $candidateCode does not match manifest $declared")
                return UpdateError.PACKAGE_MISMATCH
            }
        }

        if (!signedLikeInstalledApp(context, file, archive)) {
            Log.w(LOG_TAG, "downloaded update has a different signing certificate")
            return UpdateError.SIGNATURE
        }
        return null
    }

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? = runCatching {
        pm.getPackageArchiveInfo(file.absolutePath, signatureFlags())
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /**
     * True when the downloaded APK carries a signer this install already trusts.
     *
     * On API 28+ [PackageManager.hasSigningCertificate] is asked first, because it understands
     * signing-key rotation: an APK signed with a rotated successor key is legitimate even though
     * its certificate digest differs from the installed one. The digest comparison is the
     * fallback for older releases and for the rotation-free case.
     */
    private fun signedLikeInstalledApp(
        context: Context,
        file: File,
        archive: PackageInfo
    ): Boolean {
        val pm = context.packageManager
        val candidateCerts = signingCertificates(archive)
        if (candidateCerts.isEmpty()) {
            Log.w(LOG_TAG, "downloaded update ${file.name} carries no signature")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val accepted = candidateCerts.any { cert ->
                runCatching {
                    pm.hasSigningCertificate(
                        context.packageName,
                        cert,
                        PackageManager.CERT_INPUT_RAW_X509
                    )
                }.getOrDefault(false)
            }
            if (accepted) return true
        }

        val installedCerts = runCatching {
            pm.getPackageInfo(context.packageName, signatureFlags())
        }.getOrNull()?.let(::signingCertificates).orEmpty()
        if (installedCerts.isEmpty()) return false

        val installedDigests = installedCerts.mapTo(HashSet()) { sha256(it) }
        return candidateCerts.any { sha256(it) in installedDigests }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            return signers?.map { it.toByteArray() }.orEmpty()
        }
        return info.signatures?.map { it.toByteArray() }.orEmpty()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
