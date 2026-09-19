package tk.glucodata.data.meal

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Keeps the label photos of products that were read but not (yet) sent to Open Food Facts, per
 * barcode, so a later "Send" can still carry them. Files live in the cache directory as
 * `meal_photos/<barcode>/<kind>-<uuid>.jpg`; only the newest [MAX_PRODUCTS] products are kept,
 * and a product's photos are dropped after a successful upload.
 */
object LabelPhotoStore {
    const val MAX_PRODUCTS = 20
    private const val ROOT = "meal_photos"

    fun root(context: Context): File = File(context.cacheDir, ROOT).apply { mkdirs() }

    private fun dirFor(context: Context, barcode: String): File = File(root(context), barcode.filter(Char::isDigit).ifEmpty { "unknown" })

    /** Moves [photos] under the barcode and prunes old products. Returns the stored set. */
    fun store(context: Context, barcode: String, photos: List<ContributionPhoto>): List<ContributionPhoto> {
        if (photos.isEmpty()) return photosFor(context, barcode)
        val dir = dirFor(context, barcode).apply { mkdirs() }
        for (photo in photos) {
            if (!photo.file.exists() || photo.file.length() == 0L) continue
            if (photo.file.parentFile == dir) continue
            val target = File(dir, "${photo.kind.storageValue}-${UUID.randomUUID()}.jpg")
            if (!photo.file.renameTo(target)) {
                runCatching { photo.file.copyTo(target, overwrite = true); photo.file.delete() }
            }
        }
        dir.setLastModified(System.currentTimeMillis())
        prune(context)
        return photosFor(context, barcode)
    }

    fun photosFor(context: Context, barcode: String): List<ContributionPhoto> {
        val dir = dirFor(context, barcode)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.length() > 0 }.orEmpty()
            .sortedBy { it.name }
            .map { f -> ContributionPhoto(f, LabelPhotoKind.fromStorage(f.name.substringBefore('-'))) }
    }

    fun clear(context: Context, barcode: String) {
        dirFor(context, barcode).deleteRecursively()
    }

    /** Drops everything beyond the newest [MAX_PRODUCTS] products and stray loose files. */
    fun prune(context: Context, max: Int = MAX_PRODUCTS) {
        val dirs = root(context).listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.lastModified() }
        dirs.drop(max).forEach { it.deleteRecursively() }
    }

    fun clearAll(context: Context) {
        root(context).deleteRecursively()
    }
}
