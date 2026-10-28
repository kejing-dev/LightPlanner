package io.embodit.lightplanner.ui.planner.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.lightplanner.domain.Billboard
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.LayerQuad
import io.embodit.lightplanner.domain.LineKind
import io.embodit.lightplanner.domain.PlaneSurface
import io.embodit.lightplanner.domain.Quat4
import io.embodit.lightplanner.domain.Vec3
import io.embodit.lightplanner.platform.LpLog
import io.embodit.lightplanner.ui.theme.LpColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/**
 * GPU-side resource creation (textures) paced over frames: each call blocks the main thread until the
 * render thread takes it, and at the idle emulator's 1 - 2 fps a burst of thirty stalls the app past the
 * input-dispatch timeout (startup ANR seen on sibling apps). One task per owner: a newer one replaces
 * the pending one.
 */
internal class WorkQueue {
    private val tasks = LinkedHashMap<Any, () -> Unit>()

    fun enqueue(owner: Any, task: () -> Unit) {
        tasks.remove(owner)
        tasks[owner] = task
    }

    fun cancel(owner: Any) {
        tasks.remove(owner)
    }

    /** Runs up to [n] tasks; returns how many are still waiting. */
    fun run(n: Int): Int {
        var left = n
        while (left > 0 && tasks.isNotEmpty()) {
            val key = tasks.keys.first()
            val task = tasks.remove(key) ?: continue
            runCatching(task).onFailure { LpLog.e("WORK task failed: $it", it) }
            left--
        }
        return tasks.size
    }

    val pending: Int
        get() = tasks.size

    fun clear() = tasks.clear()
}

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

internal fun boxCollision(size: Vec3) = CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler: deleting one pin closed the materials of all pins), so shared ones are made
 * global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun drop(resource: Resource) {
        if (owned.remove(resource)) runCatching { resource.close() }
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials, one per look, shared by every entity (design `palette`). Unlit colours
 * are exact only with tone mapping off (Spatial Ruler). Nothing here is lit: the room's own light is
 * never re-rendered (design `container.notes`).
 */
internal class LpMaterials(private val shared: SharedResources) {
    val plaster: Material = unlit(LpColors.LampPlaster, twoSided = true)
    val stale: Material = unlit(LpColors.StaleGrey, twoSided = true)
    val amber: Material = unlit(LpColors.Interaction, twoSided = true)
    val amberSheer: Material = sheer(LpColors.Interaction, 0.75f)
    val amberBreath: Material = sheer(LpColors.Interaction, 0.55f)
    val lineOk: Material = sheer(LpColors.Passable, 0.80f)
    val lineDark: Material = sheer(LpColors.Error, 0.85f)
    val lineAccent: Material = sheer(LpColors.SpacingAccent, 0.55f)
    val lineWall: Material = sheer(LpColors.Alert, 0.85f)
    val reticle: Material = sheer(LpColors.White, 0.85f)
    val reticleDash: Material = sheer(LpColors.White, 0.6f)
    val reticleBad: Material = sheer(LpColors.Error, 0.9f)
    val snapGuide: Material = sheer(LpColors.Interaction, 0.9f)

    // one material per light colour (0.1.1: ten colours), made on first use and shared like the rest
    private val glows = HashMap<Cct, Material>()
    private val cones = HashMap<Cct, Material>()
    private val poolDiscs = HashMap<Cct, Material>()

    fun glow(cct: Cct): Material = glows.getOrPut(cct) { unlit(LpColors.pool(cct), twoSided = true) }

    fun cone(cct: Cct): Material = cones.getOrPut(cct) { cone(LpColors.pool(cct)) }

    fun poolDisc(cct: Cct): Material = poolDiscs.getOrPut(cct) { sheer(LpColors.pool(cct), 0.35f) }

    // the placing ghost (taste profile D1): a see-through downlight and a faint pool where the ray points
    private val ghosts = HashMap<Cct, Material>()
    val ghostPlaster: Material = sheer(LpColors.LampPlaster, 0.5f)

    fun ghost(cct: Cct): Material = ghosts.getOrPut(cct) { sheer(LpColors.pool(cct), 0.6f) }

    fun line(kind: LineKind): Material = when (kind) { LineKind.OK -> lineOk; LineKind.DARK_GAP -> lineDark; LineKind.ACCENT -> lineAccent }

    private fun unlit(color: Color, twoSided: Boolean = false): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share (Spatial Ruler). */
    private fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /** The selected lamp's light cone: 10 % body (design `spatialLayout.ceiling.cone`). */
    private fun cone(color: Color): Material = sheer(color, 0.12f)

    /**
     * A material driven by a bitmap (light layers, label plates): white base colour, the texture carries
     * colour and alpha. [soft] = TRANSPARENT blending (pools); else MASKED with a 0.5 cut (crisp plates).
     * Whether the engine blends per texel is the design's spike item 1; `pools disc` is the fallback.
     */
    fun textured(soft: Boolean): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(if (soft) BlendingMode.TRANSPARENT else BlendingMode.MASKED).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                if (!soft) runCatching { setOpacityThreshold(0.5f) }
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(!soft)
            }
        )

    fun release(m: Material) = shared.drop(m)
}

