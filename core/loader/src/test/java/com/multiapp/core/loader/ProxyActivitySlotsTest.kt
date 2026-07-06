package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyActivitySlotsTest {

    @Test
    fun `slot catalog exposes four proxy slots per launch mode`() {
        val hostPackageName = "com.multiapp.app"
        val classNames = ProxyActivitySlots.classNames(hostPackageName)
        val launchModes = ProxyActivitySlots.launchModeByClassName(hostPackageName)

        assertEquals(12, classNames.size)
        assertEquals(12, launchModes.size)
        assertEquals("$hostPackageName.container.ProxyActivity0", classNames[0])
        assertEquals("$hostPackageName.container.ProxyActivity3", classNames[3])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTop0", classNames[4])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTop3", classNames[7])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTask0", classNames[8])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTask3", classNames[11])
        assertNull(launchModes["$hostPackageName.container.ProxyActivity0"])
        assertEquals("singleTop", launchModes["$hostPackageName.container.ProxyActivitySingleTop2"])
        assertEquals("singleTask", launchModes["$hostPackageName.container.ProxyActivitySingleTask2"])
    }
}
