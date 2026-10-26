package io.embodit.lightplanner.ui.planner.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.lightplanner.domain.DragLine
import io.embodit.lightplanner.domain.FacePanel
import io.embodit.lightplanner.domain.HeadPose
import io.embodit.lightplanner.domain.Lamp
import io.embodit.lightplanner.domain.LightMath
import io.embodit.lightplanner.domain.LineKind
import io.embodit.lightplanner.domain.P2
import io.embodit.lightplanner.domain.PlaneSurface
import io.embodit.lightplanner.domain.Quat4
import io.embodit.lightplanner.domain.RoomModel
import io.embodit.lightplanner.domain.Vec3
import io.embodit.lightplanner.domain.WallWarn
import io.embodit.lightplanner.platform.LpGraph
import io.embodit.lightplanner.platform.LpLog
import io.embodit.lightplanner.ui.planner.Hit
import io.embodit.lightplanner.ui.planner.Phase
import io.embodit.lightplanner.ui.planner.PlannerEvent
import io.embodit.lightplanner.ui.planner.PlannerUiState
import io.embodit.lightplanner.ui.planner.Popup
import io.embodit.lightplanner.ui.planner.components.PanelSizes
import io.embodit.lightplanner.ui.planner.components.SceneStrings
import io.embodit.lightplanner.ui.theme.LpColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: PlannerScene? = null
}

/** The one per-frame ECS system of the app. */
class PlannerFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Ids of the attachment panels; the Compose side declares exactly these, once (design `panels`). */
object PanelIds {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val HOLE = "holePlan"
    val pool: List<String> = listOf(MAIN, SETTINGS, HOLE)
}

/**
 * The 3D side of the app: the lamps, pools, spacing lines, tags, reticle and handle, the pinch and drag
 * targets, and the placement of the three pre-built panels.
 *
 * Rules (Spatial Ruler / Theater Planner): one calibrated stage root; Compose state is rendered here
 * through [render] (idempotent); per-frame work happens in [onFrame] and never goes through Compose,
 * except [panelShown]; everything started in [attach] is stopped in [detach]. Main thread only.
 */
