package com.multiapp.app

import android.app.Application
import com.multiapp.app.container.ContainerAmsApiEvidenceRecorder
import com.multiapp.app.container.ContainerBroadcastEvidenceRecorder
import com.multiapp.core.engine.EngineRuntimeInstallers
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MultiAppApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        EngineRuntimeInstallers.installInstrumentation()
            .onFailure { Timber.e(it, "VirtualInstrumentation install failed") }

        EngineRuntimeInstallers.installBroadcastRecorder(ContainerBroadcastEvidenceRecorder(this))
        EngineRuntimeInstallers.installAmsApiEvidenceRecorder(ContainerAmsApiEvidenceRecorder(this))

        Timber.d("MultiApp initialized")
    }
}
