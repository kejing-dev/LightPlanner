# Light Planner (灯光布点)

MR downlight planning app for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space only),
package `io.embodit.lightplanner`. Look at the spot you want lit and pinch: a downlight lands on the real
ceiling right above it, its light pool falls on the real desk / sofa / floor, the spacing lines on the ceiling
turn red where the gap would be dark, every piece of furniture gets a tag (dark / dim / good / bright / uneven),
and the hole plan gives the electrician the numbers.

Design package (binding): `../spatial-app-factory/apps/light-planner/design-package.json` (states S00 - S17,
`lightModel`, `panels`, `entities3d`, palette, copy). Approved sample: `../spatial-app-factory/apps/light-planner/sample.html`.
Skeleton D, built from Theater Planner (`../theater-planner`, itself from Furniture Fit / Spatial Ruler
`../spatial-ruler`): panel pool (PanelRig), Stage root, plane colliders + backstop, world anchors, gesture
conversion (DragLine), lazy follow, theme and fonts, debug receiver, thumbstick input.

## Structure

```
app/src/main/java/io/embodit/lightplanner/
  Main.kt                   DefaultStage { LightPlannerTheme { PlannerScreen() } }
  domain/                   pure Kotlin
    Vec3.kt Geometry.kt     vectors, P2 (floor plane), HeadPose, PanelFacing, PlaneSurface (two-sided ray hits)
    Lamp.kt                 Beam (24/36 accent, 60/90 general), Output (400/650/900 lm), Cct, Lamp, LampParams
    Room.kt                 RoomModel: floor, WALL footprints, ceiling planes (CEILING or 2-4 m up), receivers
                            (upward planes with a SurfaceKind: desk / coffee table / sofa / bed / floor / other),
                            ceilingYAt(xz) (covering ceiling polygon, else the largest one unbounded, else manual)
    LightMath.kt            cos^n beam, E = I cos a / d^2, pool radius, references, bands + hysteresis,
                            spacing limit min(D, 1.5 (H - 0.75)), neighbour lines, wall rules, row snap
    LightField.kt           the sampled light per receiver (4 cm cells, <= 192 a side, shadow by higher receivers),
                            grades, pools, lines, wall notes, lamp infos -> LightResult (worker thread)
    Plan.kt                 Plan / SavedLamp JSON (files/plan.json), Settings, AnchorPose, Units
    DragLine.kt             system-drag model (Spatial Ruler round 5c)
  data/Stores.kt            AnchorGateway / PlanStore interfaces, FilePlanStore (plan.json + prefs)
  platform/                 LpGraph (services), LpLog, HeadTracker, ControllerStick (thumbstick X), PlaneTracker
                            (debug: hideCeiling), WorldAnchorGateway, LaunchActivity, SpatialApplication
  ui/planner/               PlannerUiState (+ designState S00..S17), PlannerEvent, PlannerViewModel, PlannerScreen
  ui/planner/scene/         PlannerScene (ECS: one registered System, lamps / layers / lines / tags / reticle / handle,
                            pinch + drag + stick, panel placement), Views (LampView, SelectView, ReticleView,
                            LightLayerView, LinePool, TextPlate bitmaps), PanelRig, SceneKit (materials, meshes)
  ui/planner/components/    SpatialUI panels: MainPanel (setup states, plan view, lamp view, inline bar, disclaimer),
                            SettingsPanel, HolePlanPanel (top view Canvas + table), PlannerStage, Strings, GlassRefresh
  ui/theme/                 LightPlannerTheme (PicoTheme + 4 semantic roles + Noto Sans / Noto Sans SC subset), LpColors, LpType
app/src/debug/              DebugCommandReceiver + DebugCommands (QA hooks)
tools/                      make_icon.py (3D icon layers + 512 export), subset_cjk_font.py
```