class PlannerScene(private val sink: (PlannerEvent) -> Unit) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planes = LpGraph.planes
    private val head = LpGraph.head
    private val stick = LpGraph.stick

    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: LpMaterials? = null
    private var meshes: LpMeshes? = null
    private var colliders: RoomColliders? = null
    private val work = WorkQueue()
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private var ui = PlannerUiState()
    private var strings: SceneStrings? = null
    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastFrameMs = -1L
    private var frameIntervalMs = 16L
    private var lastLogMs = 0L
    private var lastTrackingLost = false
    private var lastPanelPressMs = -1L
    private var lastDragEndMs = -1L
    private var popupClosedMs = -1L
    private var lastPopup = Popup.NONE
    private var lightKey = 0L
    private var lastLight: Any? = null

    /** Debug switches (AGENTS.md): textured pools vs fallback discs, and the texture v direction. */
    @Volatile var poolsTextured = true
    @Volatile var flipV = false

    // views
    private val lampViews = HashMap<String, LampView>()
    private val layerViews = HashMap<String, LightLayerView>()
    private val tagPlates = HashMap<String, TextPlate>()
    private val labelPlates = ArrayList<TextPlate>()
    private var labelsUsed = 0
    private var select: SelectView? = null
    private var reticle: ReticleView? = null
    private var lines: LinePool? = null
    private var wallLines: LinePool? = null
    private var guides: LinePool? = null
    private var discs: PoolDiscs? = null

    // live placement (drag / thumbstick), never through Compose state
    private class Drag(val id: String, val press: Vec3, val handle: Boolean, val levelY: Float) {
        var dir: Vec3? = null
        var equivalent = 0f
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var totalPx = Vec3.ZERO
        var begun = false
        var moves = 0
        var lastLogMs = 0L
        var lastSendMs = 0L
    }

    private var drag: Drag? = null
    private var live: Pair<String, P2>? = null
    private var liveGuides: List<Pair<P2, P2>> = emptyList()
    private var stickTarget: String? = null
    private var stickNeutralMs = -1L
    private var snapped = false

    private val mainFollow = PanelFollow("main") { h -> restMain(h) }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z, Spatial Ruler):
        // move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        LpLog.i("STAGE root offset=${LpLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.MAIN, PanelSizes.MAIN_W, PanelSizes.MAIN_H, 1f)
        rig.world(PanelIds.SETTINGS, PanelSizes.SETTINGS_W, PanelSizes.SETTINGS_H, 1f)
        rig.world(PanelIds.HOLE, PanelSizes.HOLE_W, PanelSizes.HOLE_H, 1f)
        head.start()
        stick.start()
        LpGraph.anchors.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        startSensing()
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) LpLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        LpLog.i("SCENE attached")
    }

    /** The meshes, one per yielded frame (a mesh costs about a frame at idle fps), then the fixed views (S00). */
    private suspend fun build(root: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = LpMaterials(shared)
        materials = m
        yield()
        val meshes = LpMeshes(shared)
        for (step in meshes.steps) {
            // a mesh costs about a second of main thread on the idle emulator (1-2 fps): give the thread air between
            // meshes so the system never sees it blocked for long (startup ANR seen on sibling apps)
            step()
            yield()
            delay(if (frameIntervalMs > SLOW_FRAME_MS) SLOW_MESH_GAP_MS else FAST_MESH_GAP_MS)
        }
        this.meshes = meshes
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        colliders.sync(planes.all)
        yield()
        select = SelectView(root, meshes, m)
        reticle = ReticleView(root, meshes, m)
        yield()
        lines = LinePool(root, meshes, m, "spacing", 32)
        wallLines = LinePool(root, meshes, m, "wall", 16)
        guides = LinePool(root, meshes, m, "guide", 2)
        discs = PoolDiscs(root, meshes, m)
        yield()
        repeat(LABEL_SLOTS) { labelPlates += TextPlate(root, meshes, m, work, "label:$it") }
        built = true
        LpLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0} meshes=${meshes.steps.size}")
        render(ui)
        sink(PlannerEvent.SceneReady)
    }

    private fun startSensing() {
        val ok =
            runCatching {
                planes.start {
                    colliders?.sync(planes.all)
                    sink(PlannerEvent.PlanesChanged(RoomModel(planes.all.toList())))
                }
            }
        if (ok.isFailure) {
            LpLog.e("PLANES unavailable: ${ok.exceptionOrNull()}")
            sink(PlannerEvent.PlanesUnavailable)
        }
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        planes.stop()
        head.stop()
        stick.stop()
        endDrag(cancelled = true)
        stickTarget = null
        LpLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        startSensing()
        LpLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        stick.stop()
        LpGraph.anchors.stop()
        panelShown.clear()
        work.clear()
        lampViews.values.forEach { it.destroy() }
        lampViews.clear()
        layerViews.values.forEach { it.destroy() }
        layerViews.clear()
        tagPlates.values.forEach { it.destroy() }
        tagPlates.clear()
        labelPlates.forEach { it.destroy() }
        labelPlates.clear()
        select?.destroy()
        reticle?.destroy()
        lines?.destroy()
        wallLines?.destroy()
        guides?.destroy()
        discs?.destroy()
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        select = null
        reticle = null
        lines = null
        wallLines = null
        guides = null
        discs = null
        stageRoot = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        drag = null
        live = null
        stickTarget = null
        lastLight = null
        mainFollow.reset()
        LpLog.i("SCENE detached")
    }

    fun setStrings(s: SceneStrings) {
        strings = s
        tagPlates.values.forEach { it.hide() }
        if (built) render(ui)
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: PlannerUiState) {
        val before = ui
        ui = state
        val root = stageRoot ?: return
        val rig = rig ?: return
        if (!built) {
            rig[PanelIds.MAIN]?.show()
            return
        }
        val m = materials ?: return
        val meshes = meshes ?: return
        if (state.popup == Popup.NONE && lastPopup != Popup.NONE) popupClosedMs = SystemClock.elapsedRealtime()
        lastPopup = state.popup
        val showRoom = state.inPlan && !state.trackingLost
        val ids = if (showRoom) state.lamps.map { it.id }.toSet() else emptySet()
        for (id in lampViews.keys.filter { it !in ids }) lampViews.remove(id)?.destroy()
        if (showRoom) for (l in state.lamps) if (l.id !in lampViews) lampViews[l.id] = LampView(root, meshes, m, work, l.id)
        if (before.dragging != null && state.dragging == null && drag == null) clearLive()
        if (!showRoom) {
            lampViews.values.forEach { it.hide() }
            layerViews.values.forEach { it.hide() }
            tagPlates.values.forEach { it.hide() }
            labelPlates.forEach { it.hide() }
            select?.hide()
            lines?.hideAll()
            wallLines?.hideAll()
            guides?.hideAll()
            discs?.hideAll()
            reticle?.hide()
        } else {
            drawLayers(state)
            drawLines(state)
        }
        panels(state)
    }

    /** The light layers (textured) or the fallback discs, and the surface tags; rebuilt only when the light result changes. */
    private fun drawLayers(state: PlannerUiState) {
        val root = stageRoot ?: return
        val meshes = meshes ?: return
        val m = materials ?: return
        val light = state.light
        val key = Triple(light, state.heat, flipV)
        if (key != lastLight) {
            lastLight = key
            lightKey++
        }
        val seen = HashSet<String>()
        if (poolsTextured) {
            discs?.hideAll()
            for (f in light.fields) {
                seen += f.receiverId
                val v = layerViews.getOrPut(f.receiverId) { LightLayerView(root, meshes, m, work, f.receiverId) }
                v.show(f, state.heat, flipV, lightKey)
            }
        } else {
            layerViews.values.forEach { it.hide() }
            val d = discs ?: return
            d.begin()
            for (p in light.pools) {
                val lamp = state.lamp(p.lampId) ?: continue
                val rc = state.room.receivers.firstOrNull { it.id == p.receiverId } ?: continue
                d.draw(p.center, rc.y, p.radius, lamp.cct)
            }
            d.end()
        }
        for (id in layerViews.keys.filter { it !in seen }) layerViews.remove(id)?.destroy()
        // tags over furniture
        val st = strings
        val tagged = HashSet<String>()
        if (st != null) {
            for (rc in state.room.receivers) {
                if (!rc.kind.tagged || tagged.size >= TAG_SLOTS) continue
                val g = light.grade(rc.id) ?: continue
                tagged += rc.id
                val plate = tagPlates.getOrPut(rc.id) { TextPlate(root, meshes, m, work, "tag:${rc.id.take(8)}") }
                val sameKind = state.room.receivers.filter { it.kind == rc.kind }
                val name = if (sameKind.size > 1) "${st.kind(rc.kind)} ${sameKind.indexOf(rc) + 1}" else st.kind(rc.kind)
                val text = "$name · ${st.grade(g.grade)}"
                val color = gradeColor(g.grade)
                plate.content("$text|${g.grade}") { Plates.tag(text, g.grade, color) }
                val c = rc.plane.centroid
                plate.place(Vec3(c.x, rc.y + Dims.TAG_ABOVE, c.z), Dims.TAG_H)
            }
        }
        for (id in tagPlates.keys.filter { it !in tagged }) tagPlates.remove(id)?.destroy()
    }

    private fun gradeColor(g: io.embodit.lightplanner.domain.Grade) =
        when (g) {
            io.embodit.lightplanner.domain.Grade.DARK -> LpColors.Error
            io.embodit.lightplanner.domain.Grade.DIM, io.embodit.lightplanner.domain.Grade.UNEVEN, io.embodit.lightplanner.domain.Grade.BRIGHT -> LpColors.Alert
            io.embodit.lightplanner.domain.Grade.OK -> LpColors.Passable
        }

    /** Spacing lines, wall lines and their labels (design `spatialLayout.ceiling.spacingLines`). */
    private fun drawLines(state: PlannerUiState) {
        val m = materials ?: return
        val lp = lines ?: return
        val wp = wallLines ?: return
        val st = strings
        lp.begin()
        wp.begin()
        var labels = 0
        val show = state.settings.showSpacing
        if (show) {
            for (ln in state.light.lines) {
                val a = positionOf(state.lamp(ln.a) ?: continue)
                val b = positionOf(state.lamp(ln.b) ?: continue)
                val ya = a.y - Dims.LINE_BELOW
                val yb = b.y - Dims.LINE_BELOW
                lp.draw(Vec3(a.x, ya, a.z), Vec3(b.x, yb, b.z), Dims.LINE_W, m.line(ln.kind), dashes = ln.kind == LineKind.DARK_GAP)
                val touchesSelected = state.selected != null && (ln.a == state.selected || ln.b == state.selected)
                if ((ln.kind == LineKind.DARK_GAP || touchesSelected) && labels < labelPlates.size) {
                    val text = String.format(Locale.US, "%.2f m", ln.distance) + if (ln.kind == LineKind.DARK_GAP && st != null) " · ${st.darkGap}" else ""
                    val color = when (ln.kind) { LineKind.DARK_GAP -> LpColors.Error; LineKind.OK -> LpColors.Passable; LineKind.ACCENT -> LpColors.SpacingAccent }
                    val plate = labelPlates[labels++]
                    plate.content("$text|${ln.kind}") { Plates.label(text, color) }
                    plate.place(Vec3((a.x + b.x) / 2f, minOf(ya, yb) - 0.05f, (a.z + b.z) / 2f), Dims.LABEL_H)
                }
            }
            for (w in state.light.wallNotes) {
                val lamp = state.lamp(w.lampId) ?: continue
                val p = positionOf(lamp)
                val y = p.y - Dims.LINE_BELOW
                val toWall = P2(w.wallPoint.x - p.x, w.wallPoint.z - p.z)
                val reach = minOf(toWall.length, WALL_STUB_M)
                val end = P2(p.x, p.z) + toWall.normalized() * reach
                wp.draw(Vec3(p.x, y, p.z), Vec3(end.x, y, end.z), Dims.WALL_LINE_W, m.lineWall, dashes = false)
                if (st != null && labels < labelPlates.size) {
                    val text = if (w.warn == WallWarn.TOO_CLOSE) st.wallClose else st.wallEdge
                    val plate = labelPlates[labels++]
                    plate.content("wall|$text") { Plates.label(text, LpColors.Alert) }
                    plate.place(Vec3((p.x + end.x) / 2f, y - 0.05f, (p.z + end.z) / 2f), Dims.LABEL_H)
                }
            }
        }
        lp.end()
        wp.end()
        labelsUsed = labels
        for (i in labels until labelPlates.size) labelPlates[i].hide()
    }

    private fun panels(state: PlannerUiState) {
        val rig = rig ?: return
        rig[PanelIds.MAIN]?.show()
        if (state.popup != Popup.SETTINGS) rig[PanelIds.SETTINGS]?.hide()
        if (state.popup != Popup.HOLE_PLAN) rig[PanelIds.HOLE]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        pollTracking(now)
        if (built) {
            // textures a few per frame (two on the idle emulator), never a burst
            work.run(if (frameIntervalMs > SLOW_FRAME_MS) SLOW_WORK_PER_FRAME else FAST_WORK_PER_FRAME)
            stepStick(now)
            drawLive(now)
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        if (lastFrameMs > 0) frameIntervalMs = (now - lastFrameMs).coerceIn(1L, 5000L)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            LpLog.i("FRAME n=$frames dt=${frameIntervalMs} head=${LpLog.pos(pose.position)} fwd=${LpLog.pos(pose.forward)} lamps=${ui.lamps.size} planes=${planes.all.size} layers=${layerViews.size} stick=${stick.status()}")
        }
    }

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastTrackingLost) {
            lastTrackingLost = lost
            sink(PlannerEvent.TrackingChanged(lost))
        }
    }

    /** The live position of a lamp: the dragged / nudged one, else the state's. */
    private fun positionOf(l: Lamp): Vec3 {
        val lv = live?.takeIf { it.first == l.id } ?: return l.pos
        val y = ui.room.ceilingYAt(lv.second) ?: l.pos.y
        return Vec3(lv.second.x, y, lv.second.z)
    }

    /** Lamps, numbers, the selection, tags, labels, guides and the reticle: what follows the head or a drag every frame. */
    private fun drawLive(now: Long) {
        val state = ui
        val showRoom = state.inPlan && !state.trackingLost
        if (!showRoom) {
            reticle?.hide()
            return
        }
        val eye = pose.position
        val selId = state.dragging ?: state.selected
        for (l in state.lamps) {
            val v = lampViews[l.id] ?: continue
            val p = positionOf(l)
            v.place(p, l.cct, stale = false, big = state.popup == Popup.HOLE_PLAN)
            v.number.content("no:${l.no}") { Plates.number(l.no.toString()) }
            v.number.face(eye)
        }
        tagPlates.values.forEach { it.face(eye) }
        for (i in 0 until minOf(labelsUsed, labelPlates.size)) labelPlates[i].face(eye)
        // the selected lamp: breathing ring, cone, handle
        val sel = selId?.let { id -> state.lamp(id) }
        val sv = select
        if (sel != null && sv != null) {
            val p = positionOf(sel)
            val under = highestReceiverUnder(P2.of(p), p.y)
            val surfaceY = under?.y ?: (state.room.floorY ?: (p.y - 2.5f))
            sv.draw(p, sel.cct, surfaceY, LightMath.poolRadius(sel.beam, p.y - surfaceY), now)
        } else sv?.hide()
        // snap guides while dragging / nudging
        val gp = guides
        if (gp != null) {
            gp.begin()
            if (live != null) {
                val l = live!!
                val y = (ui.room.ceilingYAt(l.second) ?: 2.5f) - Dims.LINE_BELOW - 0.001f
                for ((at, dir) in liveGuides) {
                    val d = Vec3(dir.x, 0f, dir.z)
                    gp.draw(Vec3(at.x, y, at.z) - d * GUIDE_HALF_M, Vec3(at.x, y, at.z) + d * GUIDE_HALF_M, Dims.GUIDE_W, materials!!.snapGuide, dashes = false)
                }
            }
            gp.end()
        }
        drawReticle(state)
    }

    /** The highest receiving surface whose polygon holds [xz] and lies below [belowY]. */
    private fun highestReceiverUnder(xz: P2, belowY: Float): io.embodit.lightplanner.domain.Receiver? =
        ui.room.receivers.filter { it.y < belowY - 0.05f && it.plane.contains(Vec3(xz.x, it.y, xz.z)) }.maxByOrNull { it.y }

    private fun drawReticle(state: PlannerUiState) {
        val r = reticle ?: return
        if (!state.placingEnabled || !hasHead || drag != null) {
            r.hide()
            return
        }
        val hit = state.room.hitAny(pose.position, pose.forward, RETICLE_REACH_M)
        if (hit == null) {
            r.hide()
            return
        }
        val plane = hit.plane
        val xz = P2.of(hit.point)
        when {
            state.room.isCeiling(plane) -> r.ceiling(hit.point)
            plane.isHorizontal -> {
                val cy = state.room.ceilingYAt(xz)
                if (cy == null || cy <= hit.point.y + 0.2f) r.bad(hit.point, hit.normal) else r.surface(hit.point, cy, pose.position)
            }
            else -> r.bad(hit.point, hit.normal)
        }
    }

    // =========================================================================================
    // thumbstick: nudge the selected lamp sideways along the ceiling, snapping into rows
    // =========================================================================================

    private fun stickTarget(): String? {
        val s = ui
        if (!s.placingEnabled) return null
        return s.dragging ?: s.selected
    }

    private fun stepStick(now: Long) {
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        val x = stick.x(now)
        val rate = stickRate(x)
        val target = stickTarget()
        stickTarget?.let { if (it != target) finishStick("target") }
        if (rate != 0f && target != null && drag == null) {
            val lamp = ui.lamp(target) ?: return
            if (stickTarget == null) {
                stickTarget = target
                LpLog.i(String.format(Locale.US, "STICK nudge start id=%s x=%.2f", target, x))
            }
            stickNeutralMs = -1L
            val current = P2.of(positionOf(lamp))
            val right = pose.right.let { P2(it.x, it.z).normalized() }
            setLive(lamp, current + right * (rate * dt))
        } else if (stickTarget != null) {
            if (stickNeutralMs < 0) stickNeutralMs = now else if (now - stickNeutralMs >= STICK_RELEASE_MS) finishStick("release")
        }
    }

    /** Stick X -> metres per second: nothing in the dead zone, then up to 0.4 m/s on a square curve. */
    private fun stickRate(x: Float): Float {
        val a = abs(x)
        if (!a.isFinite() || a <= STICK_DEADZONE) return 0f
        val t = ((a - STICK_DEADZONE) / (1f - STICK_DEADZONE)).coerceIn(0f, 1f)
        return sign(x) * STICK_MAX_M_S * t * t
    }

    private fun finishStick(why: String) {
        val id = stickTarget ?: return
        stickTarget = null
        val l = live
        LpLog.i("STICK nudge end id=$id why=$why at=${l?.second}")
        if (l == null || l.first != id) {
            clearLive()
            return
        }
        sink(PlannerEvent.StickMoved(id, l.second))
        clearLive()
    }

    /** Debug `stick`: the stick reads [x] for [ms]. */
    fun debugStick(x: Float, ms: Long): String {
        stick.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d target=%s", x, ms, stickTarget() ?: "-")
    }

    // =========================================================================================
    // live placement (shared by drag and stick)
    // =========================================================================================

    private fun setLive(lamp: Lamp, xzIn: P2) {
        val room = ui.room
        val others = ui.lamps.filter { it.id != lamp.id }
        val (snappedXz, guidesNow) = LightMath.snap(room.keepOffWalls(xzIn, WALL_INSET_M), others, room.mainAxis())
        if (guidesNow.isNotEmpty() && !snapped) LpLog.i("SNAP id=${lamp.id} at=(${fmt(snappedXz.x)}, ${fmt(snappedXz.z)})")
        snapped = guidesNow.isNotEmpty()
        live = lamp.id to snappedXz
        liveGuides = guidesNow
    }

    private fun clearLive() {
        live = null
        liveGuides = emptyList()
        snapped = false
    }

    // =========================================================================================
    // panels
    // =========================================================================================

    /** The main panel's rest pose (design `spatialLayout.panel.anchor`): 0.65 m, 22 degrees left, 20 down, adjusted when looking up or down. */
    private fun restMain(h: HeadPose): Vec3 {
        val pitch = gazePitchDeg(h)
        val (yawLeft, pitchDeg) =
            when {
                pitch > LOOK_UP_DEG -> MAIN_LEFT_DEG to (pitch - 25f) // looking at the ceiling: 25 degrees under the gaze, never on the ceiling
                pitch < -LOOK_DOWN_DEG -> SIDE_LEFT_DEG to (pitch + 15f) // looking at the desk: parked to the side, not over the pool
                else -> MAIN_LEFT_DEG to -MAIN_DOWN_DEG
            }
        return restAt(h, yawLeft, pitchDeg, MAIN_M)
    }

    private fun restAt(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    private fun gazePitchDeg(h: HeadPose): Float = Math.toDegrees(asin(h.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val state = ui
        val mainSlot = rig[PanelIds.MAIN] ?: return
        val hold = state.popup != Popup.NONE || now - lastPanelPressMs < KEY_HOLD_MS || drag != null
        mainFollow.step(pose, now, hold)?.let { mainSlot.moveTo(it) }
        val facing = FacePanel.facing(mainSlot.position, pose.position, null)
        val mw = PanelSizes.MAIN_W * DP_M
        val mh = PanelSizes.MAIN_H * DP_M
        when (state.popup) {
            Popup.SETTINGS -> {
                val sw = PanelSizes.SETTINGS_W * DP_M
                val sh = PanelSizes.SETTINGS_H * DP_M
                rig[PanelIds.SETTINGS]?.show(mainSlot.position + facing.right * (mw / 2f + sw / 2f + 0.02f) + facing.up * ((mh - sh) / 2f) + facing.normal * 0.01f)
            }
            Popup.HOLE_PLAN -> {
                val hw = PanelSizes.HOLE_W * DP_M
                val hh = PanelSizes.HOLE_H * DP_M
                rig[PanelIds.HOLE]?.show(mainSlot.position + facing.right * (mw / 2f + hw / 2f + 0.02f) + facing.up * ((hh - mh) / 2f + 0.02f) + facing.normal * 0.01f)
            }
            Popup.NONE -> Unit
        }
    }

    /** A key of a panel was pressed: the main panel holds still for a moment (lazy follow). */
    fun onPanelPointer() {
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    fun headPose(): HeadPose? = if (hasHead) pose else null

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun lampOf(entity: Entity?): String? {
        if (entity == null) return null
        for ((id, v) in lampViews) if (v.owns(entity)) return id
        return null
    }

    private fun planeOf(entity: Entity?): PlaneSurface? {
        if (entity == null) return null
        val id = colliders?.planeOf(entity) ?: return null
        return planes.all.firstOrNull { it.id == id }
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (lastDragEndMs > 0 && now - lastDragEndMs < TAP_AFTER_DRAG_MS) {
            LpLog.i("TAP ignored reason=after-drag target=$name")
            return
        }
        if (popupClosedMs > 0 && now - popupClosedMs < TAP_AFTER_CLOSE_MS) {
            LpLog.i("TAP ignored reason=after-close target=$name")
            return
        }
        val lampId = lampOf(target)
        val handle = target != null && select?.ownsHandle(target) == true
        val plane = planeOf(target)
        val hit: Hit =
            when {
                lampId != null -> Hit.Lamp(lampId)
                handle -> Hit.Handle(ui.dragging ?: ui.selected ?: "")
                plane != null && stage != null -> Hit.Surface(stage, plane)
                else -> Hit.Nothing
            }
        LpLog.i("TAP target=$name hit=${hit::class.simpleName} stage=${LpLog.pos(stage)} head=${LpLog.pos(pose.position)}")
        sink(PlannerEvent.Pinched(hit))
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            "start" -> {
                drag = null
                val target = value.targetEntity
                val name = runCatching { target?.getName() }.getOrNull() ?: "none"
                val press = if (start != null) toStage(start.x, start.y, start.z) else null
                if (press == null) return
                if (!ui.placingEnabled) {
                    LpLog.i("DRAG refused state=${ui.designState}")
                    return
                }
                val lampId = lampOf(target)
                val sv = select
                val selectedId = ui.selected
                val onHandle = target != null && sv?.ownsHandle(target) == true
                val plane = planeOf(target)
                // the four starting points (design `input.dragRule`)
                val d: Drag? =
                    when {
                        lampId != null -> Drag(lampId, press, handle = false, levelY = press.y)
                        onHandle && selectedId != null -> Drag(selectedId, press, handle = true, levelY = sv!!.handleY)
                        plane != null && !ui.room.isCeiling(plane) -> {
                            val xz = P2.of(press)
                            val near = ui.lamps.minByOrNull { (it.xz - xz).length }
                            if (near != null && (near.xz - xz).length < SELECT_M) Drag(near.id, press, handle = true, levelY = press.y) else null
                        }
                        else -> null
                    }
                LpLog.i("DRAG start target=$name lamp=${d?.id ?: "-"} handle=${d?.handle} stage=${LpLog.pos(press)}")
                drag = d
            }
            "move" -> {
                val d = drag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) beginDrag(d)
                if (d.begun) {
                    val dir = d.dir ?: return
                    val turned = DragLine.turn(dir, d.hand - d.prevHand, d.equivalent, pose.right, pose.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    val lamp = ui.lamp(d.id) ?: return
                    val xz: P2? =
                        if (d.handle) DragLine.onLevel(pose.position, turned, d.levelY, 8f)
                        else ui.room.hitCeiling(pose.position, turned)?.let { P2.of(it) }
                    if (xz != null) setLive(lamp, xz)
                    if (now - d.lastSendMs >= DRAG_SEND_MS) {
                        d.lastSendMs = now
                        live?.let { sink(PlannerEvent.DragMoved(d.id, it.second)) }
                    }
                    if (d.moves <= 3 || now - d.lastLogMs > 500) {
                        d.lastLogMs = now
                        LpLog.i("DRAG move id=${d.id} live=${live?.second} n=${d.moves}")
                    }
                }
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                if (!d.begun) {
                    LpLog.i("DRAG $phase not-begun n=${d.moves}")
                    if (d.handle && ui.selected != d.id) sink(PlannerEvent.Pinched(Hit.Lamp(d.id)))
                    return
                }
                lastDragEndMs = now
                LpLog.i("DRAG $phase id=${d.id} n=${d.moves}")
                endDrag(cancelled = phase == "cancel", id = d.id)
            }
        }
    }

    private fun beginDrag(d: Drag) {
        d.begun = true
        val to = d.press - pose.position
        if (to.length < 1e-3f) return
        d.dir = to.normalized()
        val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        d.equivalent = DragLine.equivalentDepth(to.length, pitch)
        d.prevHand = d.hand
        sink(PlannerEvent.DragStarted(d.id))
        LpLog.i("DRAG begin id=${d.id} handle=${d.handle} depth=${String.format(Locale.US, "%.2f", d.equivalent)}")
    }

    private fun endDrag(cancelled: Boolean, id: String? = null) {
        val dragged = id ?: drag?.id ?: ui.dragging ?: return
        drag = null
        val l = live?.takeIf { it.first == dragged }
        sink(PlannerEvent.DragEnded(dragged, if (cancelled) null else l?.second))
        clearLive()
    }

    /** Debug: a drag-like move of [id] by (dx, dz) metres, written at once (or held when [hold]). */
    fun debugMove(lampIn: Lamp?, dx: Float, dz: Float, hold: Boolean): String {
        // the caller passes the ViewModel's lamp: the scene's own copy can lag a frame behind on the idle emulator
        val lamp = lampIn ?: return "no lamp"
        val id = lamp.id
        setLive(lamp, lamp.xz + P2(dx, dz))
        val l = live ?: return "no live"
        if (hold) {
            sink(PlannerEvent.DragStarted(id))
            sink(PlannerEvent.DragMoved(id, l.second))
            return "holding $id at ${l.second} guides=${liveGuides.size}"
        }
        sink(PlannerEvent.DragStarted(id))
        sink(PlannerEvent.DragEnded(id, l.second))
        val at = l.second
        clearLive()
        return "moved $id to $at"
    }

    fun debugDragCancel() {
        if (drag != null || live != null || ui.dragging != null) endDrag(cancelled = true)
        stickTarget = null
        clearLive()
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    fun debugHideMode(mode: String): String {
        val rig = rig ?: return "no rig"
        rig.hideMode = if (mode == "park") PanelRig.HideMode.PARK else PanelRig.HideMode.AUTO
        return "hidemode=${rig.hideMode}"
    }

    fun poseLine(): String = "POSE hmd pos=${LpLog.pos(pose.position)} ${LpLog.quat(pose.rotation)} fwd=${LpLog.pos(pose.forward)} valid=$hasHead"

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun planeLines(): List<String> =
        planes.all.map { "PLANE ${it.label} center=${LpLog.pos(it.centroid)} normal=${LpLog.pos(it.normal)} area=${String.format(Locale.US, "%.2f", it.area)} verts=${it.vertices.size} ceiling=${ui.room.isCeiling(it)} kind=${ui.room.receiverOf(it)?.kind ?: "-"}" }

    fun layerLines(): List<String> = layerViews.values.map { "LAYER surface=${it.receiverId.take(8)} painted=${it.painted}" } + "LAYER textured=$poolsTextured flipV=$flipV pendingWork=${work.pending}"

    private fun fmt(v: Float) = String.format(Locale.US, "%.3f", v)

    companion object {
        const val MAIN_LEFT_DEG = 22f
        const val MAIN_DOWN_DEG = 20f
        const val SIDE_LEFT_DEG = 35f
        const val MAIN_M = 0.65f
        const val LOOK_UP_DEG = 25f
        const val LOOK_DOWN_DEG = 35f
        const val KEY_HOLD_MS = 600L
        const val DRAG_START_M = 0.01f
        const val DRAG_SEND_MS = 200L
        const val TAP_AFTER_DRAG_MS = 400L
        const val TAP_AFTER_CLOSE_MS = 400L
        const val STICK_RELEASE_MS = 150L
        const val STICK_DEADZONE = 0.15f
        const val STICK_MAX_M_S = 0.4f
        const val SELECT_M = 0.12f
        const val WALL_INSET_M = 0.10f
        const val GUIDE_HALF_M = 4f
        const val WALL_STUB_M = 0.6f
        const val RETICLE_REACH_M = 8f
        const val LABEL_SLOTS = 8
        const val TAG_SLOTS = 8
        const val LOG_MS = 5000L
        const val SLOW_FRAME_MS = 60L
        const val SLOW_MESH_GAP_MS = 700L
        const val SLOW_WORK_PER_FRAME = 2
        const val FAST_WORK_PER_FRAME = 6
        const val FAST_MESH_GAP_MS = 30L
    }
}

/**
 * Lazy follow of the main panel (design `spatialLayout.panel.anchor`): the panel rests where it is; once
 * the head has turned more than 30 degrees sideways, or looks more than 25 degrees above / 35 below the
 * panel's direction, for 0.6 s, it eases (0.4 s) to the rest pose of that moment.
 */
internal class PanelFollow(private val name: String, private val rest: (HeadPose) -> Vec3) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            LpLog.i("FOLLOW $name park pos=${LpLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        val toPanel = current - head.position
        val dist = toPanel.length
        val flat = P2.of(toPanel)
        val heading = head.level()
        val yawAway = if (flat.length < 1e-3f) 0f else Math.toDegrees(acos((flat.normalized().dot(heading)).coerceIn(-1f, 1f).toDouble())).toFloat()
        val panelPitch = Math.toDegrees(asin((toPanel.y / dist).coerceIn(-1f, 1f).toDouble())).toFloat()
        val gazePitch = Math.toDegrees(asin(head.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        val pitchAway = gazePitch - panelPitch
        val out = yawAway > YAW_WINDOW_DEG || pitchAway > UP_WINDOW_DEG || pitchAway < -DOWN_WINDOW_DEG || dist < 0.3f || dist > 1.2f
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            LpLog.i(String.format(Locale.US, "FOLLOW %s slide yaw=%.1f pitch=%.1f dist=%.2f to=%s", name, yawAway, pitchAway, dist, LpLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 600L
        private const val SLIDE_MS = 400f
        private const val YAW_WINDOW_DEG = 30f
        private const val UP_WINDOW_DEG = 45f
        private const val DOWN_WINDOW_DEG = 55f
    }
}
