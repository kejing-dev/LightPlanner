package io.embodit.lightplanner.domain

/** What a horizontal receiving surface is to the user (design `lightModel.relative.labels`). */
enum class SurfaceKind(val reference: Float) {
    DESK(200f),
    COFFEE(70f),
    SOFA(85f),
    BED(55f),
    FLOOR(55f),
    OTHER(55f);

    /** Furniture gets a floating tag (design `spatialLayout.surfaces.tags`). */
    val tagged: Boolean
        get() = this == DESK || this == COFFEE || this == SOFA || this == BED
}

/** A surface that receives light: an upward horizontal plane, with its kind and height. */
data class Receiver(val plane: PlaneSurface, val kind: SurfaceKind, val index: Int) {
    val y: Float
        get() = plane.centroid.y

    val id: String
        get() = plane.id

    val label: String
        get() = plane.label
}

/**
 * What the app knows of the room (design `container.notes`): the floor, the WALL planes, the ceiling
 * planes (semantic CEILING, or a horizontal plane 2 - 4 m above the floor that is not furniture) and
 * the receiving surfaces (every other horizontal plane). [manualCeiling] is the user's own ceiling
 * height above the floor (S13); it only counts while no ceiling is detected.
 */
data class RoomModel(val planes: List<PlaneSurface>, val manualCeiling: Float? = null) {
    val walls: List<PlaneSurface> = planes.filter { it.semantic == "WALL" && it.isVertical }

    private val horizontal = planes.filter { it.isHorizontal }

    val floorY: Float? = horizontal.filter { it.semantic == "FLOOR" }.minOfOrNull { it.centroid.y } ?: horizontal.minOfOrNull { it.centroid.y }

    val ceilings: List<PlaneSurface> =
        horizontal.filter { p ->
            val f = floorY
            p.semantic == "CEILING" || (p.semantic !in FURNITURE && f != null && p.centroid.y - f in CEILING_MIN_M..CEILING_MAX_M)
        }

    /** Receiving surfaces: every horizontal plane that is not a ceiling, the floor first, then by height. */
    val receivers: List<Receiver> =
        horizontal.filter { p -> ceilings.none { it.id == p.id } && p.area > MIN_RECEIVER_AREA }
            .sortedWith(compareBy({ it.semantic != "FLOOR" }, { it.centroid.y }))
            .mapIndexed { i, p -> Receiver(p, kindOf(p), i) }

    val hasFloor: Boolean
        get() = floorY != null

    val hasCeiling: Boolean
        get() = ceilings.isNotEmpty() || (manualCeiling != null && floorY != null)

    val ceilingIsManual: Boolean
        get() = ceilings.isEmpty() && manualCeiling != null && floorY != null

    /** Any furniture (TABLE / SOFA / BED / CHAIR ...) besides the floor (design S17). */
    val hasFurniture: Boolean
        get() = receivers.any { it.kind != SurfaceKind.FLOOR }

    /** The ceiling's typical height above the floor (the largest ceiling plane), for the status line. */
    fun ceilingHeight(): Float? {
        val f = floorY ?: return null
        val main = ceilings.maxByOrNull { it.area }
        if (main != null) return main.centroid.y - f
        return manualCeiling
    }

    /** The detected ceiling's height above the floor, or null (used to offer "use 2.74 m" over a manual value). */
    fun detectedCeilingHeight(): Float? {
        val f = floorY ?: return null
        return ceilings.maxByOrNull { it.area }?.let { it.centroid.y - f }
    }

    /**
     * Ceiling height (Stage y) above the spot [xz]: the ceiling plane whose polygon holds it, else the
     * largest ceiling as an unbounded plane (detected polygons are smaller than the real ceiling), else
     * the manual ceiling above the floor; null when there is no ceiling at all.
     */
    fun ceilingYAt(xz: P2): Float? {
        val covering = ceilings.filter { it.contains(Vec3(xz.x, it.centroid.y, xz.z)) }
        covering.maxByOrNull { it.area }?.let { return it.centroid.y }
        ceilings.maxByOrNull { it.area }?.let { return it.centroid.y }
        val f = floorY ?: return null
        val m = manualCeiling ?: return null
        return f + m
    }

    fun isCeiling(plane: PlaneSurface): Boolean = ceilings.any { it.id == plane.id }

    fun receiverOf(plane: PlaneSurface): Receiver? = receivers.firstOrNull { it.id == plane.id }

    /** Nearest two-sided hit of a ray on any detected plane. */
    fun hitAny(origin: Vec3, direction: Vec3, maxDistance: Float = 20f): SurfaceHit? =
        planes.mapNotNull { it.hit(origin, direction, maxDistance) }.minByOrNull { it.distance }

