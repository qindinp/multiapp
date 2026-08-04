package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MicroMsgProcessNameCompatTest {

    @Test
    fun isMicroMsgPackageExactMatch() {
        assertTrue(MicroMsgProcessNameCompat.isMicroMsgPackage("com.tencent.mm"))
    }

    @Test
    fun isMicroMsgPackageSubPackage() {
        assertTrue(MicroMsgProcessNameCompat.isMicroMsgPackage("com.tencent.mm.push"))
    }

    @Test
    fun isMicroMsgPackageRejectsUnrelated() {
        assertFalse(MicroMsgProcessNameCompat.isMicroMsgPackage("com.example.app"))
    }

    @Test
    fun isMicroMsgPackageRejectsSimilarButDifferent() {
        assertFalse(MicroMsgProcessNameCompat.isMicroMsgPackage("com.tencent.mx"))
        assertFalse(MicroMsgProcessNameCompat.isMicroMsgPackage("org.tencent.mm"))
    }

    @Test
    fun isMicroMsgPackageRejectsNullAndEmpty() {
        assertFalse(MicroMsgProcessNameCompat.isMicroMsgPackage(null))
        assertFalse(MicroMsgProcessNameCompat.isMicroMsgPackage(""))
    }

    @Test
    fun resolveProcessNameGetterReturnsNullWhenHolderMissing() {
        val loader = object : ClassLoader(getSystemClassLoader()) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == MicroMsgProcessNameCompat.PROCESS_NAME_HOLDER_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(MicroMsgProcessNameCompat.resolveProcessNameGetter(loader))
    }

    @Test
    fun hookResultDefaultHasAnyInstalledFalse() {
        val result = MicroMsgProcessNameCompat.HookResult()
        assertFalse(result.holderClassFound)
        assertFalse(result.getterMethodFound)
        assertFalse(result.hooked)
        assertFalse(result.anyInstalled)
    }

    /**
     * Regression guard for the class-name bug: dexdump prints the raw dex
     * descriptor "Lin5/f1;" whose leading 'L' is the descriptor marker. The
     * binary name for Class.forName must be "in5.f1", not "lin5.f1" (the
     * previous value threw ClassNotFoundException and left WeChat unhooked).
     */
    @Test
    fun holderClassNameMatchesDexDescriptorEvidence() {
        assertEquals("in5.f1", MicroMsgProcessNameCompat.PROCESS_NAME_HOLDER_CLASS)
        assertEquals("a", MicroMsgProcessNameCompat.PROCESS_NAME_GETTER)
        assertEquals("com.tencent.mm", MicroMsgProcessNameCompat.GUEST_PROCESS_NAME)
    }

    @Test
    fun shouldFallbackToGuestDataDirMatchesOriginAndVirtual() {
        assertTrue(
            MicroMsgProcessNameCompat.shouldFallbackToGuestDataDir(
                "com.tencent.mm",
                "com.tencent.mm",
                "com.multiapp.instance.abc"
            )
        )
        assertTrue(
            MicroMsgProcessNameCompat.shouldFallbackToGuestDataDir(
                "com.multiapp.instance.abc",
                "com.tencent.mm",
                "com.multiapp.instance.abc"
            )
        )
    }

    @Test
    fun shouldFallbackToGuestDataDirRejectsHostAndNull() {
        assertFalse(
            MicroMsgProcessNameCompat.shouldFallbackToGuestDataDir(
                "com.multiapp.app",
                "com.tencent.mm",
                "com.multiapp.instance.abc"
            )
        )
        assertFalse(
            MicroMsgProcessNameCompat.shouldFallbackToGuestDataDir(
                null,
                "com.tencent.mm",
                "com.multiapp.instance.abc"
            )
        )
    }

    @Test
    fun resolveFallbackDataDirKeepsOriginalWhenPresent() {
        val original = File("/data/user/0/com.multiapp.app/files/instance_data/abc")
        val guest = File("/data/user/0/com.multiapp.app/files/instance_data/xyz")
        assertSame(original, MicroMsgProcessNameCompat.resolveFallbackDataDir(original, guest))
    }

    @Test
    fun resolveFallbackDataDirUsesGuestDirWhenMissing() {
        val guest = File("/data/user/0/com.multiapp.app/files/instance_data/xyz")
        assertEquals(guest, MicroMsgProcessNameCompat.resolveFallbackDataDir(null, guest))
    }

    @Test
    fun installDataDirFallbackHookReportsContextImplMissingOnJvm() {
        val result = MicroMsgProcessNameCompat.installDataDirFallbackHook(
            guestDataDir = "/data/user/0/com.multiapp.app/files/instance_data/xyz",
            originPackageName = "com.tencent.mm",
            virtualPackageName = "com.multiapp.instance.xyz",
            hookEngine = com.multiapp.core.hook.HookEngine.getInstance()
        )
        assertFalse(result.contextClassFound)
        assertFalse(result.getterMethodFound)
        assertFalse(result.hooked)
    }
}