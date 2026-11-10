@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package tk.glucodata.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontVariation
import tk.glucodata.R

// ============================================================================
//  COMPREHENSIVE FONT CONFIGURATION (PER-STYLE CONTROL)
//  Font: IBM Plex Sans Variable (Supports Cyrillic)
// ============================================================================

val MainFontFile = R.font.ibm_plex_sans_var

// --- HELPER VALUES ---
// WIDTHS (wdth):
// - 100f (Standard): Normal width.
// - 90f (Semi-Condensed): Sleek look.
// - 85f (Condensed): Minimum width for IBM Plex Sans. Best for Data.
const val WidthStandard = 100f
const val WidthSemiCondensed = 90f
const val WidthCondensed = 85f

// OPTICAL SIZE (opsz):
// IBM Plex Sans Variable does NOT support Optical Size.
// This axis has been removed.

// ============================================================================
//  GUIDELINES & EXAMPLES
// ============================================================================
/*
 *  CONFIGURATION: IBM PLEX SANS
 *  - Supports Cyrillic characters!
 *  - Width range: 85 (Narrow) to 100 (Wide).
 *  - Weight range: 100 (Thin) to 700 (Bold).
 *
 *  STYLE: SLEEK & EFFICIENT
 *  - Width: 90f for general text.
 *  - Weight: 400 (Regular) for most elements.
 */

// ----------------------------------------------------------------------------
//  1. DISPLAY STYLES (Huge numbers, Hero screens)
// ----------------------------------------------------------------------------

// Display Large (Main Glucose Value)
const val DisplayLarge_Weight = 500
const val DisplayLarge_Width  = WidthCondensed
const val DisplayLarge_Space  = -0.55

// Display Medium
const val DisplayMedium_Weight = 400
const val DisplayMedium_Width  = WidthStandard
const val DisplayMedium_Space  = 0.0

// Display Small
const val DisplaySmall_Weight = 400
const val DisplaySmall_Width  = WidthStandard
const val DisplaySmall_Space  = 0.0

// ----------------------------------------------------------------------------
//  2. HEADLINE STYLES (Section headers, Top App Bars)
// ----------------------------------------------------------------------------

// Headline Large
const val HeadlineLarge_Weight = 500
const val HeadlineLarge_Width  = WidthStandard
const val HeadlineLarge_Space  = 0.0

// Headline Medium
const val HeadlineMedium_Weight = 400
const val HeadlineMedium_Width  = WidthStandard
const val HeadlineMedium_Space  = 0.0

// Headline Small
const val HeadlineSmall_Weight = 500
const val HeadlineSmall_Width  = WidthStandard
const val HeadlineSmall_Space  = 0.0

// ----------------------------------------------------------------------------
//  3. TITLE STYLES (Card titles, Dialog titles, Lists)
// ----------------------------------------------------------------------------

// Title Large (Page Headers)
const val TitleLarge_Weight = 500
const val TitleLarge_Width  = WidthCondensed
const val TitleLarge_Space  = 0.25

// Title Medium (List Items, Settings)
const val TitleMedium_Weight = 400 // Regular (Sleek List Look)
const val TitleMedium_Width  = WidthStandard
const val TitleMedium_Space  = 0.15

// Title Small
const val TitleSmall_Weight = 500 // Medium (Distinction)
const val TitleSmall_Width  = WidthStandard
const val TitleSmall_Space  = 0.1

// ----------------------------------------------------------------------------
//  4. BODY STYLES (Paragraphs, Long text)
// ----------------------------------------------------------------------------

// Body Large
const val BodyLarge_Weight = 400
const val BodyLarge_Width  = WidthStandard
const val BodyLarge_Space  = 0.5

// Body Medium (Standard Settings Description)
const val BodyMedium_Weight = 400 // Regular (Restored from 300 to match user needs)
const val BodyMedium_Width  = WidthStandard
const val BodyMedium_Space  = 0.25

// Body Small
const val BodySmall_Weight = 400
const val BodySmall_Width  = WidthStandard
const val BodySmall_Space  = 0.4

// ----------------------------------------------------------------------------
//  5. LABEL STYLES (Buttons, Tags, Captions)
// ----------------------------------------------------------------------------

// Label Large (Buttons)
const val LabelLarge_Weight = 500
const val LabelLarge_Width  = WidthStandard
const val LabelLarge_Space  = 0.1

// Label Medium
const val LabelMedium_Weight = 500
const val LabelMedium_Width  = WidthStandard
const val LabelMedium_Space  = 0.5

// Label Small (Captions)
const val LabelSmall_Weight = 500
const val LabelSmall_Width  = WidthStandard
const val LabelSmall_Space  = 0.5


// ============================================================================
//  INTERNAL FACTORY
// ============================================================================

/**
 * One FontFamily per width, holding a real instance of the variable font at every weight the
 * app asks for.
 *
 * Each style used to get a family with a single Font pinned to its own weight. Compose picks a
 * font by `fontWeight`, so any `fontWeight =` override on top of a style then found no match:
 * Medium quietly rendered as the style's own weight, and SemiBold/Bold fell back to a
 * synthesised faux-bold of it. With every weight registered, an override selects the matching
 * instance of the variable font instead.
 */
private val PlexWeights = intArrayOf(100, 200, 300, 400, 500, 600, 700)

private val plexFamilies = HashMap<Float, FontFamily>()

private fun ibmPlexSans(width: Float): FontFamily =
    plexFamilies.getOrPut(width) {
        try {
            FontFamily(
                PlexWeights.map { w ->
                    Font(
                        MainFontFile,
                        weight = FontWeight(w),
                        variationSettings = FontVariation.Settings(
                            FontVariation.weight(w),
                            FontVariation.width(width)
                        )
                    )
                }
            )
        } catch (th: Throwable) {
            android.util.Log.w("AppTypography", "Variable font fallback activated", th)
            FontFamily(Font(MainFontFile))
        }
    }

