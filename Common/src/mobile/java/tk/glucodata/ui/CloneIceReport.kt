package tk.glucodata.ui

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The facts in the native ICE status report (ICEstatus.cpp) that the connection card shows as
 * rows of its own. The report is HTML built from a fixed format, so each fact is its labelled
 * line: "Phase: …", "Local: <address> <candidate type>", "Remote: …", "ICE State: …", and the
 * connect time as ctime() text on the last line.
 */
internal data class CloneIceReport(
    val phase: String? = null,
    val local: String? = null,
    val remote: String? = null,
    val state: String? = null,
    val connectedAtMs: Long? = null,
) {
    companion object {
        private val ctime = Regex("""^[A-Z][a-z]{2} [A-Z][a-z]{2} +\d{1,2} \d{2}:\d{2}:\d{2} \d{4}$""")

        fun parse(html: String?): CloneIceReport {
            if (html.isNullOrBlank()) return CloneIceReport()
            val lines = html
                .replace(Regex("(?i)<br\\s*/?>|</p>|</h\\d>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            fun field(name: String) = lines.firstOrNull { it.startsWith("$name:") }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
            return CloneIceReport(
                phase = field("Phase"),
                local = field("Local")?.let(::address),
                remote = field("Remote")?.let(::address),
                state = field("ICE State"),
                connectedAtMs = lines.lastOrNull { ctime.matches(it) }?.let(::connectTime),
            )
        }

        /** "192.168.1.117:48277 Host" → "192.168.1.117": the port and candidate type say nothing to a person. */
        private fun address(value: String): String {
            val endpoint = value.substringBefore(' ')
            return when {
                endpoint.startsWith("[") -> endpoint.substringAfter('[').substringBefore(']')
                endpoint.count { it == ':' } == 1 -> endpoint.substringBefore(':')
                else -> endpoint
            }
        }

        private fun connectTime(value: String): Long? = try {
            SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US).parse(value.replace(Regex(" +"), " "))?.time
        } catch (_: ParseException) {
            null
        }
    }
}
