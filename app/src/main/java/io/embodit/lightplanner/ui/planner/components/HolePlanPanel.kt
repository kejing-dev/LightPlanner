package io.embodit.lightplanner.ui.planner.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.lightplanner.R
import io.embodit.lightplanner.domain.Lamp
import io.embodit.lightplanner.domain.LineKind
import io.embodit.lightplanner.domain.P2
import io.embodit.lightplanner.domain.Units
import io.embodit.lightplanner.ui.planner.PlannerEvent
import io.embodit.lightplanner.ui.planner.PlannerUiState
import io.embodit.lightplanner.ui.planner.debug.probe
import io.embodit.lightplanner.ui.theme.LpColors
import io.embodit.lightplanner.ui.theme.LpType
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val RowH = 44.dp

/**
 * The hole-plan slot (design `panels.holePlan`, 560 x 460 dp): a top view (walls, numbered lamps, the
 * spacing between neighbours, each lamp's distance to its two nearest walls in cm, the ceiling height)
 * and a read-only table. Saved with a system screenshot (design decision).
 */
@Composable
internal fun HolePlanPanel(state: PlannerUiState?, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.HOLE_W.dp, PanelSizes.HOLE_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val grey = PicoTheme.colorScheme.labelSecondary
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(name = "back", icon = R.drawable.ic_back, description = rs(R.string.action_back), enabled = true) { onEvent(PlannerEvent.PopupClosed) }
            Text(text = rs(R.string.hole_title), color = ink, style = LpType.title, maxLines = 1, modifier = Modifier.weight(1f).probe("hole_title"))
            val h = state.ceilingHeight
            Text(text = if (h != null) rs(R.string.hole_ceiling, Units.m(h)) else "", color = grey, style = LpType.status, maxLines = 1, modifier = Modifier.probe("hole_ceiling"))
        }
        TopView(state, modifier = Modifier.fillMaxWidth().height(206.dp))
        Text(text = rs(R.string.hole_rule), color = grey, style = LpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HoleTable(state, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(text = rs(R.string.hole_note), color = PicoTheme.colorScheme.labelTertiary, style = LpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("hole_note"))
    }
}

/** The plan seen from above, in the room's own axes (longest wall horizontal). */
@Composable
private fun TopView(state: PlannerUiState, modifier: Modifier) {
    val room = state.room
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    val wallColor = grey
    val lampFill = PicoTheme.colorScheme.interaction
    val okColor = PicoTheme.colorScheme.passable
    val darkColor = PicoTheme.colorScheme.error
    val u = room.mainAxis()
    val v = u.right
    fun proj(p: P2) = Offset(p.dot(u), p.dot(v))
    val pts = ArrayList<Offset>()
    val walls = room.walls.map { room.wallSegment(it) }
    for ((a, b) in walls) {
        pts += proj(a); pts += proj(b)
    }
    for (l in state.lamps) pts += proj(l.xz)
    for (f in state.light.fields) {
        pts += Offset(P2(f.minX, f.minZ).dot(u), P2(f.minX, f.minZ).dot(v))
        pts += Offset(P2(f.minX + f.width, f.minZ + f.depth).dot(u), P2(f.minX + f.width, f.minZ + f.depth).dot(v))
    }
    val textPaint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink.toArgbInt()
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    val dimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = grey.toArgbInt(); textAlign = android.graphics.Paint.Align.CENTER }
    Canvas(modifier = modifier.probe("top_view")) {
        if (pts.isEmpty()) return@Canvas
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val margin = 26f * density
        val spanX = max(0.5f, maxX - minX)
        val spanY = max(0.5f, maxY - minY)
        val k = min((size.width - 2 * margin) / spanX, (size.height - 2 * margin) / spanY)
        val ox = (size.width - spanX * k) / 2f
        val oy = (size.height - spanY * k) / 2f
        fun at(p: P2): Offset {
            val q = proj(p)
            return Offset(ox + (q.x - minX) * k, oy + (q.y - minY) * k)
        }
        textPaint.textSize = 12f * density
        dimPaint.textSize = 10f * density
        // walls
        for ((a, b) in walls) drawLine(wallColor, at(a), at(b), strokeWidth = 3f * density)
        // spacing lines with their lengths
        for (ln in state.light.lines) {
            val a = state.lamp(ln.a) ?: continue
            val b = state.lamp(ln.b) ?: continue
            val pa = at(a.xz)
            val pb = at(b.xz)
            val color = when (ln.kind) { LineKind.OK -> okColor; LineKind.DARK_GAP -> darkColor; LineKind.ACCENT -> grey }
            drawLine(color, pa, pb, strokeWidth = 1.5f * density, pathEffect = if (ln.kind == LineKind.DARK_GAP) PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density)) else null)
            val mid = Offset((pa.x + pb.x) / 2f, (pa.y + pb.y) / 2f)
            drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.2f", ln.distance), mid.x, mid.y - 4f * density, dimPaint)
        }
        // each lamp: dimension lines to its two nearest walls, then the numbered dot
        for (l in state.lamps) {
            val info = state.light.info(l.id) ?: continue
            val p = at(l.xz)
            for ((d, foot) in info.wallDistances) {
                val f = at(foot)
                drawLine(grey.copy(alpha = 0.7f), p, f, strokeWidth = 1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density)))
                val mid = Offset((p.x + f.x) / 2f, (p.y + f.y) / 2f)
                drawContext.canvas.nativeCanvas.drawText(Units.cm(d), mid.x, mid.y - 3f * density, dimPaint)
            }
        }
        for (l in state.lamps) {
            val p = at(l.xz)
            drawCircle(lampFill, radius = 9f * density, center = p)
            drawCircle(LpColors.TapeInk, radius = 9f * density, center = p, style = Stroke(width = 1f * density))
            drawContext.canvas.nativeCanvas.drawText(l.no.toString(), p.x, p.y + 4.5f * density, textPaint.apply { color = LpColors.TapeInk.toArgbInt() })
        }
    }
}

@Composable
private fun HoleTable(state: PlannerUiState, modifier: Modifier) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell(rs(R.string.hole_col_no), 56.dp, grey)
            Cell(rs(R.string.hole_col_beam), 96.dp, grey)
            Cell(rs(R.string.hole_col_watt), 96.dp, grey)
            Cell(rs(R.string.hole_col_cct), 96.dp, grey)
            Text(text = rs(R.string.hole_col_wall), color = grey, style = LpType.label, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).probe("hole_table")) {
            for (l in state.lamps.sortedBy { it.no }) {
                val info = state.light.info(l.id)
                Row(modifier = Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cell(l.no.toString(), 56.dp, ink)
                    Cell("${beamName(l.beam)} ${l.beam.deg.toInt()}°", 96.dp, ink)
                    Cell("${l.output.watts} W", 96.dp, ink)
                    Cell(if (l.cct.white) "${l.cct.kelvin} K" else lightName(l.cct), 96.dp, ink)
                    Text(text = info?.wallDistances?.joinToString(" · ") { Units.cm(it.first) } ?: "–", color = ink, style = LpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, color: Color) {
    Text(text = text, color = color, style = LpType.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width))
}

private fun Color.toArgbInt(): Int = this.toArgb()

@Suppress("unused")
private fun lampLabel(l: Lamp) = l.no.toString()
