package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyActivitySlotsTest {

    @Test
    fun `slot catalog exposes eight proxy slots per launch mode`() {
        val hostPackageName = "com.multiapp.app"
        val classNames = ProxyActivitySlots.classNames(hostPackageName)
        val launchModes = ProxyActivitySlots.launchModeByClassName(hostPackageName)
        val processNames = ProxyActivitySlots.processNameByClassName(hostPackageName)

        assertEquals(24, classNames.size)
        assertEquals(24, launchModes.size)
        assertEquals(24, processNames.size)
        assertEquals("$hostPackageName.container.ProxyActivity0", classNames[0])
        assertEquals("$hostPackageName.container.ProxyActivity7", classNames[7])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTop0", classNames[8])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTop7", classNames[15])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTask0", classNames[16])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTask7", classNames[23])
        assertNull(launchModes["$hostPackageName.container.ProxyActivity0"])
        assertEquals("singleTop", launchModes["$hostPackageName.container.ProxyActivitySingleTop7"])
        assertEquals("singleTask", launchModes["$hostPackageName.container.ProxyActivitySingleTask7"])
        assertEquals("$hostPackageName:v0", processNames["$hostPackageName.container.ProxyActivity0"])
        assertEquals("$hostPackageName:v0", processNames["$hostPackageName.container.ProxyActivitySingleTop0"])
        assertEquals("$hostPackageName:v0", processNames["$hostPackageName.container.ProxyActivitySingleTask0"])
        assertEquals("$hostPackageName:v7", processNames["$hostPackageName.container.ProxyActivity7"])
        assertEquals("$hostPackageName:v7", processNames["$hostPackageName.container.ProxyActivitySingleTop7"])
        assertEquals("$hostPackageName:v7", processNames["$hostPackageName.container.ProxyActivitySingleTask7"])
        assertEquals(8, processNames.values.distinct().size)
    }
}
