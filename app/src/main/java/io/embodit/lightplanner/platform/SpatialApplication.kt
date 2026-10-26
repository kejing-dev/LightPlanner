package io.embodit.lightplanner.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.lightplanner.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
