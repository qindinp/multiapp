package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ProxySlotContract

object ProxyActivitySlots {
    const val SLOT_COUNT: Int = 24
    const val SLOT_ASSIGNMENT_FILE: String = ProxySlotContract.SLOT_ASSIGNMENT_FILE

    fun classNames(hostPackageName: String): List<String> = buildList {
        repeat(SLOT_COUNT) { index ->
            add("$hostPackageName.container.ProxyActivity$index")
        }
        repeat(SLOT_COUNT) { index ->
            add("$hostPackageName.container.ProxyActivitySingleTop$index")
        }
        repeat(SLOT_COUNT) { index ->
            add("$hostPackageName.container.ProxyActivitySingleTask$index")
        }
    }

    fun launchModeByClassName(hostPackageName: String): Map<String, String?> = buildMap {
        repeat(SLOT_COUNT) { index ->
            put("$hostPackageName.container.ProxyActivity$index", null)
        }
        repeat(SLOT_COUNT) { index ->
            put("$hostPackageName.container.ProxyActivitySingleTop$index", "singleTop")
        }
        repeat(SLOT_COUNT) { index ->
            put("$hostPackageName.container.ProxyActivitySingleTask$index", "singleTask")
        }
    }

    fun processNameByClassName(hostPackageName: String): Map<String, String> = buildMap {
        repeat(SLOT_COUNT) { index ->
            val processName = "$hostPackageName:v$index"
            put("$hostPackageName.container.ProxyActivity$index", processName)
            put("$hostPackageName.container.ProxyActivitySingleTop$index", processName)
            put("$hostPackageName.container.ProxyActivitySingleTask$index", processName)
        }
    }

    fun processNameForClassName(hostPackageName: String, className: String): String? =
        processNameByClassName(hostPackageName)[className]

    fun classNamesForProcessSlot(hostPackageName: String, processSlot: String?): List<String> {
        val index = processSlotIndex(hostPackageName, processSlot) ?: return classNames(hostPackageName)
        return listOf(
            "$hostPackageName.container.ProxyActivity$index",
            "$hostPackageName.container.ProxyActivitySingleTop$index",
            "$hostPackageName.container.ProxyActivitySingleTask$index"
        )
    }

    fun processSlotIndex(hostPackageName: String, processSlot: String?): Int? {
        if (processSlot.isNullOrBlank()) return null
        val expectedPrefix = "$hostPackageName:v"
        if (!processSlot.startsWith(expectedPrefix)) return null
        return processSlot
            .removePrefix(expectedPrefix)
            .toIntOrNull()
            ?.takeIf { it in 0 until SLOT_COUNT }
    }
}
