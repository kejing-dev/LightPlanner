package io.embodit.lightplanner.ui.planner.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.lightplanner.R
import io.embodit.lightplanner.domain.Beam
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.Output
import io.embodit.lightplanner.domain.Units
import io.embodit.lightplanner.ui.planner.BarKind
import io.embodit.lightplanner.ui.planner.InlineBar
import io.embodit.lightplanner.ui.planner.MANUAL_HEIGHTS
import io.embodit.lightplanner.ui.planner.Phase
import io.embodit.lightplanner.ui.planner.PlannerEvent
import io.embodit.lightplanner.ui.planner.PlannerUiState
import io.embodit.lightplanner.ui.planner.Popup
import io.embodit.lightplanner.ui.planner.debug.probe
import io.embodit.lightplanner.ui.theme.LpColors
import io.embodit.lightplanner.ui.theme.LpType
import java.util.Locale

private val RowH = 44.dp
private val KeyW = 52.dp
private val Radius = 28.dp

/**
 * The corner panel (design `panels.main`, 400 x 340 dp at 0.65 m): the setup states (S00 / S01 / S02 /
 * S12 / S13 / S16), the plan view (S03 / S04 / S07 / S08 title row) and the lamp view (S05 / S06). The
 * inline bar sits on its own row above the resident disclaimer line (design R1-6).
 */
@Composable
internal fun MainPanel(state: PlannerUiState?, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.MAIN_W.dp, PanelSizes.MAIN_H.dp)
                .clip(RoundedCornerShape(Radius))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        when {
            state.phase == Phase.LOADING -> Loading()
            state.phase == Phase.WELCOME -> Welcome(onEvent)
            state.phase == Phase.FINDING -> Finding(state)
            state.phase == Phase.NO_CEILING -> NoCeiling(onEvent)
            state.phase == Phase.MANUAL_HEIGHT -> ManualHeight(state, onEvent)
            state.phase == Phase.MISMATCH -> Mismatch(state, onEvent)
            state.phase == Phase.NO_PERMISSION -> NoPermission(onEvent)
            state.trackingLost -> Lost()
            state.selected != null && state.selectedLamp != null -> LampView(state, onEvent)
            else -> PlanView(state, onEvent)
        }
    }
}

// ---- setup states --------------------------------------------------------------------------

@Composable
private fun ColumnScope.Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(40.dp))
            Text(text = rs(R.string.loading), color = PicoTheme.colorScheme.labelSecondary, style = LpType.status, maxLines = 1, modifier = Modifier.probe("loading"))
        }
    }
}

@Composable
private fun ColumnScope.Welcome(onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Spacer(modifier = Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Pictogram(R.drawable.ic_look_up, rs(R.string.welcome_look))
        Pictogram(R.drawable.ic_pinch, rs(R.string.welcome_pinch))
        Pictogram(R.drawable.ic_sit, rs(R.string.welcome_sit))
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(text = rs(R.string.welcome_title), color = ink, style = LpType.instruction, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("welcome_title"))
    Text(text = rs(R.string.welcome_body), color = PicoTheme.colorScheme.labelSecondary, style = LpType.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(modifier = Modifier.weight(1f))
    Primary(name = "start", text = rs(R.string.action_start)) { onEvent(PlannerEvent.StartClicked) }
}

@Composable
private fun Pictogram(icon: Int, caption: String) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillTertiary), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = ink)
        }
        Text(text = caption, color = PicoTheme.colorScheme.labelSecondary, style = LpType.label, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.Finding(state: PlannerUiState) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Spacer(modifier = Modifier.height(6.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(PicoTheme.colorScheme.fillTertiary), contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(R.drawable.ic_look_up), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
        }
    }
    Text(text = rs(R.string.finding_title), color = ink, style = LpType.instruction, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("finding_title"))
    Spacer(modifier = Modifier.weight(1f))
    val room = state.room
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(rs(R.string.finding_ceiling), room.ceilings.isNotEmpty(), "chip_ceiling")
        Chip(rs(R.string.finding_floor), room.hasFloor, "chip_floor")
        Chip(rs(R.string.finding_furniture, room.receivers.count { it.kind != io.embodit.lightplanner.domain.SurfaceKind.FLOOR }), room.hasFurniture, "chip_furniture")
    }
}

