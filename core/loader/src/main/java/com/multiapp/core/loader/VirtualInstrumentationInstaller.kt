package com.multiapp.core.loader

import android.app.Instrumentation
import android.util.Log

object VirtualInstrumentationInstaller {

    private const val TAG = "VirtualInstrInstaller"

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
            ActivityThreadCompat.setInstrumentation(VirtualInstrumentation(current))
            Log.i(TAG, "VirtualInstrumentation installed: base=${current.javaClass.name}")
        }
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
