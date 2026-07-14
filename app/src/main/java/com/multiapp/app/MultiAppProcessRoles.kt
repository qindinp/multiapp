package com.multiapp.app

import android.app.Application
import android.os.Build
import com.multiapp.core.engine.EngineRuntimeIpcContract
import java.io.File

internal enum class MultiAppProcessRole {
    HOST,
    ENGINE_SERVER,
    GUEST,
    UNKNOWN
}

internal data class MultiAppProcessStartupPolicy(
    val connectEngineClient: Boolean,
    val installGuestRuntime: Boolean
)

internal object MultiAppProcessRoles {
    private const val GUEST_PROCESS_SLOT_COUNT = 8

    fun currentProcessName(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Application.getProcessName().orEmpty()
        else -> runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrDefault("")
    }

    fun resolve(hostPackageName: String, processName: String): MultiAppProcessRole {
        if (hostPackageName.isBlank() || processName.isBlank()) return MultiAppProcessRole.UNKNOWN
        if (processName == hostPackageName) return MultiAppProcessRole.HOST
        if (processName == EngineRuntimeIpcContract.engineProcessName(hostPackageName)) {
            return MultiAppProcessRole.ENGINE_SERVER
        }
        val prefix = "$hostPackageName:v"
        val slot = processName.removePrefix(prefix).toIntOrNull()
        return if (
            processName.startsWith(prefix) &&
            slot != null &&
            slot in 0 until GUEST_PROCESS_SLOT_COUNT &&
            processName == "$prefix$slot"
        ) {
            MultiAppProcessRole.GUEST
        } else {
            MultiAppProcessRole.UNKNOWN
        }
    }

    fun startupPolicy(role: MultiAppProcessRole): MultiAppProcessStartupPolicy = when (role) {
        MultiAppProcessRole.HOST -> MultiAppProcessStartupPolicy(
            connectEngineClient = true,
            installGuestRuntime = false
        )
        MultiAppProcessRole.GUEST -> MultiAppProcessStartupPolicy(
            connectEngineClient = true,
            installGuestRuntime = true
        )
        MultiAppProcessRole.ENGINE_SERVER,
        MultiAppProcessRole.UNKNOWN -> MultiAppProcessStartupPolicy(
            connectEngineClient = false,
            installGuestRuntime = false
        )
    }
}
