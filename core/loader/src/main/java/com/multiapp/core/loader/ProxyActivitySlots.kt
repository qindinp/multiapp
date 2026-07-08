package com.multiapp.core.loader

object ProxyActivitySlots {
    const val SLOT_COUNT: Int = 8
    const val SLOT_ASSIGNMENT_FILE: String = "proxy_activity_slots.properties"

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

    fun processNameByClassName(hostPackageName: String): Map<String, String> =
        classNames(hostPackageName).mapIndexed { index, className ->
            className to "$hostPackageName:v$index"
        }.toMap()

    fun processNameForClassName(hostPackageName: String, className: String): String? =
        processNameByClassName(hostPackageName)[className]
}
