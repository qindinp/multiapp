package com.multiapp.core.loader

import android.util.Log
import com.multiapp.core.hook.PackerLoadResult

import com.multiapp.core.hook.PackerRuntime
import com.multiapp.core.hook.PackerRuntimeAdaptation
import com.multiapp.core.hook.PackerRuntimeContext
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackerRuntimeStageTest {

    // JVM 单测环境未 mock android.util.Log；PackerRuntimeStage 的诊断日志
    // （Log.d）会命中 android.util.Log.not mocked 错误。照
    // EngineProcessBootstrapTransportTest 的模式：mockkStatic + every + unmockkStatic。
    @BeforeEach
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @AfterEach
    fun unmockAndroidLog() {
        unmockkStatic(Log::class)
    }


    @TempDir
    lateinit var tempDir: Path

    private fun createZip(vararg entries: String): Path {
        val zipPath = tempDir.resolve("stage_${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
            for (name in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.closeEntry()
            }
        }
        return zipPath
    }

    private fun instanceRecord(): VirtualInstanceRecord = VirtualInstanceRecord(
        instanceId = "inst-1",
        originPackageName = "com.example.packed",
        virtualPackageName = "com.multiapp.instance.inst1",
        displayName = "PackedApp",
        dataRoot = "/data/inst-1",
        compatibilityMode = CompatibilityMode.STANDARD,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun baseInput(): BootstrapStageInput = BootstrapStageInput(
        instanceId = "inst-1",
        instance = instanceRecord(),
        originApkPath = "/data/inst-1/base.apk",
        nativeLibraryDir = "/data/inst-1/lib/arm64-v8a",
        guestClassLoader = java.net.URLClassLoader(arrayOf(), ClassLoader.getSystemClassLoader())
    )

    private fun fakeRuntime(detected: Boolean, loadOk: Boolean): PackerRuntime = object : PackerRuntime {
        override val name: String = "FakePacker"
        override fun detect(originLibDir: File?, originApkPath: String?): Boolean = detected
        override fun prepareFiles(context: PackerRuntimeContext): Boolean = true
        override fun loadPackerLibrary(context: PackerRuntimeContext): PackerLoadResult =
            PackerLoadResult(jiaguLoaded = loadOk, stubAppLoadSucceeded = loadOk,
                diagnostics = listOf("fake-diagnostic"))
        override fun verifyRegisterNatives(guestCl: ClassLoader): Boolean = loadOk
    }

    private fun fakeDispatcher(runtimes: List<PackerRuntime>): PackerRuntimeAdaptation =
        object : PackerRuntimeAdaptation {
            override fun detect(originLibDir: File?, originApkPath: String?): PackerRuntime? =
                runtimes.firstOrNull { it.detect(originLibDir, originApkPath) }

            override fun execute(context: PackerRuntimeContext): PackerLoadResult? {
                val runtime = detect(context.originLibDir?.let(::File), context.originApkPath)
                    ?: return null
                val load = runtime.loadPackerLibrary(context)
                return load.copy(stubNativesVerified = runtime.verifyRegisterNatives(context.guestClassLoader))
            }
        }

    @Test
    fun `disabled stage skips without touching dispatcher`() {
        var dispatcherTouched = false
        val stage = PackerRuntimeStage(
            packerEnabled = false,
            dispatcherProvider = {
                dispatcherTouched = true
                fakeDispatcher(emptyList())
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals("PACKER_ADAPTATION_DISABLED",
            output.result.evidence.firstOrNull { it.key == "packerSkipReason" }?.value)
        assertTrue(!dispatcherTouched, "disabled stage must not construct the dispatcher")
    }

    @Test
    fun `missing classloader skips`() {
        val stage = PackerRuntimeStage(packerEnabled = true,
            dispatcherProvider = { fakeDispatcher(emptyList()) })
        val output = stage.execute(baseInput().copy(guestClassLoader = null))
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals("GUEST_CLASS_LOADER_MISSING",
            output.result.evidence.firstOrNull { it.key == "packerSkipReason" }?.value)
    }

    @Test
    fun `no packer detected skips`() {
        val stage = PackerRuntimeStage(packerEnabled = true,
            dispatcherProvider = {
                fakeDispatcher(listOf(fakeRuntime(detected = false, loadOk = false)))
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals("NO_PACKER_DETECTED",
            output.result.evidence.firstOrNull { it.key == "packerSkipReason" }?.value)
        assertTrue(
            output.result.evidence.any { it.key == "preDetectNativeHooks" },
            "NO_PACKER_DETECTED must still record the pre-detect native hook attempt"
        )
        assertTrue(
            output.result.evidence.any { it.key == "javaExitHook" },
            "NO_PACKER_DETECTED must record the Java exit suppression hook attempt"
        )
    }

    @Test
    fun `java exit suppression install attempt is recorded on success path`() {
        val stage = PackerRuntimeStage(packerEnabled = true,
            dispatcherProvider = {
                fakeDispatcher(listOf(fakeRuntime(detected = true, loadOk = true)))
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertTrue(
            evidence.containsKey("javaExitHook"),
            "SUCCESS evidence must record the Java exit suppression hook attempt"
        )
        assertTrue(
            (evidence["javaExitHook"] ?: "").contains("systemExitHooked"),
            "javaExitHook evidence should carry per-target install status: ${evidence["javaExitHook"]}"
        )
    }

    @Test
    fun `packer detected and loaded reports success evidence`() {
        val stage = PackerRuntimeStage(packerEnabled = true,
            dispatcherProvider = {
                fakeDispatcher(listOf(fakeRuntime(detected = true, loadOk = true)))
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("FakePacker", evidence["packerName"] ?: "")
        assertEquals("true", evidence["jiaguLoaded"])
        assertEquals("true", evidence["stubNativesVerified"])
    }

    @Test
    fun `packer detected but load failed reports degraded`() {
        val stage = PackerRuntimeStage(packerEnabled = true,
            dispatcherProvider = {
                fakeDispatcher(listOf(fakeRuntime(detected = true, loadOk = false)))
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.DEGRADED, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("FakePacker", evidence["packerName"] ?: "")
        assertEquals("false", evidence["jiaguLoaded"])
    }

    @Test
    fun `dispatcher exception degrades but does not block`() {
        val stage = PackerRuntimeStage(
            packerEnabled = true,
            dispatcherProvider = {
                throw IllegalStateException("boom")
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.DEGRADED, output.result.status)
        assertTrue(output.result.evidence.any { it.key == "packerStage" && it.value == "DISPATCHER_UNAVAILABLE" })
    }

    @Test
    fun `qq reader family gets QQ_READER_SPECIAL clone profile`() {
        var capturedContext: PackerRuntimeContext? = null
        val runtime = fakeRuntime(detected = true, loadOk = true)
        val dispatcher = object : PackerRuntimeAdaptation {
            override fun detect(originLibDir: File?, originApkPath: String?): PackerRuntime? = runtime
            override fun execute(context: PackerRuntimeContext): PackerLoadResult? {
                capturedContext = context
                return runtime.loadPackerLibrary(context)
            }
        }
        val stage = PackerRuntimeStage(packerEnabled = true, dispatcherProvider = { dispatcher })
        val input = baseInput().copy(
            instance = instanceRecord().copy(originPackageName = "com.qidian.QDReader")
        )
        stage.execute(input)
        assertEquals("QQ_READER_SPECIAL", capturedContext?.cloneProfile)
    }

    @Test
    fun `non qq reader gets null clone profile`() {
        var capturedContext: PackerRuntimeContext? = null
        val runtime = fakeRuntime(detected = true, loadOk = true)
        val dispatcher = object : PackerRuntimeAdaptation {
            override fun detect(originLibDir: File?, originApkPath: String?): PackerRuntime? = runtime
            override fun execute(context: PackerRuntimeContext): PackerLoadResult? {
                capturedContext = context
                return runtime.loadPackerLibrary(context)
            }
        }
        val stage = PackerRuntimeStage(packerEnabled = true, dispatcherProvider = { dispatcher })
        stage.execute(baseInput())
        assertEquals(null, capturedContext?.cloneProfile)
    }

    @Test
    fun compatibilityHookPolicyEnablesCmdlineSpoof() {
        val policy = PackerRuntimeStage.compatibilityHookPolicy()
        assertTrue(policy.cmdlineSpoof, "packed-app COMPATIBILITY policy must spoof /proc/self")
    }

    @Test
    fun `success evidence records typed packer family from apk`() {
        // 真实 360 Jiagu APK fixture -> stage 用 PackerDetector.detectEvidence 记录家族证据
        val apkPath = createZip("lib/arm64-v8a/libjiagu.so")
        val stage = PackerRuntimeStage(
            packerEnabled = true,
            dispatcherProvider = {
                fakeDispatcher(listOf(fakeRuntime(detected = true, loadOk = true)))
            }
        )
        val output = stage.execute(baseInput().copy(originApkPath = apkPath.toString()))
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("QIHOO_360", evidence["packerFamily"] ?: "")
        assertEquals("HIGH", evidence["packerConfidence"] ?: "")
        assertEquals("ROUTE_SPECIFIC", evidence["packerStrategy"] ?: "")
        assertTrue(
            (evidence["packerSignals"] ?: "").contains("L1_SO:libjiagu.so"),
            "expected L1_SO:libjiagu.so in packerSignals: ${evidence["packerSignals"]}"
        )
    }

    @Test
    fun `no packer skip records UNKNOWN family evidence`() {
        val stage = PackerRuntimeStage(
            packerEnabled = true,
            dispatcherProvider = {
                fakeDispatcher(listOf(fakeRuntime(detected = false, loadOk = false)))
            }
        )
        val output = stage.execute(baseInput())
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("UNKNOWN", evidence["packerFamily"] ?: "")
    }
}
