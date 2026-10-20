package tk.glucodata.ui.calibration

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** «Школа калибровки» GIS Monitor: простыми словами, когда и как калибровать, плюс состояние текущего датчика. */
@Composable
fun CalibrationSchoolScreen(navController: NavController, onTriggerCalibration: (tk.glucodata.ui.CalibrationSheetState) -> Unit = {}) {
    val ctx = LocalContext.current
    val start = ctx.getSharedPreferences("gis_bridge", Context.MODE_PRIVATE).getLong("start", 0L)
    val hours = if (start > 0) (System.currentTimeMillis() - start) / 3_600_000.0 else -1.0
    val (title, text) = when {
        hours < 0 && ctx.getSharedPreferences("gis_bridge", Context.MODE_PRIVATE).contains("ts") ->
            "Датчик подключён" to "Возраст датчика пока не определён. Калибруйте, когда сахар ровный."
        hours < 0 -> "Датчик ещё не найден" to "Как только придут первые значения, здесь появится совет."
        hours < 24 -> "Сейчас калибровать нельзя" to "Датчику ${Math.round(hours)} ч. Первые сутки он привыкает к коже, значения плавают. Подождите ещё ${Math.ceil(24 - hours).toInt()} ч."
        hours < 48 -> "Лучше подождать" to "Датчику ${Math.round(hours)} ч. Калибровка возможна, но точнее будет после 48 часов."
        else -> "Можно калибровать" to "Датчик «устоялся». Сделайте замер глюкометром и введите его в калибровках."
    }
    val ok = hours >= 48 || hours < 0 && ctx.getSharedPreferences("gis_bridge", Context.MODE_PRIVATE).contains("ts")
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Калибровка", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            ),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(text, fontSize = 15.sp)
            }
        }
        val sp = ctx.getSharedPreferences("gis_bridge", Context.MODE_PRIVATE)
        val raw = sp.getFloat("raw", 0f).toDouble(); val cal = if (sp.contains("cal")) sp.getFloat("cal", 0f).toDouble() else null
        if (raw > 0) {
            val mmol = tk.glucodata.Applic.unit == 1
            fun f(v: Double) = if (mmol) String.format(java.util.Locale.US, "%.1f", v / 18.0182) else Math.round(v).toString()
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Сырое", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(f(raw), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary) }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) { Text("Калиброванное", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(f(cal ?: raw), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    }
                    val msg = if (cal == null) "Калибровок нет: в AAPS и Nightscout уходит сырое значение." else {
                        val d = cal - raw
                        "Поправка " + (if (d >= 0) "+" else "−") + f(Math.abs(d)).let { if (mmol) it else it } + ". В AAPS и Nightscout уходит калиброванное значение."
                    }
                    Text(msg, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Как калибровать", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "1" to "Вымойте руки и сделайте замер глюкометром.",
            "2" to "Калибруйте, когда сахар ровный: без стрелок вверх и вниз, не после еды и не после инсулина.",
            "3" to "Нужно три замера на разных уровнях, например 5, 8 и 12. Тогда линия подстроится точнее.",
            "4" to "Сразу введите число в калибровках. Старый замер уже не подходит.",
        ).forEach { (n, t) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(n, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(t, fontSize = 15.sp)
            }
        }
        Text(
            "Не калибруйте при резких скачках, если сахар меняется быстро или если сомневаетесь в замере.",
            fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MeterPhotoButton(onTriggerCalibration)
        OutlinedButton(onClick = { navController.navigate("calibrations") }, Modifier.fillMaxWidth()) { Text("Открыть калибровки") }
        Spacer(Modifier.height(80.dp))
    }
}

/** Калибровка по фото глюкометра: снимок → распознавание числа → обычное окно калибровки с уже подставленным значением (его можно поправить). */
@Composable
private fun MeterPhotoButton(onTriggerCalibration: (tk.glucodata.ui.CalibrationSheetState) -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val busy = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val photo = androidx.compose.runtime.remember { java.io.File(ctx.cacheDir, "meter").apply { mkdirs() }.let { java.io.File(it, "meter.jpg") } }
    val uri = androidx.compose.runtime.remember { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", photo) }

    fun openSheet(valueDisplay: Float?) {
        val sp = ctx.getSharedPreferences("gis_bridge", Context.MODE_PRIVATE)
        val mmol = tk.glucodata.Applic.unit == 1
        fun d(v: Float) = if (mmol) v / 18.0182f else v
        val raw = d(sp.getFloat("raw", 0f))
        val auto = if (sp.contains("cal")) d(sp.getFloat("cal", 0f)) else raw
        onTriggerCalibration(tk.glucodata.ui.CalibrationSheetState.New(auto, raw, System.currentTimeMillis(), "", null, valueDisplay))
    }

    val shot = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        busy.value = true
        scope.launch {
            val value = withContext(kotlinx.coroutines.Dispatchers.Default) { readMeter(photo, tk.glucodata.Applic.unit == 1) }
            busy.value = false
            if (value == null) android.widget.Toast.makeText(ctx, "Число не распознано. Введите его вручную.", android.widget.Toast.LENGTH_LONG).show()
            openSheet(value)
        }
    }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shot.launch(uri) else android.widget.Toast.makeText(ctx, "Нужен доступ к камере, чтобы сфотографировать глюкометр.", android.widget.Toast.LENGTH_LONG).show()
    }
    Button(
        onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) shot.launch(uri)
            else perm.launch(android.Manifest.permission.CAMERA)
        },
        enabled = !busy.value,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (busy.value) "Читаю число…" else "Калибровка по фото глюкометра") }
    Text(
        "Сфотографируйте экран глюкометра. Число подставится само, вы проверите его и подтвердите.",
        fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Читает число с фото. Возвращает значение в единицах экрана (ммоль/л или мг/дл) или null. */
private suspend fun readMeter(file: java.io.File, mmol: Boolean): Float? = try {
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
    val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
    val text = kotlinx.coroutines.suspendCancellableCoroutine<String> { c ->
        com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { c.resume(it.text) { } }
            .addOnFailureListener { c.resume("") { } }
    }
    val found = Regex("""\d{1,3}(?:[.,]\d)?""").findAll(text).mapNotNull { it.value.replace(',', '.').toFloatOrNull() }.toList()
    // ммоль/л: 1.0–33.3 с десятой; мг/дл: целое 20–600. Подбираем под единицы экрана.
    val mgdl = found.firstOrNull { it == Math.rint(it.toDouble()).toFloat() && it in 20f..600f }
    val mm = found.firstOrNull { it in 1.0f..33.3f && it != Math.rint(it.toDouble()).toFloat() }
    when {
        mm != null -> if (mmol) mm else mm * 18.0182f
        mgdl != null -> if (mmol) mgdl / 18.0182f else mgdl
        else -> null
    }
} catch (t: Throwable) { null }
