package io.embodit.lightplanner.ui.planner.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.Grade
import io.embodit.lightplanner.domain.LightMath
import io.embodit.lightplanner.domain.P2
import io.embodit.lightplanner.domain.Quat4
import io.embodit.lightplanner.domain.SurfaceField
import io.embodit.lightplanner.domain.Vec3
import io.embodit.lightplanner.platform.LpLog
import io.embodit.lightplanner.ui.theme.LpColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Sizes of the things in the room (design `spatialLayout`). */
internal object Dims {
    const val RING_D = 0.10f
    const val RING_BELOW = 0.002f
    const val GLOW_D = 0.074f
    const val HIT = 0.12f
    const val SELECT_D = 0.14f
    const val SELECT_BREATH = 0.02f
    const val HANDLE_D = 0.08f
    const val HANDLE_ABOVE = 0.006f
    const val LINE_W = 0.006f
    const val LINE_BELOW = 0.003f
    const val WALL_LINE_W = 0.006f
    const val GUIDE_W = 0.0015f
    const val LAYER_ABOVE = 0.004f
    const val TAG_ABOVE = 0.25f
    const val NUMBER_H = 0.06f
    const val TAG_H = 0.06f
    const val LABEL_H = 0.035f
    const val RETICLE_RING_D = 0.10f
    const val RETICLE_DOT_D = 0.06f
}

/**
 * One downlight (design `spatialLayout.ceiling.lampRing`): a plaster ring 2 mm under the ceiling, a
 * glowing disc in the colour temperature, a 12 cm hit box and the number plate beside it.
 */
internal class LampView(root: Entity, meshes: LpMeshes, private val m: LpMaterials, queue: WorkQueue, val id: String) {
    private val ring = Part(root, meshes.ring, m.plaster, "lamp:$id")
    private val glow = Part(root, meshes.disc, m.glow(Cct.DEFAULT), "lampGlow:$id")
    val number = TextPlate(root, meshes, m, queue, "lampNo:$id")
    private val hit = Entity()
    private var hitSet = false
    var pos: Vec3 = Vec3.ZERO
        private set

    init {
        runCatching { hit.setName("lampHit:$id") }
        hit.components.set(InteractableComponent())
        root.addChild(hit)
    }

    fun owns(entity: Entity): Boolean = entity.id == hit.id || entity.id == ring.entity.id || entity.id == glow.entity.id

    fun place(center: Vec3, cct: Cct, stale: Boolean, big: Boolean) {
        pos = center
        val y = center.y - Dims.RING_BELOW
        ring.place(Vec3(center.x, y, center.z), Vec3(Dims.RING_D, 1f, Dims.RING_D))
        glow.place(Vec3(center.x, y - 0.0005f, center.z), Vec3(Dims.GLOW_D, 1f, Dims.GLOW_D))
        ring.material(if (stale) m.stale else m.plaster)
        glow.material(if (stale) m.stale else m.glow(cct))
        if (!hitSet) {
            hit.components.set(boxCollision(Vec3(Dims.HIT, 0.04f, Dims.HIT)))
            hitSet = true
        }
        hit.setLocal(position = Vec3(center.x, center.y - 0.02f, center.z))
        hit.enabled = true
        number.place(Vec3(center.x, y - 0.04f, center.z), if (big) Dims.NUMBER_H * 1.4f else Dims.NUMBER_H)
    }

    fun hide() {
        ring.show(false)
        glow.show(false)
        number.hide()
        hit.enabled = false
    }

    fun destroy() {
        ring.destroy()
        glow.destroy()
        number.destroy()
        runCatching { hit.destroy(true) }
    }
}

/**
 * What goes with the selected lamp (design S05): the breathing amber ring, the light cone down to the
 * highest surface below, and the drag handle on that surface (ring + four small arrows + 12 cm hit box).
 */
internal class SelectView(root: Entity, meshes: LpMeshes, private val m: LpMaterials) {
    private val breath = Part(root, meshes.thinRing, m.amberBreath, "selectRing")
    private val cone = Part(root, meshes.cone, m.cone(Cct.DEFAULT), "cone")
    private val handleRing = Part(root, meshes.thinRing, m.amber, "handle")
    private val arrows = List(4) { Strip(root, meshes.band, m.amber, "handleArrow:$it") }
    private val hit = Entity()
    var handleY = 0f
        private set
    var handleXZ = P2.ZERO
        private set

    init {
        runCatching { hit.setName("handleHit") }
        hit.components.set(InteractableComponent())
        hit.components.set(boxCollision(Vec3(Dims.HIT, 0.04f, Dims.HIT)))
        root.addChild(hit)
        hide()
    }

    fun ownsHandle(entity: Entity): Boolean = entity.id == hit.id || entity.id == handleRing.entity.id

