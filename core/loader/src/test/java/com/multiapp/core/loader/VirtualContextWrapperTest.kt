package com.multiapp.core.loader

import com.multiapp.core.model.virtual.VirtualContextConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VirtualContextWrapperTest {

    @Test
    fun `VirtualContextConfig carries instance identity`() {
        val config = VirtualContextConfig(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc",
            dataDir = "/data/user/0/com.multiapp/app_instance/inst-001",
            sourceDir = "/data/user/0/com.multiapp/app_instance/inst-001/base.apk",
            nativeLibraryDir = "/data/user/0/com.multiapp/app_instance/inst-001/lib",
            classLoader = ClassLoader.getSystemClassLoader()
        )

        assertEquals("inst-001", config.instanceId)
        assertEquals("com.example.app", config.originPackageName)
        assertEquals("com.multiapp.instance.abc", config.virtualPackageName)
        assertEquals("/data/user/0/com.multiapp/app_instance/inst-001", config.dataDir)
        assertEquals("/data/user/0/com.multiapp/app_instance/inst-001/base.apk", config.sourceDir)
        assertEquals("/data/user/0/com.multiapp/app_instance/inst-001/lib", config.nativeLibraryDir)
    }

    @Test
    fun `VirtualContextConfig nativeLibraryDir can be null`() {
        val config = VirtualContextConfig(
            instanceId = "inst-002",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.def",
            dataDir = "/tmp/test",
            sourceDir = "/tmp/test/base.apk",
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )

        assertNull(config.nativeLibraryDir)
    }

    @Test
    fun `VirtualContextConfig equality by data class`() {
        val a = VirtualContextConfig("i1", "o1", "v1", "/d1", "/s1", null, ClassLoader.getSystemClassLoader())
        val b = VirtualContextConfig("i1", "o1", "v1", "/d1", "/s1", null, ClassLoader.getSystemClassLoader())
        assertEquals(a, b)
    }
}