/** Programmatic meshes, built once per scene (creating a mesh costs about one rendered frame). */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** A unit cube centred on the origin (scaled to any box). */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val faces = listOf(Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, -1f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f))
        for (n in faces) {
            val nn = Vec3(n.x, n.y, n.z)
            val u = (if (abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).let { it.rejectFrom(nn).normalized() }
            val v = nn.cross(u)
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = (nn + u * a + v * b) * 0.5f
                positions += Vector3(p.x, p.y, p.z)
                normals += n
            }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /**
     * A unit square in the local XZ plane (centred, facing +Y), corners and UVs from [LayerQuad]: the light layers'
     * texel (ix, iz) lands at that cell. [legacy] is the 0.1.0 mapping (mirrored along Z), kept for `flipv on`.
     */
    fun unitSquare(name: String, legacy: Boolean = false): MeshResource {
        val positions = LayerQuad.CORNERS.map { it.sdk() }
        val uv = LayerQuad.uv(legacy).map { (u, v) -> Vector2(u, v) }
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1, 0, 3, 2), normals = List(4) { UP }, uv0 = uv)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** A unit quad in the local XY plane facing +Z: label plates, corners and UVs from [Billboard] (v = 1 on top, the engine's convention). */
    fun unitQuad(name: String): MeshResource {
        val positions = Billboard.CORNERS.map { it.sdk() }
        val uv = Billboard.UV.map { (u, v) -> Vector2(u, v) }
        val model = MeshModel(positions = positions, triangleIndices = Billboard.TRIANGLES, normals = List(4) { FRONT }, uv0 = uv)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z, cut into [dashes] (0 = solid): scaled to any length and width. */
    fun unitBand(name: String, dashes: Int = 0, fill: Float = 0.55f): MeshResource {
        val rects = if (dashes <= 0) listOf(0f to 1f) else List(dashes) { i -> val p = 1f / dashes; (i * p + p * (1f - fill) / 2f) to (i * p + p * (1f + fill) / 2f) }
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for ((x0, x1) in rects) {
            val b = positions.size
            positions += listOf(Vector3(x0, -0.5f, 0f), Vector3(x1, -0.5f, 0f), Vector3(x1, 0.5f, 0f), Vector3(x0, 0.5f, 0f))
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }

    /** Unit disc (radius 0.5) in the local XZ plane facing +Y: glow discs, dots, pool fallback discs. */
    fun unitDisc(name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * 0.5).toFloat(), 0f, (sin(a) * 0.5).toFloat())
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Flat ring (inner [inner], outer 0.5) in the local XZ plane facing +Y; [dashes] > 0 cuts it into dashes (the reticle ring). */
    fun unitRing(name: String, inner: Float = 0.38f, segments: Int = 48, dashes: Int = 0): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0 until segments) {
            if (dashes > 0 && ((i * dashes / segments) % 2 == 1)) continue
            val s = 2 * PI * i / segments
            val e = 2 * PI * (i + 1) / segments
            val b = positions.size
            positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
            positions += Vector3((cos(s) * 0.5).toFloat(), 0f, (sin(s) * 0.5).toFloat())
            positions += Vector3((cos(e) * 0.5).toFloat(), 0f, (sin(e) * 0.5).toFloat())
            positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
            indices += listOf(b, b + 2, b + 1, b, b + 3, b + 2)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /**
     * A truncated cone: top ring radius [top] at y = 0, bottom ring radius 0.5 at y = -1, open ends.
     * Scaled (2 r, dh, 2 r) it is the selected lamp's light cone from the ceiling to the surface below.
     */
    fun unitCone(name: String, top: Float = 0.06f, segments: Int = 32): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            positions += Vector3(c * top, 0f, s * top)
            positions += Vector3(c * 0.5f, -1f, s * 0.5f)
            normals += Vector3(c, 0f, s)
            normals += Vector3(c, 0f, s)
        }
        for (i in 0 until segments) {
            val a = i * 2
            indices += listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, -0.5f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }
}

