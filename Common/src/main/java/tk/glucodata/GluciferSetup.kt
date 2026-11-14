package tk.glucodata

import java.net.URI

/** QR codes carry the same private URL accepted by manual setup. */
object GluciferSetup {
    fun parseQr(text: String): String? = text.trim().takeIf { it.length <= 2048 && GluciferSender.validUrl(it) }
    fun hostLabel(url: String): String = URI(url).let { "${it.scheme}://${it.host}" + if (it.port >= 0) ":${it.port}" else "" }
}
