package io.embodit.lightplanner.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** Relative brightness band of a surface (design `lightModel.relative.bands`). */
enum class Grade { DARK, DIM, OK, BRIGHT, UNEVEN }

/** Colour of a spacing line (design `spatialLayout.ceiling.spacingLines`). */
enum class LineKind { OK, DARK_GAP, ACCENT }

/** A line between two lamps on the ceiling, judged. */
data class SpacingLine(val a: String, val b: String, val distance: Float, val limit: Float, val kind: LineKind) {
    val key: String
        get() = if (a < b) "$a|$b" else "$b|$a"
}

/** A lamp's wall warning (design `lightModel.spacingRule.wall`). */
enum class WallWarn { TOO_CLOSE, EDGE_DARK }

data class WallNote(val lampId: String, val distance: Float, val warn: WallWarn, val wallPoint: P2)

/** One lamp's light pool on one receiving surface. */
data class Pool(val lampId: String, val receiverId: String, val center: P2, val radius: Float, val drop: Float)

/** The judgement of one receiving surface. */
data class SurfaceGrade(val receiverId: String, val kind: SurfaceKind, val grade: Grade, val ratio: Float, val uniformity: Float)

/** A lamp's distances for the strip and the hole plan. */
data class LampInfo(val lampId: String, val wallDistance: Float?, val nearestLampDistance: Float?, val wallDistances: List<Pair<Float, P2>>)

/**
 * Direct-light arithmetic only (design `lightModel`): a cos^n beam, E = I(a) cos a / d^2, geometric pool
 * radius r = dh tan(theta / 2), relative bands against internal references, the spacing limit
 * S_max = min(D, 1.5 (H - 0.75)) and the wall rules. No lux ever leaves this file (acceptance).
 */
object LightMath {
    const val WORK_PLANE_M = 0.75f
    const val DIRECT_FACTOR = 0.7f
    const val SPACING_CAP = 1.5f
    const val WALL_CLOSE_M = 0.30f
    const val WALL_EDGE_FACTOR = 0.6f
    const val OUTERMOST_BAND_M = 0.5f
    const val SNAP_M = 0.04f
    const val HYSTERESIS = 0.05f
    const val CENTER_OPACITY = 0.55f
    const val MAX_OPACITY = 0.72f
    const val UNEVEN_MIN_RATIO = 0.4f
    const val MAX_LAMPS = 16
    const val CELL_M = 0.04f
    const val MAX_CELLS = 192

    /** Peak intensity (candela) of a lamp: I0 = Phi (n + 1) / 2 pi. */
    fun peakIntensity(lamp: Lamp): Float = lamp.output.lumens * (lamp.beam.n + 1f) / (2f * Math.PI.toFloat())

    /** Direct illuminance at a point [dh] below the lamp and [horizontal] metres aside. */
    fun illuminance(i0: Float, n: Float, dh: Float, horizontal: Float): Float {
        if (dh <= 0.01f) return 0f
        val d2 = dh * dh + horizontal * horizontal
        val d = sqrt(d2)
        val cosA = dh / d
        return i0 * cosA.pow(n) * cosA / d2
    }

    /** Pool radius (design `pool`): the half-power cone meets the surface [dh] below the lamp. */
    fun poolRadius(beam: Beam, dh: Float): Float = max(0f, dh) * tan(beam.halfRad)

    /** The pool diameter on the work plane for the spacing rule, given a ceiling height [h] above the floor. */
    fun poolDiameter(beam: Beam, h: Float): Float = 2f * max(0f, h - WORK_PLANE_M) * tan(beam.halfRad)

    /** S_max for two general lamps: min(D, 1.5 x mounting height) with the smaller D and the lower ceiling. */
    fun spacingLimit(a: Beam, b: Beam, hA: Float, hB: Float): Float {
        val h = min(hA, hB)
        val d = min(poolDiameter(a, h), poolDiameter(b, h))
        return min(d, SPACING_CAP * max(0f, h - WORK_PLANE_M))
    }

    /** The band of a ratio (reference already applied), with hysteresis against the previous grade. */
    fun grade(ratio: Float, uniformity: Float, previous: Grade?): Grade {
        var g =
            when {
                ratio < 0.5f -> Grade.DARK
                ratio < 0.8f -> Grade.DIM
                ratio <= 2.0f -> Grade.OK
                else -> Grade.BRIGHT
            }
        if (previous != null && previous != Grade.UNEVEN && g != previous) {
            // stay in the previous band while the ratio sits within 5 % of its edge
            val keep =
                when (previous) {
                    Grade.DARK -> ratio < 0.5f * (1f + HYSTERESIS)
                    Grade.DIM -> ratio >= 0.5f * (1f - HYSTERESIS) && ratio < 0.8f * (1f + HYSTERESIS)
                    Grade.OK -> ratio >= 0.8f * (1f - HYSTERESIS) && ratio <= 2.0f * (1f + HYSTERESIS)
                    Grade.BRIGHT -> ratio > 2.0f * (1f - HYSTERESIS)
                    Grade.UNEVEN -> false
                }
            if (keep) g = previous
        }
        if (g != Grade.DARK && uniformity < UNEVEN_MIN_RATIO && ratio >= 0.5f) g = Grade.UNEVEN
        return g
    }

    /** Pool alpha of one lamp at [e] against its own centre value: 55 % x (E / E_c)^2 (design, the sample's square fall-off). */
    fun poolAlpha(e: Float, eCenter: Float): Float {
        if (eCenter <= 0f) return 0f
        val q = (e / eCenter).coerceIn(0f, 1f)
        return CENTER_OPACITY * q * q
    }

