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

        if (!EngineRuntimeInstallers.installSystemServerClient(this)) {
            Timber.w("Engine system-server Binder unavailable; durable state fallback active")
        }

        EngineRuntimeInstallers.installInstrumentation(this)
            .onFailure { Timber.e(it, "VirtualInstrumentation install failed") }

        EngineRuntimeInstallers.installAmsComponentDispatcher(this)
        EngineRuntimeInstallers.installContentResolver().also { result ->
            if (!result.installed) {
                Timber.w("Engine ContentService proxy unavailable: ${result.status}:${result.reason}")
            }
        }
        EngineRuntimeInstallers.installBroadcastRecorder(ContainerBroadcastEvidenceRecorder(this))
        EngineRuntimeInstallers.installAmsApiEvidenceRecorder(ContainerAmsApiEvidenceRecorder(this))

        Timber.d("MultiApp initialized")
    }
}
