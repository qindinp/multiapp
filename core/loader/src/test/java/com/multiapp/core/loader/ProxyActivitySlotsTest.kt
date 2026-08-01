package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ProxySlotContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyActivitySlotsTest {

    @Test
    fun `slot assignment file delegates to proxy slot contract`() {
        assertEquals(ProxySlotContract.SLOT_ASSIGNMENT_FILE, ProxyActivitySlots.SLOT_ASSIGNMENT_FILE)
    }

    @Test
    fun `slot catalog exposes 24 proxy slots per launch mode`() {
        val hostPackageName = "com.multiapp.app"
        val classNames = ProxyActivitySlots.classNames(hostPackageName)
        val launchModes = ProxyActivitySlots.launchModeByClassName(hostPackageName)
        val processNames = ProxyActivitySlots.processNameByClassName(hostPackageName)

        assertEquals(72, classNames.size)
        assertEquals(72, launchModes.size)
        assertEquals(72, processNames.size)
        assertEquals("$hostPackageName.container.ProxyActivity0", classNames[0])
        assertEquals("$hostPackageName.container.ProxyActivity23", classNames[23])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTop0", classNames[24])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTop23", classNames[47])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTask0", classNames[48])
        assertEquals("$hostPackageName.container.ProxyActivitySingleTask23", classNames[71])
        assertNull(launchModes["$hostPackageName.container.ProxyActivity0"])
        assertEquals("singleTop", launchModes["$hostPackageName.container.ProxyActivitySingleTop23"])
        assertEquals("singleTask", launchModes["$hostPackageName.container.ProxyActivitySingleTask23"])
        assertEquals("$hostPackageName:v0", processNames["$hostPackageName.container.ProxyActivity0"])
        assertEquals("$hostPackageName:v0", processNames["$hostPackageName.container.ProxyActivitySingleTop0"])
        assertEquals("$hostPackageName:v0", processNames["$hostPackageName.container.ProxyActivitySingleTask0"])
        assertEquals("$hostPackageName:v23", processNames["$hostPackageName.container.ProxyActivity23"])
        assertEquals("$hostPackageName:v23", processNames["$hostPackageName.container.ProxyActivitySingleTop23"])
        assertEquals("$hostPackageName:v23", processNames["$hostPackageName.container.ProxyActivitySingleTask23"])
        assertEquals(24, processNames.values.distinct().size)
    }
}