    /** Where a ray meets the ceiling: a ceiling polygon first, else the unbounded plane at the ceiling height. */
    fun hitCeiling(origin: Vec3, direction: Vec3): Vec3? {
        ceilings.mapNotNull { it.hit(origin, direction) }.minByOrNull { it.distance }?.let { return it.point }
        val y = ceilingYAt(P2.of(origin + direction * 2f)) ?: return null
        if (direction.y <= 1e-4f) return null
        val t = (y - origin.y) / direction.y
        if (t <= 0f || t > 20f) return null
        val p = origin + direction * t
        return Vec3(p.x, ceilingYAt(P2.of(p)) ?: y, p.z)
    }

    /** Horizontal distance from [xz] to the nearest wall footprint, with that wall. */
    fun nearestWall(xz: P2): Pair<Float, PlaneSurface>? {
        var best: Pair<Float, PlaneSurface>? = null
        for (w in walls) {
            val d = wallDistance(xz, w)
            if (best == null || d < best.first) best = d to w
        }
        return best
    }

    /** Distance in the floor plane from [xz] to the wall's footprint segment. */
    fun wallDistance(xz: P2, wall: PlaneSurface): Float {
        val (a, b) = wallSegment(wall)
        return segmentDistance(xz, a, b)
    }

    /** The two ends of a wall's footprint on the floor (its vertices projected to xz, the farthest pair). */
    fun wallSegment(wall: PlaneSurface): Pair<P2, P2> {
        val pts = wall.vertices.map { P2(it.x, it.z) }
        var a = pts[0]
        var b = pts[0]
        var best = -1f
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val d = (pts[i] - pts[j]).length
            if (d > best) {
                best = d
                a = pts[i]
                b = pts[j]
            }
        }
        return a to b
    }

    /** Does the floor-plane segment [a]-[b] cross a wall's footprint (two lamps on either side of a wall, design R2-1)? */
    fun crossesWall(a: P2, b: P2): Boolean =
        walls.any { w ->
            val (c, d) = wallSegment(w)
            segmentsCross(a, b, c, d)
        }

    /** The room's main axis in the floor plane: along the longest wall, else +X (design `lightModel.snap`). */
    fun mainAxis(): P2 {
        val longest = walls.maxByOrNull { w -> wallSegment(w).let { (a, b) -> (a - b).length } } ?: return P2(1f, 0f)
        val (a, b) = wallSegment(longest)
        val d = (b - a).normalized()
        return if (d.length < 0.5f) P2(1f, 0f) else d
    }

    /** Keeps [xz] at least [inset] from every wall footprint (dragging stops before a wall, design T4). */
    fun keepOffWalls(xz: P2, inset: Float): P2 {
        var p = xz
        repeat(2) {
            for (w in walls) {
                val (a, b) = wallSegment(w)
                val q = closestOnSegment(p, a, b)
                val d = (p - q).length
                if (d < inset && d > 1e-5f) p = q + (p - q).normalized() * inset
            }
        }
        return p
    }

    private fun kindOf(p: PlaneSurface): SurfaceKind {
        val f = floorY ?: p.centroid.y
        return when (p.semantic) {
            "FLOOR" -> SurfaceKind.FLOOR
            "TABLE", "DESK" -> if (p.centroid.y - f >= DESK_MIN_M) SurfaceKind.DESK else SurfaceKind.COFFEE
            "SOFA", "COUCH", "CHAIR" -> SurfaceKind.SOFA
            "BED" -> SurfaceKind.BED
            else -> SurfaceKind.OTHER
        }
    }

    companion object {
        val EMPTY = RoomModel(emptyList())
        const val CEILING_MIN_M = 2.0f
        const val CEILING_MAX_M = 4.0f
        const val DESK_MIN_M = 0.55f
        const val MIN_RECEIVER_AREA = 0.05f
        val FURNITURE = setOf("TABLE", "DESK", "SOFA", "COUCH", "CHAIR", "BED", "CABINET", "SHELF", "FLOOR", "STORAGE", "SCREEN", "DOOR", "WINDOW")

        fun segmentDistance(p: P2, a: P2, b: P2): Float = (p - closestOnSegment(p, a, b)).length

        fun closestOnSegment(p: P2, a: P2, b: P2): P2 {
            val ab = b - a
            val l2 = ab.dot(ab)
            if (l2 < 1e-9f) return a
            val t = ((p - a).dot(ab) / l2).coerceIn(0f, 1f)
            return a + ab * t
        }

        fun segmentsCross(a: P2, b: P2, c: P2, d: P2): Boolean {
            fun side(p: P2, q: P2, r: P2): Float = (q.x - p.x) * (r.z - p.z) - (q.z - p.z) * (r.x - p.x)
            val d1 = side(c, d, a)
            val d2 = side(c, d, b)
            val d3 = side(a, b, c)
            val d4 = side(a, b, d)
            return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        }
    }
}
