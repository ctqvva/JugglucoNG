package tk.glucodata.drivers.ottai

import java.net.URI
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/** GBL v3 envelope validation, not a replacement for the sensor's signature verification. */
internal class OttaiOtaPackage private constructor(
    private val content: ByteArray,
    val sha256: String,
) {
    val size: Int get() = content.size
    fun chunk(offset: Int, size: Int): ByteArray = content.copyOfRange(offset, minOf(offset + size, content.size))
    fun bytes(): ByteArray = content.copyOf()

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val HEADER = 0x03A617EBL
        private const val END = 0xFC0404FCL
        private val tags = setOf(HEADER, END, 0xF40A0AF4L, 0xF50909F5L, 0xFE0101FEL,
            0xF60808F6L, 0xF70A0AF7L, 0xF30B0BF3L, 0xFB0505FBL,
            0x5EA617EBL, 0x76A617EBL)

        fun parse(bytes: ByteArray): OttaiOtaPackage {
            require(bytes.size in 28..MAX_BYTES) { "gbl_size" }
            fun u32(at: Int): Long = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            require(u32(0) == HEADER && u32(4) == 8L && u32(8) ushr 24 == 3L) { "gbl_header" }
            var at = 0
            var end = -1
            while (at < bytes.size) {
                require(bytes.size - at >= 8) { "gbl_truncated" }
                val tag = u32(at)
                val length = u32(at + 4)
                require(tag in tags || tag in setOf(0xFA0606FAL, 0xF90707F9L, 0xFD0303FDL,
                    0xFD0505FDL, 0xFD0707FDL)) { "gbl_tag" }
                require(at == 0 || tag != HEADER) { "gbl_header_duplicate" }
                require(length <= bytes.size.toLong() - at - 8) { "gbl_length" }
                at += 8 + length.toInt()
                if (tag == END) {
                    require(length == 4L) { "gbl_end" }
                    end = at
                    break
                }
            }
            require(end > 0 && bytes.copyOfRange(end, bytes.size).all { it == 0xff.toByte() }) { "gbl_end" }
            require(CRC32().apply { update(bytes, 0, end) }.value == 0x2144df1cL) { "gbl_crc" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            return OttaiOtaPackage(bytes.copyOf(), digest)
        }
    }
}

internal object OttaiOtaProtocol {
    // Recovered CN 1.44 sequence in PR #727, deliberately not the standard SiLabs 0x03 finish.
    const val FINISH: Byte = 0x06
    const val WRITE_DELAY_MS = 60L
    // 2a28 is a vendor string, not a semantic version. The cloud API and journal need it intact.
    // 512 bytes is the BLE attribute limit; only empty/control/non-ASCII values are rejected.
    /**
     * The 2a28 value is a C string in a fixed buffer: field logs show
     * `E1.2.6(V1.7.SH2616.1),STDRD\0G`, i.e. a terminator followed by stale bytes. Keep only
     * what precedes the first NUL so [version] does not reject the whole revision.
     */
    fun revisionText(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.copyOfRange(0, end).toString(Charsets.US_ASCII)
    }

    fun version(value: String): String? = value.trim { it.isWhitespace() || it == '\u0000' }
        .takeIf { it.length in 1..512 && it.all { ch -> ch in ' '..'~' } }

    private val applicationPattern = Regex("[Vv]?([0-9]+(?:\\.[0-9]+)*)([._+-][A-Za-z0-9][A-Za-z0-9._+-]*)?")
    private val compositePattern = Regex("[Vv]?[Ee][0-9]+(?:\\.[0-9]+)*\\(([Vv][^()]+)\\)(?:,[A-Za-z0-9._+-]+)?")
    private data class Revision(val numbers: List<BigInteger>, val build: String, val composite: Boolean)

    private fun application(value: String): Revision? {
        val raw = version(value) ?: return null
        val embedded = compositePattern.matchEntire(raw)?.groupValues?.get(1)
        val match = applicationPattern.matchEntire(embedded ?: raw) ?: return null
        return Revision(match.groupValues[1].split('.').map { it.toBigInteger() },
            match.groupValues[2], embedded != null)
    }

    // Factory commands are understood for the V1 family. A different major revision can still
    // run the OTA experiment, but must not silently inherit V1 factory-parameter writes.
    fun minor(version: String): Int {
        val parts = application(version)?.numbers ?: return -1
        return if (parts.first() == BigInteger.ONE)
            parts.getOrNull(1)?.toString()?.toIntOrNull() ?: -1 else -1
    }

    fun sameVersion(left: String, right: String): Boolean {
        val from = version(left) ?: return false
        val to = version(right) ?: return false
        if (from == to) return true
        val a = application(from) ?: return false
        val b = application(to) ?: return false
        // Composite identifiers include E-version/build/variant identity beyond the V profile.
        return !a.composite && !b.composite && compareVersions(a, b) == 0 && a.build == b.build
    }

