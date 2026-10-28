package io.embodit.lightplanner.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.lightplanner.domain.Beam
import io.embodit.lightplanner.domain.Cct
import io.embodit.lightplanner.domain.LampParams
import io.embodit.lightplanner.domain.LanguagePref
import io.embodit.lightplanner.domain.Output
import io.embodit.lightplanner.domain.P2
import io.embodit.lightplanner.domain.Vec3
import io.embodit.lightplanner.domain.RoomModel
import io.embodit.lightplanner.domain.SurfaceKind
import io.embodit.lightplanner.platform.LpGraph
import io.embodit.lightplanner.platform.LpLog
import io.embodit.lightplanner.ui.planner.BarKind
import io.embodit.lightplanner.ui.planner.Phase
import io.embodit.lightplanner.ui.planner.PlannerEvent
import io.embodit.lightplanner.ui.planner.PlannerViewModel
import io.embodit.lightplanner.ui.planner.Popup
import io.embodit.lightplanner.ui.planner.debug.Probe
import io.embodit.lightplanner.ui.planner.scene.PlannerScene
import java.io.File
import java.util.Locale

/**
 * Test hooks of the debug build (design `verification.debugHooks`). One `CMD` log line (tag
 * `LightPlanner`) answers every command, and every state change logs `STATE Sxx ...`.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.lightplanner.DEBUG -p io.embodit.lightplanner --es cmd NAME [--es s TEXT] [--es a NUMBERS]"
 *
 * `state` s=S00..S17 jumps to that design state (S04-S08, S11, S14 seed the demo lamps first).
 * Driving: `seedLamps` s=demo|<n> · `lamp` s="<no> select|delete|beam <deg>|output s|m|l|cct <k>" ·
 * `drag` s="<no> <dx> <dz> [hold]" · `place` a=x,z · `heat` s=on|off · `hole` · `settings` · `ui` s=control ·
 * `stick` a=x,ms · `lang` s=system|zh|en · `noCeiling` s=on|off · `noSurface` s=on|off · `trackingLost` s=on|off ·
 * `mismatch` · `noPermission` · `restore` · `clear` · `bar` s=long|normal · `pools` s=tex|disc · `flipv` s=on|off ·
 * `hidemode` s=auto|park · `ray` a=x,y,z[,ms] · `trigger` · `virtual` s=on|off|recenter|size a=dw,dd,dh. Reading back: `state` (no s) · `dump` · `pose` · `panels` · `planes` · `layers` · `bounds`.
 */
