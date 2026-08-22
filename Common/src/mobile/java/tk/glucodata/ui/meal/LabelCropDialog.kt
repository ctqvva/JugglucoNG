package tk.glucodata.ui.meal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R

/** Normalised crop rectangle (0..1 of the upright image). */
private data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun clamped(): CropRect {
        val l = left.coerceIn(0f, 1f); val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f); val b = bottom.coerceIn(0f, 1f)
        return CropRect(minOf(l, r - MIN), minOf(t, b - MIN), maxOf(r, l + MIN), maxOf(b, t + MIN))
    }

    companion object {
        const val MIN = 0.08f
        val DEFAULT = CropRect(0.06f, 0.06f, 0.94f, 0.94f)
    }
}

private enum class Handle { TL, TR, BL, BR, MOVE }

/**
 * Crop a label photo down to the label: drag the corners, keep what matters. The file is
 * rewritten upright (EXIF rotation applied) and cropped, which helps the on-device text
 * recognition and is a better photo for Open Food Facts. "Use as is" leaves the file alone.
 */
@Composable
internal fun LabelCropDialog(
    file: File,
    onDone: (cropped: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var preview by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var rect by remember(file.path) { mutableStateOf(CropRect.DEFAULT) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(file.path) {
        preview = withContext(Dispatchers.IO) { decodeUpright(file, maxEdge = 1280) }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDone(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        title = { Text(stringResource(R.string.meal_crop_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.meal_crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                val bmp = preview
                if (bmp == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    CropSurface(bitmap = bmp, rect = rect, onRectChange = { rect = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview != null && !working,
                onClick = {
                    working = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { cropFile(file, rect) }
                        working = false
                        onDone(ok)
                    }
                }
            ) { Text(stringResource(R.string.meal_crop_apply)) }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = { onDone(false) }) { Text(stringResource(R.string.meal_crop_skip)) }
        }
    )
}

@Composable
private fun CropSurface(bitmap: Bitmap, rect: CropRect, onRectChange: (CropRect) -> Unit) {
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val density = LocalDensity.current
    val handlePx = with(density) { 28.dp.toPx() }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.5f, 2f))) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // Letterbox the image inside the box.
        val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
        val imgW = bitmap.width * scale
        val imgH = bitmap.height * scale
        val ox = (boxW - imgW) / 2f
        val oy = (boxH - imgH) / 2f
        fun toPx(r: CropRect) = Rect(ox + r.left * imgW, oy + r.top * imgH, ox + r.right * imgW, oy + r.bottom * imgH)
        var active by remember { mutableStateOf<Handle?>(null) }

        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = toPx(rect)
                            fun near(x: Float, y: Float) = (pos - Offset(x, y)).getDistance() < handlePx
                            active = when {
                                near(r.left, r.top) -> Handle.TL
                                near(r.right, r.top) -> Handle.TR
                                near(r.left, r.bottom) -> Handle.BL
                                near(r.right, r.bottom) -> Handle.BR
                                r.contains(pos) -> Handle.MOVE
                                else -> null
                            }
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                        onDrag = { change, drag ->
                            change.consume()
                            val dx = drag.x / imgW
                            val dy = drag.y / imgH
                            val r = rect
                            val next = when (active) {
                                Handle.TL -> r.copy(left = r.left + dx, top = r.top + dy)
                                Handle.TR -> r.copy(right = r.right + dx, top = r.top + dy)
                                Handle.BL -> r.copy(left = r.left + dx, bottom = r.bottom + dy)
                                Handle.BR -> r.copy(right = r.right + dx, bottom = r.bottom + dy)
                                Handle.MOVE -> {
                                    val w = r.right - r.left; val h = r.bottom - r.top
                                    val l = (r.left + dx).coerceIn(0f, 1f - w); val t = (r.top + dy).coerceIn(0f, 1f - h)
                                    CropRect(l, t, l + w, t + h)
                                }
                                null -> r
                            }
                            onRectChange(next.clamped())
                        }
                    )
                }
        ) {
            val r = toPx(rect)
            val shade = Color.Black.copy(alpha = 0.45f)
            // Outside the crop: darkened
            drawRect(shade, Offset(ox, oy), Size(imgW, r.top - oy))
            drawRect(shade, Offset(ox, r.bottom), Size(imgW, oy + imgH - r.bottom))
            drawRect(shade, Offset(ox, r.top), Size(r.left - ox, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(ox + imgW - r.right, r.height))
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(width = 3f))
            val hs = handlePx * 0.35f
            listOf(r.topLeft, Offset(r.right, r.top), Offset(r.left, r.bottom), r.bottomRight).forEach { c ->
                drawCircle(Color.White, radius = hs, center = c)
                drawCircle(Color.Black.copy(alpha = 0.6f), radius = hs, center = c, style = Stroke(width = 2f))
            }
        }
    }
}

/** Decodes the photo upright (EXIF applied), downsampled so the longer edge is at most [maxEdge]. */
internal fun decodeUpright(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
    val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rotation = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (rotation == 0f) return raw
    val matrix = Matrix().apply { postRotate(rotation) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated !== raw) raw.recycle()
    return rotated
}

/** Rewrites [file] as the upright, cropped JPEG. Returns false when nothing could be decoded. */
private fun cropFile(file: File, rect: CropRect): Boolean {
    val upright = decodeUpright(file, maxEdge = 2048) ?: return false
    return try {
        val r = rect.clamped()
        val x = (r.left * upright.width).toInt().coerceIn(0, upright.width - 1)
        val y = (r.top * upright.height).toInt().coerceIn(0, upright.height - 1)
        val w = ((r.right - r.left) * upright.width).toInt().coerceIn(1, upright.width - x)
        val h = ((r.bottom - r.top) * upright.height).toInt().coerceIn(1, upright.height - y)
        val cropped = Bitmap.createBitmap(upright, x, y, w, h)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (cropped !== upright) cropped.recycle()
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    } catch (t: Throwable) {
        false
    } finally {
        upright.recycle()
    }
}
