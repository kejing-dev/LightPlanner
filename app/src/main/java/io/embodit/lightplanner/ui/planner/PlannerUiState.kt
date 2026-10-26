package io.embodit.lightplanner.ui.planner

import io.embodit.lightplanner.domain.Beam
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.Lamp
import io.embodit.lightplanner.domain.LampParams
import io.embodit.lightplanner.domain.LanguagePref
import io.embodit.lightplanner.domain.LightResult
import io.embodit.lightplanner.domain.Output
import io.embodit.lightplanner.domain.PlaneSurface
import io.embodit.lightplanner.domain.RoomModel
import io.embodit.lightplanner.domain.Settings
import io.embodit.lightplanner.domain.Vec3

/** The main phases (design `states`): S00 LOADING, S01 WELCOME, S02 FINDING, S03..S07 PLAN, S13 NO_CEILING / MANUAL_HEIGHT, S12 MISMATCH, S16 NO_PERMISSION. */
enum class Phase { LOADING, WELCOME, FINDING, PLAN, NO_CEILING, MANUAL_HEIGHT, MISMATCH, NO_PERMISSION }

/** The two attached popup slots (design `panels.settings` / `panels.holePlan`), mutually exclusive. */
enum class Popup { NONE, SETTINGS, HOLE_PLAN }

/** Inline bars on the main panel (design `panels.inlineBars`). Sticky ones stay until their condition ends. */
enum class BarKind(val sticky: Boolean) {
    DELETED(false),
    MAX_LAMPS(false),
    NO_FURNITURE(true),
    RESTORED(false),
    RESTORED_PARTIAL(false),
    CEILING_FOUND(true),
}

data class InlineBar(val kind: BarKind, val id: Int, val n: Int = 0, val value: Float = 0f)

/** The manual ceiling heights offered in S13 (design R2-6: 3.3 m added). */
val MANUAL_HEIGHTS = listOf(2.4f, 2.6f, 2.8f, 3.0f, 3.3f)

/**
 * The whole UI state (design `states`, `panels`). Pure data: panels and the scene read it; only the
 * ViewModel writes it.
 */
data class PlannerUiState(
    val phase: Phase = Phase.LOADING,
    val room: RoomModel = RoomModel.EMPTY,
    val lamps: List<Lamp> = emptyList(),
    val selected: String? = null,
    val dragging: String? = null,
    val heat: Boolean = false,
    val popup: Popup = Popup.NONE,
    /** The clear confirmation inside the settings slot (S10). */
    val clearConfirm: Boolean = false,
    /** The restart confirmation inside the S12 panel. */
    val restartConfirm: Boolean = false,
    val bar: InlineBar? = null,
    val trackingLost: Boolean = false,
    val light: LightResult = LightResult.EMPTY,
    val lastParams: LampParams = LampParams(),
    /** The height picked in the manual-height step (S13 -> T15). */
    val manualPick: Float = 2.6f,
    /** The lamp deleted a moment ago, kept for undo (design T7). */
    val undo: Lamp? = null,
    val settings: Settings = Settings(),
    /** S12: the "look again" retry is running. */
    val retrying: Boolean = false,
    /** S12: how many lamps the saved plan holds (the lamps list is empty there). */
    val savedCount: Int = 0,
    /** Debug: stay in S00. */
    val holdLoading: Boolean = false,
    /** Debug: furniture counts as absent (S17). */
    val debugNoSurface: Boolean = false,
) {
    val inPlan: Boolean
        get() = phase == Phase.PLAN

    /** The scene takes placing pinches and drags (design `input`). */
    val placingEnabled: Boolean
        get() = inPlan && !trackingLost && popup == Popup.NONE

    val selectedLamp: Lamp?
        get() = selected?.let { id -> lamps.firstOrNull { it.id == id } }

    val ceilingHeight: Float?
        get() = room.ceilingHeight()

    val noFurniture: Boolean
        get() = inPlan && room.hasCeiling && (debugNoSurface || !room.hasFurniture)

    fun lamp(id: String): Lamp? = lamps.firstOrNull { it.id == id }

    /** The design state the app is in now (design `states`), for the log and the QA station. */
    val designState: String
        get() =
            when {
                phase == Phase.NO_PERMISSION -> "S16"
                phase == Phase.LOADING -> "S00"
                phase == Phase.WELCOME -> "S01"
                phase == Phase.FINDING -> "S02"
                phase == Phase.NO_CEILING || phase == Phase.MANUAL_HEIGHT -> "S13"
                phase == Phase.MISMATCH -> "S12"
                trackingLost -> "S15"
                popup == Popup.HOLE_PLAN -> "S08"
                popup == Popup.SETTINGS && clearConfirm -> "S10"
                popup == Popup.SETTINGS -> "S09"
                bar?.kind == BarKind.RESTORED || bar?.kind == BarKind.RESTORED_PARTIAL -> "S11"
                bar?.kind == BarKind.MAX_LAMPS -> "S14"
                dragging != null -> "S06"
                selected != null -> "S05"
                bar?.kind == BarKind.NO_FURNITURE && lamps.isEmpty() -> "S17"
                heat -> "S07"
                lamps.isEmpty() -> "S03"
                else -> "S04"
            }
}

