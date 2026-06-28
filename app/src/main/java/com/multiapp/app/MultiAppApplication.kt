package com.multiapp.app

import android.app.Application
import com.multiapp.app.container.ContainerBroadcastEvidenceRecorder
import com.multiapp.core.loader.VirtualBroadcastRecorders
import com.multiapp.core.loader.VirtualInstrumentationInstaller
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MultiAppApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        VirtualInstrumentationInstaller.install()
            .onFailure { Timber.e(it, "VirtualInstrumentation install failed") }

        VirtualBroadcastRecorders.install(ContainerBroadcastEvidenceRecorder(this))

        Timber.d("MultiApp initialized")
    }
}
