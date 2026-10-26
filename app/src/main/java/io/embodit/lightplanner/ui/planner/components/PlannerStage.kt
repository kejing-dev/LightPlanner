package io.embodit.lightplanner.ui.planner.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.lightplanner.platform.LpLog
import io.embodit.lightplanner.ui.planner.PlannerEvent
import io.embodit.lightplanner.ui.planner.PlannerUiState
import io.embodit.lightplanner.ui.planner.Popup
import io.embodit.lightplanner.ui.planner.debug.LocalProbePanel
import io.embodit.lightplanner.ui.planner.debug.Probe
import io.embodit.lightplanner.ui.planner.scene.PanelIds
import io.embodit.lightplanner.ui.planner.scene.PlannerScene

/**
 * The Stage content: one full-size SpatialView with the lamps, the room's pinch targets and the panel
 * pool. Every attachment panel is declared once with a fixed size, shown or not ([PlannerScene] parents
 * and places them). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler).
 *
 * Window model: corner panel plus two attached slots (settings, hole plan), design `container.windowModel`.
 */
@Composable
internal fun PlannerStage(state: PlannerUiState, scene: PlannerScene, onEvent: (PlannerEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.MAIN) {
                PoolPanel(PanelIds.MAIN, scene, pressed = { scene.onPanelPointer() }) { inView -> MainPanel(state = if (inView) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SETTINGS) {
                PoolPanel(PanelIds.SETTINGS, scene, pressed = { scene.onPanelPointer() }) { inView -> SettingsPanel(state = if (inView && state.popup == Popup.SETTINGS) state else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.HOLE) {
                PoolPanel(PanelIds.HOLE, scene, pressed = { scene.onPanelPointer() }) { inView -> HolePlanPanel(state = if (inView && state.popup == Popup.HOLE_PLAN) state else null, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel: [content] gets `inView` (the panel has been moved into view; a pooled panel
 * draws only then, Spatial Ruler finding 3). Debug builds log every press as `PTR panel=<id> ev=`.
 */
@Composable
private fun PoolPanel(id: String, scene: PlannerScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            LpLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