    /** [surfaceY] the height of the highest receiving surface under the lamp (floor when none), [radius] the pool radius there. */
    fun draw(lamp: Vec3, cct: Cct, surfaceY: Float, radius: Float, nowMs: Long) {
        val pulse = 0.5f + 0.5f * sin(nowMs / 1000.0 * 2.0 * Math.PI / 1.6).toFloat()
        val d = Dims.SELECT_D + Dims.SELECT_BREATH * pulse
        breath.place(Vec3(lamp.x, lamp.y - Dims.RING_BELOW - 0.001f, lamp.z), Vec3(d, 1f, d))
        val dh = lamp.y - surfaceY
        if (dh > 0.05f) {
            cone.material(m.cone(cct))
            cone.place(Vec3(lamp.x, lamp.y - 0.004f, lamp.z), Vec3(max(0.12f, 2f * radius), dh - 0.01f, max(0.12f, 2f * radius)))
        } else cone.show(false)
        handleY = surfaceY
        handleXZ = P2(lamp.x, lamp.z)
        val hc = Vec3(lamp.x, surfaceY + Dims.HANDLE_ABOVE, lamp.z)
        handleRing.place(hc, Vec3(Dims.HANDLE_D, 1f, Dims.HANDLE_D))
        val r = Dims.HANDLE_D / 2f + 0.012f
        val dirs = listOf(Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f))
        for (i in 0 until 4) arrows[i].draw(hc + dirs[i] * r, hc + dirs[i] * (r + 0.02f), 0.006f, Vec3.UP)
        hit.setLocal(position = Vec3(lamp.x, surfaceY + 0.02f, lamp.z))
        hit.enabled = true
    }

    fun hide() {
        breath.show(false)
        cone.show(false)
        handleRing.show(false)
        arrows.forEach { it.hide() }
        hit.enabled = false
    }

    fun destroy() {
        breath.destroy()
        cone.destroy()
        handleRing.destroy()
        arrows.forEach { it.destroy() }
        runCatching { hit.destroy(true) }
    }
}

/**
 * The gaze reticle (design `spatialLayout.reticle`): a dashed lamp ring on the ceiling; on a lower surface
 * a dot plus a dashed vertical line up to the ceiling ring; a red cross where a lamp cannot go.
 */
internal class ReticleView(root: Entity, meshes: LpMeshes, private val m: LpMaterials) {
    private val ring = Part(root, meshes.dashedRing, m.reticleDash, "reticleRing")
    private val dot = Part(root, meshes.disc, m.reticle, "reticleDot")
    private val pole = Strip(root, meshes.dashes, m.reticleDash, "reticlePole")
    private val cross = List(2) { Strip(root, meshes.band, m.reticleBad, "reticleCross:$it") }

    init {
        hide()
    }

    fun ceiling(at: Vec3) {
        ring.place(Vec3(at.x, at.y - Dims.RING_BELOW - 0.0015f, at.z), Vec3(Dims.RETICLE_RING_D, 1f, Dims.RETICLE_RING_D))
        dot.show(false)
        pole.hide()
        cross.forEach { it.hide() }
    }

    fun surface(at: Vec3, ceilingY: Float, viewer: Vec3) {
        dot.place(Vec3(at.x, at.y + 0.004f, at.z), Vec3(Dims.RETICLE_DOT_D, 1f, Dims.RETICLE_DOT_D))
        pole.draw(Vec3(at.x, at.y + 0.05f, at.z), Vec3(at.x, ceilingY - 0.02f, at.z), 0.004f, (viewer - at).rejectFrom(Vec3.UP))
        ring.place(Vec3(at.x, ceilingY - Dims.RING_BELOW - 0.0015f, at.z), Vec3(Dims.RETICLE_RING_D, 1f, Dims.RETICLE_RING_D))
        cross.forEach { it.hide() }
    }

    fun bad(at: Vec3, normal: Vec3) {
        val n = normal.normalized()
        var u = Vec3.UP.rejectFrom(n)
        if (u.length < 0.1f) u = Vec3.RIGHT.rejectFrom(n)
        u = u.normalized()
        val v = n.cross(u).normalized()
        val c = at + n * 0.004f
        val s = 0.035f
        cross[0].draw(c - u * s - v * s, c + u * s + v * s, 0.008f, n)
        cross[1].draw(c - u * s + v * s, c + u * s - v * s, 0.008f, n)
        ring.show(false)
        dot.show(false)
        pole.hide()
    }

    fun hide() {
        ring.show(false)
        dot.show(false)
        pole.hide()
        cross.forEach { it.hide() }
    }

    fun destroy() {
        ring.destroy()
        dot.destroy()
        pole.destroy()
        cross.forEach { it.destroy() }
    }
}