/** What a pinch in the room landed on (the scene resolves the collider; the ViewModel applies the rules). */
sealed interface Hit {
    data class Lamp(val id: String) : Hit

    data class Handle(val id: String) : Hit

    /** A detected plane at [point]. */
    data class Surface(val point: Vec3, val plane: PlaneSurface) : Hit

    /** The backstop or anything else. */
    data object Nothing : Hit
}

/** Events from the panels, the scene and the platform. */
sealed interface PlannerEvent {
    data object SceneReady : PlannerEvent

    data class PlanesChanged(val room: RoomModel) : PlannerEvent

    data class TrackingChanged(val lost: Boolean) : PlannerEvent

    data object PlanesUnavailable : PlannerEvent

    data object StartClicked : PlannerEvent

    data class Pinched(val hit: Hit) : PlannerEvent

    data class DragStarted(val id: String) : PlannerEvent

    /** Live position while dragging (<= 5 Hz, design `limits`). */
    data class DragMoved(val id: String, val xz: io.embodit.lightplanner.domain.P2) : PlannerEvent

    /** [xz] null: cancelled, the lamp goes back to where it was pressed. */
    data class DragEnded(val id: String, val xz: io.embodit.lightplanner.domain.P2?) : PlannerEvent

    /** The thumbstick nudged the selected lamp (taste profile D, adapted: a downlight has no facing). */
    data class StickMoved(val id: String, val xz: io.embodit.lightplanner.domain.P2) : PlannerEvent

    data class BeamSelected(val beam: Beam) : PlannerEvent

    data class OutputSelected(val output: Output) : PlannerEvent

    data class CctSelected(val cct: Cct) : PlannerEvent

    data object DeleteClicked : PlannerEvent

    data object UndoClicked : PlannerEvent

    data object DoneClicked : PlannerEvent

    data object HeatToggled : PlannerEvent

    data object HolePlanClicked : PlannerEvent

    data object SettingsClicked : PlannerEvent

    data object PopupClosed : PlannerEvent

    data class LanguageSelected(val language: LanguagePref) : PlannerEvent

    data class ShowSpacingToggled(val on: Boolean) : PlannerEvent

    data object ClearClicked : PlannerEvent

    data object ClearConfirmed : PlannerEvent

    data object ClearCancelled : PlannerEvent

    data object RetryFindClicked : PlannerEvent

    data object ManualHeightClicked : PlannerEvent

    data class ManualHeightPicked(val height: Float) : PlannerEvent

    data object UseManualHeight : PlannerEvent

    data object UseDetectedCeiling : PlannerEvent

    data object MismatchRetry : PlannerEvent

    data object MismatchRestart : PlannerEvent

    data object RestartConfirmed : PlannerEvent

    data object RestartCancelled : PlannerEvent

    data object PermissionClicked : PlannerEvent

    data object BarClosed : PlannerEvent
}

sealed interface PlannerEffect {
    /** A short system sound: "place", "select", "reject", "snap". */
    data class Feedback(val kind: String) : PlannerEffect
}