@Composable
private fun Chip(text: String, done: Boolean, name: String) {
    val ink = if (done) LpColors.TapeInk else PicoTheme.colorScheme.labelSecondary
    Row(
        modifier = Modifier.height(32.dp).clip(CircleShape).background(if (done) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.fillTertiary).padding(horizontal = 12.dp).probe(name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (done) Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp), tint = ink)
        Text(text = text, color = ink, style = LpType.label, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ColumnScope.NoCeiling(onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    Text(text = rs(R.string.no_ceiling_title), color = ink, style = LpType.instruction, maxLines = 1, modifier = Modifier.probe("no_ceiling_title"))
    Text(text = "· " + rs(R.string.no_ceiling_tip1), color = grey, style = LpType.status, maxLines = 1)
    Text(text = "· " + rs(R.string.no_ceiling_tip2), color = grey, style = LpType.status, maxLines = 1)
    Text(text = "· " + rs(R.string.no_ceiling_tip3), color = grey, style = LpType.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(modifier = Modifier.weight(1f))
    Primary(name = "retry", text = rs(R.string.action_retry)) { onEvent(PlannerEvent.RetryFindClicked) }
    Secondary(name = "manual_height", text = rs(R.string.action_manual_height)) { onEvent(PlannerEvent.ManualHeightClicked) }
}

@Composable
private fun ColumnScope.ManualHeight(state: PlannerUiState, onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Text(text = rs(R.string.manual_title), color = ink, style = LpType.instruction, maxLines = 1, modifier = Modifier.probe("manual_title"))
    Spacer(modifier = Modifier.height(8.dp))
    Segments(items = MANUAL_HEIGHTS, selected = state.manualPick, width = (PanelSizes.MAIN_W - 32f).dp, enabled = true, name = { "height_" + String.format(Locale.US, "%.1f", it) }, onSelect = { onEvent(PlannerEvent.ManualHeightPicked(it)) }) { h ->
        Text(text = String.format(Locale.US, "%.1f m", h), color = ink, style = LpType.segment, maxLines = 1, softWrap = false)
    }
    Spacer(modifier = Modifier.weight(1f))
    Primary(name = "use_this", text = rs(R.string.action_use_this)) { onEvent(PlannerEvent.UseManualHeight) }
    Secondary(name = "retry", text = rs(R.string.action_retry)) { onEvent(PlannerEvent.RetryFindClicked) }
}

@Composable
private fun ColumnScope.Mismatch(state: PlannerUiState, onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    if (state.restartConfirm) {
        Spacer(modifier = Modifier.weight(1f))
        Text(text = rs(R.string.restart_confirm, state.savedCount), color = ink, style = LpType.instruction, maxLines = 2, modifier = Modifier.probe("restart_confirm"))
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) { Secondary(name = "restart_cancel", text = rs(R.string.action_cancel)) { onEvent(PlannerEvent.RestartCancelled) } }
            Box(modifier = Modifier.weight(1f)) { Primary(name = "restart_confirm", text = rs(R.string.action_clear), danger = true) { onEvent(PlannerEvent.RestartConfirmed) } }
        }
        return
    }
    Text(text = rs(R.string.mismatch_title), color = ink, style = LpType.instruction, maxLines = 2, modifier = Modifier.probe("mismatch_title"))
    Text(text = rs(R.string.mismatch_body), color = PicoTheme.colorScheme.labelSecondary, style = LpType.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(modifier = Modifier.weight(1f))
    if (state.retrying) {
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(24.dp))
        }
    } else Primary(name = "look_again", text = rs(R.string.action_look_again)) { onEvent(PlannerEvent.MismatchRetry) }
    Secondary(name = "restart", text = rs(R.string.action_restart)) { onEvent(PlannerEvent.MismatchRestart) }
}

private fun savedCount(state: PlannerUiState): Int = state.lamps.size

@Composable
private fun ColumnScope.NoPermission(onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Spacer(modifier = Modifier.height(10.dp))
    Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(40.dp), tint = ink)
    Text(text = rs(R.string.permission_title), color = ink, style = LpType.instruction, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("permission_title"))
    Spacer(modifier = Modifier.weight(1f))
    Primary(name = "grant", text = rs(R.string.action_grant)) { onEvent(PlannerEvent.PermissionClicked) }
}

@Composable
private fun ColumnScope.Lost() {
    val ink = PicoTheme.colorScheme.labelPrimary
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(40.dp), tint = ink)
            Text(text = rs(R.string.lost_title), color = ink, style = LpType.instruction, maxLines = 2, modifier = Modifier.probe("lost_title"))
        }
    }
}

// ---- plan view (S03 / S04 / S07 / S08) -------------------------------------------------------

