package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EnginePrewarmPolicy
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineTaskPolicy
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.IdentityHashMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EngineVirtualizationEndpointContractTest {
    private lateinit var bundles: BundleHarness

    @BeforeEach
    fun installBundleHarness() {
        bundles = BundleHarness().also { it.installConstructorMock() }
    }

    @AfterEach
    fun removeBundleHarness() {
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun `install endpoint returns the authoritative engine result`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = EngineResult.pass(
            operation = "installOrRefreshPackage",
            originPackageName = ORIGIN_PACKAGE,
            message = "snapshot refreshed"
        )
        every { engine.installOrRefreshPackage(ORIGIN_PACKAGE) } returns expected

        val result = endpoint(engine).engineInstallOrRefreshPackage(ORIGIN_PACKAGE)
            .toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        assertNull(result?.runtimeIdentity)
        verify(exactly = 1) { engine.installOrRefreshPackage(ORIGIN_PACKAGE) }
    }

    @Test
    fun `create endpoint returns the authoritative engine result`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = EngineResult.pass(
            operation = "createInstance",
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            message = "instance created"
        )
        every { engine.createInstance(ORIGIN_PACKAGE) } returns expected

        val result = endpoint(engine).engineCreateInstance(ORIGIN_PACKAGE)
            .toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        verify(exactly = 1) { engine.createInstance(ORIGIN_PACKAGE) }
    }

    @Test
    fun `launch endpoint decodes the full request and returns runtime identity`() {
        val engine = mockk<VirtualizationEngine>()
        val request = launchRequest()
        val runtime = runtime()
        val expected = EngineResult.pass(
            operation = "launchInstance",
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            message = "launched",
            runtime = runtime
        )
        every { engine.launchInstance(request) } returns expected

        val result = endpoint(engine).engineLaunchInstance(
            request.toEngineIpcBundle(bundles::create)
        ).toEngineRemoteResultOrNull()

        assertEquals(expected.copy(runtime = null), result?.result)
        assertTrue(requireNotNull(result?.runtimeIdentity).matches(runtime))
        verify(exactly = 1) { engine.launchInstance(request) }
    }

    @Test
    fun `stop endpoint returns the authoritative engine result`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = EngineResult.pass(
            operation = "stopInstance",
            instanceId = INSTANCE_ID,
            message = "runtime stopped"
        )
        every { engine.stopInstance(INSTANCE_ID) } returns expected

        val result = endpoint(engine).engineStopInstance(INSTANCE_ID)
            .toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        verify(exactly = 1) { engine.stopInstance(INSTANCE_ID) }
    }

    @Test
    fun `query endpoint returns the authoritative runtime identity`() {
        val engine = mockk<VirtualizationEngine>()
        val runtime = runtime()
        every { engine.queryRuntimeState(INSTANCE_ID) } returns runtime

        val identity = endpoint(engine).engineQueryRuntimeState(INSTANCE_ID)
            .toEngineRuntimeIdentityOrNull()

        assertTrue(requireNotNull(identity).matches(runtime))
        verify(exactly = 1) { engine.queryRuntimeState(INSTANCE_ID) }
    }

    @Test
    fun `export endpoint returns the authoritative evidence report`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = evidence()
        every { engine.exportEvidence(INSTANCE_ID) } returns expected

        val report = endpoint(engine).engineExportEvidence(INSTANCE_ID)
            .toEngineEvidenceOrNull()

        assertEquals(expected, report)
        verify(exactly = 1) { engine.exportEvidence(INSTANCE_ID) }
    }

    @Test
    fun `launch endpoint rejects a malformed Bundle without invoking the engine`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)
        val malformed = launchRequest().toEngineIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE, "UNKNOWN_PROFILE")
        }

        val result = endpoint(engine).engineLaunchInstance(malformed)
            .toEngineRemoteResultOrNull()

        assertEquals(EngineResultStatus.FAIL, result?.result?.status)
        assertEquals("launchInstance", result?.result?.operation)
        assertEquals("invalid_engine_ipc_request", result?.result?.message)
        verify(exactly = 0) { engine.launchInstance(any()) }
    }

    @Test
    fun `query and export fail closed when the server owner is unavailable`() {
        val endpoint = endpoint(virtualizationEngine = null)

        val query = endpoint.engineQueryRuntimeState(INSTANCE_ID)
            .toEngineRemoteResultOrNull()
        val export = endpoint.engineExportEvidence(INSTANCE_ID)
            .toEngineRemoteResultOrNull()

        assertEquals(EngineResultStatus.FAIL, query?.result?.status)
        assertEquals("queryRuntimeState", query?.result?.operation)
        assertEquals("engine_server_owner_unavailable", query?.result?.message)
        assertNull(endpoint.engineQueryRuntimeState(INSTANCE_ID).toEngineRuntimeIdentityOrNull())
        assertEquals(EngineResultStatus.FAIL, export?.result?.status)
        assertEquals("exportEvidence", export?.result?.operation)
        assertEquals("engine_server_owner_unavailable", export?.result?.message)
        assertNull(endpoint.engineExportEvidence(INSTANCE_ID).toEngineEvidenceOrNull())
    }

    @Test
    fun `query endpoint returns an explicit strict miss when runtime does not exist`() {
        val engine = mockk<VirtualizationEngine>()
        every { engine.queryRuntimeState(INSTANCE_ID) } returns null

        val response = endpoint(engine).engineQueryRuntimeState(INSTANCE_ID)

        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(
            EngineResultStatus.FAIL.name,
            response.getString(EngineRuntimeIpcContract.KEY_STATUS)
        )
        assertEquals(INSTANCE_ID, response.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals("runtime_not_found", response.getString(EngineRuntimeIpcContract.KEY_REASON))
        assertNull(response.toEngineRuntimeIdentityOrNull())
        verify(exactly = 1) { engine.queryRuntimeState(INSTANCE_ID) }
    }

    @Test
    fun `export endpoint returns strict failure evidence when runtime is missing`() {
        val engine = mockk<VirtualizationEngine>()
        val missing = EngineEvidenceReport(
            instanceId = INSTANCE_ID,
            evidenceSessionId = "runtime-missing",
            status = EngineResultStatus.FAIL,
            profile = EngineProfile.BASELINE,
            entries = mapOf("reason" to "runtime_not_found")
        )
        every { engine.exportEvidence(INSTANCE_ID) } returns missing

        assertEquals(
            missing,
            missing.toEngineEvidenceBundle(bundles::create).toEngineEvidenceOrNull()
        )

        val response = endpoint(engine).engineExportEvidence(INSTANCE_ID)
            .toEngineEvidenceOrNull()

        assertEquals(EngineResultStatus.FAIL, response?.status)
        assertEquals(INSTANCE_ID, response?.instanceId)
        assertEquals("runtime-missing", response?.evidenceSessionId)
        assertEquals(EngineProfile.BASELINE, response?.profile)
        verify(exactly = 1) { engine.exportEvidence(INSTANCE_ID) }
    }

    @Test
    fun `query and export reject a caller outside the host uid`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)
        val endpoint = endpoint(engine, callerUid = HOST_UID + 1)

        val query = endpoint.engineQueryRuntimeState(INSTANCE_ID)
        val export = endpoint.engineExportEvidence(INSTANCE_ID)

        assertUnauthorized(query)
        assertUnauthorized(export)
        assertNull(query.toEngineRuntimeIdentityOrNull())
        assertNull(export.toEngineEvidenceOrNull())
        verify(exactly = 0) { engine.queryRuntimeState(any()) }
        verify(exactly = 0) { engine.exportEvidence(any()) }
    }

    private fun endpoint(
        virtualizationEngine: VirtualizationEngine?,
        callerUid: Int = HOST_UID
    ): EngineRuntimeBinderEndpoint {
        // Local JVM tests have no Android Binder implementation. Allocate the concrete
        // endpoint without running Stub.attachInterface; these contract methods only
        // require the owner and caller-authorization fields initialized below.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        val endpoint = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, EngineRuntimeBinderEndpoint::class.java) as EngineRuntimeBinderEndpoint
        setEndpointField(endpoint, "hostUid", HOST_UID)
        setEndpointField(endpoint, "virtualizationEngine", virtualizationEngine)
        setEndpointField(endpoint, "callingUid", { callerUid })
        return endpoint
    }

    private fun setEndpointField(
        endpoint: EngineRuntimeBinderEndpoint,
        name: String,
        value: Any?
    ) {
        EngineRuntimeBinderEndpoint::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(endpoint, value)
        }
    }

    private fun assertUnauthorized(response: Bundle) {
        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(
            EngineResultStatus.FAIL.name,
            response.getString(EngineRuntimeIpcContract.KEY_STATUS)
        )
        assertEquals("caller_uid_mismatch", response.getString(EngineRuntimeIpcContract.KEY_REASON))
    }

    private fun launchRequest() = LaunchInstanceRequest(
        instanceId = INSTANCE_ID,
        profile = EngineProfile.COMPAT_HOOK,
        requestedLauncherActivityClass = "com.test.Launcher",
        reason = "recents",
        targetComponentClassName = "com.test.Target",
        launchFlags = 0x10200000,
        taskPolicy = EngineTaskPolicy.REUSE_EXISTING,
        prewarmPolicy = EnginePrewarmPolicy.REQUIRED,
        evidenceMode = EngineEvidenceMode.FULL
    )

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = "com.multiapp.instance.test",
        dataRoot = "/data/user/0/$HOST_PACKAGE/files/instances/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = "com.multiapp.instance.test",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "/data/app/test/base.apk",
            dataDir = "/data/user/0/$HOST_PACKAGE/files/instances/$INSTANCE_ID"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "$HOST_PACKAGE:v2",
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity2",
        evidenceSessionId = "evidence-session",
        runtimeEpoch = 7L,
        engineSessionId = "engine-session",
        processId = 4321,
        processName = "$HOST_PACKAGE:v2",
        state = VirtualRuntimeState.RUNNING
    )

    private fun evidence() = EngineEvidenceReport(
        instanceId = INSTANCE_ID,
        evidenceSessionId = "evidence-session",
        status = EngineResultStatus.PARTIAL,
        profile = EngineProfile.BASELINE
    )

    private class BundleHarness {
        private val values = IdentityHashMap<Bundle, MutableMap<String, Any?>>()

        fun installConstructorMock() {
            mockkConstructor(Bundle::class)
            every { anyConstructed<Bundle>().putString(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<String?>()
            }
            every { anyConstructed<Bundle>().getString(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? String
            }
            every { anyConstructed<Bundle>().putBoolean(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { anyConstructed<Bundle>().getBoolean(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Boolean ?: false
            }
            every { anyConstructed<Bundle>().putInt(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Int>()
            }
            every { anyConstructed<Bundle>().getInt(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Int ?: 0
            }
            every { anyConstructed<Bundle>().putLong(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Long>()
            }
            every { anyConstructed<Bundle>().getLong(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Long ?: 0L
            }
            every { anyConstructed<Bundle>().putBundle(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { anyConstructed<Bundle>().getBundle(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Bundle
            }
            every { anyConstructed<Bundle>().containsKey(any()) } answers {
                valuesFor(self as Bundle).containsKey(firstArg())
            }
            every { anyConstructed<Bundle>().keySet() } answers {
                valuesFor(self as Bundle).keys
            }
        }

        fun create(): Bundle {
            val bundle = mockk<Bundle>()
            every { bundle.putString(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<String?>()
            }
            every { bundle.getString(any()) } answers {
                valuesFor(bundle)[firstArg()] as? String
            }
            every { bundle.putBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { bundle.getBoolean(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: false
            }
            every { bundle.putInt(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Int>()
            }
            every { bundle.getInt(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Int ?: 0
            }
            every { bundle.putLong(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Long>()
            }
            every { bundle.getLong(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Long ?: 0L
            }
            every { bundle.putBundle(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { bundle.getBundle(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Bundle
            }
            every { bundle.containsKey(any()) } answers {
                valuesFor(bundle).containsKey(firstArg())
            }
            every { bundle.keySet() } answers { valuesFor(bundle).keys }
            return bundle
        }

        private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
            values.getOrPut(bundle) { linkedMapOf() }
    }

    private companion object {
        const val INSTANCE_ID = "instance-test"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val HOST_UID = 10123
    }
}
