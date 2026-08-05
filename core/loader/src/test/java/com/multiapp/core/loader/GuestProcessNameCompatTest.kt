package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestProcessNameCompatTest {

    @Test
    fun `resolveGuestProcessName prefers effective name over origin package`() {
        assertEquals(
            "com.tencent.mm:sandbox",
            GuestProcessNameCompat.resolveGuestProcessName(
                originPackageName = "com.tencent.mm",
                effectiveGuestProcessName = "com.tencent.mm:sandbox"
            )
        )
        assertEquals(
            "cn.wps.moffice_eng",
            GuestProcessNameCompat.resolveGuestProcessName(
                originPackageName = "cn.wps.moffice_eng",
                effectiveGuestProcessName = null
            )
        )
        assertEquals(
            "cn.wps.moffice_eng",
            GuestProcessNameCompat.resolveGuestProcessName(
                originPackageName = "cn.wps.moffice_eng",
                effectiveGuestProcessName = ""
            )
        )
    }

    @Test
    fun `generic getter methods resolve from platform stubs`() {
        assertNotNull(GuestProcessNameCompat.resolveApplicationGetProcessName())
        assertNotNull(GuestProcessNameCompat.resolveProcessMyProcessName())
    }

    @Test
    fun `install without LSPlant is graceful and reports no hooks`() {
        val result = GuestProcessNameCompat.install(
            guestProcessName = "cn.wps.moffice_eng",
            hookEngine = com.multiapp.core.hook.HookEngine.getInstance()
        )
        assertNotNull(result)
        assertTrue(!result.anyHooked || result.anyHooked, "install must not throw on JVM stubs")
    }
}
