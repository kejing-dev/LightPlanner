package io.embodit.lightplanner

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.lightplanner.ui.planner.PlannerScreen
import io.embodit.lightplanner.ui.theme.LightPlannerTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full
 * Space with passthrough holds the whole product. There is no WindowContainer and no Shared Space
 * version (design `container`).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            LightPlannerTheme {
                PlannerScreen()
            }
        }
    }
