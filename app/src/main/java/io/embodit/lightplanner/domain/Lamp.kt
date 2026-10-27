package io.embodit.lightplanner.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln

/** Beam angle (design `lightModel.lamp.beam`): 24 / 36 are accent lighting, 60 / 90 are general lighting. */
enum class Beam(val deg: Float, val tag: String) {
    SPOT(24f, "24"),
    NARROW(36f, "36"),
    STD(60f, "60"),
    FLOOD(90f, "90");

    /** General lighting takes part in the spacing rule; accent lighting only gets a grey line. */
    val general: Boolean
        get() = this == STD || this == FLOOD

    val halfRad: Float
        get() = Math.toRadians(deg / 2.0).toFloat()

    /** cos^n falls to one half at the beam's half angle. */
    val n: Float
        get() = (ln(0.5) / ln(cos(halfRad.toDouble()))).toFloat()

    companion object {
        val DEFAULT = STD

        fun byTag(tag: String?): Beam? = entries.firstOrNull { it.tag == tag || it.name.equals(tag, ignoreCase = true) }

        fun byDeg(deg: Float): Beam = entries.minByOrNull { abs(it.deg - deg) } ?: STD
    }
}

/** Light output (design `lightModel.lamp.output`): about 5 / 7 / 10 W. */
enum class Output(val lumens: Float, val watts: Int, val tag: String) {
    S(400f, 5, "s"),
    M(650f, 7, "m"),
    L(900f, 10, "l");

    companion object {
        val DEFAULT = M

        fun byTag(tag: String?): Output? = entries.firstOrNull { it.tag == tag || it.name.equals(tag, ignoreCase = true) }
    }
}

/**
 * The light's colour (design `lightModel.lamp.cct`; 0.1.1 user feedback: "光的颜色也可以选最好"): five white
 * colour temperatures, plus five colours for RGB downlights. It only tints the pool, the cone and the lamp's
 * glow; brightness and grades stay those of the lamp's output. [rgb] is the pool's tint (0..1, brightest
 * channel 1); [kelvin] is 0 for a colour.
 */
enum class Cct(val kelvin: Int, val tag: String, val rgb: FloatArray) {
    K2700(2700, "2700", floatArrayOf(1.00f, 0.76f, 0.48f)),
    K3000(3000, "3000", floatArrayOf(1.00f, 0.84f, 0.60f)),
    K4000(4000, "4000", floatArrayOf(1.00f, 0.95f, 0.84f)),
    K5000(5000, "5000", floatArrayOf(0.96f, 0.98f, 1.00f)),
    K6500(6500, "6500", floatArrayOf(0.80f, 0.89f, 1.00f)),
    RED(0, "red", floatArrayOf(1.00f, 0.32f, 0.28f)),
    AMBER(0, "amber", floatArrayOf(1.00f, 0.62f, 0.18f)),
    GREEN(0, "green", floatArrayOf(0.38f, 1.00f, 0.52f)),
    BLUE(0, "blue", floatArrayOf(0.36f, 0.56f, 1.00f)),
    VIOLET(0, "violet", floatArrayOf(0.74f, 0.44f, 1.00f));

    /** A white colour temperature (the hole plan prints "3000 K"); false for a colour. */
    val white: Boolean
        get() = kelvin > 0

    companion object {
        val DEFAULT = K3000
        val WHITES: List<Cct> = entries.filter { it.white }
        val COLOURS: List<Cct> = entries.filter { !it.white }

        fun byTag(tag: String?): Cct? = entries.firstOrNull { it.tag == tag || it.name.equals(tag, ignoreCase = true) || (it.white && it.kelvin.toString() == tag) }
    }
}

/**
 * One downlight (design `dataModel.plan.lamps[]`): [pos] is the point on the ceiling (Stage metres,
 * y = the ceiling height above that spot). [no] is the number shown to the user; it never shifts
 * when another lamp is deleted (design T7).
 */
data class Lamp(
    val id: String,
    val no: Int,
    val pos: Vec3,
    val beam: Beam = Beam.DEFAULT,
    val output: Output = Output.DEFAULT,
    val cct: Cct = Cct.DEFAULT,
    val anchorId: String? = null,
) {
    val xz: P2
        get() = P2(pos.x, pos.z)

    val params: LampParams
        get() = LampParams(beam, output, cct)
}

/** The three parameters a new lamp inherits from the last one that was selected (design `newLampInherits`). */
data class LampParams(val beam: Beam = Beam.DEFAULT, val output: Output = Output.DEFAULT, val cct: Cct = Cct.DEFAULT)
