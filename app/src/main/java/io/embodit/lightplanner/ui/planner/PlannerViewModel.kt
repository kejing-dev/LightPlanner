package io.embodit.lightplanner.ui.planner

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.lightplanner.data.AnchorGateway
import io.embodit.lightplanner.data.AnchorOutcome
import io.embodit.lightplanner.data.AnchorRecord
import io.embodit.lightplanner.data.PlanStore
import io.embodit.lightplanner.domain.Grade
import io.embodit.lightplanner.domain.Lamp
import io.embodit.lightplanner.domain.LampParams
import io.embodit.lightplanner.domain.LightField
import io.embodit.lightplanner.domain.LightMath
import io.embodit.lightplanner.domain.LightResult
import io.embodit.lightplanner.domain.P2
import io.embodit.lightplanner.domain.Plan
import io.embodit.lightplanner.domain.PlaneSurface
import io.embodit.lightplanner.domain.RoomModel
import io.embodit.lightplanner.domain.SavedLamp
import io.embodit.lightplanner.domain.Settings
import io.embodit.lightplanner.domain.Vec3
import io.embodit.lightplanner.platform.LpGraph
import io.embodit.lightplanner.platform.LpLog
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The whole behaviour of the app (design `states`, `interactions`, `lightModel`): events from the panels
 * and the scene come in through [onEvent], the state goes out through [state]. The light computation
 * runs on a worker thread and lands as [LightResult]. Pure Kotlin apart from the injected services.
 */
