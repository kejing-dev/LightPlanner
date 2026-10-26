package io.embodit.lightplanner.platform

import android.content.Context
import io.embodit.lightplanner.data.FilePlanStore
import io.embodit.lightplanner.platform.anchor.WorldAnchorGateway
import io.embodit.lightplanner.platform.sense.PlaneTracker
import io.embodit.lightplanner.platform.tracking.ControllerStick
import io.embodit.lightplanner.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the sensing services (every start has a
 * stop: `PlannerScene.attach` / `detach`); the ViewModel only sees interfaces and lambdas.
 */
object LpGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val stick = ControllerStick()

    @Volatile private var store: FilePlanStore? = null

    fun store(context: Context): FilePlanStore = store ?: synchronized(this) { store ?: FilePlanStore(context).also { store = it } }
}