@Composable
private fun ColumnScope.PlanView(state: PlannerUiState, onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    val holeOpen = state.popup == Popup.HOLE_PLAN
    Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically) {
        Text(text = rs(if (holeOpen) R.string.title_hole_open else R.string.title), color = ink, style = LpType.title, maxLines = 1, modifier = Modifier.weight(1f).probe("title"))
        Key(name = "settings", icon = R.drawable.ic_settings, description = rs(R.string.action_settings), enabled = true) { onEvent(PlannerEvent.SettingsClicked) }
    }
    Text(text = statusLine(state), color = grey, style = LpType.status, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("status"))
    if (state.heat) {
        Legend()
    } else {
        Text(text = rs(if (state.lamps.isEmpty()) R.string.hint_empty else R.string.hint_planning), color = ink, style = LpType.hint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("hint"))
    }
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val canHeat = state.lamps.isNotEmpty() && !holeOpen
        val canHole = state.lamps.isNotEmpty() && !holeOpen
        Box(modifier = Modifier.weight(1f)) { Toggle(name = "heat", icon = R.drawable.ic_heat, text = rs(R.string.btn_heat), on = state.heat, enabled = canHeat) { onEvent(PlannerEvent.HeatToggled) } }
        Box(modifier = Modifier.weight(1f)) { Toggle(name = "hole", icon = R.drawable.ic_plan, text = rs(R.string.btn_hole), on = false, enabled = canHole) { onEvent(PlannerEvent.HolePlanClicked) } }
    }
    Footer(state, onEvent)
}

@Composable
private fun statusLine(state: PlannerUiState): String {
    val n = state.lamps.size
    val h = state.ceilingHeight
    return when {
        h == null -> rs(R.string.status_lamps_no_ceiling, n)
        state.room.ceilingIsManual -> rs(R.string.status_lamps_manual, n, Units.m(h))
        else -> rs(R.string.status_lamps, n, Units.m(h))
    }
}

/** The 4-cell legend of the brightness map (design `panels.main.content.heat`). */
@Composable
private fun Legend() {
    val grey = PicoTheme.colorScheme.labelSecondary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.probe("legend")) {
        Row(modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LpColors.Heat1))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LpColors.Heat2))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LpColors.Heat3))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(LpColors.Heat4))
        }
        Text(text = rs(R.string.legend_title), color = PicoTheme.colorScheme.labelPrimary, style = LpType.status, maxLines = 1)
        Text(text = rs(R.string.legend_note), color = grey, style = LpType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- lamp view (S05 / S06) -----------------------------------------------------------------------

@Composable
private fun ColumnScope.LampView(state: PlannerUiState, onEvent: (PlannerEvent) -> Unit) {
    val lamp = state.selectedLamp ?: return
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    val dragging = state.dragging != null
    val width = (PanelSizes.MAIN_W - 32f).dp
    Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = rs(R.string.lamp_title, lamp.no), color = ink, style = LpType.title, maxLines = 1, modifier = Modifier.weight(1f).probe("lamp_title"))
        Key(name = "delete", icon = R.drawable.ic_trash, description = rs(R.string.action_delete), enabled = !dragging) { onEvent(PlannerEvent.DeleteClicked) }
        Key(name = "done", icon = R.drawable.ic_check, description = rs(R.string.action_done), enabled = !dragging, accent = true) { onEvent(PlannerEvent.DoneClicked) }
    }
    Column(modifier = Modifier.alpha(if (dragging) PanelSizes.DISABLED_ALPHA else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Segments(items = Beam.entries, selected = lamp.beam, width = width, enabled = !dragging, name = { "beam_${it.tag}" }, onSelect = { onEvent(PlannerEvent.BeamSelected(it)) }) { b ->
            Text(text = beamName(b), color = ink, style = LpType.segment, maxLines = 1, softWrap = false)
        }
        Text(
            text = rs(if (lamp.beam.general) R.string.beam_explain_general else R.string.beam_explain_accent, lamp.beam.deg.toInt()),
            color = grey,
            style = LpType.label,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp).probe("beam_explain"),
        )
        Segments(items = Output.entries, selected = lamp.output, width = width, enabled = !dragging, name = { "output_${it.tag}" }, onSelect = { onEvent(PlannerEvent.OutputSelected(it)) }) { o ->
            Text(text = rs(when (o) { Output.S -> R.string.output_s; Output.M -> R.string.output_m; Output.L -> R.string.output_l }), color = ink, style = LpType.segment, maxLines = 1, softWrap = false)
        }
        Segments(items = Cct.entries, selected = lamp.cct, width = width, enabled = !dragging, name = { "cct_${it.tag}" }, onSelect = { onEvent(PlannerEvent.CctSelected(it)) }) { c ->
            Text(text = rs(when (c) { Cct.K2700 -> R.string.cct_2700; Cct.K3000 -> R.string.cct_3000; Cct.K4000 -> R.string.cct_4000 }), color = ink, style = LpType.segment, maxLines = 1, softWrap = false)
        }
    }
    Spacer(modifier = Modifier.weight(1f))
    val info = state.light.info(lamp.id)
    val wall = info?.wallDistance
    val near = info?.nearestLampDistance
    val line =
        when {
            dragging -> rs(R.string.dragging, wall?.let { Units.m(it) } ?: "–")
            wall != null && near != null -> rs(R.string.lamp_distances, Units.m(wall), Units.m(near))
            wall != null -> rs(R.string.lamp_distances_wall, Units.m(wall))
            else -> rs(R.string.lamp_distances_none)
        }
    Text(text = line, color = ink, style = LpType.status, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("lamp_distances"))
    Text(text = rs(R.string.hint_stick), color = grey, style = LpType.label, maxLines = 1, modifier = Modifier.probe("hint_stick"))
    Footer(state, onEvent)
}