One direction of data: panel controls and scene pinches / drags -> `PlannerViewModel.onEvent` -> `PlannerUiState`
(+ `LightResult` from a worker thread) -> `PlannerScene.render` (entities, panels). Per-frame work (drag, thumbstick,
reticle, breathing ring, label facing, lazy follow) is in `PlannerScene.onFrame` only; the only Compose state it
writes is `panelShown`.

## Rules of the room (what the code does)

- Placing (T1 / T2): a pinch on a ceiling plane puts the lamp there; on an upward surface it goes to
  `ceilingYAt(x, z)` above that spot; on a wall, or where no ceiling is above, the reticle shows a red cross
  and the pinch is refused (`PLACE rejected reason=`). Within 12 cm of an existing lamp's projection the pinch
  selects that lamp instead. 16 lamps at most (S14 bar). A new lamp inherits the last selected lamp's parameters.
- Light (design `lightModel`): I(a) = I0 cos^n a, n = ln 0.5 / ln cos(theta/2), I0 = Phi (n+1) / 2 pi, E = I cos a / d^2,
  summed over lamps per 4 cm cell, cells beyond 2 r skipped, a cell is shadowed when the line to the lamp crosses a
  higher receiver's polygon. Pool alpha = 55 % (E / E_centre)^2 per lamp, capped at 72 %; the half-power rim
  r = dh tan(theta/2) is one cell brighter. Heat view: ratio E / reference on the indigo -> cream ramp at 70 %.
- Grades: mean E of the central 60 % of the surface / reference (desk 200, coffee table 70, sofa 85, bed / floor 55),
  bands < 0.5 dark, < 0.8 dim, <= 2 good, else bright; min / mean < 0.4 in that region -> uneven; 5 % hysteresis.
- Spacing lines: every general lamp (60 / 90) to its two nearest general lamps on the same side of every wall;
  green when s <= min(D, 1.5 (H - 0.75)), red dashed + "1.42 m · 中间会暗" when longer; accent lamps draw one grey
  line to the nearest lamp. Labels only on red lines and lines of the selected lamp.
- Wall rules (general lamps): < 0.30 m to the nearest wall -> amber line "离墙太近"; the outermost lamp towards a
  wall more than 0.6 x its nearest-lamp spacing away -> "墙边会暗".
- Dragging: the ring drags along the ceiling (ray -> ceiling plane); the amber handle under the selected lamp drags
  at the handle's locked height (ray -> that level), so it does not jump surfaces; a press within 12 cm of an
  unselected lamp's projection selects it and drags its handle. Lamps stop 10 cm before a wall footprint. Within
  4 cm of another lamp's coordinate along the room's main axis (longest wall) or across it, the lamp snaps into the
  row and a thin amber guide line appears (`SNAP` log). Live moves reach the ViewModel at <= 5 Hz (`DragMoved`),
  the drop writes `MOVE id= via=drag`.
- Thumbstick (taste profile D, adapted): a downlight has no facing, so stick X nudges the selected lamp sideways
  (head-right) along the ceiling at up to 0.4 m/s (dead zone 0.15) with the same row snap and guide line; the lamp
  panel's last line says 「摇杆左右微调位置」. Release commits (`STICK nudge end`). Recorded as a deviation.
- Panels (0.1.1, taste profile D9 / D10, user: "要抬着头看"): the main panel (400 x 400 dp, drawn 1.1x) rests 0.85 m
  from the current head position along its yaw, 15 degrees under the horizon whatever the gaze pitch (never above
  the eyes; 0.1.0 followed the gaze up to the ceiling), centre 20 degrees left of straight ahead so the reticle and
  the furniture tag straight ahead stay free (1.25x at 15 degrees covered the bed tag on the emulator). Checked by
  `PanelFollowTest` (turn 90 / 180, look up, side park). It follows lazily (30 degrees sideways, too near / far, for 0.6 s -> 0.4 s ease), so turning round re-centres
  it in front; looking down more than 35 degrees it parks 38 degrees to the side at the same height (no follow loop
  once it is where it would slide to). Settings (400 x 420) and the hole plan are pre-built slots to the right of it,
  top-aligned with it (they grow downwards), mutually exclusive; while one is open a pinch in the room only closes it and the next 0.4 s of
  pinches are dropped (`TAP ignored reason=after-close`).