/** The shared meshes of the scene (design `budget.meshes`), built one per yielded frame by the scene. */
internal class LpMeshes(private val shared: SharedResources) {
    lateinit var cube: MeshResource
    lateinit var square: MeshResource
    lateinit var squareFlipped: MeshResource
    lateinit var quad: MeshResource
    lateinit var band: MeshResource
    lateinit var dashes: MeshResource
    lateinit var disc: MeshResource
    lateinit var ring: MeshResource
    lateinit var thinRing: MeshResource
    lateinit var dashedRing: MeshResource
    lateinit var cone: MeshResource

    /** The build steps, in order; the scene runs one per frame. */
    val steps: List<() -> Unit> =
        listOf(
            { cube = shared.keep(MeshFactory.unitCube("unitCube")) },
            { square = shared.keep(MeshFactory.unitSquare("unitSquare")) },
            { squareFlipped = shared.keep(MeshFactory.unitSquare("unitSquareLegacy", legacy = true)) },
            { quad = shared.keep(MeshFactory.unitQuad("unitQuad")) },
            { band = shared.keep(MeshFactory.unitBand("unitBand")) },
            { dashes = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 12)) },
            { disc = shared.keep(MeshFactory.unitDisc("unitDisc")) },
            { ring = shared.keep(MeshFactory.unitRing("unitRing", inner = 0.38f)) },
            { thinRing = shared.keep(MeshFactory.unitRing("unitThinRing", inner = 0.44f)) },
            { dashedRing = shared.keep(MeshFactory.unitRing("unitDashedRing", inner = 0.42f, segments = 48, dashes = 12)) },
            { cone = shared.keep(MeshFactory.unitCone("unitCone")) },
        )
}

/** A mesh entity on a shared unit mesh: placed by centre, size and rotation. */
internal class Part(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
        show(true)
    }

    /** A disc / square turned so that its face normal (+Y) points along [normal]. */
    fun placeFacing(center: Vec3, size: Vec3, normal: Vec3, meshNormal: Vec3 = Vec3.UP) {
        place(center, size, Quat4.fromTo(meshNormal, normal.normalized()))
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }

    val shown: Boolean
        get() = on

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/**
 * A straight strip between two points with its face turned along a normal (made square to the strip):
 * the shared unit band scaled to length and width (spacing lines, wall lines, guides, reticle dashes).
 */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/**
 * A bitmap-textured quad in the room (design `entities3d`: lamp numbers, surface tags, spacing labels),
 * [heightM] tall per line of [Plates.LINE_PX] pixels (a two-line tag is twice as tall), turned about the
 * vertical towards the viewer every frame. The bitmap is re-made only when its text changes.
 */
internal class TextPlate(parent: Entity, meshes: LpMeshes, private val materials: LpMaterials, private val queue: WorkQueue, name: String) {
    private val material: UnlitMaterial = materials.textured(soft = false)
    private val part = Part(parent, meshes.quad, material, name)
    private var texture: TextureResource? = null
    private var key: String? = null
    private var aspect = 1f
    private var lines = 1f
    private var heightM = 0.03f
    private var center = Vec3.ZERO

    init {
        part.show(false)
    }

    /** Sets the content; [paint] draws it into a bitmap (ARGB, any size); returns true when it changed. */
    fun content(key: String, paint: () -> Bitmap): Boolean {
        if (key == this.key) return false
        this.key = key
        queue.enqueue(this) {
            val bitmap = paint()
            aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            lines = bitmap.height.toFloat() / Plates.LINE_PX
            val old = texture
            val tex = runCatching { TextureResource.create(bitmap) }.onFailure { LpLog.e("PLATE texture failed: $it", it) }.getOrNull()
            if (tex != null) {
                material.setBaseColorTexture(tex)
                texture = tex
                ready = true
                old?.let { runCatching { it.close() } }
            }
            bitmap.recycle()
        }
        return true
    }

    /** True once a texture has been uploaded (the plate is not shown before). */
    var ready = false
        private set

    fun place(center: Vec3, heightM: Float) {
        this.center = center
        this.heightM = heightM
    }

    /** Per frame: yaw towards [viewer], no pitch, no roll, positive scale (design `spatialLayout.surfaces.tags`, profile D10). */
    fun face(viewer: Vec3) {
        if (!ready) return
        if (!part.shown) part.show(true)
        val h = heightM * lines
        part.place(center, Vec3(h * aspect, h, 1f), Billboard.yawTowards(center, viewer))
    }

    fun hide() = part.show(false)

    val shown: Boolean
        get() = part.shown

    fun destroy() {
        queue.cancel(this)
        part.destroy()
        texture?.let { runCatching { it.close() } }
        texture = null
        materials.release(material)
    }
}

/** Thin box colliders over the detected planes (pinch targets) and a backstop shell that catches a pinch anywhere. */
internal class RoomColliders(private val root: Entity) {
    private class Collider(val entity: Entity, var center: Vec3, var size: Vec3, var rotation: Quat4)