class PlannerViewModel(
    private val anchors: AnchorGateway,
    private val store: PlanStore,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(PlannerUiState(settings = store.loadSettings()))
    val state: StateFlow<PlannerUiState> = _state

    private val _effects = MutableSharedFlow<PlannerEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<PlannerEffect> = _effects

    /** Timed inline bars stay 60 s (debug `bar long`). */
    @Volatile var longBars = false

    /** Debug: stay in S02 whatever the planes say. */
    @Volatile var debugHoldFinding = false

    private var saved: Plan? = null
    private var nextNo = 1
    private var barSeq = 0
    private val anchorRecords = HashMap<String, AnchorRecord>()
    private var barJob: Job? = null
    private var saveJob: Job? = null
    private var findJob: Job? = null
    private var restoreJob: Job? = null
    private var lightJob: Job? = null
    private var lightDirty = false
    private var undoJob: Job? = null
    private var planesJob: Job? = null
    private var lastPlanesComputeMs = 0L
    private var lastLogged: String? = null
    private var lastLoggedLine: String? = null
    private var dragOrigin: Lamp? = null
    private var permissionDenied = false
    private var ceilingOffered = false
    private val anchorJobs = HashMap<String, Job>()

    init {
        saved = store.loadPlan()
        nextNo = saved?.nextNo ?: 1
        saved?.manualCeiling?.let { h -> update { it.copy(room = it.room.copy(manualCeiling = h)) } }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: PlannerEvent) {
        val s = _state.value
        when (e) {
            PlannerEvent.SceneReady -> onSceneReady()
            is PlannerEvent.PlanesChanged -> onPlanes(e.room)
            is PlannerEvent.TrackingChanged -> onTracking(e.lost)
            PlannerEvent.PlanesUnavailable -> {
                permissionDenied = true
                if (s.phase == Phase.FINDING || s.phase == Phase.LOADING) update { it.copy(phase = Phase.NO_PERMISSION) }
            }
            PlannerEvent.StartClicked -> if (s.phase == Phase.WELCOME) {
                settings { it.copy(firstRunDone = true) }
                enterFinding()
            }
            is PlannerEvent.Pinched -> onPinched(e.hit)
            is PlannerEvent.DragStarted -> onDragStarted(e.id)
            is PlannerEvent.DragMoved -> if (s.dragging == e.id) moveLamp(e.id, e.xz, save = false)
            is PlannerEvent.DragEnded -> onDragEnded(e)
            is PlannerEvent.StickMoved -> if (s.placingEnabled && s.lamp(e.id) != null) moveLamp(e.id, e.xz, save = true, via = "stick")
            is PlannerEvent.BeamSelected -> editSelected { it.copy(beam = e.beam) }
            is PlannerEvent.OutputSelected -> editSelected { it.copy(output = e.output) }
            is PlannerEvent.CctSelected -> editSelected { it.copy(cct = e.cct) }
            PlannerEvent.DeleteClicked -> deleteSelected()
            PlannerEvent.UndoClicked -> undoDelete()
            PlannerEvent.DoneClicked -> if (s.selected != null) update { it.copy(selected = null, dragging = null) }
            PlannerEvent.HeatToggled -> if (s.inPlan && s.popup != Popup.HOLE_PLAN) update { it.copy(heat = !it.heat) }
            PlannerEvent.HolePlanClicked -> if (s.inPlan && s.lamps.isNotEmpty()) update { it.copy(popup = if (it.popup == Popup.HOLE_PLAN) Popup.NONE else Popup.HOLE_PLAN, clearConfirm = false) }
            PlannerEvent.SettingsClicked -> if (s.phase != Phase.LOADING) update { it.copy(popup = if (it.popup == Popup.SETTINGS) Popup.NONE else Popup.SETTINGS, clearConfirm = false) }
            PlannerEvent.PopupClosed -> update { it.copy(popup = Popup.NONE, clearConfirm = false) }
            is PlannerEvent.LanguageSelected -> settings { it.copy(language = e.language) }
            is PlannerEvent.ShowSpacingToggled -> settings { it.copy(showSpacing = e.on) }
            PlannerEvent.ClearClicked -> if (s.popup == Popup.SETTINGS) update { it.copy(clearConfirm = true) }
            PlannerEvent.ClearConfirmed -> clearAll(backToFinding = false)
            PlannerEvent.ClearCancelled -> update { it.copy(clearConfirm = false) }
            PlannerEvent.RetryFindClicked -> if (s.phase == Phase.NO_CEILING || s.phase == Phase.MANUAL_HEIGHT) enterFinding()
            PlannerEvent.ManualHeightClicked -> if (s.phase == Phase.NO_CEILING) update { it.copy(phase = Phase.MANUAL_HEIGHT) }
            is PlannerEvent.ManualHeightPicked -> update { it.copy(manualPick = e.height) }
            PlannerEvent.UseManualHeight -> useManualHeight()
            PlannerEvent.UseDetectedCeiling -> {
                update { it.copy(room = it.room.copy(manualCeiling = null), bar = if (it.bar?.kind == BarKind.CEILING_FOUND) null else it.bar) }
                relevel()
                recompute()
                scheduleSave()
            }
            PlannerEvent.MismatchRetry -> if (s.phase == Phase.MISMATCH && !s.retrying) startRestore(RESTORE_RETRY_MS)
            PlannerEvent.MismatchRestart -> if (s.phase == Phase.MISMATCH) update { it.copy(restartConfirm = true) }
            PlannerEvent.RestartConfirmed -> if (s.phase == Phase.MISMATCH) clearAll(backToFinding = true)
            PlannerEvent.RestartCancelled -> update { it.copy(restartConfirm = false) }
            PlannerEvent.PermissionClicked -> if (s.phase == Phase.NO_PERMISSION) {
                permissionDenied = false
                enterFinding()
            }
            PlannerEvent.BarClosed -> update { it.copy(bar = if (it.bar?.kind?.sticky == true) it.bar else null) }
        }
    }

    // ---- start-up -------------------------------------------------------------------------

    private fun onSceneReady() {
        val s = _state.value
        if (s.phase != Phase.LOADING || s.holdLoading) return
        when {
            permissionDenied -> update { it.copy(phase = Phase.NO_PERMISSION) }
            !s.settings.firstRunDone -> update { it.copy(phase = Phase.WELCOME) }
            saved?.lamps?.isNotEmpty() == true -> startRestore(RESTORE_FIRST_MS)
            saved?.manualCeiling != null -> enterPlan()
            else -> enterFinding()
        }
    }

    /** Debug: leave S00 now. */
    fun debugLeaveLoading() {
        if (_state.value.phase == Phase.LOADING) {
            update { it.copy(holdLoading = false) }
            onSceneReady()
        }
    }

    private fun enterFinding() {
        findJob?.cancel()
        update { it.copy(phase = Phase.FINDING, popup = Popup.NONE, restartConfirm = false) }
        if (_state.value.room.ceilings.isNotEmpty() && !debugHoldFinding) {
            enterPlan()
            return
        }
        findJob =
            scope.launch {
                delay(FIND_TIMEOUT_MS)
                if (_state.value.phase == Phase.FINDING) {
                    LpLog.i("CEILING not found after ${FIND_TIMEOUT_MS / 1000} s")
                    update { it.copy(phase = Phase.NO_CEILING) }
                }
            }
    }

    private fun enterPlan() {
        findJob?.cancel()
        update { it.copy(phase = Phase.PLAN, popup = Popup.NONE, restartConfirm = false, retrying = false) }
        relevel()
        recompute()
        refreshFurnitureBar()
    }

    private fun useManualHeight() {
        val s = _state.value
        if (s.phase != Phase.MANUAL_HEIGHT) return
        if (!s.room.hasFloor) {
            LpLog.w("MANUAL height without a floor plane; waiting")
        }
        update { it.copy(room = it.room.copy(manualCeiling = it.manualPick)) }
        LpLog.i("CEILING manual h=${fmt(s.manualPick)}")
        scheduleSave()
        enterPlan()
    }

    // ---- planes / tracking --------------------------------------------------------------------

    private fun onPlanes(incoming: RoomModel) {
        val s = _state.value
        val room = RoomModel(incoming.planes, s.room.manualCeiling)
        val hadCeiling = s.room.ceilings.isNotEmpty()
        update { it.copy(room = room) }
        if (s.phase == Phase.FINDING && room.ceilings.isNotEmpty() && !debugHoldFinding) {
            LpLog.i("CEILING found h=${room.ceilingHeight()?.let { fmt(it) }} planes=${room.planes.size}")
            enterPlan()
            return
        }
        if (s.phase == Phase.NO_CEILING && room.ceilings.isNotEmpty() && !debugHoldFinding) {
            enterPlan()
            return
        }
        if (!s.inPlan) return
        // a real ceiling appears while a manual height is in use: offer it, do not switch by itself (design R2-6)
        if (room.manualCeiling != null && room.ceilings.isNotEmpty() && !ceilingOffered) {
            val detected = room.detectedCeilingHeight()
            if (detected != null && abs(detected - room.manualCeiling) > CEILING_OFFER_DIFF_M) {
                ceilingOffered = true
                update { it.copy(bar = InlineBar(BarKind.CEILING_FOUND, ++barSeq, value = detected)) }
            }
        }
        if (!hadCeiling && room.ceilings.isNotEmpty()) relevel()
        refreshFurnitureBar()
        // planes keep being re-reported: recompute the light at most every 2 s (design `lifecycle`)
        val now = SystemClock.elapsedRealtime()
        planesJob?.cancel()
        val wait = (PLANES_RECOMPUTE_MS - (now - lastPlanesComputeMs)).coerceAtLeast(0L)
        planesJob =
            scope.launch {
                delay(wait)
                lastPlanesComputeMs = SystemClock.elapsedRealtime()
                relevel()
                recompute()
            }
    }

    private fun refreshFurnitureBar() {
        val s = _state.value
        val show = s.noFurniture
        val bar = s.bar
        if (show && bar == null) update { it.copy(bar = InlineBar(BarKind.NO_FURNITURE, ++barSeq)) }
        else if (!show && bar?.kind == BarKind.NO_FURNITURE) update { it.copy(bar = null) }
    }

    private fun onTracking(lost: Boolean) {
        val s = _state.value
        if (lost == s.trackingLost) return
        if (lost) {
            LpLog.i("TRACK lost")
            val origin = dragOrigin
            update { st ->
                val lamps = if (origin != null && st.dragging == origin.id) st.lamps.map { if (it.id == origin.id) origin else it } else st.lamps
                st.copy(trackingLost = true, dragging = null, lamps = lamps)
            }
            dragOrigin = null
        } else {
            LpLog.i("TRACK recovered")
            update { it.copy(trackingLost = false) }
        }
    }

    // ---- pinches ------------------------------------------------------------------------------

    private fun onPinched(hit: Hit) {
        val s = _state.value
        if (s.popup != Popup.NONE) {
            // a pinch in the room only closes the popup (design `container.notes`); the scene drops the next 0.4 s
            update { it.copy(popup = Popup.NONE, clearConfirm = false) }
            LpLog.i("TAP consumed reason=popup")
            return
        }
        if (!s.inPlan || s.trackingLost) {
            LpLog.i("TAP ignored state=${s.designState}")
            return
        }
        when (hit) {
            is Hit.Lamp -> select(hit.id, "ring")
            is Hit.Handle -> select(hit.id, "handle")
            is Hit.Surface -> onSurfacePinched(hit.point, hit.plane)
            Hit.Nothing -> reject("nothing")
        }
    }

    private fun onSurfacePinched(point: Vec3, plane: PlaneSurface) {
        val s = _state.value
        val xz = P2.of(point)
        // within 12 cm of a lamp's projection: select it (design `selectRule`)
        val near = s.lamps.minByOrNull { (it.xz - xz).length }
        if (near != null && (near.xz - xz).length < SELECT_M) {
            select(near.id, "projection")
            return
        }
        val room = s.room
        val isCeiling = room.isCeiling(plane)
        if (!isCeiling && plane.isVertical) {
            reject("wall")
            return
        }
        if (!isCeiling && !plane.isHorizontal) {
            reject("oblique")
            return
        }
        val ceilY = if (isCeiling) plane.centroid.y else room.ceilingYAt(xz)
        if (ceilY == null || (!isCeiling && ceilY <= point.y + 0.2f)) {
            reject("no-ceiling-above")
            return
        }
        if (s.lamps.size >= LightMath.MAX_LAMPS) {
            LpLog.i("PLACE refused reason=max")
            timedBar(InlineBar(BarKind.MAX_LAMPS, ++barSeq))
            return
        }
        val lamp = Lamp(id = UUID.randomUUID().toString().take(8), no = nextNo++, pos = Vec3(xz.x, ceilY, xz.z), beam = s.lastParams.beam, output = s.lastParams.output, cct = s.lastParams.cct)
        update { it.copy(lamps = it.lamps + lamp, selected = lamp.id, undo = null, bar = if (it.bar?.kind == BarKind.DELETED) null else it.bar) }
        LpLog.i("PLACE id=${lamp.id} no=${lamp.no} at=${LpLog.pos(lamp.pos)} from=${if (isCeiling) "ceiling" else plane.semantic} hit=${LpLog.pos(point)} beam=${lamp.beam.tag} out=${lamp.output.tag} cct=${lamp.cct.tag}")
        _effects.tryEmit(PlannerEffect.Feedback("place"))
        createAnchor(lamp)
        recompute()
        scheduleSave()
    }

    private fun select(id: String, via: String) {
        val lamp = _state.value.lamp(id) ?: return
        update { it.copy(selected = id, lastParams = lamp.params) }
        LpLog.i("SELECT id=$id no=${lamp.no} via=$via")
        _effects.tryEmit(PlannerEffect.Feedback("select"))
    }

    private fun reject(reason: String) {
        LpLog.i("PLACE rejected reason=$reason")
        _effects.tryEmit(PlannerEffect.Feedback("reject"))
    }

    // ---- drag / stick -------------------------------------------------------------------------

    private fun onDragStarted(id: String) {
        val s = _state.value
        if (!s.placingEnabled) return
        val lamp = s.lamp(id) ?: return
        dragOrigin = lamp
        update { it.copy(dragging = id, selected = id, lastParams = lamp.params) }
    }

    private fun onDragEnded(e: PlannerEvent.DragEnded) {
        val s = _state.value
        if (s.dragging != e.id) {
            update { it.copy(dragging = null) }
            return
        }
        val origin = dragOrigin
        dragOrigin = null
        if (e.xz == null) {
            // cancelled: back to where it was pressed (design S06)
            if (origin != null) update { st -> st.copy(dragging = null, lamps = st.lamps.map { if (it.id == origin.id) origin else it }) }
            else update { it.copy(dragging = null) }
            recompute()
            return
        }
        update { it.copy(dragging = null) }
        moveLamp(e.id, e.xz, save = true, via = "drag")
    }

    private fun moveLamp(id: String, xzIn: P2, save: Boolean, via: String = "live") {
        val s = _state.value
        val lamp = s.lamp(id) ?: return
        val xz = s.room.keepOffWalls(xzIn, WALL_INSET_M)
        val y = s.room.ceilingYAt(xz) ?: lamp.pos.y
        val moved = lamp.copy(pos = Vec3(xz.x, y, xz.z))
        update { st -> st.copy(lamps = st.lamps.map { if (it.id == id) moved else it }) }
        recompute()
        if (save) {
            LpLog.i("MOVE id=$id via=$via at=${LpLog.pos(moved.pos)}")
            refreshAnchor(moved)
            scheduleSave()
        }
    }

    // ---- lamp parameters, delete, undo -------------------------------------------------------------

    private fun editSelected(f: (Lamp) -> Lamp) {
        val s = _state.value
        val id = s.selected ?: return
        val lamp = s.lamp(id) ?: return
        val next = f(lamp)
        update { st -> st.copy(lamps = st.lamps.map { if (it.id == id) next else it }, lastParams = next.params) }
        LpLog.i("EDIT id=$id beam=${next.beam.tag} out=${next.output.tag} cct=${next.cct.tag}")
        recompute()
        scheduleSave()
    }

    private fun deleteSelected() {
        val s = _state.value
        val id = s.selected ?: return
        val lamp = s.lamp(id) ?: return
        update { st -> st.copy(lamps = st.lamps.filter { it.id != id }, selected = null, dragging = null, undo = lamp) }
        LpLog.i("DELETE id=$id no=${lamp.no}")
        timedBar(InlineBar(BarKind.DELETED, ++barSeq, n = lamp.no))
        // the anchor stays for the undo window (design `dataModel.plan`)
        undoJob?.cancel()
        undoJob =
            scope.launch {
                delay(if (longBars) 60_000L else UNDO_MS)
                val u = _state.value.undo
                if (u?.id == lamp.id) {
                    update { it.copy(undo = null) }
                    lamp.anchorId?.let { aid -> anchors.remove(aid) }
                    anchorRecords.remove(lamp.id)
                }
            }
        recompute()
        scheduleSave()
    }

    private fun undoDelete() {
        val s = _state.value
        val lamp = s.undo ?: return
        if (s.lamps.size >= LightMath.MAX_LAMPS) return
        undoJob?.cancel()
        update { it.copy(lamps = it.lamps + lamp, undo = null, bar = if (it.bar?.kind == BarKind.DELETED) null else it.bar, selected = lamp.id) }
        LpLog.i("UNDO id=${lamp.id} no=${lamp.no}")
        if (lamp.anchorId == null) createAnchor(lamp)
        recompute()
        scheduleSave()
    }

    private fun clearAll(backToFinding: Boolean) {
        LpLog.i("CLEAR all lamps=${_state.value.lamps.size}")
        saveJob?.cancel()
        undoJob?.cancel()
        restoreJob?.cancel()
        val old = _state.value.lamps + listOfNotNull(_state.value.undo)
        anchorJobs.values.forEach { it.cancel() }
        anchorJobs.clear()
        scope.launch { for (l in old) l.anchorId?.let { anchors.remove(it) } }
        anchorRecords.clear()
        saved = null
        nextNo = 1
        runCatching { store.savePlan(null) }
        update {
            it.copy(
                lamps = emptyList(), selected = null, dragging = null, undo = null, bar = null, heat = false, popup = Popup.NONE, clearConfirm = false,
                restartConfirm = false, retrying = false, light = LightResult.EMPTY, lastParams = LampParams(),
                room = it.room.copy(manualCeiling = if (backToFinding) null else it.room.manualCeiling),
            )
        }
        if (backToFinding) enterFinding() else {
            recompute()
            refreshFurnitureBar()
        }
    }

    // ---- light ---------------------------------------------------------------------------------

    /** Every lamp's y follows the ceiling above it (local soffits, a ceiling that arrives later). */
    private fun relevel() {
        val s = _state.value
        var changed = false
        val lamps =
            s.lamps.map { l ->
                val y = s.room.ceilingYAt(l.xz) ?: return@map l
                if (abs(y - l.pos.y) > 0.005f) {
                    changed = true
                    l.copy(pos = Vec3(l.pos.x, y, l.pos.z))
                } else l
            }
        if (changed) update { it.copy(lamps = lamps) }
    }

    /** Recomputes the light on a worker thread; a call while one runs re-runs once it finishes. */
    fun recompute() {
        if (lightJob?.isActive == true) {
            lightDirty = true
            return
        }
        lightDirty = false
        lightJob =
            scope.launch {
                val s = _state.value
                val previous = s.light.grades.associate { it.receiverId to it.grade }
                val room = s.room
                val lamps = s.lamps
                val result = withContext(Dispatchers.Default) { LightField.compute(lamps, room, previous, { l -> (room.ceilingYAt(l.xz) ?: l.pos.y) - (room.floorY ?: 0f) }) }
                update { it.copy(light = result) }
                if (result.millis > 150) LpLog.w("LIGHT slow ms=${result.millis} samples=${result.sampleCount} fields=${result.fields.size}")
                // this job is still "active" from inside its own body: let go of it before re-running for the changes that came in meanwhile
                lightJob = null
                if (lightDirty) recompute()
            }
    }

    // ---- settings, bars ---------------------------------------------------------------------------

    private fun settings(f: (Settings) -> Settings) {
        val next = f(_state.value.settings)
        store.saveSettings(next)
        update { it.copy(settings = next) }
    }

    private fun timedBar(bar: InlineBar) {
        update { it.copy(bar = bar) }
        barJob?.cancel()
        barJob =
            scope.launch {
                delay(if (longBars) 60_000L else if (bar.kind == BarKind.DELETED || bar.kind == BarKind.RESTORED_PARTIAL) BAR_LONG_MS else BAR_MS)
                update { if (it.bar?.id == bar.id) it.copy(bar = null) else it }
            }
    }

    // ---- anchors and persistence -------------------------------------------------------------------

    /** One world anchor per lamp (design `referenceRepo.reuse`): created at the lamp, remembered by id. */
    private fun createAnchor(lamp: Lamp) {
        anchorJobs[lamp.id]?.cancel()
        anchorJobs[lamp.id] =
            scope.launch {
                when (val r = anchors.create(lamp.pos, "lamp-${lamp.id}")) {
                    is AnchorOutcome.Ok -> {
                        anchorRecords[lamp.id] = r.value
                        update { st -> st.copy(lamps = st.lamps.map { if (it.id == lamp.id) it.copy(anchorId = r.value.id) else it }, undo = if (st.undo?.id == lamp.id) st.undo.copy(anchorId = r.value.id) else st.undo) }
                        LpLog.i("ANCHOR created lamp=${lamp.id} id=${r.value.id.take(8)} at=${LpLog.pos(r.value.pose.position)}")
                        scheduleSave()
                    }
                    is AnchorOutcome.Failed -> LpLog.w("ANCHOR create failed lamp=${lamp.id} code=${r.code} msg=${r.message}")
                }
                anchorJobs.remove(lamp.id)
            }
    }

    /** A lamp that moved more than 50 mm from its anchor gets a new one (design `referenceRepo.reuse`). */
    private fun refreshAnchor(lamp: Lamp) {
        val rec = anchorRecords[lamp.id]
        val aid = lamp.anchorId
        if (aid == null || rec == null) {
            if (anchorJobs[lamp.id]?.isActive != true) createAnchor(lamp)
            return
        }
        if (rec.pose.position.distanceTo(lamp.pos) <= ANCHOR_REBUILD_M) return
        anchorRecords.remove(lamp.id)
        update { st -> st.copy(lamps = st.lamps.map { if (it.id == lamp.id) it.copy(anchorId = null) else it }) }
        scope.launch { anchors.remove(aid) }
        createAnchor(lamp.copy(anchorId = null))
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DELAY_MS)
                saveNow()
            }
    }

    private fun saveNow() {
        val s = _state.value
        val plan =
            Plan(
                lamps = s.lamps.map { l -> SavedLamp(l.id, l.no, l.pos, l.beam, l.output, l.cct, l.anchorId, anchorRecords[l.id]?.pose?.position) },
                manualCeiling = s.room.manualCeiling,
                nextNo = nextNo,
                updatedAt = System.currentTimeMillis(),
            )
        val ok = runCatching { store.savePlan(plan) }.isSuccess
        if (ok) {
            saved = plan
            LpLog.i("SAVE ok lamps=${plan.lamps.size} anchored=${plan.lamps.count { it.anchorId != null }}")
        } else LpLog.w("SAVE failed")
    }

    /**
     * Restore (design S11 / S12, `dataModel.restore`): load the anchors for up to [waitMs]; a lamp whose
     * anchor is found moves with it; one whose anchor is gone follows the shift of a found lamp; none
     * found -> S12.
     */
    private fun startRestore(waitMs: Long) {
        val plan = saved ?: return
        restoreJob?.cancel()
        update { it.copy(retrying = it.phase == Phase.MISMATCH) }
        restoreJob =
            scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                var found: Map<String, AnchorRecord> = emptyMap()
                while (SystemClock.elapsedRealtime() - t0 < waitMs) {
                    val r = anchors.loadAll()
                    if (r is AnchorOutcome.Ok) {
                        val wanted = plan.lamps.mapNotNull { it.anchorId }.toSet()
                        found = r.value.filter { it.id in wanted }.associateBy { it.id }
                        if (found.size == wanted.size && wanted.isNotEmpty()) break
                    }
                    delay(RESTORE_POLL_MS)
                }
                if (found.isEmpty()) {
                    LpLog.i("RESTORE mismatch lamps=${plan.lamps.size}")
                    update { it.copy(phase = Phase.MISMATCH, retrying = false, restartConfirm = false, popup = Popup.NONE, savedCount = plan.lamps.size) }
                    return@launch
                }
                restoreFrom(plan, found)
            }
    }

    private fun restoreFrom(plan: Plan, found: Map<String, AnchorRecord>) {
        var shift: Vec3? = null
        val restored = ArrayList<Lamp>()
        var byAnchor = 0
        for (sl in plan.lamps) {
            val rec = sl.anchorId?.let { found[it] }
            if (rec != null) {
                val d = if (sl.anchorPos != null) rec.pose.position - sl.anchorPos else Vec3.ZERO
                if (shift == null) shift = d
                anchorRecords[sl.id] = rec
                restored += Lamp(sl.id, sl.no, sl.pos + d, sl.beam, sl.output, sl.cct, rec.id)
                byAnchor++
            }
        }
        val fallback = shift ?: Vec3.ZERO
        for (sl in plan.lamps) {
            if (restored.any { it.id == sl.id }) continue
            val lamp = Lamp(sl.id, sl.no, sl.pos + fallback, sl.beam, sl.output, sl.cct, null)
            restored += lamp
        }
        restored.sortBy { it.no }
        nextNo = maxOf(plan.nextNo, (restored.maxOfOrNull { it.no } ?: 0) + 1)
        val partial = byAnchor < restored.size
        update { it.copy(lamps = restored, selected = null, dragging = null, retrying = false, restartConfirm = false) }
        enterPlanAfterRestore()
        for (l in restored) if (l.anchorId == null) createAnchor(l)
        timedBar(InlineBar(if (partial) BarKind.RESTORED_PARTIAL else BarKind.RESTORED, ++barSeq, n = if (partial) restored.size - byAnchor else restored.size))
        LpLog.i("RESTORE ok lamps=${restored.size} byAnchor=$byAnchor shift=${LpLog.pos(fallback)}")
        scheduleSave()
    }

    private fun enterPlanAfterRestore() {
        val s = _state.value
        if (s.room.hasCeiling) enterPlan()
        else {
            // the plan is back but the ceiling is not yet: wait for it (S02), the lamps keep their saved height
            enterFinding()
        }
    }

    // ---- debug seams (driven by the debug receiver only) --------------------------------------

    fun debugSet(f: (PlannerUiState) -> PlannerUiState) {
        update(f)
        recompute()
    }

    fun debugRestore() {
        saved = store.loadPlan()
        if (saved == null || saved!!.lamps.isEmpty()) {
            LpLog.w("CMD restore: nothing saved")
            return
        }
        startRestore(RESTORE_FIRST_MS)
    }

    fun debugMismatch() {
        restoreJob?.cancel()
        if (saved == null || saved!!.lamps.isEmpty()) saved = Plan(_state.value.lamps.map { SavedLamp(it.id, it.no, it.pos, it.beam, it.output, it.cct, it.anchorId, anchorRecords[it.id]?.pose?.position) }, _state.value.room.manualCeiling, nextNo, 0L)
        update { it.copy(phase = Phase.MISMATCH, lamps = emptyList(), selected = null, dragging = null, popup = Popup.NONE, retrying = false, restartConfirm = false, light = LightResult.EMPTY, savedCount = saved?.lamps?.size ?: 0) }
    }

    fun debugBar(kind: BarKind, n: Int = 0, value: Float = 0f) {
        val bar = InlineBar(kind, ++barSeq, n, value)
        if (kind.sticky) update { it.copy(bar = bar) } else timedBar(bar)
    }

    /** Debug `seedLamps`: a set of lamps laid out from the room (design `verification.debugHooks`). */
    fun debugSeed(spots: List<Pair<P2, LampParams>>) {
        val s = _state.value
        val lamps = ArrayList<Lamp>()
        for ((xz, params) in spots) {
            if (lamps.size + s.lamps.size >= LightMath.MAX_LAMPS) break
            val y = s.room.ceilingYAt(xz) ?: continue
            lamps += Lamp(UUID.randomUUID().toString().take(8), nextNo++, Vec3(xz.x, y, xz.z), params.beam, params.output, params.cct)
        }
        update { it.copy(lamps = it.lamps + lamps, selected = null, dragging = null) }
        lamps.forEach { createAnchor(it) }
        LpLog.i("SEED added=${lamps.size} total=${_state.value.lamps.size}")
        recompute()
        scheduleSave()
    }

    fun debugDeleteLamp(id: String) {
        update { it.copy(selected = id) }
        deleteSelected()
    }

    fun debugPlace(xz: P2, params: LampParams? = null): Boolean {
        val s = _state.value
        val y = s.room.ceilingYAt(xz) ?: return false
        val p = params ?: s.lastParams
        if (s.lamps.size >= LightMath.MAX_LAMPS) {
            timedBar(InlineBar(BarKind.MAX_LAMPS, ++barSeq))
            return false
        }
        val lamp = Lamp(UUID.randomUUID().toString().take(8), nextNo++, Vec3(xz.x, y, xz.z), p.beam, p.output, p.cct)
        update { it.copy(lamps = it.lamps + lamp, selected = lamp.id) }
        createAnchor(lamp)
        recompute()
        scheduleSave()
        return true
    }

    /** `STATE` line for the QA station: the design state, the room and every lamp. */
    fun stateLine(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.append("STATE ").append(s.designState).append(" phase=").append(s.phase).append(" lamps=").append(s.lamps.size)
        sb.append(" ceilingH=").append(s.ceilingHeight?.let { fmt(it) } ?: "-").append(if (s.room.ceilingIsManual) " manual" else "")
        sb.append(" planes=").append(s.room.planes.size).append(" receivers=").append(s.room.receivers.size).append(" heat=").append(s.heat).append(" popup=").append(s.popup)
        s.bar?.let { sb.append(" bar=").append(it.kind) }
        for (l in s.lamps) {
            val info = s.light.info(l.id)
            sb.append(String.format(Locale.US, " | #%d %s beam=%s out=%s cct=%s wall=%s near=%s%s", l.no, LpLog.pos(l.pos), l.beam.tag, l.output.tag, l.cct.tag, info?.wallDistance?.let { fmt(it) } ?: "-", info?.nearestLampDistance?.let { fmt(it) } ?: "-", if (l.id == s.selected) " *" else ""))
        }
        for (ln in s.light.lines) sb.append(String.format(Locale.US, " | line %s-%s s=%.2f max=%.2f %s", noOf(s, ln.a), noOf(s, ln.b), ln.distance, ln.limit, ln.kind))
        for (w in s.light.wallNotes) sb.append(String.format(Locale.US, " | wall %s w=%.2f %s", noOf(s, w.lampId), w.distance, w.warn))
        for (g in s.light.grades) sb.append(String.format(Locale.US, " | %s %s ratio=%.2f uni=%.2f %s", g.kind, g.receiverId.take(8), g.ratio, g.uniformity, g.grade))
        return sb.toString()
    }

    /** `DUMP` lines: every pool radius per (lamp, surface) and the hole-plan dimensions. */
    fun dumpLines(): List<String> {
        val s = _state.value
        val out = ArrayList<String>()
        out += stateLine()
        for (p in s.light.pools) out += String.format(Locale.US, "POOL lamp=%s surface=%s center=(%.3f, %.3f) r=%.3f dh=%.3f", noOf(s, p.lampId), p.receiverId.take(8), p.center.x, p.center.z, p.radius, p.drop)
        for (f in s.light.fields) {
            var lit = 0
            var zero = 0
            for (i in f.e.indices) if (f.mask[i]) if (f.e[i] > 0.5f) lit++ else zero++
            out += String.format(Locale.US, "FIELD surface=%s kind=%s y=%.3f cells=%dx%d cell=%.3f lit=%d dark=%d", f.receiverId.take(8), f.kind, f.y, f.w, f.h, f.cell, lit, zero)
        }
        for (l in s.lamps) {
            val info = s.light.info(l.id) ?: continue
            out += String.format(Locale.US, "HOLE #%d walls=%s", l.no, info.wallDistances.joinToString(",") { String.format(Locale.US, "%.2f", it.first) })
        }
        return out
    }

    private fun noOf(s: PlannerUiState, id: String): String = s.lamp(id)?.no?.let { "#$it" } ?: id

    // =========================================================================================

    private inline fun update(f: (PlannerUiState) -> PlannerUiState) {
        val next = f(_state.value)
        _state.value = next
        val ds = next.designState
        if (ds != lastLogged) {
            lastLogged = ds
            val line = stateLine()
            if (line != lastLoggedLine) {
                lastLoggedLine = line
                LpLog.i(line)
            }
        }
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return PlannerViewModel(LpGraph.anchors, LpGraph.store(app)) as T
        }
    }

    companion object {
        const val FIND_TIMEOUT_MS = 20_000L
        const val SAVE_DELAY_MS = 500L
        const val RESTORE_FIRST_MS = 4_000L
        const val RESTORE_RETRY_MS = 10_000L
        const val RESTORE_POLL_MS = 800L
        const val BAR_MS = 3_000L
        const val BAR_LONG_MS = 5_000L
        const val UNDO_MS = 5_000L
        const val PLANES_RECOMPUTE_MS = 2_000L
        const val ANCHOR_REBUILD_M = 0.05f
        const val WALL_INSET_M = 0.10f
        const val CEILING_OFFER_DIFF_M = 0.03f
        const val SELECT_M = 0.12f
    }
}
