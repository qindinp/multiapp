package com.multiapp.core.loader

import android.annotation.TargetApi
import android.app.Instrumentation
import android.os.Build
import android.util.Log

object VirtualInstrumentationInstaller {

    private const val TAG = "VirtualInstrInstaller"
    private const val API_LEVEL_COMPONENT_CALLER = 35

    @Volatile
    private var originalInstrumentation: Instrumentation? = null

    fun install(): Result<Unit> {
        return runCatching {
            val current = ActivityThreadCompat.getInstrumentation()
            if (current is VirtualInstrumentation) {
                Log.i(TAG, "VirtualInstrumentation already installed")
                return@runCatching
            }
            originalInstrumentation = current
            ActivityThreadCompat.setInstrumentation(createVirtualInstrumentation(current))
            Log.i(TAG, "VirtualInstrumentation installed: base=${current.javaClass.name}")
        }
    }

    private fun createVirtualInstrumentation(base: Instrumentation): VirtualInstrumentation {
        return if (Build.VERSION.SDK_INT >= API_LEVEL_COMPONENT_CALLER) {
            createApi35VirtualInstrumentation(base)
        } else {
            VirtualInstrumentation(base)
        }
    }

    @TargetApi(API_LEVEL_COMPONENT_CALLER)
    private fun createApi35VirtualInstrumentation(base: Instrumentation): VirtualInstrumentation {
        return VirtualInstrumentationApi35(base)
    }

    fun restore(): Result<Unit> {
        return runCatching {
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