/**
 * The light layer of one receiving surface (design `spatialLayout.surfaces.lightLayer`): the shared unit
 * square scaled to the field's bounding box, 4 mm above the plane, with its own bitmap texture (pools or
 * heat). The texture is rebuilt only when the field or the mode changes.
 */
internal class LightLayerView(root: Entity, private val meshes: LpMeshes, private val m: LpMaterials, private val queue: WorkQueue, val receiverId: String) {
    private val material: UnlitMaterial = m.textured(soft = true)
    private var part = Part(root, meshes.square, material, "light:${receiverId.take(8)}")
    private val root = root
    private var texture: TextureResource? = null
    private var flipped = false
    private var paintedKey: Any? = null
    private var pending: Pair<Vec3, Vec3>? = null
    var painted = 0
        private set

    init {
        // nothing to show until the first texture is in: a textureless quad would draw white
        part.show(false)
    }

    fun show(field: SurfaceField, heat: Boolean, flipV: Boolean, key: Any) {
        if (flipV != flipped) {
            // the uv convention is the spike item: `flipv on` swaps the mesh (AGENTS.md)
            part.destroy()
            part = Part(root, if (flipV) meshes.squareFlipped else meshes.square, material, "light:${receiverId.take(8)}")
            part.show(false)
            painted = 0
            flipped = flipV
            paintedKey = null
        }
        if (key != paintedKey) {
            paintedKey = key
            queue.enqueue(this) { paint(field, heat) }
        }
        if (painted > 0) part.place(Vec3(field.centerX, field.y + Dims.LAYER_ABOVE, field.centerZ), Vec3(field.width, 1f, field.depth))
        else pending = Vec3(field.centerX, field.y + Dims.LAYER_ABOVE, field.centerZ) to Vec3(field.width, 1f, field.depth)
    }

