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

/** «Школа калибровки» GIS Monitor: простыми словами, когда и как калибровать, плюс состояние текущего датчика. */
@Composable
fun CalibrationSchoolScreen(navController: NavController) {
    val ctx = LocalContext.current
    val start = ctx.getSharedPreferences("gis_bridge", Context.MODE_PRIVATE).getLong("start", 0L)
    val hours = if (start > 0) (System.currentTimeMillis() - start) / 3_600_000.0 else -1.0
    val (title, text) = when {
        hours < 0 -> "Датчик ещё не найден" to "Как только придут первые значения, здесь появится совет."
        hours < 24 -> "Сейчас калибровать нельзя" to "Датчику ${Math.round(hours)} ч. Первые сутки он привыкает к коже, значения плавают. Подождите ещё ${Math.ceil(24 - hours).toInt()} ч."
        hours < 48 -> "Лучше подождать" to "Датчику ${Math.round(hours)} ч. Калибровка возможна, но точнее будет после 48 часов."
        else -> "Можно калибровать" to "Датчик «устоялся». Сделайте замер глюкометром и введите его в калибровках."
    }
    val ok = hours >= 48
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
        Button(onClick = { navController.navigate("calibrations") }, Modifier.fillMaxWidth()) { Text("Открыть калибровки") }
        Spacer(Modifier.height(80.dp))
    }
}
