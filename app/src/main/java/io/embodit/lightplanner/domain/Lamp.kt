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

/** Colour temperature (design `lightModel.lamp.cct`): only the colour of the pool. */
enum class Cct(val kelvin: Int, val tag: String) {
    K2700(2700, "2700"),
    K3000(3000, "3000"),
    K4000(4000, "4000");

    companion object {
        val DEFAULT = K3000

        fun byTag(tag: String?): Cct? = entries.firstOrNull { it.tag == tag || it.name.equals(tag, ignoreCase = true) || it.kelvin.toString() == tag }
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