- Persistence: one world anchor per lamp (`ANCHOR created lamp=`); plan.json saved 0.5 s after a change with each
  lamp's position, anchor id and the anchor's position then. Restore on launch: anchors loaded for up to 4 s; a lamp
  whose anchor is found moves with it, the rest follow the shift of a found lamp (S11 partial bar); none found ->
  S12 with "look again" (10 s) and "start over" (confirm). A lamp moved more than 5 cm from its anchor gets a new
  one; a deleted lamp keeps its anchor 5 s for undo.
- Ceiling: S02 waits 5 s for a CEILING plane (or any horizontal plane 2 - 4 m above the floor), then the virtual room
  takes over (0.1.1, taste profile D7, `domain/VirtualRoom.kt`, log `ROOM virtual reason=timeout`): a 4 x 5 x 2.7 m box
  built around the current head position and yaw (user 1 m behind its centre; floor = scanned floor, else 1.6 m under
  the eyes) with floor / ceiling / 4 walls / one coffee table as ordinary planes, merged with the scanned planes
  (scanned ceilings are left out while it is in use; its floor / walls / table only when the scan has none). Settings
  shows width / depth / height steppers and "移到我面前"; without it, "改用虚拟房间". A real ceiling that arrives later
  replaces an untouched timeout room by itself (`ROOM scanned ... replaces`), otherwise it offers "识别到天花板 · 改用".
  S13 (manual heights 2.4 - 3.3 m) is still in the code, reached by `state S13`. Every lamp's y follows the ceiling
  above it when planes change. The virtual room is not saved: after a relaunch without a scan it is rebuilt in front.

## UI rules

