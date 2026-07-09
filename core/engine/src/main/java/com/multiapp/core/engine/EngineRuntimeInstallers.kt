package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorders
import com.multiapp.core.loader.VirtualBroadcastRecorders
import com.multiapp.core.loader.VirtualInstrumentationInstaller

object EngineRuntimeInstallers {
    fun installInstrumentation(): Result<Unit> =
        VirtualInstrumentationInstaller.install(
            processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
            activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
        )

    fun installBroadcastRecorder(recorder: EngineBroadcastRecorder) {
        VirtualBroadcastRecorders.install(recorder)
    }

    fun installAmsApiEvidenceRecorder(recorder: EngineAmsApiEvidenceRecorder) {
        VirtualAmsApiEvidenceRecorders.install(recorder)
    }
}
