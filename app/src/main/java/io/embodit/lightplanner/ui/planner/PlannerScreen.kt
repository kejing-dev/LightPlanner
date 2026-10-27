package io.embodit.lightplanner.ui.planner

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import io.embodit.lightplanner.platform.LpLog
import io.embodit.lightplanner.ui.planner.components.LocalLpResources
import io.embodit.lightplanner.ui.planner.components.PlannerStage
import io.embodit.lightplanner.domain.LanguagePref
import io.embodit.lightplanner.ui.planner.components.SceneStrings
import io.embodit.lightplanner.ui.planner.components.localizedResources
import io.embodit.lightplanner.ui.planner.debug.Probe
import io.embodit.lightplanner.ui.planner.scene.PlannerFrameSystem
import io.embodit.lightplanner.ui.planner.scene.PlannerScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [PlannerScene] (entities, per-frame work, pinches and drags) and [PlannerStage] (the panels), and owns nothing.
 */
@Composable
fun PlannerScreen() {
    val context = LocalContext.current
    val viewModel: PlannerViewModel = viewModel(factory = PlannerViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { PlannerScene(viewModel::onEvent).also { s -> viewModel.head = s::headPose } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<PlannerFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): PlannerViewModel = viewModel

                override fun scene(): PlannerScene = scene
            }
        // in the background every tracker stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        LpLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<PlannerFrameSystem>()
            scene.detach()
            LpLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PlannerEffect.Feedback ->
                    runCatching {
                        audio.playSystem(
                            when (effect.kind) {
                                "select" -> SpatialSoundEffect.StateSelected
                                "reject" -> SpatialSoundEffect.StateFailure
                                "snap" -> SpatialSoundEffect.OpDragEnd
                                else -> SpatialSoundEffect.OpClick
                            }
                        )
                    }.onFailure { LpLog.w("FEEDBACK failed: $it") }
            }
        }
    }

    // the in-app language switches at run time: panels and the 3D labels read their copy from this
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    LaunchedEffect(resources, scene) { scene.setStrings(SceneStrings.from(resources), SceneStrings.from(localizedResources(context, LanguagePref.ZH))) }
    CompositionLocalProvider(LocalLpResources provides resources) {
        PlannerStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