    /** A bare package V-profile can read back inside a full E(...),variant revision. */
    fun matchesTarget(readback: String, target: String): Boolean {
        if (sameVersion(readback, target)) return true
        val actual = application(readback) ?: return false
        val expected = application(target) ?: return false
        return actual.composite && !expected.composite && compareVersions(actual, expected) == 0 &&
            (expected.build.isEmpty() || actual.build == expected.build)
    }

    /** Cloud metadata may omit the variant or give only the embedded application profile. */
    fun materialsVersionMatches(sensor: String, cloud: String): Boolean {
        if (sameVersion(sensor, cloud)) return true
        val a = version(sensor) ?: return false
        val b = version(cloud) ?: return false
        if (application(a)?.composite == true && application(b)?.composite == true) {
            return (',' !in a || ',' !in b) && a.substringBefore(',') == b.substringBefore(',')
        }
        return matchesTarget(a, b) || matchesTarget(b, a)
    }

    private fun compareVersions(source: Revision, target: Revision): Int {
        val from = source.numbers
        val to = target.numbers
        for (index in 0 until maxOf(from.size, to.size)) {
            val comparison = (to.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(from.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun isUpgrade(source: String, target: String): Boolean {
        require(version(source) != null && version(target) != null) { "target_version" }
        if (sameVersion(source, target)) return false
        val from = application(source)
        val to = application(target)
        // An unfamiliar vendor spelling must reach the authoritative cloud offer, not a local
        // compatibility whitelist. Numeric downgrade checks apply when both are understood.
        if (from == null || to == null) return true
        val order = compareVersions(from, to)
        // Build labels have no established local ordering. A distinct explicit build selected
        // by the cloud is eligible even within the same application profile.
        return order > 0 || (order == 0 && (to.composite || to.build.isNotEmpty()) && !matchesTarget(source, target))
    }

    fun downloadUri(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "download_url" }
        return uri
    }

    fun targetVersion(url: String): String {
        val file = downloadUri(url).path.substringAfterLast('/')
        require(file.endsWith(".gbl", ignoreCase = true)) { "download_filename" }
        val target = version(file.dropLast(4)) ?: throw IllegalArgumentException("target_version")
        require(application(target) != null) { "target_version" }
        return target
    }

    fun chunkSize(mtu: Int): Int {
        require(mtu >= 23) { "mtu" }
        return (minOf(244, mtu - 3) / 4) * 4
    }

    fun maxActiveSeconds(value: ByteArray, session: String): Long? {
        fun decode(bytes: ByteArray): Long? {
            if (bytes.size < 4) return null
            val count = if (bytes.size >= 8) 8 else 4
            var seconds = 0L
            for (index in 0 until count) seconds = seconds or ((bytes[index].toLong() and 255) shl (8 * index))
            return seconds.takeIf { it in 0..(45L * 86_400L) }
        }
        return OttaiCrypto.decryptPayload(value, session)?.let(::decode)
            ?: value.takeIf { it.size == 4 || it.size == 8 }?.let(::decode)
    }

    fun latestDataNo(cipher: ByteArray, session: String, version: String): Int? = runCatching {
        val payload = OttaiCrypto.decryptPayload(cipher, session) ?: return null
        OttaiParser.frameRecords(payload, version).map(OttaiParser::parseRecord)
            .lastOrNull(OttaiParser::isRecordSane)?.dataNo
    }.getOrNull()

    fun coefficientPacket(csv: String): ByteArray {
        val values = csv.split(',').map { it.trim().toFloatOrNull() ?: throw IllegalArgumentException("coefficient") }
        require(values.size in 1..255 && values.all { it.isFinite() }) { "coefficient" }
        val packet = ByteBuffer.allocate(2 + values.size * 4 + 2).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x09.toByte()).put(values.size.toByte())
        values.forEach { packet.putFloat(it) }
        var crc = 0xffff
        packet.array().take(packet.position()).forEach { byte ->
            crc = crc xor (byte.toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        return packet.putShort(crc.toShort()).array()
    }

    fun calibrationPacket(k: Double?, b: Double?, session: String): ByteArray {
        require(k != null && b != null && k.isFinite() && b.isFinite()) { "calibration_missing" }
        val plain = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(k).putDouble(b).array()
        val encrypted = OttaiCrypto.encryptPayload(plain, session) ?: throw IllegalArgumentException("calibration_encrypt")
        require(encrypted.size == 16) { "calibration_length" }
        return byteArrayOf(0x0b) + encrypted
    }
}

/** Acknowledged offsets only. A timeout/disconnect never retries an uncertain chunk. */
internal class OttaiOtaTransfer(val image: OttaiOtaPackage, mtu: Int) {
    private val chunkSize = OttaiOtaProtocol.chunkSize(mtu)
    var acknowledgedBytes = 0
        private set
    private var pendingBytes = 0
    fun nextChunk(): ByteArray {
        check(pendingBytes == 0 && acknowledgedBytes < image.size)
        return image.chunk(acknowledgedBytes, chunkSize).also { pendingBytes = it.size }
    }
    fun acknowledge() {
        check(pendingBytes > 0)
        acknowledgedBytes += pendingBytes
        pendingBytes = 0
    }
    val complete: Boolean get() = acknowledgedBytes == image.size && pendingBytes == 0
}