- All 2D UI is SpatialUI inside `PicoTheme`. No Material / Material3 (gate: `../spatial-app-factory/bin/material-grep.txt`).
- Every panel is a pre-built Stage attachment panel (AlertDialog / SnackbarHost / native popups do not work in a Stage).
- The heat switch is a `Button` drawn as a toggle (the SpatialUI toggle button's Kotlin name was not confirmed).
- 3D labels (lamp numbers, furniture tags with the 4-cell bar, spacing / wall labels) are bitmap plates
  (`TextPlate`, MASKED unlit material), not panels, turned about the vertical towards the eye every frame
  (`domain/Billboard.kt`). The engine samples a bitmap with v = 0 at its BOTTOM row: 0.1.0 gave the plate's top
  v = 0 and every label was upside down on the headset; `Billboard.UV` and `BillboardTest` pin the fix (yaw only,
  front to the eye, +X to the viewer's right, bitmap top row on top). Furniture tags lead with the Chinese name
  ("茶几 · 偏暗"), with the UI-language line under it when that is not Chinese; the light pools are per-surface bitmaps on a shared unit square
  (`LightLayerView`, TRANSPARENT unlit material). Whether the engine blends per texel is the design's spike item;
  `pools disc` falls back to flat 35 % discs. The layer quad's UVs follow the same bottom-up v as the plates (`LayerQuad`, `LayerMappingTest`; 0.1.1 — 0.1.0 had them mirrored along Z); `flipv on` swaps back to the 0.1.0 mapping for comparison.
- Copy in `res/values` / `res/values-zh`; read through `rs()` (`LocalLpResources`) so the language switches live;
  the 3D plates get their copy through `SceneStrings`. After changing zh copy run `python tools/subset_cjk_font.py`.
- Launcher icon: `res/drawable/ic_launcher_foreground.xml` on plum `#4A2D5E`; `tools/make_icon.py` writes the 3D
  icon layers and the 512 export (`../spatial-app-factory/apps/light-planner/icon.png`).

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cmd /c "cd /d C:\Users\Admin\Downloads\light-planner && .\gradlew.bat assembleDebug --no-daemon --console=plain > .build.log 2>&1"
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. `local.properties` holds `sdk.dir` with forward slashes.
Install: `adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk`, launch:
`adb -s <serial> shell monkey -p io.embodit.lightplanner -c android.intent.category.LAUNCHER 1`.
Log: `adb -s <serial> logcat -s LightPlanner` (prefixes `STATE PLACE SELECT MOVE EDIT DELETE UNDO SNAP STICK DRAG TAP
PLANES CEILING ANCHOR SAVE RESTORE LIGHT FRAME FOLLOW PANEL RIG CMD DUMP POOL FIELD HOLE PLANE LAYER`).

## Debug commands (debug build only)

`adb -s <serial> shell "am broadcast -a io.embodit.lightplanner.DEBUG -p io.embodit.lightplanner --es cmd NAME [--es s TEXT] [--es a NUMBERS] [--ef x N] [--ef z N]"`

| cmd | arguments | effect |
|---|---|---|
| `state` | s=S00..S17 | jump to that design state (S04-S12, S14, S15 seed the demo lamps first; the base clears the plan); without `s` prints the `STATE` line |
| `seedLamps` | s=demo \| n | demo: two 60-degree lamps 2.6 m apart (red at H 2.7), one over the first tagged surface (high output), one 0.2 m from the nearest wall (2700 K), one 24-degree accent; n: a grid of n |
| `lamp` | s="<no> select \| delete \| beam 24/36/60/90 \| output s/m/l \| cct 2700/3000/4000/5000/6500/red/amber/green/blue/violet" | select / edit / delete lamp number no |
| `virtual` | s=on \| off \| recenter \| size (a=dw,dd,dh metres) | use the virtual room now / back to the scan / rebuild it in front / resize |
| `drag` | s="<no> dx dz [hold]" | move lamp no by (dx, dz) metres on the ceiling (snap applies); `hold` keeps it in S06 until `release` |
| `release` | | end a held drag |
| `place` | a=x,z or --ef x --ef z | place a lamp at (x, z) (Stage metres) |
| `heat` s=on\|off · `hole` · `settings` · `close` | | brightness map, hole plan, settings, close the open slot |
| `ui` | s=control | press a control by name (below) |
| `stick` | a=x,ms | the thumbstick reads x for ms (nudge + snap QA) |
| `lang` | s=system\|zh\|en | language |
| `noCeiling` s=on\|off · `noSurface` s=on\|off · `trackingLost` s=on\|off · `mismatch` · `noPermission` [s=off] | | S13 / S17 / S15 / S12 / S16 |
| `restore` | | re-run the restore from plan.json (S11) |
| `clear` | | clear the plan without the confirmation |
| `bar` | s=long\|normal | timed inline bars stay 60 s |
| `pools` | s=tex\|disc | textured light layers (default) or flat fallback discs |
| `flipv` | s=on\|off | mirror the light layers' v axis (spike check) |
| `hidemode` | s=auto\|park | how hidden panels are stowed |
| `dump` · `pose` · `panels` · `planes` · `layers` · `bounds` | | reading back; `dump` writes files/dump.txt (STATE, POOL per lamp x surface with r, FIELD per surface with lit / dark cells, HOLE wall distances, PLANE kinds) |

Control names: `settings heat hole done delete bar_action` (plan / lamp view) · `beam_24 beam_36 beam_60 beam_90
output_s output_m output_l cct_2700 cct_3000 cct_4000 cct_5000 cct_6500 cct_red cct_amber cct_green cct_blue
cct_violet` (lamp view) · `start retry manual_height height_2.4 .. height_3.3
use_this look_again restart restart_cancel restart_confirm grant` (setup states) · `close lang_system lang_zh lang_en
show_spacing clear clear_cancel clear_confirm virtual_use virtual_recenter virtual_width_minus/_plus
virtual_depth_minus/_plus virtual_height_minus/_plus` (settings) · `back` (hole plan). Panel ids: `main settings holePlan`.

## QA screenshot list (one per design state; `state Sxx`, wait ~8 s for the panels; seeded states need the room's planes)

S00 loading spinner · S01 welcome (three pictograms, 开始) · S02 finding (chips) · S03 empty plan, reticle on the
desk (dot + dashed pole + dashed ring) · S04 demo lamps: one red "中间会暗" line, a green line, a grey accent line,
an amber wall line, furniture tags · S05 lamp 3 selected (amber breathing ring, cone, handle; `lamp 3 beam 24` for a
second shot: small pool, grey line) · S06 lamp 3 held mid-drag (guide line when snapped) · S07 heat view + legend ·
S08 hole plan slot · S09 settings slot (then `lang en`) · S10 clear confirmation · S11 restored bar (or force-stop
and relaunch) · S12 room mismatch (`ui restart` for the confirmation) · S13 no ceiling (`ui manual_height`, `ui
height_2.6`, `ui use_this`: status line "层高 2.60 m · 手动") · S14 "最多 16 盏" bar · S15 tracking lost · S16 no
permission · S17 no furniture bar.

## Verified on emulator-5560 (2026-10-26, Pico_Emulator_6_1_fresh) and notes for QA

- Clean launches (`pm clear` + launch) x5 across builds 4 - 8 and one after an emulator reboot: no app ANR. The one
  `am_anr` seen (05:42:35) came from `adb install -r` replacing the running Stage ("Waited 5000ms for FocusEvent", app
  main thread idle in the trace); its stale "isn't responding" dialogs only went away with an emulator reboot. Always
  `am force-stop` before `install -r`, and reboot the emulator if the dialog sticks.
- Spike items settled: `UnlitMaterial` TRANSPARENT + `TextureResource.create(bitmap)` blends per texel (soft pools,
  bright rim, label plates); pool centres match `dump` POOL lines and are not mirrored. `pools disc` / `flipv` remain as
  debug switches only. Textures are created through a per-frame work queue (2 per frame when frames are slow, 6 when
  fast) and meshes one per frame with a 0.7 s gap on the idle emulator: never a burst on the main thread.
- The room reports CEILING planes at 3.27 / 3.50 / 3.69 m plus a horizontal WALL soffit at 3.19 m (counted as a
  ceiling); `ceilingHeight` shows the largest one: 3.29 m above the 0.40 m floor. Spacing limit for 60-degree lamps here is
  2.94 m, so the demo pair 2.6 m apart is green; the red lines come from the #3-#2 and #4-#1 pairs.
- Semantics seen: TABLE (desk / coffee by height), BED, CHAIR (shown as sofa), CABINET / UNKNOWN (no tag), FLOOR, WALL,
  CURTAIN, DOOR. The light compute takes 1.3 - 1.7 s for 5 lamps (worker thread), 5 s for 16.
- Camera for the screenshots: `tools/turn.sh YAW PITCH` (0.032 deg per px; the toolbar `camera_rotation_button` must be On,
  `tools/harness/uia_cam.ps1 set camera_rotation_button on`, and the emulator window must not be minimized). Lamp states
  (S04 - S06, S14) at pitch +30, S07 at -30, panel states level. `tools/shot.sh NAME "cmd|s" ...` sends commands, waits
  with `fast on`, captures the console frame.
- The idle emulator composes at 1 - 2 fps: the debug `state` jumps wait 1.5 s between steps and the `drag` command takes
  the ViewModel's lamp, not the scene's copy.
- Not verified: the controller thumbstick (emulator eye mode; `stick` command instead), real pinches through the
  harness, S16 on a device, a real headset.
