package io.embodit.lightplanner.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The sampled light on one receiving surface: a grid of [LightMath.CELL_M] cells over the polygon's
 * bounding box in Stage x / z. [e] is the summed direct illuminance per cell, [alpha] the summed pool
 * opacity (design `pool`), [r], [g], [b] the E-weighted pool colour, [edge] marks the bright rim of a
 * pool and [mask] the cells inside the polygon. Pure data: the scene paints it into a bitmap.
 */
class SurfaceField(
    val receiverId: String,
    val kind: SurfaceKind,
    val y: Float,
    val minX: Float,
    val minZ: Float,
    val cell: Float,
    val w: Int,
    val h: Int,
    val mask: BooleanArray,
) {
    val e = FloatArray(w * h)
    val alpha = FloatArray(w * h)
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
    val edge = BooleanArray(w * h)

    val width: Float
        get() = w * cell

    val depth: Float
        get() = h * cell

    val centerX: Float
        get() = minX + width / 2f

    val centerZ: Float
        get() = minZ + depth / 2f

    fun index(ix: Int, iz: Int) = iz * w + ix

    fun x(ix: Int) = minX + (ix + 0.5f) * cell

    fun z(iz: Int) = minZ + (iz + 0.5f) * cell
}

/** Everything the light computation produces for one plan (design `budget.recompute`). */
data class LightResult(
    val fields: List<SurfaceField>,
    val grades: List<SurfaceGrade>,
    val pools: List<Pool>,
    val lines: List<SpacingLine>,
    val wallNotes: List<WallNote>,
    val infos: Map<String, LampInfo>,
    val sampleCount: Int,
    val millis: Long,
) {
    fun grade(receiverId: String): SurfaceGrade? = grades.firstOrNull { it.receiverId == receiverId }

    fun info(lampId: String): LampInfo? = infos[lampId]

    companion object {
        val EMPTY = LightResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), 0, 0L)
    }
}

/**
 * Direct light only (design `lightModel.shadow`, `limits`): for every receiving surface and every lamp,
 * the cells within twice the pool radius get E = I(a) cos a / d^2; a cell is in shadow when the line
 * to the lamp passes through a higher receiving surface. Runs on a worker thread; nothing here touches
 * the SDK.
 */
object LightField {
    fun compute(lamps: List<Lamp>, room: RoomModel, previous: Map<String, Grade>, ceilingY: (Lamp) -> Float, maxCells: Int = LightMath.MAX_CELLS): LightResult {
        val t0 = System.nanoTime()
        val fields = ArrayList<SurfaceField>()
        val pools = ArrayList<Pool>()
        val grades = ArrayList<SurfaceGrade>()
        var samples = 0
        val receivers = room.receivers
        for (rc in receivers) {
            val f = grid(rc, maxCells) ?: continue
            fields += f
            val higher = receivers.filter { it.y > rc.y + 0.02f }
            for (lamp in lamps) {
                val lampY = lamp.pos.y
                val dh = lampY - rc.y
                if (dh <= 0.05f) continue
                val i0 = LightMath.peakIntensity(lamp)
                val n = lamp.beam.n
                val eCenter = i0 / (dh * dh)
                val radius = LightMath.poolRadius(lamp.beam, dh)
                val cut = 2f * radius
                val rgb = lamp.cct.rgb
                val blockers = higher.filter { it.y < lampY }
                val lx = lamp.pos.x
                val lz = lamp.pos.z
                val ix0 = max(0, ((lx - cut - f.minX) / f.cell).toInt())
                val ix1 = min(f.w - 1, ((lx + cut - f.minX) / f.cell).toInt())
                val iz0 = max(0, ((lz - cut - f.minZ) / f.cell).toInt())
                val iz1 = min(f.h - 1, ((lz + cut - f.minZ) / f.cell).toInt())
                var lit = 0
                for (iz in iz0..iz1) for (ix in ix0..ix1) {
                    val idx = f.index(ix, iz)
                    if (!f.mask[idx]) continue
                    val px = f.x(ix)
                    val pz = f.z(iz)
                    val hd = sqrt((px - lx) * (px - lx) + (pz - lz) * (pz - lz))
                    if (hd > cut) continue
                    samples++
                    if (blockers.isNotEmpty() && shadowed(px, rc.y, pz, lamp.pos, blockers)) continue
                    val e = LightMath.illuminance(i0, n, dh, hd)
                    f.e[idx] += e
                    f.alpha[idx] += LightMath.poolAlpha(e, eCenter)
                    f.r[idx] += rgb[0] * e
                    f.g[idx] += rgb[1] * e
                    f.b[idx] += rgb[2] * e
                    if (abs(hd - radius) < f.cell * 0.6f) f.edge[idx] = true
                    lit++
                }
                if (lit > 0) pools += Pool(lamp.id, rc.id, P2(lx, lz), radius, dh)
            }
            grades += grade(f, rc, previous[rc.id])
        }
        val lines = LightMath.spacingLines(lamps, room, ceilingY)
        val notes = LightMath.wallNotes(lamps, room, ceilingY)
        val infos = lamps.associate { it.id to LightMath.info(it, lamps, room) }
        return LightResult(fields, grades, pools, lines, notes, infos, samples, (System.nanoTime() - t0) / 1_000_000L)
    }