    private val colliders = HashMap<String, Collider>()
    private val byEntity = HashMap<Long, String>()
    private var backstop: Entity? = null

    fun isBackstop(entity: Entity): Boolean = runCatching { entity.getName() }.getOrNull()?.startsWith("backstop") == true

    /** The plane id a collider entity stands for, or null. */
    fun planeOf(entity: Entity): String? = byEntity[entity.id]

    fun sync(planes: Collection<PlaneSurface>) {
        val seen = HashSet<String>()
        for (s in planes) {
            seen += s.id
            upsert(s)
        }
        for (id in colliders.keys.filter { it !in seen }) colliders.remove(id)?.let {
            byEntity.remove(it.entity.id)
            runCatching { it.entity.destroy(true) }
        }
    }

    private fun upsert(surface: PlaneSurface) {
        val fit = fit(surface) ?: return
        val existing = colliders[surface.id]
        if (existing == null) {
            val e = Entity()
            runCatching { e.setName("plane:${surface.label.replace('-', '_')}") }
            e.components.set(boxCollision(fit.second))
            e.components.set(InteractableComponent())
            root.addChild(e)
            e.setLocal(position = fit.first, rotation = fit.third)
            colliders[surface.id] = Collider(e, fit.first, fit.second, fit.third)
            byEntity[e.id] = surface.id
            return
        }
        if (existing.size.distanceTo(fit.second) > 0.001f) {
            existing.entity.components.set(boxCollision(fit.second))
            existing.size = fit.second
        }
        if (existing.center.distanceTo(fit.first) > 0.0005f || abs(existing.rotation.x - fit.third.x) + abs(existing.rotation.w - fit.third.w) > 1e-4f) {
            existing.entity.setLocal(position = fit.first, rotation = fit.third)
            existing.center = fit.first
            existing.rotation = fit.third
        }
    }

    fun buildBackstop() {
        if (backstop != null) return
        val shell = Entity()
        runCatching { shell.setName("backstop") }
        root.addChild(shell)
        val h = 9f
        val t = 0.2f
        val cy = 1.5f
        val slabs =
            listOf(
                Triple("px", Vec3(h, cy, 0f), Vec3(t, 2 * h, 2 * h)), Triple("nx", Vec3(-h, cy, 0f), Vec3(t, 2 * h, 2 * h)),
                Triple("pz", Vec3(0f, cy, h), Vec3(2 * h, 2 * h, t)), Triple("nz", Vec3(0f, cy, -h), Vec3(2 * h, 2 * h, t)),
                Triple("py", Vec3(0f, cy + h, 0f), Vec3(2 * h, t, 2 * h)), Triple("ny", Vec3(0f, cy - h, 0f), Vec3(2 * h, t, 2 * h)),
            )
        for ((tag, center, size) in slabs) {
            val slab = Entity()
            runCatching { slab.setName("backstop:$tag") }
            slab.components.set(boxCollision(size))
            slab.components.set(InteractableComponent())
            shell.addChild(slab)
            slab.setLocal(position = center)
        }
        backstop = shell
    }

    fun clear() {
        colliders.values.forEach { runCatching { it.entity.destroy(true) } }
        colliders.clear()
        byEntity.clear()
        runCatching { backstop?.destroy(true) }
        backstop = null
    }

    /** Oriented box around the polygon in its own plane: centre, size (6 mm thick), rotation. */
    private fun fit(s: PlaneSurface): Triple<Vec3, Vec3, Quat4>? {
        val v = s.vertices
        if (v.size < 3) return null
        val n = s.normal
        var u = Vec3.ZERO
        for (i in v.indices) {
            val e = (v[(i + 1) % v.size] - v[i]).rejectFrom(n)
            if (e.length > 0.01f) {
                u = e.normalized()
                break
            }
        }
        if (u.length < 0.5f) return null
        val w = n.cross(u).normalized()
        var u0 = Float.MAX_VALUE
        var u1 = -Float.MAX_VALUE
        var w0 = Float.MAX_VALUE
        var w1 = -Float.MAX_VALUE
        for (p in v) {
            val d = p - v[0]
            u0 = minOf(u0, d.dot(u))
            u1 = maxOf(u1, d.dot(u))
            w0 = minOf(w0, d.dot(w))
            w1 = maxOf(w1, d.dot(w))
        }
        if (u1 - u0 < 0.01f || w1 - w0 < 0.01f) {
            LpLog.w("PLANES skip degenerate ${s.label}")
            return null
        }
        val center = v[0] + u * ((u0 + u1) / 2f) + w * ((w0 + w1) / 2f)
        return Triple(center, Vec3(u1 - u0, w1 - w0, 0.006f), Quat4.fromBasis(u, w, n))
    }
}
