package tk.glucodata.ui.meal

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import tk.glucodata.data.meal.OcrLine

/**
 * On-device text recognition of a label photo (ML Kit, Latin script, no network). Returns the
 * lines with their geometry so the parsers can tell the big print from the table rows.
 * `InputImage.fromFilePath` applies the photo's EXIF rotation, which the camera sets.
 */
object LabelOcr {
    suspend fun recognize(context: Context, uri: Uri): List<OcrLine> {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = recognizer.process(image).await()
            return text.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = line.boundingBox
                    OcrLine(
                        text = line.text,
                        top = box?.top ?: 0,
                        left = box?.left ?: 0,
                        height = box?.height() ?: 0
                    )
                }
            }
        } finally {
            recognizer.close()
        }
    }
}
