package io.embodit.lightplanner.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.Grade

/**
 * The design's exact colours (design-package `palette`). This is the only file with colour literals;
 * the four semantic roles are injected into `PicoTheme` by [LightPlannerTheme], the brand tokens are
 * used directly (3D materials, plates, the heat ramp).
 */
object LpColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Interaction = Color(0xFFE8A23A) // design-style: fixed-figma-color interaction: select amber
    val Passable = Color(0xFF57C99A) // design-style: fixed-figma-color passable: spacing ok
    val Alert = Color(0xFFF2B84B) // design-style: fixed-figma-color alert: wall warnings
    val Error = Color(0xFFF0645A) // design-style: fixed-figma-color error: dark gap, cannot place

    // ---- brand tokens outside ColorScheme
    val PoolWarm2700 = Color(0xFFFFC27A) // design-style: fixed-figma-color poolWarm2700
    val PoolWarm3000 = Color(0xFFFFD79A) // design-style: fixed-figma-color poolWarm3000
    val PoolNeutral4000 = Color(0xFFFFF1D6) // design-style: fixed-figma-color poolNeutral4000
    val LampPlaster = Color(0xFFF3EFE8) // design-style: fixed-figma-color lampPlaster: the downlight trim
    val SpacingAccent = Color(0xFFDCDCDC) // design-style: fixed-figma-color spacingAccent (55 %)
    val Heat0 = Color(0xFF1C1F4A) // design-style: fixed-figma-color heat0
    val Heat1 = Color(0xFF5A3A7A) // design-style: fixed-figma-color heat1
    val Heat2 = Color(0xFFC46A3C) // design-style: fixed-figma-color heat2
    val Heat3 = Color(0xFFF2B84B) // design-style: fixed-figma-color heat3
    val Heat4 = Color(0xFFFFF4DE) // design-style: fixed-figma-color heat4
    val IconBg = Color(0xFF4A2D5E) // design-style: fixed-figma-color iconBg: plum, the app icon
    val IconGlyph = Color(0xFFFFD58A) // design-style: fixed-figma-color iconGlyph: warm light
    val PlateInk = Color(0xF016181C) // design-style: fixed-figma-color plateInk: 3D label plates
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val OnInkSecondary = Color(0xA8FFFFFF) // design-style: fixed-figma-color onInkSecondary white 66 %
    val TapeInk = Color(0xFF16181C) // design-style: fixed-figma-color tapeInk: content on the semantic fills
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: tracking lost

    fun pool(cct: Cct): Color =
        when (cct) {
            Cct.K2700 -> PoolWarm2700
            Cct.K3000 -> PoolWarm3000
            Cct.K4000 -> PoolNeutral4000
        }

    /** The 4-step legend colour of a grade (design `semantic.亮度图`). */
    fun heat(grade: Grade): Color =
        when (grade) {
            Grade.DARK -> Heat1
            Grade.DIM -> Heat2
            Grade.OK -> Heat3
            Grade.BRIGHT -> Heat4
            Grade.UNEVEN -> Heat2
        }

    @Composable
    fun gradeInk(grade: Grade): Color =
        when (grade) {
            Grade.DARK -> PicoTheme.colorScheme.error
            Grade.DIM, Grade.UNEVEN -> PicoTheme.colorScheme.alert
            Grade.OK -> PicoTheme.colorScheme.passable
            Grade.BRIGHT -> PicoTheme.colorScheme.alert
        }

    /** Continuous heat ramp (dark indigo -> plum -> ochre -> amber -> cream) for a ratio 0..2.5. */
    fun heatRamp(ratio: Float): IntArray {
        val stops = arrayOf(Heat0, Heat1, Heat2, Heat3, Heat4)
        val t = (ratio / 2.5f).coerceIn(0f, 0.9999f) * (stops.size - 1)
        val i = t.toInt()
        val f = t - i
        val a = stops[i]
        val b = stops[i + 1]
        return intArrayOf(
            ((a.red + (b.red - a.red) * f) * 255f).toInt(),
            ((a.green + (b.green - a.green) * f) * 255f).toInt(),
            ((a.blue + (b.blue - a.blue) * f) * 255f).toInt(),
        )
    }
}

/** Type roles of the design (`typography.roles`): tabular figures wherever a number is shown. */
object LpType {
    val instruction: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val hint: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

    val status: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val segment: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
}