// ---- footer: inline bar + disclaimer -------------------------------------------------------------

@Composable
private fun ColumnScope.Footer(state: PlannerUiState, onEvent: (PlannerEvent) -> Unit) {
    val bar = state.bar
    if (bar != null) InlineBarRow(bar, onEvent)
    Text(text = rs(R.string.disclaimer), color = PicoTheme.colorScheme.labelTertiary, style = LpType.label, maxLines = 1, modifier = Modifier.probe("disclaimer"))
}

@Composable
private fun InlineBarRow(bar: InlineBar, onEvent: (PlannerEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val (text, action, event) =
        when (bar.kind) {
            BarKind.DELETED -> Triple(rs(R.string.bar_deleted, bar.n), rs(R.string.action_undo), PlannerEvent.UndoClicked as PlannerEvent)
            BarKind.MAX_LAMPS -> Triple(rs(R.string.bar_max), null, null)
            BarKind.NO_FURNITURE -> Triple(rs(R.string.bar_no_furniture), null, null)
            BarKind.RESTORED -> Triple(rs(R.string.bar_restored, bar.n), null, null)
            BarKind.RESTORED_PARTIAL -> Triple(rs(R.string.bar_restored_partial, bar.n), null, null)
            BarKind.CEILING_FOUND -> Triple(rs(R.string.bar_ceiling_found, Units.m(bar.value)), rs(R.string.action_use_detected), PlannerEvent.UseDetectedCeiling as PlannerEvent)
        }
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(start = 12.dp, end = 4.dp).probe("bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, color = ink, style = LpType.status, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null && event != null) {
            Button(
                onClick = { onEvent(event) },
                modifier = Modifier.probe("bar_action"),
                size = ButtonDefaults.Regular.copy(height = 28.dp, minWidth = 64.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = LpColors.TapeInk),
            ) { Text(text = action, color = LpColors.TapeInk, style = LpType.label, maxLines = 1) }
        }
    }
}

// ---- shared controls -------------------------------------------------------------------------

@Composable
internal fun Primary(name: String, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val container = if (danger) PicoTheme.colorScheme.error else PicoTheme.colorScheme.interaction
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().probe(name),
        size = ButtonDefaults.Regular.copy(height = RowH),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = LpColors.TapeInk),
    ) { Text(text = text, color = LpColors.TapeInk, maxLines = 1) }
}

@Composable
internal fun Secondary(name: String, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().probe(name),
        size = ButtonDefaults.Regular.copy(height = RowH),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) { Text(text = text, color = ink, maxLines = 1) }
}

/** A toggle-looking button (the heat switch; the SpatialUI toggle button's Kotlin name was not confirmed, AGENTS.md). */
@Composable
private fun Toggle(name: String, icon: Int, text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (on) LpColors.TapeInk else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().probe(name),
        size = ButtonDefaults.Regular.copy(height = RowH),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = if (on && enabled) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, color = ink, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun Key(name: String, icon: Int, description: String, enabled: Boolean, accent: Boolean = false, width: Dp = KeyW, onClick: () -> Unit) {
    val ink = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (accent) LpColors.TapeInk else PicoTheme.colorScheme.labelPrimary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (accent && enabled) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = width, height = 40.dp),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp), tint = ink) }
}

@Composable
internal fun <T> Segments(items: List<T>, selected: T, width: Dp, enabled: Boolean, name: (T) -> String, onSelect: (T) -> Unit, title: @Composable (T) -> Unit) {
    // the control keeps about 4 dp of its own padding on each side (Furniture Fit's menu measurement)
    val itemW = (width - 8.dp) / items.size
    SegmentControl(modifier = Modifier.width(width).height(40.dp).alpha(if (enabled) 1f else PanelSizes.DISABLED_ALPHA), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { if (enabled) onSelect(item) },
                modifier = Modifier.width(itemW).fillMaxHeight().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = { title(item) },
            )
        }
    }
}
