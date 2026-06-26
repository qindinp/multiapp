package com.multiapp.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainerRuntimeRecordsTest {

    @Test
    fun `protected baseline mode is hook-free by contract`() {
        assertTrue(CompatibilityMode.PROTECTED_BASELINE.requiresHookFreeRuntime)
        assertFalse(CompatibilityMode.PROTECTED_BASELINE.requiresRuntimeHooks)
        assertTrue(CompatibilityMode.PROTECTED_BASELINE.isHookFree)
        assertFalse(CompatibilityMode.PROTECTED_BASELINE.allowsOptionalHookRuntime)
        assertTrue(CompatibilityMode.entries.all { it.isHookFree })
        assertEquals(CompatibilityMode.APP_CONTAINER, CompatibilityMode.DEFAULT)
    }

    @Test
    fun `install manifest tracks origin native libraries`() {
        val manifest = InstallArtifactManifest(
            originPackageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0",
            originApkPath = "/virtual/apks/qqreader.apk",
            originApkSha256 = "origin-sha",
            containerBuildId = "container-1",
            compatibilityProfileId = "hook-free",
            abiList = listOf("arm64-v8a"),
            createdAtMillis = 1000,
            artifacts = listOf(
                InstallArtifact(
                    kind = InstallArtifactKind.ORIGIN_APK,
                    path = "/virtual/apks/qqreader.apk",
                    sha256 = "origin-sha",
                    sizeBytes = 42
                ),
                InstallArtifact(
                    kind = InstallArtifactKind.NATIVE_LIBRARY,
                    path = "/virtual/lib/arm64-v8a/libjiagu_vip.so",
                    sha256 = "lib-sha",
                    sizeBytes = 24,
                    abi = "arm64-v8a"
                )
            )
        )

        assertTrue(manifest.hasOriginNativeLibraries())
        assertEquals(1, manifest.artifactsOf(InstallArtifactKind.NATIVE_LIBRARY).size)
        assertEquals(
            listOf(
                "/virtual/apks/qqreader.apk",
                "/virtual/lib/arm64-v8a/libjiagu_vip.so"
            ),
            manifest.artifactPaths()
        )
    }

    @Test
    fun `virtual package exposes protected baseline flag`() {
        val record = VirtualPackageRecord(
            packageName = "com.qq.reader",
            appName = "QQ Reader",
            sourceApkPath = "/virtual/apks/qqreader.apk",
            sourceApkSha256 = "origin-sha",
            compatibilityMode = CompatibilityMode.PROTECTED_BASELINE
        )

        assertTrue(record.isProtectedBaseline)
        assertTrue(record.isHookFree)
    }

    @Test
    fun `virtual instance reuses isolated data root`() {
        val dataRoot = InstanceDataRoot.forUser(
            instanceId = "qqreader-0",
            originalPackageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0"
        )
        val record = VirtualInstanceRecord(
            instanceId = "qqreader-0",
            packageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0",
            userPartitionName = "owner",
            dataRoot = dataRoot,
            cloneProfile = CloneProfile.QQ_READER_SPECIAL,
            compatibilityMode = CompatibilityMode.PROTECTED_BASELINE,
            createdAtMillis = 1000
        )

        assertTrue(record.protectedBaselineEnabled)
        assertTrue(record.isHookFree)
        assertTrue(record.canLaunch())
        assertEquals("/data/data/com.multiapp.app.stub.p0/files", record.dataRoot.filesDir)
    }

    @Test
    fun `virtual package owns matching instances and counts components`() {
        val dataRoot = InstanceDataRoot.forUser(
            instanceId = "qqreader-0",
            originalPackageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0"
        )
        val instance = VirtualInstanceRecord(
            instanceId = "qqreader-0",
            packageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0",
            userPartitionName = "owner",
            dataRoot = dataRoot,
            createdAtMillis = 1000
        )
        val packageRecord = VirtualPackageRecord(
            packageName = "com.qq.reader",
            appName = "QQ Reader",
            sourceApkPath = "/virtual/apks/qqreader.apk",
            sourceApkSha256 = "origin-sha",
            activities = listOf(VirtualComponentRecord("ReaderActivity")),
            services = listOf(VirtualComponentRecord("ReaderService")),
            receivers = listOf(VirtualComponentRecord("ReaderReceiver"))
        )

        assertTrue(packageRecord.ownsInstance(instance))
        assertEquals(3, packageRecord.declaredComponentCount())
    }

    @Test
    fun `virtual instance launch state is immutable`() {
        val dataRoot = InstanceDataRoot.forUser(
            instanceId = "qqreader-0",
            originalPackageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0"
        )
        val instance = VirtualInstanceRecord(
            instanceId = "qqreader-0",
            packageName = "com.qq.reader",
            stubPackageName = "com.multiapp.app.stub.p0",
            userPartitionName = "owner",
            dataRoot = dataRoot,
            createdAtMillis = 1000
        )
        val slot = ProcessSlot(slotIndex = 0, processName = ":p0")
        val running = instance.withLaunchState(slot, launchedAtMillis = 2000)

        assertEquals(VirtualInstanceState.STOPPED, instance.state)
        assertEquals(VirtualInstanceState.RUNNING, running.state)
        assertEquals(slot, running.processSlot)
        assertEquals(2000, running.lastLaunchedAtMillis)
    }

    @Test
    fun `records reject blank package names`() {
        assertFailsWith<IllegalArgumentException> {
            VirtualPackageRecord(
                packageName = "",
                appName = "QQ Reader",
                sourceApkPath = "/virtual/apks/qqreader.apk",
                sourceApkSha256 = "origin-sha"
            )
        }
    }
}
