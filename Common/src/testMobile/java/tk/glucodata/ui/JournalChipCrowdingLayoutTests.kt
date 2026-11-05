package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.theme.AppTypography

/** Screenshot-shaped dose clusters, rendered with the real chip and placement/spread solver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w600dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JournalChipCrowdingLayoutTests {
    @get:Rule val compose = createComposeRule()

    private data class Dose(val x: Float, val amount: Float, val insulin: String, val color: Int)
    private val teal = 0xff00a99d.toInt()
    private val blue = 0xff2876c4.toInt()
    private val purple = 0xff5c57b1.toInt()
    private val olive = 0xff929b43.toInt()
    // Relative horizontal anchors and dose values from the supplied week-scale screenshot.
    private val doses = listOf(
        Dose(20f, 5.5f, "Rapid A", teal), Dose(26f, 3.5f, "Rapid A", teal),
        Dose(32f, 6f, "Rapid A", teal), Dose(80f, 5f, "Rapid A", teal),
        Dose(86f, 4f, "Rapid A", teal), Dose(92f, 2f, "Rapid A", teal),
        Dose(98f, 10f, "Basal A", olive), Dose(138f, 2f, "Rapid A", teal),
        Dose(148f, 10f, "Basal A", olive), Dose(195f, 15f, "Basal B", purple),
        Dose(232f, 2f, "Rapid B", blue), Dose(246f, 2f, "Rapid B", blue),
        Dose(286f, 10f, "Basal B", purple), Dose(300f, 2.5f, "Rapid B", blue),
        Dose(312f, 6f, "Rapid B", blue), Dose(320f, 15f, "Basal B", purple),
        Dose(326f, 15f, "Basal B", purple)
    )

    @Test fun crowdedInsulinBandCompactsOnTallPlotsAndStillOpensEveryDose() {
        var compact by mutableStateOf(false)
        var plotHeight by mutableFloatStateOf(520f)
        var fontScale by mutableFloatStateOf(1f)
        var separate by mutableStateOf(false)
        var expanded by mutableStateOf<Int?>(null)
        var shown = emptyList<Int>()
        var groups = emptyList<IntArray>()
        var leaders = IntArray(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    val text = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelMedium
                    val number = remember { DecimalFormat("0.#", DecimalFormatSymbols(Locale.US)) }
                    fun label(amount: Float) = "${number.format(amount)} U"
                    fun width(label: String) = 36f + text.measure(label, style).size.width.coerceAtMost(88) + 1f
                    val chipHeight = text.measure("0", style).size.height + 10f
                    val touchInset = ((48f - chipHeight) / 2f).coerceAtLeast(0f)
                    val duration = 7L * 24 * 3_600_000L
                    val markers = doses.mapIndexed { index, dose ->
                        JournalChartMarker(index.toLong(), (dose.x / 360 * duration).toLong(),
                            JournalEntryType.INSULIN, dose.insulin, dose.color, "", label(dose.amount), amount = dose.amount)
                    }
                    fun top(index: Int) = if (separate) {
                        plotHeight - 34f - with(Density(1f, fontScale)) { insulinSeparateCurveHeight(doses[index].amount, plotHeight) }
                    } else plotHeight - 104f
                    leaders = if (compact) JournalChipDensity.leaders(markers.mapIndexed { index, marker ->
                        JournalChipDensity.Entry(marker.timestamp, width(marker.detailText), journalChipKind(marker), top(index))
                    }, duration, 360f, chipHeight, 4f) else IntArray(doses.size) { it }
                    val explicitGroups = leaders.indices.groupBy { leaders[it] }
                    val widths = markers.indices.map { index ->
                        val members = explicitGroups.getValue(leaders[index])
                        val total = members.sumOf { doses[it].amount.toDouble() }.toFloat()
                        if (index == leaders[index]) width(label(total)) else width(markers[index].detailText)
                    }
                    val spec = JournalChipLayout.Spec(chipHeight, 10f, chipHeight + 4f, 12f, 4f,
                        0f, 360f, 8f, plotHeight - chipHeight - 8f, repeatReach = 124f, peek = 12f)
                    val placed = JournalChipLayout.place(markers.mapIndexed { index, marker ->
                        JournalChipRequest(doses[index].x, top(index), widths[index], null,
                            stackKey = journalChipKind(marker) to marker.detailText,
                            foldInto = leaders[index].takeIf { it != index }, kind = journalChipKind(marker))
                    }, spec)
                    groups = JournalChipLayout.piles(placed, 12f, 8f)
                    val groupOf = groups.flatMap { group -> group.map { it to group } }.toMap()
                    val open = expanded?.let(groupOf::get)
                    val shifts = open?.let { JournalChipLayout.spread(placed.map { it.box }, it,
                        0f, 360f, 8f, plotHeight - chipHeight - 8f, 4f, 4f, 160f) }
                    shown = markers.indices.filter { index ->
                        open?.contains(index) == true || placed[index].front == index || (!compact && !placed[index].folded)
                    }
                    Box(Modifier.width(360.dp).height(plotHeight.dp).background(MaterialTheme.colorScheme.surface).testTag("plot")) {
                        Canvas(Modifier.width(360.dp).height(plotHeight.dp)) {
                            // Synthetic glucose context; the dose geometry and components are the subject.
                            val trace = Path().apply {
                                moveTo(0f, size.height * .55f)
                                for (x in 4..360 step 4) lineTo(x.toFloat(), size.height * (.55f + .18f * kotlin.math.sin(x * .12f)))
                            }
                            drawPath(trace, Color.LightGray, style = Stroke(2f))
                            shown.forEach { index ->
                                val box = placed[index].box
                                drawLine(Color(doses[index].color).copy(alpha = .55f),
                                    Offset(doses[index].x, size.height), Offset(box.left + 8f, box.top + chipHeight / 2), 1.5f)
                            }
                        }
                        shown.forEach { index ->
                            val group = groupOf[index]
                            val isFront = group?.firstOrNull() == index
                            val isOpen = open?.contains(index) == true
                            val sum = if (isFront && !isOpen) group!!.sumOf { doses[it].amount.toDouble() }.toFloat() else doses[index].amount
                            val position = open?.indexOf(index)?.takeIf { it >= 0 }
                            val dx = position?.let { shifts!!.shifts[it][0] } ?: 0f
                            val dy = position?.let { shifts!!.shifts[it][1] } ?: 0f
                            JournalMarkerChip(markers[index], label(sum), remember { HashSet<PointerId>() },
                                width(label(sum)), Modifier.offset { IntOffset((placed[index].box.left + dx).roundToInt(),
                                    (placed[index].box.top + dy - touchInset).roundToInt()) }.testTag("dose-$index"),
                                stackCount = if (isFront && !isOpen) group!!.size else 0,
                                onClick = { expanded = if (expanded == index) null else index })
                        }
                    }
                }
            }
        }
        save("journal-crowding-before")
        compose.runOnIdle { compact = true }
        compose.waitForIdle()
        assertTrue("week-scale clusters should not remain a wall of dose chips", shown.size <= 9)
        assertEquals("every dose stays accounted for", doses.size, groups.sumOf { it.size } + shown.count { index -> groups.none { it.contains(index) } })
        val tallLeaders = leaders.copyOf()
        save("journal-crowding-after")
        compose.runOnIdle { plotHeight = 360f }
        compose.waitForIdle()
        assertArrayEquals("empty chart height must not change grouping", tallLeaders, leaders)
        compose.runOnIdle { separate = true; plotHeight = 520f }
        compose.waitForIdle()
        assertTrue("separate dose curves must also compact", shown.size <= 9)
        save("journal-crowding-separate-curves")
        compose.runOnIdle { fontScale = 1.5f }
        compose.waitForIdle()
        assertTrue("larger text still compacts", shown.size <= 9)
        save("journal-crowding-large-text")
        val pile = groups.first { it.size >= 3 }
        compose.onNodeWithTag("dose-${pile[0]}").performClick()
        pile.forEach { compose.onNodeWithTag("dose-$it").assertIsDisplayed() }
        save("journal-crowding-open")
    }

    private fun save(name: String) {
        val file = File("build/outputs/layout-regressions/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onNodeWithTag("plot").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