    /**
     * The spacing lines of a set of lamps (design `spacingRule.neighbors`): every general lamp joins its two
     * nearest general lamps on the same side of every wall; an accent lamp draws one grey line to the
     * nearest lamp. [ceilingY] gives each lamp's ceiling height above the floor.
     */
    fun spacingLines(lamps: List<Lamp>, room: RoomModel, ceilingY: (Lamp) -> Float): List<SpacingLine> {
        val out = LinkedHashMap<String, SpacingLine>()
        val general = lamps.filter { it.beam.general }
        for (a in general) {
            val near = general.filter { it.id != a.id && !room.crossesWall(a.xz, it.xz) }.sortedBy { (it.xz - a.xz).length }.take(2)
            for (b in near) {
                val s = (b.xz - a.xz).length
                val limit = spacingLimit(a.beam, b.beam, ceilingY(a), ceilingY(b))
                val line = SpacingLine(a.id, b.id, s, limit, if (s > limit + 0.005f) LineKind.DARK_GAP else LineKind.OK)
                out.putIfAbsent(line.key, line)
            }
        }
        for (a in lamps.filter { !it.beam.general }) {
            val b = lamps.filter { it.id != a.id && !room.crossesWall(a.xz, it.xz) }.minByOrNull { (it.xz - a.xz).length } ?: continue
            val line = SpacingLine(a.id, b.id, (b.xz - a.xz).length, 0f, LineKind.ACCENT)
            out.putIfAbsent(line.key, line)
        }
        return out.values.toList()
    }

    /** Wall rules for general lamps (design `spacingRule.wall`). */
    fun wallNotes(lamps: List<Lamp>, room: RoomModel, ceilingY: (Lamp) -> Float): List<WallNote> {
        val out = ArrayList<WallNote>()
        val general = lamps.filter { it.beam.general }
        for (a in general) {
            val (w, wall) = room.nearestWall(a.xz) ?: continue
            val (p, q) = room.wallSegment(wall)
            val foot = RoomModel.closestOnSegment(a.xz, p, q)
            if (w < WALL_CLOSE_M) {
                out += WallNote(a.id, w, WallWarn.TOO_CLOSE, foot)
                continue
            }
            val nearest = general.filter { it.id != a.id }.minOfOrNull { (it.xz - a.xz).length }
            val s = nearest ?: spacingLimit(a.beam, a.beam, ceilingY(a), ceilingY(a))
            // outermost towards that wall: no other general lamp between it and the wall within a 0.5 m band
            val toWall = (foot - a.xz)
            val n = toWall.normalized()
            val outermost =
                general.none { o ->
                    if (o.id == a.id) return@none false
                    val v = o.xz - a.xz
                    val along = v.dot(n)
                    val lateral = abs(v.dot(n.right))
                    along > 0.02f && along < w && lateral < OUTERMOST_BAND_M
                }
            if (outermost && w > WALL_EDGE_FACTOR * s) out += WallNote(a.id, w, WallWarn.EDGE_DARK, foot)
        }
        return out
    }

    /** Distances for the strip / hole plan: nearest wall, nearest other lamp, and the two nearest walls' feet. */
    fun info(lamp: Lamp, lamps: List<Lamp>, room: RoomModel): LampInfo {
        val walls =
            room.walls.map { w ->
                val (p, q) = room.wallSegment(w)
                val foot = RoomModel.closestOnSegment(lamp.xz, p, q)
                (lamp.xz - foot).length to foot
            }.sortedBy { it.first }
        val nearest = lamps.filter { it.id != lamp.id }.minOfOrNull { (it.xz - lamp.xz).length }
        return LampInfo(lamp.id, walls.firstOrNull()?.first, nearest, pickTwoWalls(walls))
    }

    /** The nearest wall and the nearest wall roughly at right angles to it (the two dimensions an electrician marks). */
    private fun pickTwoWalls(walls: List<Pair<Float, P2>>): List<Pair<Float, P2>> {
        if (walls.size < 2) return walls
        val first = walls[0]
        val second = walls.drop(1).firstOrNull { abs((it.second - first.second).normalized().dot((first.second - it.second).normalized())) < 2f } ?: walls[1]
        return listOf(first, second)
    }

    /**
     * Row snap while dragging (design `lightModel.snap`): within 4 cm of another lamp's coordinate along
     * the room's main axis or its perpendicular, the dragged lamp takes that coordinate. Returns the
     * snapped point and the guide axes that engaged (unit directions of the guide lines).
     */
    fun snap(p: P2, others: List<Lamp>, axis: P2): Pair<P2, List<Pair<P2, P2>>> {
        val u = axis.normalized()
        val v = u.right
        var pu = p.dot(u)
        var pv = p.dot(v)
        val guides = ArrayList<Pair<P2, P2>>(2)
        var bestU = SNAP_M
        var bestV = SNAP_M
        var snapU: Float? = null
        var snapV: Float? = null
        for (o in others) {
            val ou = o.xz.dot(u)
            val ov = o.xz.dot(v)
            if (abs(ou - pu) < bestU) {
                bestU = abs(ou - pu)
                snapU = ou
            }
            if (abs(ov - pv) < bestV) {
                bestV = abs(ov - pv)
                snapV = ov
            }
        }
        if (snapU != null) pu = snapU
        if (snapV != null) pv = snapV
        val q = u * pu + v * pv
        if (snapU != null) guides += q to v // a line along v through the snapped u coordinate
        if (snapV != null) guides += q to u
        return q to guides
    }
}
