package com.multiapp.core.model.virtual

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VirtualContextFactoryTest {

    @Test
    fun `VirtualContextConfig data class with all values`() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val config = VirtualContextConfig(
            instanceId = "test-instance-001",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            dataDir = "/data/data/com.example.virtual/test-instance-001",
            sourceDir = "/data/app/com.example.origin/base.apk",
            nativeLibraryDir = "/data/app/com.example.origin/lib/arm64",
            classLoader = classLoader
        )

        assertEquals("test-instance-001", config.instanceId)
        assertEquals("com.example.origin", config.originPackageName)
        assertEquals("com.example.virtual", config.virtualPackageName)
        assertEquals("/data/data/com.example.virtual/test-instance-001", config.dataDir)
        assertEquals("/data/app/com.example.origin/base.apk", config.sourceDir)
        assertEquals("/data/app/com.example.origin/lib/arm64", config.nativeLibraryDir)
        assertEquals(classLoader, config.classLoader)
        assertEquals("com.example.origin", config.effectiveGuestProcessName)
    }

    @Test
    fun `VirtualContextConfig data class with null nativeLibraryDir`() {
        val config = VirtualContextConfig(
            instanceId = "test-instance-002",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            dataDir = "/data/data/com.example.virtual/test-instance-002",
            sourceDir = "/data/app/com.example.origin/base.apk",
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )

        assertNull(config.nativeLibraryDir)
    }

    @Test
    fun `VirtualContextConfig inherits base and split paths from package snapshot`() {
        val snapshot = VirtualPackageSnapshot(
            instanceId = "test-instance-003",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            applicationLabel = "Example",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 23,
            sourceDir = "/runtime/base.apk",
            publicSourceDir = "/public/base.apk",
            splitSourceDirs = listOf("/runtime/split_config.arm64_v8a.apk"),
            splitPublicSourceDirs = listOf("/public/split_config.arm64_v8a.apk"),
            splitNames = listOf("config.arm64_v8a"),
            dataDir = "/data/data/com.example.virtual/test-instance-003",
            processName = ":worker"
        )
        val config = VirtualContextConfig(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = snapshot.nativeLibraryDir,
            classLoader = ClassLoader.getSystemClassLoader(),
            packageSnapshot = snapshot
        )

        assertEquals("/public/base.apk", config.publicSourceDir)
        assertEquals(
            listOf("/runtime/base.apk", "/runtime/split_config.arm64_v8a.apk"),
            config.codeSourceDirs
        )
        assertEquals(
            listOf("/public/base.apk", "/public/split_config.arm64_v8a.apk"),
            config.publicResourceDirs
        )
        assertEquals(listOf("config.arm64_v8a"), config.splitNames)
        assertEquals("com.example.origin:worker", config.effectiveGuestProcessName)
    }

    @Test
    fun `VirtualContextConfig immutable copy creates new instance`() {
        val original = VirtualContextConfig(
            instanceId = "instance-1",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            dataDir = "/data/virtual/instance-1",
            sourceDir = "/data/app/origin.apk",
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val copied = original.copy(
            instanceId = "instance-2",
            dataDir = "/data/virtual/instance-2"
        )

        assertEquals("instance-1", original.instanceId)
        assertEquals("instance-2", copied.instanceId)
        assertEquals(original.originPackageName, copied.originPackageName)
        assertEquals(original.virtualPackageName, copied.virtualPackageName)
    }

    @Test
    fun `VirtualContextConfig equality works correctly`() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val config1 = VirtualContextConfig(
            instanceId = "instance-1",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            dataDir = "/data/virtual/instance-1",
            sourceDir = "/data/app/origin.apk",
            nativeLibraryDir = null,
            classLoader = classLoader
        )
        val config2 = VirtualContextConfig(
            instanceId = "instance-1",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            dataDir = "/data/virtual/instance-1",
            sourceDir = "/data/app/origin.apk",
            nativeLibraryDir = null,
            classLoader = classLoader
        )

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `VirtualContextConfig destructuring works`() {
        val config = VirtualContextConfig(
            instanceId = "inst-1",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.example.virtual",
            dataDir = "/data/dir",
            sourceDir = "/data/source.apk",
            nativeLibraryDir = "/lib/dir",
            classLoader = ClassLoader.getSystemClassLoader()
        )

        val (instanceId, originPackageName, virtualPackageName, dataDir, sourceDir, nativeLibDir, classLoader) = config

        assertEquals("inst-1", instanceId)
        assertEquals("com.example.origin", originPackageName)
        assertEquals("com.example.virtual", virtualPackageName)
        assertEquals("/data/dir", dataDir)
        assertEquals("/data/source.apk", sourceDir)
        assertEquals("/lib/dir", nativeLibDir)
        assertNotNull(classLoader)
    }

    @Test
    fun `VirtualContextFactory interface exists and can be implemented`() {
        val factory = object : VirtualContextFactory {
            override fun createGuestContext(config: VirtualContextConfig): VirtualContextSpec {
                return VirtualContextSpec.from(config)
            }
        }

        assertNotNull(factory)
    }

    @Test
    fun `VirtualContextSpec maps config without Android framework objects`() {
        val config = VirtualContextConfig(
            instanceId = "test-instance-004",
            originPackageName = "com.example.origin",
            virtualPackageName = "com.multiapp.instance.example",
            dataDir = "/data/user/0/com.multiapp.app/files/instances/test-instance-004/data",
            sourceDir = "/runtime/base.apk",
            nativeLibraryDir = "/runtime/lib/arm64-v8a",
            classLoader = ClassLoader.getSystemClassLoader(),
            processSlot = ":v2",
            effectiveGuestProcessName = "com.example.origin:remote"
        )

        val spec = VirtualContextSpec.from(config)

        assertEquals(config.virtualPackageName, spec.packageName)
        assertEquals(config.originPackageName, spec.originPackageName)
        assertEquals("${config.dataDir}/files", spec.filesDir)
        assertEquals("${config.dataDir}/cache", spec.cacheDir)
        assertEquals(config.nativeLibraryDir, spec.nativeLibraryDir)
        assertEquals(":v2", spec.processSlot)
        assertEquals("com.example.origin:remote", spec.effectiveGuestProcessName)
    }
}
