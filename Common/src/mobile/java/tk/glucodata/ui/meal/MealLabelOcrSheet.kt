@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tk.glucodata.ui.meal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.meal.AmountUnit
import tk.glucodata.data.meal.ContributionPhoto
import tk.glucodata.data.meal.LabelNutrition
import tk.glucodata.data.meal.LabelPhotoKind
import tk.glucodata.data.meal.NutritionLabelParser
import tk.glucodata.data.meal.NutritionReference
import tk.glucodata.data.meal.NutritionSource
import tk.glucodata.data.meal.OcrLine
import tk.glucodata.data.meal.PackQuantity
import tk.glucodata.data.meal.PackQuantityParser
import tk.glucodata.data.meal.ProductNameGuesser
import tk.glucodata.data.meal.ScannedProduct
import tk.glucodata.data.meal.ServingSizeParser

/** One photo of the packaging, what it shows, and what the OCR read off it. */
internal class LabelPhoto(val file: File, val kind: LabelPhotoKind) {
    var lines: List<OcrLine> by mutableStateOf(emptyList())
    var processing: Boolean by mutableStateOf(true)
    var failed: Boolean by mutableStateOf(false)
}

/** Where label photos live until they are uploaded or discarded. */
internal fun labelPhotoDir(context: android.content.Context): File =
    File(context.cacheDir, "meal_photos").apply { mkdirs() }

internal fun deleteLabelPhotos(photos: List<ContributionPhoto>) {
    photos.forEach { runCatching { it.file.delete() } }
}

/**
 * Reads a product off its packaging when the barcode is unknown (or there is none): one or more
 * photos — the nutrition table, the front for name/brand/net quantity, any further side — each
 * OCR'd on the device, the parsers run over everything, and the result is handed on as a
 * pre-filled product to confirm. Nothing leaves the phone from here; uploading to Open Food
 * Facts is a separate, opt-in step after confirmation.
 */
/**
 * @param photosOnly collect photos for an upload without running OCR (the product is already
 *   known); [onPhotos] receives them instead of [onProduct].
 */
