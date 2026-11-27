package tk.glucodata.share.ican

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Vendor login password hash: Argon2id, salt = the password bytes themselves,
 * t=3, m=32768 KiB, p=1, version 0x13 (19), 32-byte output, lowercase hex.
 *
 * Constants recovered from the vendor app's Argon2Util (Dart AOT) and validated
 * against server behaviour (raw password is rejected, the hash is accepted).
 */
internal object ICanPasswordHash {

    fun hash(password: String): String {
        val bytes = password.toByteArray(Charsets.UTF_8)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(32768)
            .withParallelism(1)
            .withSalt(bytes)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(32)
        generator.generateBytes(bytes, out)
        val hex = StringBuilder(out.size * 2)
        for (b in out) {
            hex.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            hex.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return hex.toString()
    }
}