// ============================================================================
//  TYPOGRAPHY DEFINITION
// ============================================================================

private val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ibmPlexSans(DisplayLarge_Width),
        fontWeight = FontWeight(DisplayLarge_Weight),
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = DisplayLarge_Space.sp
    ),
    displayMedium = TextStyle(
        fontFamily = ibmPlexSans(DisplayMedium_Width),
        fontWeight = FontWeight(DisplayMedium_Weight),
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = DisplayMedium_Space.sp
    ),
    displaySmall = TextStyle(
        fontFamily = ibmPlexSans(DisplaySmall_Width),
        fontWeight = FontWeight(DisplaySmall_Weight),
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = DisplaySmall_Space.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = ibmPlexSans(HeadlineLarge_Width),
        fontWeight = FontWeight(HeadlineLarge_Weight),
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = HeadlineLarge_Space.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = ibmPlexSans(HeadlineMedium_Width),
        fontWeight = FontWeight(HeadlineMedium_Weight),
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = HeadlineMedium_Space.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = ibmPlexSans(HeadlineSmall_Width),
        fontWeight = FontWeight(HeadlineSmall_Weight),
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = HeadlineSmall_Space.sp
    ),
    titleLarge = TextStyle(
        fontFamily = ibmPlexSans(TitleLarge_Width),
        fontWeight = FontWeight(TitleLarge_Weight),
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = TitleLarge_Space.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ibmPlexSans(TitleMedium_Width),
        fontWeight = FontWeight(TitleMedium_Weight),
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = TitleMedium_Space.sp
    ),
    titleSmall = TextStyle(
        fontFamily = ibmPlexSans(TitleSmall_Width),
        fontWeight = FontWeight(TitleSmall_Weight),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = TitleSmall_Space.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ibmPlexSans(BodyLarge_Width),
        fontWeight = FontWeight(BodyLarge_Weight),
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = BodyLarge_Space.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ibmPlexSans(BodyMedium_Width),
        fontWeight = FontWeight(BodyMedium_Weight),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = BodyMedium_Space.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ibmPlexSans(BodySmall_Width),
        fontWeight = FontWeight(BodySmall_Weight),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = BodySmall_Space.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ibmPlexSans(LabelLarge_Width),
        fontWeight = FontWeight(LabelLarge_Weight),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = LabelLarge_Space.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ibmPlexSans(LabelMedium_Width),
        fontWeight = FontWeight(LabelMedium_Weight),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = LabelMedium_Space.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ibmPlexSans(LabelSmall_Width),
        fontWeight = FontWeight(LabelSmall_Weight),
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = LabelSmall_Space.sp
    )
)

// 4. Emphasized Styles (Extensions for Expressive Look)

// Glucose Value: Uses the Display Large config but allows local overrides if needed
val Typography.displayLargeExpressive: TextStyle
    get() = displayLarge.copy(
        letterSpacing = (-0.5).sp
    )

// Status Indicators
val Typography.labelSmallPrim: TextStyle
    get() = labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    )

// Settings Labels
val Typography.labelLargeExpressive: TextStyle
    get() = labelLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
    )

/**
 * One weight step up for the emphasized styles: Medium for the display styles, which are already
 * large enough to carry it, SemiBold for everything else. material3 1.5 reads these from the
 * Typography (titleMediumEmphasized and friends); left unset they fall back to the library's
 * baseline styles in its default font, not IBM Plex.
 */
private fun TextStyle.emphasized(): TextStyle =
    copy(fontWeight = if ((fontSize.value) >= 36f) FontWeight.Medium else FontWeight.SemiBold)

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge,
    displayLargeEmphasized = BaseTypography.displayLarge.emphasized(),
    displayMedium = BaseTypography.displayMedium,
    displayMediumEmphasized = BaseTypography.displayMedium.emphasized(),
    displaySmall = BaseTypography.displaySmall,
    displaySmallEmphasized = BaseTypography.displaySmall.emphasized(),
    headlineLarge = BaseTypography.headlineLarge,
    headlineLargeEmphasized = BaseTypography.headlineLarge.emphasized(),
    headlineMedium = BaseTypography.headlineMedium,
    headlineMediumEmphasized = BaseTypography.headlineMedium.emphasized(),
    headlineSmall = BaseTypography.headlineSmall,
    headlineSmallEmphasized = BaseTypography.headlineSmall.emphasized(),
    titleLarge = BaseTypography.titleLarge,
    titleLargeEmphasized = BaseTypography.titleLarge.emphasized(),
    titleMedium = BaseTypography.titleMedium,
    titleMediumEmphasized = BaseTypography.titleMedium.emphasized(),
    titleSmall = BaseTypography.titleSmall,
    titleSmallEmphasized = BaseTypography.titleSmall.emphasized(),
    bodyLarge = BaseTypography.bodyLarge,
    bodyLargeEmphasized = BaseTypography.bodyLarge.emphasized(),
    bodyMedium = BaseTypography.bodyMedium,
    bodyMediumEmphasized = BaseTypography.bodyMedium.emphasized(),
    bodySmall = BaseTypography.bodySmall,
    bodySmallEmphasized = BaseTypography.bodySmall.emphasized(),
    labelLarge = BaseTypography.labelLarge,
    labelLargeEmphasized = BaseTypography.labelLarge.emphasized(),
    labelMedium = BaseTypography.labelMedium,
    labelMediumEmphasized = BaseTypography.labelMedium.emphasized(),
    labelSmall = BaseTypography.labelSmall,
    labelSmallEmphasized = BaseTypography.labelSmall.emphasized()
)