internal object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, cmd: String, s: String?, a: String?, floats: Map<String, Float>) {
        val host = Probe.host
        if (host == null) {
            LpLog.w("CMD ignored: no Stage")
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val nums = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        val words = s?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
        when (cmd) {
            "state" -> if (s.isNullOrBlank()) LpLog.i(vm.stateLine()) else state(vm, scene, s.trim())
            "seedLamps", "seed" -> seed(vm, s ?: "demo")
            "lamp" -> lamp(vm, scene, words)
            "drag" -> {
                val id = idOf(vm, words.getOrNull(0)) ?: return
                val dx = words.getOrNull(1)?.toFloatOrNull() ?: floats["x"] ?: nums.getOrNull(0) ?: 0f
                val dz = words.getOrNull(2)?.toFloatOrNull() ?: floats["z"] ?: nums.getOrNull(1) ?: 0f
                LpLog.i("CMD ${scene.debugMove(vm.state.value.lamp(id), dx, dz, hold = words.contains("hold"))}")
            }
            "release" -> scene.debugDragCancel()
            "place" -> {
                val x = floats["x"] ?: nums.getOrNull(0) ?: return
                val z = floats["z"] ?: nums.getOrNull(1) ?: return
                LpLog.i("CMD place ok=${vm.debugPlace(P2(x, z))}")
            }
            "heat" -> if ((s != "off") != vm.state.value.heat) vm.onEvent(PlannerEvent.HeatToggled)
            "hole" -> vm.onEvent(PlannerEvent.HolePlanClicked)
            "settings" -> vm.onEvent(PlannerEvent.SettingsClicked)
            "close" -> vm.onEvent(PlannerEvent.PopupClosed)
            "ui" -> ui(vm, s ?: "")
            "stick" -> LpLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            "lang" -> LanguagePref.entries.firstOrNull { it.tag == s }?.let { vm.onEvent(PlannerEvent.LanguageSelected(it)) }
            "noCeiling", "no_ceiling" -> LpGraph.planes.hideCeiling = s != "off"
            // taste profile D1 (0.1.1): aim a stand-in controller ray at a Stage point (a=x,y,z[,ms]); trigger = its tap
            "ray" -> {
                if (nums.size < 3) return
                LpLog.i("CMD ${scene.debugRay(Vec3(nums[0], nums[1], nums[2]), null, (nums.getOrNull(3) ?: 60_000f).toLong())}")
            }
            "trigger" -> LpLog.i("CMD ${scene.debugTrigger()}")
            // taste profile D7 (0.1.1): on = switch to the virtual room now; recenter; size=w,d,h deltas in metres
            "virtual" ->
                when (words.getOrNull(0)) {
                    "recenter" -> vm.onEvent(PlannerEvent.VirtualRoomRecenter)
                    "size" -> vm.onEvent(PlannerEvent.VirtualRoomResize(nums.getOrNull(0) ?: 0f, nums.getOrNull(1) ?: 0f, nums.getOrNull(2) ?: 0f))
                    "off" -> vm.onEvent(PlannerEvent.UseDetectedCeiling)
                    else -> vm.onEvent(PlannerEvent.VirtualRoomUse)
                }
            "noSurface", "no_surface" -> vm.debugSet { it.copy(debugNoSurface = s != "off") }
            "trackingLost", "lost" -> LpGraph.head.forceLost = s != "off"
            "mismatch" -> vm.debugMismatch()
            "noPermission", "no_permission" -> vm.debugSet { it.copy(phase = if (s == "off") Phase.FINDING else Phase.NO_PERMISSION, popup = Popup.NONE) }
            "restore" -> vm.debugRestore()
            "clear" -> vm.onEvent(PlannerEvent.ClearConfirmed)
            "bar" -> vm.longBars = s == "long"
            "pools" -> {
                scene.poolsTextured = s != "disc"
                vm.recompute()
                LpLog.i("CMD pools textured=${scene.poolsTextured}")
            }
            "flipv" -> {
                scene.flipV = s != "off"
                vm.recompute()
                LpLog.i("CMD flipV=${scene.flipV}")
            }
            "hidemode" -> LpLog.i("CMD ${scene.debugHideMode(s ?: "auto")}")
            "dump" -> {
                val lines = vm.dumpLines() + scene.planeLines() + scene.layerLines()
                runCatching { File(context.filesDir, "dump.txt").writeText(lines.joinToString("\n")) }
                lines.forEach { LpLog.i("DUMP $it") }
            }
            "pose" -> LpLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { LpLog.i(it) }
            "planes" -> scene.planeLines().forEach { LpLog.i(it) }
            "layers" -> scene.layerLines().forEach { LpLog.i(it) }
            "bounds" ->
                listOf("main", "settings", "holePlan").forEach { panel ->
                    Probe.controlsOf(panel).forEach { (name, b) -> LpLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, name, b.cx, b.cy, b.w, b.h)) }
                }
            else -> LpLog.w("CMD unknown '$cmd'")
        }
    }

    private fun idOf(vm: PlannerViewModel, no: String?): String? {
        val s = vm.state.value
        val n = no?.toIntOrNull() ?: return s.selected ?: s.lamps.lastOrNull()?.id
        return s.lamps.firstOrNull { it.no == n }?.id ?: run { LpLog.w("CMD no lamp #$n"); null }
    }

    private fun lamp(vm: PlannerViewModel, scene: PlannerScene, words: List<String>) {
        val id = idOf(vm, words.getOrNull(0)) ?: return
        val action = words.getOrNull(1) ?: "select"
        val arg = words.getOrNull(2)
        when (action) {
            "select" -> vm.onEvent(PlannerEvent.Pinched(io.embodit.lightplanner.ui.planner.Hit.Lamp(id)))
            "delete" -> vm.debugDeleteLamp(id)
            "beam" -> {
                vm.onEvent(PlannerEvent.Pinched(io.embodit.lightplanner.ui.planner.Hit.Lamp(id)))
                (arg?.toFloatOrNull()?.let { Beam.byDeg(it) } ?: Beam.byTag(arg))?.let { vm.onEvent(PlannerEvent.BeamSelected(it)) }
            }
            "output" -> {
                vm.onEvent(PlannerEvent.Pinched(io.embodit.lightplanner.ui.planner.Hit.Lamp(id)))
                Output.byTag(arg)?.let { vm.onEvent(PlannerEvent.OutputSelected(it)) }
            }
            "cct" -> {
                vm.onEvent(PlannerEvent.Pinched(io.embodit.lightplanner.ui.planner.Hit.Lamp(id)))
                Cct.byTag(arg)?.let { vm.onEvent(PlannerEvent.CctSelected(it)) }
            }
            else -> LpLog.w("CMD lamp unknown action '$action'")
        }
    }

    private fun ui(vm: PlannerViewModel, name: String) {
        val e: PlannerEvent? =
            when {
                name == "start" -> PlannerEvent.StartClicked
                name == "settings" -> PlannerEvent.SettingsClicked
                name == "sound" -> PlannerEvent.SoundToggled
                name == "done" -> PlannerEvent.DoneClicked
                name == "delete" -> PlannerEvent.DeleteClicked
                name == "undo" || name == "bar_action" && vm.state.value.bar?.kind == BarKind.DELETED -> PlannerEvent.UndoClicked
                name == "use_detected" || name == "bar_action" -> PlannerEvent.UseDetectedCeiling
                name == "heat" -> PlannerEvent.HeatToggled
                name == "hole" -> PlannerEvent.HolePlanClicked
                name == "back" || name == "close" -> PlannerEvent.PopupClosed
                name == "retry" -> PlannerEvent.RetryFindClicked
                name == "manual_height" -> PlannerEvent.ManualHeightClicked
                name.startsWith("height_") -> name.removePrefix("height_").toFloatOrNull()?.let { PlannerEvent.ManualHeightPicked(it) }
                name == "use_this" -> PlannerEvent.UseManualHeight
                name == "look_again" -> PlannerEvent.MismatchRetry
                name == "restart" -> PlannerEvent.MismatchRestart
                name == "restart_cancel" -> PlannerEvent.RestartCancelled
                name == "restart_confirm" -> PlannerEvent.RestartConfirmed
                name == "grant" -> PlannerEvent.PermissionClicked
                name.startsWith("lang_") -> LanguagePref.entries.firstOrNull { it.tag == name.removePrefix("lang_") }?.let { PlannerEvent.LanguageSelected(it) }
                name == "show_spacing" -> PlannerEvent.ShowSpacingToggled(!vm.state.value.settings.showSpacing)
                name == "clear" -> PlannerEvent.ClearClicked
                name == "clear_cancel" -> PlannerEvent.ClearCancelled
                name == "clear_confirm" -> PlannerEvent.ClearConfirmed
                name.startsWith("beam_") -> Beam.byTag(name.removePrefix("beam_"))?.let { PlannerEvent.BeamSelected(it) }
                name.startsWith("output_") -> Output.byTag(name.removePrefix("output_"))?.let { PlannerEvent.OutputSelected(it) }
                name.startsWith("cct_") -> Cct.byTag(name.removePrefix("cct_"))?.let { PlannerEvent.CctSelected(it) }
                else -> null
            }
        if (e == null) LpLog.w("CMD ui unknown '$name'") else vm.onEvent(e)
    }

    /** The demo set (design S04 qaCapture): a pair of 60-degree lamps too far apart, one over furniture, one hugging a wall, one accent lamp. */
    private fun seed(vm: PlannerViewModel, what: String) {
        val room = vm.state.value.room
        val spots = ArrayList<Pair<P2, LampParams>>()
        val c = center(room)
        val u = room.mainAxis()
        val v = u.right
        if (what == "demo") {
            spots += (c - u * 1.3f) to LampParams(Beam.STD, Output.M, Cct.K3000)
            spots += (c + u * 1.3f) to LampParams(Beam.STD, Output.M, Cct.K3000)
            val furniture = room.receivers.firstOrNull { it.kind.tagged } ?: room.receivers.firstOrNull { it.kind != SurfaceKind.FLOOR }
            spots += (furniture?.let { P2.of(it.plane.centroid) } ?: (c + v * 1.2f)) to LampParams(Beam.STD, Output.L, Cct.K3000)
            val wall = room.nearestWall(c)
            if (wall != null) {
                val (p, q) = room.wallSegment(wall.second)
                val foot = RoomModel.closestOnSegment(c, p, q)
                val inward = (c - foot).normalized()
                spots += (foot + inward * 0.2f) to LampParams(Beam.STD, Output.M, Cct.K2700)
            }
            spots += (c - v * 1.0f) to LampParams(Beam.SPOT, Output.S, Cct.K4000)
        } else {
            val n = what.toIntOrNull() ?: 5
            val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toFloat())).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val col = i % cols - (cols - 1) / 2f
                val row = i / cols - (cols - 1) / 2f
                spots += (c + u * (col * 1.2f) + v * (row * 1.2f)) to LampParams()
            }
        }
        vm.debugSeed(spots)
    }

    /** The middle of the room: the floor's centroid, else the head. */
    private fun center(room: RoomModel): P2 {
        val floor = room.receivers.firstOrNull { it.kind == SurfaceKind.FLOOR }
        if (floor != null) return P2.of(floor.plane.centroid)
        val head = Probe.host?.scene()?.headPose()?.position
        return if (head != null) P2.of(head) else P2.ZERO
    }

    private fun later(ms: Long, block: () -> Unit) = main.postDelayed(block, ms)

    /** A calm base: in the plan with no lamps, nothing selected, no popup, debug switches off. */
    private fun base(vm: PlannerViewModel, scene: PlannerScene) {
        LpGraph.head.forceLost = false
        LpGraph.planes.hideCeiling = false
        vm.debugHoldFinding = false
        vm.longBars = true
        scene.debugDragCancel()
        if (vm.state.value.phase == Phase.LOADING) vm.debugLeaveLoading()
        if (vm.state.value.lamps.isNotEmpty() || vm.state.value.phase != Phase.PLAN) vm.onEvent(PlannerEvent.ClearConfirmed)
        vm.debugSet { it.copy(phase = Phase.PLAN, popup = Popup.NONE, clearConfirm = false, restartConfirm = false, selected = null, dragging = null, heat = false, bar = null, trackingLost = false, debugNoSurface = false, holdLoading = false, undo = null) }
    }

    private fun state(vm: PlannerViewModel, scene: PlannerScene, id: String) {
        when (id) {
            "S00" -> vm.debugSet { it.copy(holdLoading = true, phase = Phase.LOADING, popup = Popup.NONE) }
            "S01" -> {
                LpGraph.head.forceLost = false
                vm.debugSet { it.copy(holdLoading = false, phase = Phase.WELCOME, popup = Popup.NONE, trackingLost = false) }
            }
            "S02" -> {
                LpGraph.head.forceLost = false
                vm.debugHoldFinding = true
                vm.debugSet { it.copy(holdLoading = false, phase = Phase.FINDING, popup = Popup.NONE, trackingLost = false) }
            }
            "S03" -> base(vm, scene)
            "S04" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
            }
            "S05" -> {
                base(vm, scene)
                seed(vm, "demo")
                later(1500) { lamp(vm, scene, listOf("3", "select")) }
            }
            "S06" -> {
                base(vm, scene)
                seed(vm, "demo")
                later(1500) {
                    lamp(vm, scene, listOf("3", "select"))
                    LpLog.i("CMD ${scene.debugMove(vm.state.value.lamps.firstOrNull { it.no == 3 }, 0.3f, 0.2f, hold = true)}")
                }
            }
            "S07" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
                vm.onEvent(PlannerEvent.HeatToggled)
            }
            "S08" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
                later(1500) { vm.onEvent(PlannerEvent.HolePlanClicked) }
            }
            "S09" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
                later(1500) { vm.onEvent(PlannerEvent.SettingsClicked) }
            }
            "S10" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
                later(1500) {
                    vm.onEvent(PlannerEvent.SettingsClicked)
                    vm.onEvent(PlannerEvent.ClearClicked)
                }
            }
            "S11" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
                later(1500) { vm.debugBar(BarKind.RESTORED, vm.state.value.lamps.size) }
            }
            "S12" -> {
                base(vm, scene)
                seed(vm, "demo")
                later(1500) { vm.debugMismatch() }
            }
            "S13" -> {
                base(vm, scene)
                LpGraph.planes.hideCeiling = true
                vm.debugSet { it.copy(phase = Phase.NO_CEILING, room = it.room.copy(manualCeiling = null)) }
            }
            "S14" -> {
                base(vm, scene)
                seed(vm, "16")
                vm.onEvent(PlannerEvent.DoneClicked)
                later(1500) { vm.debugBar(BarKind.MAX_LAMPS) }
            }
            "S15" -> {
                base(vm, scene)
                seed(vm, "demo")
                vm.onEvent(PlannerEvent.DoneClicked)
                later(1500) { LpGraph.head.forceLost = true }
            }
            "S16" -> {
                LpGraph.head.forceLost = false
                vm.debugSet { it.copy(phase = Phase.NO_PERMISSION, popup = Popup.NONE, trackingLost = false, holdLoading = false) }
            }
            "S17" -> {
                base(vm, scene)
                vm.debugSet { it.copy(debugNoSurface = true) }
                later(1500) { vm.debugBar(BarKind.NO_FURNITURE) }
            }
            else -> LpLog.w("CMD state unknown '$id'")
        }
        LpLog.i("CMD state $id requested")
        later(3000) { LpLog.i(vm.stateLine()) }
    }
}
