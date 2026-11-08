package tk.glucodata.ui.setup

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import tk.glucodata.RestrictedMode
import tk.glucodata.drivers.aidex.AiDexProvisioningStore

/** Shared by the inline retrieval controls and the optional saved-sensor page. */
internal object AiDexKeyDocuments {
    const val MAX_BYTES = 64 * 1024

    fun readJson(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) return output.toString(Charsets.UTF_8.name())
            if (count == 0) return null
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
    }

    fun importKeys(context: Context, uri: Uri): String? {
        if (RestrictedMode.isLocked()) return null
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.use(::readJson)
        }.getOrNull() ?: return null
        if (RestrictedMode.isLocked()) return null
        return AiDexProvisioningStore.importJson(context, json)
    }

    fun exportKeys(context: Context, serial: String, uri: Uri): Boolean {
        if (RestrictedMode.isLocked()) return false
        val json = AiDexProvisioningStore.exportJson(context, serial) ?: return false
        if (RestrictedMode.isLocked()) return false
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                if (RestrictedMode.isLocked()) return false
                output.write(json.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        }.getOrDefault(false)
    }
}
