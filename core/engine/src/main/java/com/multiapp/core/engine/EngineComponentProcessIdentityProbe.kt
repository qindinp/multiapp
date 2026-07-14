package com.multiapp.core.engine

import java.io.File

data class EngineComponentProcessHostIdentity(
    val processName: String,
    val processStartTicks: Long
) {
    init {
        require(processName.isNotBlank()) { "processName must not be blank" }
        require(processStartTicks > 0L) { "processStartTicks must be positive" }
    }
}

fun interface EngineComponentProcessIdentityProbe {
    fun read(processId: Int): EngineComponentProcessHostIdentity?

    companion object {
        val PROCFS = procfs(
            readCmdline = { processId -> File("/proc/$processId/cmdline").readBytes() },
            readStat = { processId -> File("/proc/$processId/stat").readText() }
        )

        internal val PLATFORM_DEFAULT: EngineComponentProcessIdentityProbe?
            get() = PROCFS.takeIf { isAndroidRuntime() }

        internal fun procfs(
            readCmdline: (Int) -> ByteArray,
            readStat: (Int) -> String
        ) = EngineComponentProcessIdentityProbe { processId ->
            if (processId <= 0) return@EngineComponentProcessIdentityProbe null
            runCatching {
                val processName = readCmdline(processId)
                    .toString(Charsets.UTF_8)
                    .substringBefore('\u0000')
                    .takeIf(String::isNotBlank)
                    ?: return@runCatching null
                val stat = readStat(processId)
                val closingNameDelimiter = stat.lastIndexOf(')')
                    .takeIf { index -> index >= 0 }
                    ?: return@runCatching null
                val fieldsAfterName = stat
                    .substring(closingNameDelimiter + 1)
                    .trim()
                    .split(PROC_STAT_WHITESPACE)
                val processStartTicks = fieldsAfterName
                    .getOrNull(PROC_STAT_STARTTIME_OFFSET_AFTER_NAME)
                    ?.toLongOrNull()
                    ?.takeIf { ticks -> ticks > 0L }
                    ?: return@runCatching null
                EngineComponentProcessHostIdentity(processName, processStartTicks)
            }.getOrNull()
        }

        private const val PROC_STAT_STARTTIME_OFFSET_AFTER_NAME = 19
        private val PROC_STAT_WHITESPACE = Regex("\\s+")

        private fun isAndroidRuntime(): Boolean = runCatching {
            System.getProperty("java.vm.name").equals("Dalvik", ignoreCase = true) ||
                System.getProperty("java.runtime.name").equals("Android Runtime", ignoreCase = true)
        }.getOrDefault(false)
    }
}