    private fun paint(field: SurfaceField, heat: Boolean) {
        val w = field.w
        val h = field.h
        val px = IntArray(w * h)
        for (i in px.indices) {
            if (!field.mask[i]) continue
            val e = field.e[i]
            if (heat) {
                val ratio = e / field.kind.reference
                val c = LpColors.heatRamp(ratio)
                px[i] = argb(0.70f, c[0], c[1], c[2])
            } else {
                var a = min(field.alpha[i], LightMath.MAX_OPACITY)
                if (e <= 0f && !field.edge[i]) continue
                var r = 255
                var g = 215
                var b = 154
                if (e > 0f) {
                    r = (field.r[i] / e * 255f).toInt().coerceIn(0, 255)
                    g = (field.g[i] / e * 255f).toInt().coerceIn(0, 255)
                    b = (field.b[i] / e * 255f).toInt().coerceIn(0, 255)
                }
                if (field.edge[i]) {
                    // the 1.5 cm bright rim (design `pool`): one cell wide here, brighter and more opaque
                    a = max(a, 0.45f)
                    r = min(255, r + 30); g = min(255, g + 30); b = min(255, b + 30)
                }
                if (a < 0.01f) continue
                px[i] = argb(a, r, g, b)
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        val old = texture
        val tex = runCatching { TextureResource.create(bitmap) }.onFailure { LpLog.e("LAYER texture failed: $it", it) }.getOrNull()
        if (tex != null) {
            material.setBaseColorTexture(tex)
            texture = tex
            old?.let { runCatching { it.close() } }
            painted++
            pending?.let { (c, s) -> part.place(c, s) }
            pending = null
        }
        bitmap.recycle()
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b

    fun hide() {
        pending = null
        part.show(false)
    }

    fun destroy() {
        queue.cancel(this)
        part.destroy()
        texture?.let { runCatching { it.close() } }
        texture = null
        m.release(material)
    }
}

/** A pool of strips for the spacing / wall / guide lines on the ceiling (shared band meshes, design `entities3d`). */
internal class LinePool(private val root: Entity, private val meshes: LpMeshes, private val m: LpMaterials, private val name: String, private val max: Int) {
    private val solid = ArrayList<Strip>()
    private val dashed = ArrayList<Strip>()
    private var usedSolid = 0
    private var usedDashed = 0

    fun begin() {
        usedSolid = 0
        usedDashed = 0
    }

    fun draw(from: Vec3, to: Vec3, width: Float, material: com.pico.spatial.core.ecs.resource.Material, dashes: Boolean) {
        val list = if (dashes) dashed else solid
        val used = if (dashes) usedDashed else usedSolid
        if (used >= max) return
        if (list.size <= used) list += Strip(root, if (dashes) meshes.dashes else meshes.band, material, "$name:${if (dashes) "d" else "s"}:$used")
        val s = list[used]
        s.material(material)
        s.draw(from, to, width, Vec3.UP)
        if (dashes) usedDashed++ else usedSolid++
    }

    fun end() {
        for (i in usedSolid until solid.size) solid[i].hide()
        for (i in usedDashed until dashed.size) dashed[i].hide()
    }

    fun hideAll() {
        solid.forEach { it.hide() }
        dashed.forEach { it.hide() }
        usedSolid = 0
        usedDashed = 0
    }

    fun destroy() {
        solid.forEach { it.destroy() }
        dashed.forEach { it.destroy() }
    }
}

/** Bitmaps for the label plates: text on an ink capsule, with an optional 4-cell brightness bar. */
internal object Plates {
    /** Pixels per line of a plate; [TextPlate] sizes a plate by its number of lines. */
    const val LINE_PX = 64
    private const val H = LINE_PX
    private const val PAD = 22

    private fun paint(size: Float, color: Int, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    /** A number badge: dark plate, white digits (lamp numbers). */
    fun number(text: String, ink: Color = LpColors.White, plate: Color = LpColors.PlateInk): Bitmap {
        val p = paint(40f, ink.toArgb(), bold = true)
        val tw = p.measureText(text)
        val w = max(H, (tw + PAD * 2).toInt())
        val bmp = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plate.toArgb() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), H.toFloat()), H / 2f, H / 2f, bg)
        c.drawText(text, (w - tw) / 2f, H / 2f + 14f, p)
        return bmp
    }

    /**
     * A surface tag: "茶几 · 偏暗" plus a 4-cell bar (design `spatialLayout.surfaces.tags`). The Chinese name
     * leads (0.1.1, user feedback); [sub] is a smaller second line with the same in the UI language, or null.
     */
    fun tag(text: String, sub: String?, grade: Grade, gradeColor: Color): Bitmap {
        val p = paint(38f, LpColors.White.toArgb(), bold = true)
        val ps = paint(28f, LpColors.OnInkSecondary.toArgb(), bold = false)
        val tw = p.measureText(text)
        val sw = sub?.let { ps.measureText(it) } ?: 0f
        val barW = 4 * 26 + 3 * 6
        val w = max(PAD + tw + 18 + barW + PAD, PAD + sw + PAD).toInt()
        val h = if (sub == null) H else 2 * H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = LpColors.PlateInk.toArgb() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f, 24f, bg)
        c.drawText(text, PAD.toFloat(), H / 2f + 13f, p)
        if (sub != null) c.drawText(sub, PAD.toFloat(), H + H / 2f + 4f, ps)
        val filled =
            when (grade) {
                Grade.DARK -> 1
                Grade.DIM, Grade.UNEVEN -> 2
                Grade.OK -> 3
                Grade.BRIGHT -> 4
            }
        val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = gradeColor.toArgb() }
        val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = LpColors.OnInkSecondary.copy(alpha = 0.25f).toArgb() }
        var x = PAD + tw + 18
        for (i in 0 until 4) {
            c.drawRoundRect(RectF(x, H / 2f - 7f, x + 26f, H / 2f + 7f), 4f, 4f, if (i < filled) on else off)
            x += 32f
        }
        return bmp
    }

    /** A spacing label: "1.42 m · 中间会暗" in the line's colour (design `spacingRule.labels`). */
    fun label(text: String, color: Color): Bitmap {
        val p = paint(36f, color.toArgb(), bold = true)
        val tw = p.measureText(text)
        val w = (tw + PAD * 2).toInt()
        val bmp = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = LpColors.PlateInk.toArgb() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), H.toFloat()), 24f, 24f, bg)
        c.drawText(text, PAD.toFloat(), H / 2f + 12f, p)
        return bmp
    }
}

/** Keeps the pooled fallback discs when the textured layers are switched off (`pools disc`). */
internal class PoolDiscs(private val root: Entity, private val meshes: LpMeshes, private val m: LpMaterials) {
    private val discs = ArrayList<Part>()
    private var used = 0

    fun begin() {
        used = 0
    }

    fun draw(center: P2, y: Float, radius: Float, cct: Cct) {
        if (discs.size <= used) discs += Part(root, meshes.disc, m.poolDisc(cct), "poolDisc:$used")
        val d = discs[used++]
        d.material(m.poolDisc(cct))
        d.place(Vec3(center.x, y + Dims.LAYER_ABOVE, center.z), Vec3(2f * radius, 1f, 2f * radius))
    }

    fun end() {
        for (i in used until discs.size) discs[i].show(false)
    }

    fun hideAll() {
        discs.forEach { it.show(false) }
        used = 0
    }

    fun destroy() = discs.forEach { it.destroy() }
}

/** Unused rotation helper kept for symmetry with the other apps' views. */
internal fun yawOnly(from: Vec3, to: Vec3): Quat4 {
    val flat = Vec3(to.x - from.x, 0f, to.z - from.z)
    if (flat.length < 1e-3f) return Quat4.IDENTITY
    val n = flat.normalized()
    return Quat4.fromBasis(Vec3.UP.cross(n).normalized(), Vec3.UP, n)
}
