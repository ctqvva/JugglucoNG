package tk.glucodata.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.CRC32
import tk.glucodata.Log

/**
 * The readings [HistoryBatchWriter] has accepted but not stored yet, on disk, so
 * a process that dies in between does not take them with it.
 *
 * One line per reading, each with its own checksum: a line cut short by a kill
 * or a full disk is dropped on [load] without taking its neighbours along. A
 * later line for the same sensor and minute replaces an earlier one.
 *
 * Not synced to the medium on every append. What it guards against is the
 * process dying, which leaves the page cache alone, not a power loss.
 *
 * Not thread-safe; the writer calls it under its own lock.
 */
internal class HistoryBatchJournal(private val file: File) {
    /** Lines currently in the file, counting ones a later line replaced. */
    var lineCount = 0
        private set

    /** The file ends in a line cut short; what follows must not be glued to it. */
    private var tornTail = false

    fun load(): List<HistoryReading> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            tornTail = text.isNotEmpty() && !text.endsWith('\n')
            val written = text.lines().filter { it.isNotEmpty() }
            lineCount = written.size
            val lines = written.filter { it[0] != CROSSED_OUT }
            val readings = lines.mapNotNull(::decode)
            if (readings.size != lines.size) {
                Log.w(TAG, "dropped ${lines.size - readings.size} unreadable pending history lines")
            }
            readings
        } catch (e: IOException) {
            Log.stack(TAG, "load", e)
            emptyList()
        }
    }

    /** False when the readings could not be written; those already in the file still are. */
    fun append(readings: List<HistoryReading>): Boolean {
        if (readings.isEmpty()) return true
        val lengthBefore = file.length()
        return try {
            file.parentFile?.mkdirs()
            val lines = encode(readings)
            FileOutputStream(file, true).use { it.write(if (tornTail) NEWLINE + lines else lines) }
            tornTail = false
            lineCount += readings.size
            true
        } catch (e: IOException) {
            Log.stack(TAG, "append", e)
            runCatching { FileOutputStream(file, true).use { it.channel.truncate(lengthBefore) } }
                .onFailure { tornTail = true }
            false
        }
    }

    /**
     * Makes the file hold exactly [readings]. False when some of them are not in it, or the
     * file could not be changed at all.
     */
    fun replaceWith(readings: List<HistoryReading>): Boolean {
        val staged = File(file.path + ".new")
        return try {
            if (readings.isEmpty()) {
                if (file.exists() && !file.delete()) throw IOException("could not delete $file")
            } else {
                file.parentFile?.mkdirs()
                staged.writeBytes(encode(readings))
                if (!staged.renameTo(file)) throw IOException("could not replace $file")
            }
            tornTail = false
            lineCount = readings.size
            true
        } catch (e: IOException) {
            Log.stack(TAG, "replaceWith", e)
            staged.delete()
            crossOutAllBut(readings.mapTo(HashSet(), ::encodeLine))
        }
    }

    /**
     * No room for a second copy: crosses out, where they stand, the lines that are not among
     * [pending]. A restart would write them over what has replaced them since. One byte each,
     * so no other line is ever at risk. True when every pending line is in the file.
     */
    private fun crossOutAllBut(pending: Set<String>): Boolean = try {
        val found = HashSet<String>()
        if (file.exists()) {
            RandomAccessFile(file, "rw").use { raw ->
                var offset = 0L
                // ISO-8859-1 keeps one character per byte; the lines themselves are ASCII.
                for (line in file.readText(Charsets.ISO_8859_1).split('\n')) {
                    if (line in pending) {
                        found += line
                    } else if (line.isNotEmpty() && line[0] != CROSSED_OUT) {
                        raw.seek(offset)
                        raw.write(CROSSED_OUT.code)
                    }
                    offset += line.length + 1
                }
            }
        }
        found.size == pending.size
    } catch (e: IOException) {
        Log.stack(TAG, "crossOutAllBut", e)
        false
    }

    private fun encode(readings: List<HistoryReading>): ByteArray = buildString(readings.size * 80) {
        for (reading in readings) append(encodeLine(reading)).append('\n')
    }.toByteArray(Charsets.UTF_8)

    private fun encodeLine(reading: HistoryReading): String {
        val body = listOf(
            FORMAT,
            URLEncoder.encode(reading.sensorSerial, "UTF-8"),
            reading.timestamp,
            reading.value,
            reading.rawValue,
            reading.rate ?: "",
            URLEncoder.encode(reading.source, "UTF-8"),
            reading.firstStoredAt,
        ).joinToString("\t")
        return body + "\t" + checksum(body)
    }

    private fun decode(line: String): HistoryReading? = runCatching {
        val body = line.substringBeforeLast('\t')
        require(checksum(body) == line.substringAfterLast('\t'))
        val fields = body.split('\t')
        require(fields.size == 8 && fields[0] == FORMAT)
        HistoryReading(
            timestamp = fields[2].toLong(),
            sensorSerial = URLDecoder.decode(fields[1], "UTF-8"),
            value = fields[3].toFloat(),
            rawValue = fields[4].toFloat(),
            rate = fields[5].takeIf { it.isNotEmpty() }?.toFloat(),
            source = URLDecoder.decode(fields[6], "UTF-8"),
            firstStoredAt = fields[7].toLong(),
        )
    }.getOrNull()

    private fun checksum(body: String): String =
        CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value.toString(16)

    private companion object {
        const val TAG = "HistoryBatchJournal"
        const val FORMAT = "1"
        const val CROSSED_OUT = '-'
        val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}