    /** The cell grid of a receiver: 4 cm cells, at most [maxCells] a side (coarser on a big floor). */
    fun grid(rc: Receiver, maxCells: Int): SurfaceField? {
        val v = rc.plane.vertices
        if (v.size < 3) return null
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (p in v) {
            minX = min(minX, p.x); maxX = max(maxX, p.x); minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
        }
        val ext = max(maxX - minX, maxZ - minZ)
        if (ext < 0.05f) return null
        val cell = max(LightMath.CELL_M, ext / maxCells)
        val w = max(1, ceil((maxX - minX) / cell).toInt())
        val h = max(1, ceil((maxZ - minZ) / cell).toInt())
        val mask = BooleanArray(w * h)
        val y = rc.y
        for (iz in 0 until h) for (ix in 0 until w) {
            val px = minX + (ix + 0.5f) * cell
            val pz = minZ + (iz + 0.5f) * cell
            mask[iz * w + ix] = rc.plane.contains(Vec3(px, y, pz), -cell * 0.5f)
        }
        return SurfaceField(rc.id, rc.kind, y, minX, minZ, cell, w, h, mask)
    }

    /** Does the line from the sample to the lamp pass through a higher receiving surface? */
    private fun shadowed(px: Float, py: Float, pz: Float, lamp: Vec3, blockers: List<Receiver>): Boolean {
        val dy = lamp.y - py
        if (dy <= 1e-4f) return false
        for (b in blockers) {
            val t = (b.y - py) / dy
            if (t <= 0f || t >= 1f) continue
            val qx = px + (lamp.x - px) * t
            val qz = pz + (lamp.z - pz) * t
            if (b.plane.contains(Vec3(qx, b.y, qz))) return true
        }
        return false
    }

    /** Band of a surface from the mean E of its central 60 % region (design `bands`, `labels`). */
    private fun grade(f: SurfaceField, rc: Receiver, previous: Grade?): SurfaceGrade {
        val cx = f.w / 2f
        val cz = f.h / 2f
        val hx = f.w * 0.3f
        val hz = f.h * 0.3f
        var sum = 0.0
        var n = 0
        var minE = Float.MAX_VALUE
        for (iz in 0 until f.h) for (ix in 0 until f.w) {
            val idx = f.index(ix, iz)
            if (!f.mask[idx]) continue
            if (abs(ix + 0.5f - cx) > hx || abs(iz + 0.5f - cz) > hz) continue
            val e = f.e[idx]
            sum += e
            n++
            if (e < minE) minE = e
        }
        if (n == 0) {
            for (idx in f.e.indices) if (f.mask[idx]) {
                sum += f.e[idx]; n++; minE = min(minE, f.e[idx])
            }
        }
        val mean = if (n == 0) 0f else (sum / n).toFloat()
        val ratio = mean / rc.kind.reference
        val uniformity = if (mean <= 1e-3f) 1f else (minE / mean).coerceIn(0f, 1f)
        return SurfaceGrade(rc.id, rc.kind, LightMath.grade(ratio, uniformity, previous), ratio, uniformity)
    }
}