@Composable
internal fun MealLabelOcrSheet(
    barcode: String?,
    onDismiss: () -> Unit,
    onProduct: (ScannedProduct, List<ContributionPhoto>) -> Unit,
    photosOnly: Boolean = false,
    onPhotos: ((List<ContributionPhoto>) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val photos = remember { mutableStateListOf<LabelPhoto>() }
    var pendingCapture by remember { mutableStateOf<LabelPhoto?>(null) }
    var nextKind by remember { mutableStateOf(LabelPhotoKind.NUTRITION) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var handedOver by remember { mutableStateOf(false) }

    // Photos are discarded when the sheet goes away without handing them on.
    DisposableEffect(Unit) {
        onDispose {
            if (!handedOver) photos.forEach { runCatching { it.file.delete() } }
        }
    }

    fun runOcr(photo: LabelPhoto) {
        if (photosOnly) {
            photo.processing = false
            return
        }
        scope.launch {
            try {
                photo.lines = withContext(Dispatchers.Default) {
                    LabelOcr.recognize(context, Uri.fromFile(photo.file))
                }
            } catch (t: Throwable) {
                photo.failed = true
            } finally {
                photo.processing = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val photo = pendingCapture
        pendingCapture = null
        if (photo == null) return@rememberLauncherForActivityResult
        if (ok && photo.file.length() > 0) {
            photos += photo
            runOcr(photo)
        } else {
            runCatching { photo.file.delete() }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val photo = LabelPhoto(File(labelPhotoDir(context), "${UUID.randomUUID()}.jpg"), nextKind)
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> photo.file.outputStream().use { input.copyTo(it) } }
                }.isSuccess && photo.file.length() > 0
            }
            if (copied) {
                photos += photo
                runOcr(photo)
            }
        }
    }

    fun capture(kind: LabelPhotoKind) {
        val photo = LabelPhoto(File(labelPhotoDir(context), "${UUID.randomUUID()}.jpg"), kind)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo.file)
        pendingCapture = photo
        cameraLauncher.launch(uri)
    }

    val allLines = photos.flatMap { it.lines }
    val nutritionLines = photos.filter { it.kind == LabelPhotoKind.NUTRITION }.flatMap { it.lines }.ifEmpty { allLines }
    val frontLines = photos.filter { it.kind == LabelPhotoKind.FRONT }.flatMap { it.lines }.ifEmpty { allLines }
    val nutrition: LabelNutrition? = remember(allLines) { if (nutritionLines.isEmpty()) null else NutritionLabelParser.parse(nutritionLines) }
    val pack: PackQuantity? = remember(allLines) { PackQuantityParser.parse(allLines.map { it.text }) }
    val nameCandidates = remember(allLines) { ProductNameGuesser.candidates(frontLines) }
    val busy = photos.any { it.processing }
    val carbsWord = stringResource(R.string.meal_carbs)
    val proteinWord = stringResource(R.string.meal_protein)
    val fatWord = stringResource(R.string.meal_fat)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(if (photosOnly) R.string.meal_send_add_photos else R.string.meal_ocr_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = when {
                    photosOnly -> stringResource(R.string.meal_send_photos_intro)
                    barcode != null -> stringResource(R.string.meal_ocr_intro_barcode, barcode)
                    else -> stringResource(R.string.meal_ocr_intro)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Which side is the next photo of
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LabelPhotoKind.NUTRITION, LabelPhotoKind.FRONT, LabelPhotoKind.INGREDIENTS, LabelPhotoKind.OTHER).forEach { kind ->
                    FilterChip(selected = nextKind == kind, onClick = { nextKind = kind }, label = { Text(photoKindLabel(kind)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { capture(nextKind) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meal_ocr_take_photo))
                }
                FilledTonalButton(
                    onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meal_ocr_from_gallery))
                }
            }

            // The photos so far
            photos.forEachIndexed { index, photo ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (photo.processing) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = "${index + 1}. ${photoKindLabel(photo.kind)} · " + when {
                            photo.processing -> stringResource(R.string.meal_ocr_reading)
                            photo.failed -> stringResource(R.string.meal_ocr_failed)
                            else -> stringResource(R.string.meal_ocr_lines, photo.lines.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        photos.remove(photo)
                        runCatching { photo.file.delete() }
                    }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.meal_remove_item)) }
                }
            }

            // What was read
            if (photos.isNotEmpty() && !busy && !photosOnly) {
                if (nameCandidates.isNotEmpty()) {
                    Text(stringResource(R.string.meal_ocr_name_candidates), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        nameCandidates.forEach { candidate ->
                            FilterChip(selected = selectedName == candidate, onClick = { selectedName = candidate }, label = { Text(candidate) })
                        }
                    }
                }
                val facts = nutrition?.facts
                if (facts != null) {
                    Text(
                        text = "${basisLabel(nutrition.basis)} · " + facts.summary(carbsWord, proteinWord, fatWord) +
                            (facts.kcal?.let { " · ${MealFormat.grams(it, digits = 0)} kcal" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = stringResource(R.string.meal_ocr_no_table),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                pack?.let {
                    Text(
                        text = stringResource(R.string.meal_product_net_quantity) + ": ${MealFormat.grams(it.quantity)} ${it.unit.symbol} (${it.evidence})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                nutrition?.servingText?.let {
                    Text(
                        text = stringResource(R.string.meal_product_serving) + ": $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.meal_ocr_confirm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = photos.isNotEmpty() && !busy,
                    onClick = {
                        if (photosOnly) {
                            handedOver = true
                            onPhotos?.invoke(photos.map { ContributionPhoto(it.file, it.kind) })
                            return@Button
                        }
                        val facts = nutrition?.facts
                        val basis = nutrition?.basis ?: tk.glucodata.data.meal.NutritionBasis.PER_100G
                        val serving = ServingSizeParser.parse(nutrition?.servingText)
                        val product = ScannedProduct(
                            barcode = barcode,
                            displayName = selectedName ?: nameCandidates.firstOrNull().orEmpty(),
                            brand = null,
                            source = NutritionSource.OCR_LABEL,
                            facts = facts ?: tk.glucodata.data.meal.NutritionFacts(carbsGrams = 0f),
                            reference = NutritionReference(
                                basis = basis,
                                netQuantity = pack?.quantity,
                                netUnit = pack?.unit ?: if (basis == tk.glucodata.data.meal.NutritionBasis.PER_100ML) AmountUnit.MILLILITER else AmountUnit.GRAM,
                                servingText = nutrition?.servingText,
                                servingQuantity = serving?.quantity,
                                servingUnit = serving?.unit,
                                servingPieces = serving?.pieces,
                                servingPieceLabel = serving?.pieceLabel
                            )
                        )
                        handedOver = true
                        onProduct(product, photos.map { ContributionPhoto(it.file, it.kind) })
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(if (photosOnly) R.string.meal_send_use_photos else R.string.meal_ocr_use_values)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
internal fun photoKindLabel(kind: LabelPhotoKind): String = when (kind) {
    LabelPhotoKind.FRONT -> stringResource(R.string.meal_ocr_kind_front)
    LabelPhotoKind.NUTRITION -> stringResource(R.string.meal_ocr_kind_nutrition)
    LabelPhotoKind.INGREDIENTS -> stringResource(R.string.meal_ocr_kind_ingredients)
    LabelPhotoKind.PACKAGING, LabelPhotoKind.OTHER -> stringResource(R.string.meal_ocr_kind_other)
}
