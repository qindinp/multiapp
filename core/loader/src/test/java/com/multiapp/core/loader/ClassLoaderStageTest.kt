package com.multiapp.core.loader

import com.multiapp.core.model.installer.InstallRecord
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClassLoaderStageTest {

    @Test
    fun `structured factory receives guest target sdk and namespace paths`() {
        var capturedSpec: GuestClassLoaderSpec? = null
        val classLoader = ClassLoader.getSystemClassLoader()
        val installRecord = installRecord(
            originApkPath = "/artifact/base.apk",
            splitApkPaths = listOf("/artifact/feature.apk")
        )
        val stage = ClassLoaderStage(
            structuredClassLoaderFactory = GuestClassLoaderFactory { spec ->
                capturedSpec = spec
                GuestClassLoaderCreation(
                    classLoader = classLoader,
                    namespaceVerdict = GuestClassLoaderNamespaceVerdict.PASS,
                    creationMethod = "TEST_PLATFORM_FACTORY"
                )
            },
            clock = fixedClock(50L, 55L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath,
                nativeLibraryDir = "/data/instances/inst-001/lib/arm64-v8a"
            )
        )

        val spec = requireNotNull(capturedSpec)
        assertEquals(35, spec.targetSdkVersion)
        assertEquals("/artifact/base.apk", spec.baseApkPath)
        assertEquals(listOf("/artifact/feature.apk"), spec.splitApkPaths)
        assertTrue(spec.librarySearchPath.orEmpty().contains("/data/instances/inst-001/lib/arm64-v8a"))
        assertTrue(
            spec.libraryPermittedPath.replace('\\', '/')
                .contains("/data/instances/inst-001/lib/arm64-v8a")
        )
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("PASS", evidence["namespaceVerdict"])
        assertEquals("35", evidence["namespaceTargetSdk"])
        assertEquals("TEST_PLATFORM_FACTORY", evidence["namespaceCreationMethod"])
        assertEquals(classLoader.parent?.javaClass?.name.orEmpty(), evidence["classLoaderParentClass"])
        assertEquals(
            classLoader.parent?.let(System::identityHashCode)?.toString().orEmpty(),
            evidence["classLoaderParentIdentity"]
        )
    }

    @Test
    fun `structured namespace failure is terminal`() {
        val installRecord = installRecord("/artifact/base.apk")
        val stage = ClassLoaderStage(
            structuredClassLoaderFactory = GuestClassLoaderFactory {
                throw UnsatisfiedLinkError("namespace rejected")
            },
            clock = fixedClock(60L, 67L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath
            )
        )

        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("ClassLoader creation failed: namespace rejected", output.result.message)
        assertTrue(output.isTerminalFailure)
    }

    @Test
    fun `execute creates classloader and stores it in context`() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> classLoader },
            clock = fixedClock(100L, 107L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk",
                nativeLibraryDir = "/data/instances/inst-001/lib"
            )
        )

        assertEquals(RuntimeStage.CLASS_LOADER, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(7L, output.result.durationMs)
        assertSame(classLoader, output.context.guestClassLoader)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(classLoader.javaClass.name, evidence["classLoaderClass"])
        assertEquals("/data/instances/inst-001/lib", evidence["nativeLibraryDir"])
    }

    @Test
    fun `execute passes origin apk path and native library dir to factory`() {
        var capturedApkPath: String? = null
        var capturedNativeLibraryDir: String? = null
        val stage = ClassLoaderStage(
            classLoaderFactory = { apkPath, nativeLibraryDir ->
                capturedApkPath = apkPath
                capturedNativeLibraryDir = nativeLibraryDir
                ClassLoader.getSystemClassLoader()
            },
            clock = fixedClock(200L, 203L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk",
                nativeLibraryDir = "/data/instances/inst-001/lib"
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("/artifact/app.apk", capturedApkPath)
        assertEquals("/data/instances/inst-001/lib", capturedNativeLibraryDir)
    }

    @Test
    fun `execute builds dex path from base apk and split apk paths`() {
        var capturedDexPath: String? = null
        val classLoader = ClassLoader.getSystemClassLoader()
        val installRecord = installRecord(
            originApkPath = "/artifact/base.apk",
            splitApkPaths = listOf("/artifact/split_config.en.apk", "/artifact/split_feature.apk")
        )
        val stage = ClassLoaderStage(
            classLoaderFactory = { dexPath, _ ->
                capturedDexPath = dexPath
                classLoader
            },
            clock = fixedClock(250L, 254L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath,
                nativeLibraryDir = "/data/instances/inst-001/lib/arm64-v8a"
            )
        )

        val expectedDexPath = listOf(
            "/artifact/base.apk",
            "/artifact/split_config.en.apk",
            "/artifact/split_feature.apk"
        ).joinToString(File.pathSeparator)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(expectedDexPath, capturedDexPath)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(expectedDexPath, evidence["classLoaderDexPath"])
        assertEquals("3", evidence["classLoaderApkPathCount"])
        assertEquals(
            "/artifact/split_config.en.apk,/artifact/split_feature.apk",
            evidence["classLoaderSplitSourceDirs"]
        )
    }

    @Test
    fun `execute appends additional evidence to classloader result`() {
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            clock = fixedClock(300L, 305L)
        )

        val output = stage.execute(
            input = BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk"
            ),
            additionalEvidence = listOf(
                BootstrapEvidence("providerRoutingEnabled", "true"),
                BootstrapEvidence("providerHookInstallStatus", "SKIPPED")
            )
        )

        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["providerRoutingEnabled"])
        assertEquals("SKIPPED", evidence["providerHookInstallStatus"])
    }

    @Test
    fun `execute fails terminally when factory throws`() {
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> throw RuntimeException("dex load failed") },
            clock = fixedClock(400L, 411L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk"
            )
        )

        assertEquals(RuntimeStage.CLASS_LOADER, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("ClassLoader creation failed: dex load failed", output.result.message)
        assertEquals(11L, output.result.durationMs)
        assertNull(output.context.guestClassLoader)
        assertTrue(output.isTerminalFailure)
    }

    @Test
    fun `execute fails terminally when origin apk path is missing`() {
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            clock = fixedClock(500L, 502L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.CLASS_LOADER, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Origin APK path is required before ClassLoader creation", output.result.message)
        assertEquals(2L, output.result.durationMs)
        assertTrue(output.isTerminalFailure)
    }

    private fun installRecord(
        originApkPath: String,
        splitApkPaths: List<String> = emptyList()
    ) = InstallRecord(
        packageName = "com.example.app",
        originApkPath = originApkPath,
        originApkSha256 = "sha256",
        originCertSha256 = "cert-sha256",
        splitApkPaths = splitApkPaths,
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        installTimeMs = 500L
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
