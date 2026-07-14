package com.multiapp.app

import android.app.Application
import com.multiapp.app.container.ContainerAmsApiEvidenceRecorder
import com.multiapp.app.container.ContainerBroadcastEvidenceRecorder
import com.multiapp.app.container.EngineGuestRecentsRecoveryCoordinator
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

        val processName = MultiAppProcessRoles.currentProcessName()
        val role = MultiAppProcessRoles.resolve(packageName, processName)
        val startup = MultiAppProcessRoles.startupPolicy(role)

        if (!startup.connectEngineClient) {
            Timber.d("MultiApp process initialized without engine client: role=%s process=%s", role, processName)
            return
        }

        if (!EngineRuntimeInstallers.installSystemServerClient(this)) {
            Timber.e("Engine system-server Binder unavailable; live runtime authority is fail-closed")
        }

        if (!startup.installGuestRuntime) {
            Timber.d("MultiApp host client initialized: process=%s", processName)
            return
        }

        EngineGuestRecentsRecoveryCoordinator.install(this)

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

        Timber.d("MultiApp guest runtime initialized: process=%s", processName)
    }
}
