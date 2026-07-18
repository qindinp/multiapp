package com.multiapp.core.loader

import android.annotation.TargetApi
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.util.Log
import com.multiapp.core.common.AndroidCompat

object VirtualInstrumentationInstaller {

    private const val TAG = "VirtualInstrInstaller"
    private const val API_LEVEL_COMPONENT_CALLER = 35

    @Volatile
    private var originalInstrumentation: Instrumentation? = null

    fun install(
        processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
        activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
        activityOperations: VirtualActivityOperations =
            ManagerBackedVirtualActivityOperations(activityRecordManager),
        processHostContext: Context? = null
    ): Result<Unit> {
        return runCatching {
            val hiddenApiBypassApplied = AndroidCompat.bypassHiddenApis()
            if (!hiddenApiBypassApplied) {
                Log.w(TAG, "Hidden API bypass unavailable before runtime instrumentation install")
            }
            val current = ActivityThreadCompat.getInstrumentation()
            if (current is VirtualInstrumentation) {
                current.bindProcessHostContext(processHostContext)
                ActivityThreadLaunchCallbackInstaller.install(processRuntime).getOrThrow()
                Log.i(TAG, "VirtualInstrumentation already installed")
                return@runCatching
            }
            originalInstrumentation = current
            ActivityThreadCompat.setInstrumentation(
                createVirtualInstrumentation(
                    current,
                    processRuntime,
                    activityRecordManager,
                    activityOperations,
                    processHostContext
                )
            )
            ActivityThreadLaunchCallbackInstaller.install(processRuntime).getOrThrow()
            Log.i(TAG, "VirtualInstrumentation installed: base=${current.javaClass.name}")
        }
    }

    private fun createVirtualInstrumentation(
        base: Instrumentation,
        processRuntime: VirtualProcessRuntime,
        activityRecordManager: VirtualActivityRecordManager,
        activityOperations: VirtualActivityOperations,
        processHostContext: Context?
    ): VirtualInstrumentation {
        return if (Build.VERSION.SDK_INT >= API_LEVEL_COMPONENT_CALLER) {
            createApi35VirtualInstrumentation(
                base,
                processRuntime,
                activityRecordManager,
                activityOperations,
                processHostContext
            )
        } else {
            VirtualInstrumentation(
                base,
                processRuntime,
                activityRecordManager,
                activityOperations,
                processHostContext
            )
        }
    }

    @TargetApi(API_LEVEL_COMPONENT_CALLER)
    private fun createApi35VirtualInstrumentation(
        base: Instrumentation,
        processRuntime: VirtualProcessRuntime,
        activityRecordManager: VirtualActivityRecordManager,
        activityOperations: VirtualActivityOperations,
        processHostContext: Context?
    ): VirtualInstrumentation {
        return VirtualInstrumentationApi35(
            base,
            processRuntime,
            activityRecordManager,
            activityOperations,
            processHostContext
        )
    }

    fun restore(): Result<Unit> {
        return runCatching {
            ActivityThreadLaunchCallbackInstaller.restore()
            val original = originalInstrumentation ?: return@runCatching
            ActivityThreadCompat.setInstrumentation(original)
            originalInstrumentation = null
            Log.i(TAG, "VirtualInstrumentation restored: ${original.javaClass.name}")
        }
    }

    fun isInstalled(): Boolean {
        return runCatching { ActivityThreadCompat.getInstrumentation() is VirtualInstrumentation }
            .getOrDefault(false)
    }
}
