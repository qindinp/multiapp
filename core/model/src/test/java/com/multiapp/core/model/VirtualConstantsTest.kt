package com.multiapp.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualConstantsTest {

    @Test
    fun `MAX_PROCESS_SLOTS is 10`() {
        assertEquals(10, VirtualConstants.MAX_PROCESS_SLOTS)
    }

    @Test
    fun `STUB_ACTIVITIES_STANDARD is 5`() {
        assertEquals(5, VirtualConstants.STUB_ACTIVITIES_STANDARD)
    }

    @Test
    fun `STUB_ACTIVITIES_SINGLE_TOP is 2`() {
        assertEquals(2, VirtualConstants.STUB_ACTIVITIES_SINGLE_TOP)
    }

    @Test
    fun `STUB_ACTIVITIES_SINGLE_TASK is 2`() {
        assertEquals(2, VirtualConstants.STUB_ACTIVITIES_SINGLE_TASK)
    }

    @Test
    fun `STUB_ACTIVITIES_SINGLE_INSTANCE is 1`() {
        assertEquals(1, VirtualConstants.STUB_ACTIVITIES_SINGLE_INSTANCE)
    }

    @Test
    fun `total stub activities per slot is 10`() {
        val total = VirtualConstants.STUB_ACTIVITIES_STANDARD +
            VirtualConstants.STUB_ACTIVITIES_SINGLE_TOP +
            VirtualConstants.STUB_ACTIVITIES_SINGLE_TASK +
            VirtualConstants.STUB_ACTIVITIES_SINGLE_INSTANCE

        assertEquals(10, total)
    }

    @Test
    fun `STUB_SERVICES is 5`() {
        assertEquals(5, VirtualConstants.STUB_SERVICES)
    }

    @Test
    fun `STUB_PROVIDERS is 3`() {
        assertEquals(3, VirtualConstants.STUB_PROVIDERS)
    }

    @Test
    fun `STUB_RECEIVERS is 2`() {
        assertEquals(2, VirtualConstants.STUB_RECEIVERS)
    }

    @Test
    fun `total stub components per slot is 20`() {
        val total = VirtualConstants.STUB_ACTIVITIES_STANDARD +
            VirtualConstants.STUB_ACTIVITIES_SINGLE_TOP +
            VirtualConstants.STUB_ACTIVITIES_SINGLE_TASK +
            VirtualConstants.STUB_ACTIVITIES_SINGLE_INSTANCE +
            VirtualConstants.STUB_SERVICES +
            VirtualConstants.STUB_PROVIDERS +
            VirtualConstants.STUB_RECEIVERS

        assertEquals(20, total)
    }

    @Test
    fun `VIRTUAL_DIR is virtual`() {
        assertEquals("virtual", VirtualConstants.VIRTUAL_DIR)
    }

    @Test
    fun `VIRTUAL_APKS_DIR is apks`() {
        assertEquals("apks", VirtualConstants.VIRTUAL_APKS_DIR)
    }

    @Test
    fun `VIRTUAL_DATA_DIR is data`() {
        assertEquals("data", VirtualConstants.VIRTUAL_DATA_DIR)
    }

    @Test
    fun `VIRTUAL_SNAPSHOTS_DIR is snapshots`() {
        assertEquals("snapshots", VirtualConstants.VIRTUAL_SNAPSHOTS_DIR)
    }

    @Test
    fun `HOST_PACKAGE is correct`() {
        assertEquals("com.multiapp.app", VirtualConstants.HOST_PACKAGE)
    }

    @Test
    fun `STUB_PREFIX is correct`() {
        assertEquals("com.multiapp.app.stub", VirtualConstants.STUB_PREFIX)
    }

    @Test
    fun `all directory constants are non-empty`() {
        assertTrue(VirtualConstants.VIRTUAL_DIR.isNotEmpty())
        assertTrue(VirtualConstants.VIRTUAL_APKS_DIR.isNotEmpty())
        assertTrue(VirtualConstants.VIRTUAL_DATA_DIR.isNotEmpty())
        assertTrue(VirtualConstants.VIRTUAL_SNAPSHOTS_DIR.isNotEmpty())
    }

    @Test
    fun `STUB_PREFIX starts with HOST_PACKAGE`() {
        assertTrue(VirtualConstants.STUB_PREFIX.startsWith(VirtualConstants.HOST_PACKAGE))
    }
}
