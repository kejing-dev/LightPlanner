package io.embodit.lightplanner.ui.planner.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.lightplanner.R
import io.embodit.lightplanner.domain.Beam
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.Grade
import io.embodit.lightplanner.domain.LanguagePref
import io.embodit.lightplanner.domain.SurfaceKind
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalLpResources = staticCompositionLocalOf<Resources> { error("LocalLpResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalLpResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalLpResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun beamName(beam: Beam): String =
    rs(
        when (beam) {
            Beam.SPOT -> R.string.beam_spot
            Beam.NARROW -> R.string.beam_narrow
            Beam.STD -> R.string.beam_std
            Beam.FLOOD -> R.string.beam_flood
        }
    )

/** The full name of a light colour: "暖白 3000 K", "蓝光". */
@Composable
@ReadOnlyComposable
fun lightName(cct: Cct): String =
    rs(
        when (cct) {
            Cct.K2700 -> R.string.light_2700
            Cct.K3000 -> R.string.light_3000
            Cct.K4000 -> R.string.light_4000
            Cct.K5000 -> R.string.light_5000
            Cct.K6500 -> R.string.light_6500
            Cct.RED -> R.string.light_red
            Cct.AMBER -> R.string.light_amber
            Cct.GREEN -> R.string.light_green
            Cct.BLUE -> R.string.light_blue
            Cct.VIOLET -> R.string.light_violet
        }
    )

/** The short name of a light colour for a segment or a table cell: "3000K", or the colour's name. */
@Composable
@ReadOnlyComposable
fun lightShort(cct: Cct): String =
    when (cct) {
        Cct.K2700 -> rs(R.string.cct_2700)
        Cct.K3000 -> rs(R.string.cct_3000)
        Cct.K4000 -> rs(R.string.cct_4000)
        Cct.K5000 -> rs(R.string.cct_5000)
        Cct.K6500 -> rs(R.string.cct_6500)
        else -> lightName(cct)
    }

/** The copy the 3D scene draws into its label plates (surface tags), resolved for the current language. */
data class SceneStrings(
    val desk: String,
    val coffee: String,
    val sofa: String,
    val bed: String,
    val floor: String,
    val other: String,
    val dark: String,
    val dim: String,
    val ok: String,
    val bright: String,
    val uneven: String,
    val darkGap: String,
    val wallClose: String,
    val wallEdge: String,
) {
    fun kind(k: SurfaceKind): String =
        when (k) {
            SurfaceKind.DESK -> desk
            SurfaceKind.COFFEE -> coffee
            SurfaceKind.SOFA -> sofa
            SurfaceKind.BED -> bed
            SurfaceKind.FLOOR -> floor
            SurfaceKind.OTHER -> other
        }

    fun grade(g: Grade): String =
        when (g) {
            Grade.DARK -> dark
            Grade.DIM -> dim
            Grade.OK -> ok
            Grade.BRIGHT -> bright
            Grade.UNEVEN -> uneven
        }

    companion object {
        fun from(r: Resources) =
            SceneStrings(
                desk = r.getString(R.string.kind_desk),
                coffee = r.getString(R.string.kind_coffee),
                sofa = r.getString(R.string.kind_sofa),
                bed = r.getString(R.string.kind_bed),
                floor = r.getString(R.string.kind_floor),
                other = r.getString(R.string.kind_other),
                dark = r.getString(R.string.grade_dark),
                dim = r.getString(R.string.grade_dim),
                ok = r.getString(R.string.grade_ok),
                bright = r.getString(R.string.grade_bright),
                uneven = r.getString(R.string.grade_uneven),
                darkGap = r.getString(R.string.line_dark_gap),
                wallClose = r.getString(R.string.wall_close_short),
                wallEdge = r.getString(R.string.wall_edge_short),
            )
    }
}

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val MAIN_W = 400f
    const val MAIN_H = 400f
    const val SETTINGS_W = 400f
    const val SETTINGS_H = 420f
    const val HOLE_W = 560f
    const val HOLE_H = 460f

    /** Disabled content (SpatialUI's own disabled alpha). */
    const val DISABLED_ALPHA = 0.38f
}
