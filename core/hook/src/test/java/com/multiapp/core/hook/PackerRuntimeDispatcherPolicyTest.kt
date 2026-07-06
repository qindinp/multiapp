package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackerRuntimeDispatcherPolicyTest {

    @Test
    fun `strict baseline skips entire packer runtime and emits fallback diagnostics`() {
        val runtime = RecordingRuntime()
        val dispatcher = PackerRuntimeDispatcher.withRuntimesForTest(listOf(runtime))
        val context = contextFor(NativeHookPolicy.baseline())

        val result = dispatcher.execute(context)

        assertNotNull(result)
        assertFalse(result.jiaguLoaded)
        assertFalse(result.stubAppLoadSucceeded)
        assertEquals(1, runtime.detectCalls)
        assertEquals(0, runtime.prepareCalls)
        assertEquals(0, runtime.loadCalls)
        assertEquals(0, runtime.verifyCalls)
        assertEquals(0, runtime.postLoadCalls)
        assertEquals(0, runtime.stubFallbackCalls)

        val diagnostics = result.diagnostics.joinToString("\n")
        assertTrue(diagnostics.contains("PACKER_RUNTIME_SKIPPED"), diagnostics)
        assertTrue(diagnostics.contains("reason=strict hook-free baseline"), diagnostics)
        assertTrue(diagnostics.contains("fallbackSkipped=true"), diagnostics)
        assertTrue(diagnostics.contains("capability=LSPLANT_METHOD_HOOKS"), diagnostics)
        assertTrue(diagnostics.contains("capability=XPOSED_MODULES"), diagnostics)
        assertTrue(diagnostics.contains("capability=BUSINESS_NATIVE_STUBS"), diagnostics)
        assertTrue(diagnostics.contains("capability=BUSINESS_NATIVE_WRAPPERS"), diagnostics)
        assertTrue(diagnostics.contains("capability=METHOD_REPLACEMENT"), diagnostics)
        assertTrue(diagnostics.contains("capability=NO_OP_PATCHES"), diagnostics)
        assertTrue(diagnostics.contains("component=PackerRuntimeDispatcher.installStubFallback"), diagnostics)
    }

    @Test
    fun `register natives diagnostic policy executes runtime but keeps compatibility fallbacks gated`() {
        val runtime = RecordingRuntime()
        val dispatcher = PackerRuntimeDispatcher.withRuntimesForTest(listOf(runtime))
        val context = contextFor(NativeHookPolicy.registerNativesDiagnostic())

        val result = dispatcher.execute(context)

        assertNotNull(result)
        assertTrue(result.jiaguLoaded)
        assertTrue(result.stubAppLoadSucceeded)
        assertEquals(1, runtime.detectCalls)
        assertEquals(1, runtime.prepareCalls)
        assertEquals(1, runtime.loadCalls)
        assertEquals(1, runtime.verifyCalls)
        assertEquals(1, runtime.postLoadCalls)
        assertEquals(1, runtime.stubFallbackCalls)
        assertFalse(context.nativeHookPolicy.isEnabled(NativeHookCapability.BUSINESS_NATIVE_STUBS))
        assertFalse(context.nativeHookPolicy.isEnabled(NativeHookCapability.BUSINESS_NATIVE_WRAPPERS))
        assertFalse(context.nativeHookPolicy.isEnabled(NativeHookCapability.NATIVE_BASE_HOOKS))
    }

    private fun contextFor(policy: NativeHookPolicy): PackerRuntimeContext = PackerRuntimeContext(
        guestClassLoader = requireNotNull(javaClass.classLoader),
        originLibDir = "ignored",
        originApkPath = "origin.apk",
        originalApkPath = "original.apk",
        originalPackageName = "example.app",
        cloneProfile = "NORMAL",
        dataDir = null,
        stubApkPath = "stub.apk",
        bridge = NativeHookBridge.getInstance(),
        hookEngine = HookEngine.getInstance(),
        nativeHookPolicy = policy
    )

    private class RecordingRuntime : PackerRuntime {
        var detectCalls = 0
        var prepareCalls = 0
        var loadCalls = 0
        var verifyCalls = 0
        var postLoadCalls = 0
        var stubFallbackCalls = 0

        override val name: String = "RecordingRuntime"

        override fun detect(originLibDir: File?, originApkPath: String?): Boolean {
            detectCalls++
            return true
        }

        override fun prepareFiles(context: PackerRuntimeContext): Boolean {
            prepareCalls++
            return true
        }

        override fun loadPackerLibrary(context: PackerRuntimeContext): PackerLoadResult {
            loadCalls++
            return PackerLoadResult(jiaguLoaded = true, stubAppLoadSucceeded = true)
        }

        override fun verifyRegisterNatives(guestCl: ClassLoader): Boolean {
            verifyCalls++
            return true
        }

        override fun installPostLoadHooks(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
            postLoadCalls++
        }

        override fun installStubFallback(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
            stubFallbackCalls++
        }
    }
}
