package com.multiapp.core.engine

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineCapability
import com.multiapp.core.model.engine.EngineCapabilityReport
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EnginePrewarmPolicy
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineTaskPolicy
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
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
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `server generation is visible only to the host uid`() {
        assertEquals("server-generation-test", endpoint(null).getServerGenerationId())
        assertEquals("", endpoint(null, callerUid = HOST_UID + 1).getServerGenerationId())
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
    fun `refresh endpoint decodes the typed request`() {
        val engine = mockk<VirtualizationEngine>()
        val request = createRequest().install
        val expected = EngineResult.pass(
            operation = "refreshPackage",
            originPackageName = ORIGIN_PACKAGE,
            message = "generation refreshed"
        )
        every { engine.refreshPackage(request) } returns expected

        val result = endpoint(engine).engineRefreshPackage(
            request.toEngineIpcBundle(bundles::create)
        ).toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        verify(exactly = 1) { engine.refreshPackage(request) }
        verify(exactly = 0) { engine.installOrRefreshPackage(any<String>()) }
    }

    @Test
    fun `refresh endpoint rejects malformed request without invoking engine`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)

        val result = endpoint(engine).engineRefreshPackage(bundles.create())
            .toEngineRemoteResultOrNull()

        assertEquals(EngineResultStatus.FAIL, result?.result?.status)
        assertEquals("invalid_engine_ipc_request", result?.result?.message)
        verify(exactly = 0) { engine.refreshPackage(any()) }
    }

    @Test
    fun `capability endpoint returns strict authoritative report`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = EngineCapabilityReport(
            instanceId = INSTANCE_ID,
            status = EngineResultStatus.PARTIAL,
            capabilities = listOf(
                EngineCapability(
                    id = "activity",
                    subsystem = EngineSubsystem.ACTIVITY,
                    status = EngineResultStatus.PARTIAL,
                    releaseCritical = true
                )
            ),
            generatedAtMs = 123L,
            message = "runtime capability catalog"
        )
        every { engine.queryCapabilities(INSTANCE_ID) } returns expected

        val result = endpoint(engine).engineQueryCapabilities(INSTANCE_ID)
            .toEngineCapabilityReportOrNull()

        assertEquals(expected, result)
        verify(exactly = 1) { engine.queryCapabilities(INSTANCE_ID) }
    }

    @Test
    fun `capability endpoint rejects blank instance id without invoking engine`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)

        val result = endpoint(engine).engineQueryCapabilities(" ")
            .toEngineCapabilityReportOrNull()

        assertEquals(EngineResultStatus.FAIL, result?.status)
        assertEquals("invalid_instance_id", result?.message)
        verify(exactly = 0) { engine.queryCapabilities(any()) }
    }

    @Test
    fun `clear endpoint preserves requested identity when engine owner is unavailable`() {
        val result = endpoint(null).engineClearInstanceData(INSTANCE_ID)
            .toEngineRemoteResultOrNull()
            ?.result

        assertEquals(EngineResultStatus.FAIL, result?.status)
        assertEquals("clearInstanceData", result?.operation)
        assertEquals(INSTANCE_ID, result?.instanceId)
        assertEquals("engine_server_owner_unavailable", result?.message)
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
    fun `component process cannot invoke management control plane`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)
        val endpoint = endpoint(
            virtualizationEngine = engine,
            callerProcessName = "$HOST_PACKAGE:v4"
        )

        val responses = listOf(
            endpoint.engineInstallOrRefreshPackage(ORIGIN_PACKAGE),
            endpoint.engineRefreshPackage(createRequest().install.toEngineIpcBundle(bundles::create)),
            endpoint.engineCreateInstance(ORIGIN_PACKAGE),
            endpoint.engineCreateInstanceWithMetadata(createRequest().toEngineIpcBundle(bundles::create)),
            endpoint.engineLaunchInstance(launchRequest().toEngineIpcBundle(bundles::create)),
            endpoint.engineStopInstance(INSTANCE_ID),
            endpoint.engineDeleteInstance(INSTANCE_ID),
            endpoint.engineClearInstanceData(INSTANCE_ID),
            endpoint.engineQueryRuntimeState(INSTANCE_ID),
            endpoint.engineQueryCapabilities(INSTANCE_ID),
            endpoint.engineExportEvidence(INSTANCE_ID),
            endpoint.queryEvidence(INSTANCE_ID)
        )

        responses.forEach { response ->
            assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
            assertEquals(
                "management_caller_process_mismatch",
                response.getString(EngineRuntimeIpcContract.KEY_REASON)
            )
        }
        assertFalse(endpoint.stopRuntime(INSTANCE_ID, 1L))
        verify(exactly = 0) {
            engine.installOrRefreshPackage(any<String>())
            engine.refreshPackage(any())
            engine.createInstance(any<String>())
            engine.createInstance(any<CreateInstanceRequest>())
            engine.launchInstance(any())
            engine.stopInstance(any())
            engine.deleteInstance(any())
            engine.clearInstanceData(any())
            engine.queryRuntimeState(any())
            engine.queryCapabilities(any())
            engine.exportEvidence(any())
        }
    }

    @Test
    fun `metadata create endpoint decodes the idempotent request`() {
        val engine = mockk<VirtualizationEngine>()
        val request = createRequest()
        val expected = EngineResult.pass(
            operation = "createInstance",
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            message = "instance created"
        )
        every { engine.createInstance(request) } returns expected

        val result = endpoint(engine).engineCreateInstanceWithMetadata(
            request.toEngineIpcBundle(bundles::create)
        ).toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        verify(exactly = 1) { engine.createInstance(request) }
        verify(exactly = 0) { engine.createInstance(ORIGIN_PACKAGE) }
    }

    @Test
    fun `metadata create endpoint rejects malformed request without invoking engine`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)

        val result = endpoint(engine).engineCreateInstanceWithMetadata(bundles.create())
            .toEngineRemoteResultOrNull()

        assertEquals(EngineResultStatus.FAIL, result?.result?.status)
        assertEquals("invalid_engine_ipc_request", result?.result?.message)
        verify(exactly = 0) { engine.createInstance(any<CreateInstanceRequest>()) }
    }

    @Test
    fun `launch endpoint decodes the full request and returns runtime payload`() {
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
        )

        assertEquals("launchInstance", result.getString(EngineRuntimeIpcContract.KEY_OPERATION))
        assertEquals(EngineResultStatus.PASS.name, result.getString(EngineRuntimeIpcContract.KEY_STATUS))
        assertEquals(
            INSTANCE_ID,
            result.getBundle(EngineRuntimeIpcContract.KEY_ENGINE_RUNTIME)
                ?.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID)
        )
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
    fun `delete endpoint returns the authoritative engine result`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = EngineResult.pass(
            operation = "deleteInstance",
            instanceId = INSTANCE_ID,
            message = "instance deleted"
        )
        every { engine.deleteInstance(INSTANCE_ID) } returns expected

        val result = endpoint(engine).engineDeleteInstance(INSTANCE_ID)
            .toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        verify(exactly = 1) { engine.deleteInstance(INSTANCE_ID) }
    }

    @Test
    fun `clear data endpoint returns the authoritative engine result`() {
        val engine = mockk<VirtualizationEngine>()
        val expected = EngineResult.pass(
            operation = "clearInstanceData",
            instanceId = INSTANCE_ID,
            message = "instance data cleared"
        )
        every { engine.clearInstanceData(INSTANCE_ID) } returns expected

        val result = endpoint(engine).engineClearInstanceData(INSTANCE_ID)
            .toEngineRemoteResultOrNull()

        assertEquals(expected, result?.result)
        verify(exactly = 1) { engine.clearInstanceData(INSTANCE_ID) }
    }

    @Test
    fun `query endpoint returns only lightweight runtime identity`() {
        val engine = mockk<VirtualizationEngine>()
        val runtime = runtime()
        every { engine.queryRuntimeState(INSTANCE_ID) } returns runtime

        val decoded = endpoint(engine).engineQueryRuntimeState(INSTANCE_ID)

        assertTrue(decoded.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(INSTANCE_ID, decoded.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals(runtime.runtimeEpoch, decoded.getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH))
        assertNull(decoded.getBundle("packageSnapshot"))
        verify(exactly = 1) { engine.queryRuntimeState(INSTANCE_ID) }
    }

    @Test
    fun `runtime stream endpoint returns descriptor to the host process without bundling snapshot`() {
        val engine = mockk<VirtualizationEngine>()
        val runtime = runtime()
        val descriptor = mockk<ParcelFileDescriptor>()
        every { engine.queryRuntimeState(INSTANCE_ID) } returns runtime
        val endpoint = endpoint(engine).also {
            setEndpointField(it, "callingProcessName", { _: Int -> HOST_PACKAGE })
            setEndpointField(it, "authoritativeRuntimeStreamOpener", { value: VirtualInstanceRuntime ->
                assertEquals(runtime, value)
                descriptor
            })
        }

        val result = endpoint.engineOpenRuntimeState(INSTANCE_ID, bundles.create())

        assertSame(descriptor, result)
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
        assertNull(endpoint.engineQueryRuntimeState(INSTANCE_ID).toAuthoritativeRuntimeOrNull())
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
        assertNull(response.toAuthoritativeRuntimeOrNull())
        verify(exactly = 1) { engine.queryRuntimeState(INSTANCE_ID) }
    }

    @Test
    fun `general PID runtime query is self-only and rejects ambiguity`() {
        val registry = EngineRuntimeRegistry().apply { register(runtime()) }
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(INSTANCE_ID, 4321) } returns EngineProcessAuthorityDecision(
            allowed = true,
            identity = EngineProcessClientIdentity(
                instanceId = INSTANCE_ID,
                runtimeEpoch = 7L,
                engineSessionId = "engine-session",
                processSlot = "$HOST_PACKAGE:v2",
                processId = 4321
            ),
            reason = "live_runtime_authority_confirmed"
        )
        val endpoint = endpoint(virtualizationEngine = null).also {
            setEndpointField(it, "registry", registry)
            setEndpointField(it, "processControlPlane", controlPlane)
        }

        val forged = endpoint.queryRuntimeByProcessId(4322)
        assertFalse(forged.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(
            "runtime_process_id_not_calling_pid",
            forged.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
        val found = endpoint.queryRuntimeByProcessId(4321)
        assertTrue(found.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertTrue(found.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY))
        assertEquals(INSTANCE_ID, found.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals(4321, found.getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID))

        registry.register(
            runtime().copy(
                instanceId = "instance-other",
                virtualPackageName = "com.multiapp.instance.other",
                packageSnapshot = runtime().packageSnapshot.copy(
                    instanceId = "instance-other",
                    virtualPackageName = "com.multiapp.instance.other"
                )
            )
        )
        every { controlPlane.authorize("instance-other", 4321) } returns EngineProcessAuthorityDecision(
            allowed = true,
            identity = EngineProcessClientIdentity(
                instanceId = "instance-other",
                runtimeEpoch = 7L,
                engineSessionId = "engine-session",
                processSlot = "$HOST_PACKAGE:v2",
                processId = 4321
            ),
            reason = "live_runtime_authority_confirmed"
        )
        val ambiguous = endpoint.queryRuntimeByProcessId(4321)
        assertFalse(ambiguous.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals("runtime_process_id_ambiguous", ambiguous.getString(EngineRuntimeIpcContract.KEY_REASON))
    }

    @Test
    fun `URI permission target resolver authorizes a cross-process primary runtime`() {
        val caller = runtime()
        val target = runtimeFor(
            instanceId = "instance-target",
            processId = 4322,
            processSlot = "$HOST_PACKAGE:v3",
            runtimeEpoch = 8L,
            engineSessionId = "engine-target"
        )
        val registry = EngineRuntimeRegistry().apply {
            register(caller)
            register(target)
        }
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(any(), any()) } returns deniedProcessAuthority()
        every {
            controlPlane.authorize(caller.instanceId, CALLING_PID)
        } returns primaryProcessAuthority(caller)
        every {
            controlPlane.authorize(target.instanceId, 4322)
        } returns primaryProcessAuthority(target)
        val endpoint = uriPermissionEndpoint(registry, controlPlane)

        val response = endpoint.resolveUriPermissionCheckTarget(caller.instanceId, 4322)

        assertTrue(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertTrue(response.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY))
        assertEquals(target.instanceId, response.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals(4322, response.getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID))
        assertEquals(target.processSlot, response.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT))
        assertEquals(
            "uri_permission_target_primary_runtime_authorized",
            response.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
    }

    @Test
    fun `URI permission check authorizes caller separately from cross-instance target`() {
        val caller = runtime()
        val target = runtimeFor(
            instanceId = "instance-uri-target",
            processId = 4322,
            processSlot = "$HOST_PACKAGE:v3",
            runtimeEpoch = 8L,
            engineSessionId = "engine-uri-target"
        )
        val registry = EngineRuntimeRegistry().apply {
            register(caller)
            register(target)
        }
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(any(), any()) } returns deniedProcessAuthority()
        every { controlPlane.authorize(caller.instanceId, CALLING_PID) } returns
            primaryProcessAuthority(caller)
        every { controlPlane.authorize(target.instanceId, target.processId!!) } returns
            primaryProcessAuthority(target)
        val providerService = mockk<VirtualProviderService>()
        every { providerService.checkUriPermission(target.instanceId, any()) } answers {
            val request = secondArg<VirtualProviderUriGrantRequest>()
            VirtualProviderUriGrantResult(
                ownerInstanceId = caller.instanceId,
                targetInstanceId = target.instanceId,
                guestAuthority = request.guestAuthority,
                encodedPath = request.encodedPath,
                modeFlags = request.modeFlags,
                verdict = EngineResultStatus.PASS,
                granted = true,
                message = "provider_uri_grant_confirmed"
            )
        }
        val endpoint = uriPermissionEndpoint(
            registry = registry,
            controlPlane = controlPlane,
            providerService = providerService
        )
        val request = VirtualProviderUriGrantRequest(
            guestAuthority = "com.example.provider",
            encodedPath = "/items/1",
            modeFlags = EngineProviderUriGrantModes.READ,
            ownerInstanceId = caller.instanceId,
            targetInstanceId = target.instanceId,
            callingUid = HOST_UID,
            callingPid = target.processId!!,
            hostUid = HOST_UID
        )

        val response = endpoint.checkProviderUriPermissionForCaller(
            caller.instanceId,
            target.instanceId,
            providerUriGrantRequestBundle(request)
        )

        assertEquals(
            EngineResultStatus.PASS.name,
            response.getString(EngineRuntimeIpcContract.KEY_VERDICT)
        )
        assertTrue(response.getBoolean(EngineRuntimeIpcContract.KEY_GRANTED))
        assertEquals(
            target.instanceId,
            response.getString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID)
        )
        verify(exactly = 1) {
            providerService.checkUriPermission(
                target.instanceId,
                match { checked ->
                    checked.callingPid == target.processId &&
                        checked.callingUid == HOST_UID &&
                        checked.hostUid == HOST_UID
                }
            )
        }
    }

    @Test
    fun `URI permission target resolver accepts self and rejects forged caller and unknown PID`() {
        val caller = runtime()
        val forgedCallerRuntime = runtimeFor(
            instanceId = "instance-forged",
            processId = 4322,
            processSlot = "$HOST_PACKAGE:v3",
            runtimeEpoch = 8L,
            engineSessionId = "engine-forged"
        )
        val registry = EngineRuntimeRegistry().apply {
            register(caller)
            register(forgedCallerRuntime)
        }
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(any(), any()) } returns deniedProcessAuthority()
        every {
            controlPlane.authorize(caller.instanceId, CALLING_PID)
        } returns primaryProcessAuthority(caller)
        val endpoint = uriPermissionEndpoint(registry, controlPlane)

        val self = endpoint.resolveUriPermissionCheckTarget(caller.instanceId, CALLING_PID)
        assertTrue(self.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(caller.instanceId, self.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals(CALLING_PID, self.getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID))

        val forgedCaller = endpoint.resolveUriPermissionCheckTarget(
            forgedCallerRuntime.instanceId,
            4333
        )
        assertUriPermissionTargetRejected(
            forgedCaller,
            "uri_permission_caller_runtime_unauthorized"
        )

        val unknownTarget = endpoint.resolveUriPermissionCheckTarget(caller.instanceId, 4999)
        assertUriPermissionTargetRejected(
            unknownTarget,
            "uri_permission_target_process_not_found"
        )
    }

    @Test
    fun `URI permission target resolver rejects a stale primary generation`() {
        val caller = runtime()
        val target = runtimeFor(
            instanceId = "instance-stale-target",
            processId = 4322,
            processSlot = "$HOST_PACKAGE:v3",
            runtimeEpoch = 8L,
            engineSessionId = "engine-stale-target"
        )
        val registry = EngineRuntimeRegistry().apply {
            register(caller)
            register(target)
        }
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(any(), any()) } returns deniedProcessAuthority()
        every {
            controlPlane.authorize(caller.instanceId, CALLING_PID)
        } returns primaryProcessAuthority(caller)
        every {
            controlPlane.authorize(target.instanceId, 4322)
        } returns EngineProcessAuthorityDecision(
            allowed = true,
            identity = EngineProcessClientIdentity(
                instanceId = target.instanceId,
                runtimeEpoch = target.runtimeEpoch + 1L,
                engineSessionId = target.engineSessionId,
                processSlot = target.processSlot,
                processId = 4322
            ),
            reason = "stale_test_authority"
        )
        val endpoint = uriPermissionEndpoint(registry, controlPlane)

        val response = endpoint.resolveUriPermissionCheckTarget(caller.instanceId, 4322)

        assertUriPermissionTargetRejected(
            response,
            "uri_permission_target_process_not_found"
        )
    }

    @Test
    fun `URI permission target resolver accepts live component caller and target generations`() {
        val caller = runtimeFor(
            instanceId = "instance-component-caller",
            processId = 4301,
            processSlot = "$HOST_PACKAGE:v4",
            runtimeEpoch = 9L,
            engineSessionId = "engine-component-caller"
        )
        val target = runtimeFor(
            instanceId = "instance-component-target",
            processId = 4302,
            processSlot = "$HOST_PACKAGE:v5",
            runtimeEpoch = 10L,
            engineSessionId = "engine-component-target"
        )
        val registry = EngineRuntimeRegistry().apply {
            register(caller)
            register(target)
        }
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(any(), any()) } returns deniedProcessAuthority()
        val componentAuthority = mockk<EngineComponentProcessAuthority>()
        val callerIdentity = componentProcessIdentity(
            runtime = caller,
            processId = CALLING_PID,
            processSlot = "$HOST_PACKAGE:p8",
            processStartTicks = 101L,
            effectiveGuestProcessName = "$ORIGIN_PACKAGE:worker"
        )
        val targetIdentity = componentProcessIdentity(
            runtime = target,
            processId = 4333,
            processSlot = "$HOST_PACKAGE:p9",
            processStartTicks = 202L,
            effectiveGuestProcessName = "$ORIGIN_PACKAGE:remote"
        )
        every {
            componentAuthority.authorizeCaller(any(), any(), any(), any())
        } returns null
        every {
            componentAuthority.authorizeCaller(
                caller.instanceId,
                CALLING_PID,
                callerIdentity.processSlot,
                callerIdentity.processStartTicks
            )
        } returns callerIdentity
        every {
            componentAuthority.authorizeCaller(
                target.instanceId,
                targetIdentity.processId,
                targetIdentity.processSlot,
                targetIdentity.processStartTicks
            )
        } returns targetIdentity
        val endpoint = uriPermissionEndpoint(
            registry = registry,
            controlPlane = controlPlane,
            componentAuthority = componentAuthority,
            processName = { processId ->
                when (processId) {
                    callerIdentity.processId -> callerIdentity.processSlot
                    targetIdentity.processId -> targetIdentity.processSlot
                    else -> null
                }
            },
            processStartTicks = { processId ->
                when (processId) {
                    callerIdentity.processId -> callerIdentity.processStartTicks
                    targetIdentity.processId -> targetIdentity.processStartTicks
                    else -> null
                }
            }
        )

        val response = endpoint.resolveUriPermissionCheckTarget(
            caller.instanceId,
            targetIdentity.processId
        )

        assertTrue(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertTrue(response.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY))
        assertEquals(target.instanceId, response.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals(
            targetIdentity.processId,
            response.getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID)
        )
        assertEquals(
            targetIdentity.processSlot,
            response.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT)
        )
        assertEquals(
            "uri_permission_target_component_runtime_authorized",
            response.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
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
    fun `package enabled-state endpoint enforces strict fields and runtime identity`() {
        val identity = EngineProcessClientIdentity(
            instanceId = INSTANCE_ID,
            runtimeEpoch = 9L,
            engineSessionId = "engine-package-state",
            processSlot = "com.multiapp.app:v2",
            processId = CALLING_PID
        )
        val packageService = mockk<VirtualPackageService>()
        every { packageService.queryApplicationEnabledState(INSTANCE_ID) } returns
            VirtualPackageEnabledStateResult(
                instanceId = INSTANCE_ID,
                target = EnginePackageEnabledStateTarget.APPLICATION,
                enabledState = EnginePackageEnabledStates.DISABLED_USER,
                verdict = EngineResultStatus.PASS,
                found = true,
                message = "application_enabled_state_queried"
            )
        val endpoint = endpoint(
            virtualizationEngine = null,
            packageService = packageService,
            processIdentity = identity
        )

        val valid = endpoint.queryApplicationEnabledState(
            INSTANCE_ID,
            identity.toPackageEnabledStateRequestBundle(
                target = EnginePackageEnabledStateTarget.APPLICATION,
                bundleFactory = bundles::create
            )
        ).toPackageEnabledStateResultOrNull()
        val malformedRequest = identity.toPackageEnabledStateRequestBundle(
            target = EnginePackageEnabledStateTarget.APPLICATION,
            bundleFactory = bundles::create
        ).apply {
            putString("unexpected", "value")
        }
        val malformed = endpoint.queryApplicationEnabledState(INSTANCE_ID, malformedRequest)

        assertEquals(EnginePackageEnabledStates.DISABLED_USER, valid?.enabledState)
        assertEquals(identity, valid?.authorityIdentity)
        assertEquals(
            "invalid_package_enabled_state_query_application_request",
            malformed.getString(EngineRuntimeIpcContract.KEY_MESSAGE)
        )
        verify(exactly = 1) { packageService.queryApplicationEnabledState(INSTANCE_ID) }
    }

    @Test
    fun `query and export reject a caller outside the host uid`() {
        val engine = mockk<VirtualizationEngine>(relaxed = true)
        val endpoint = endpoint(engine, callerUid = HOST_UID + 1)

        val query = endpoint.engineQueryRuntimeState(INSTANCE_ID)
        val export = endpoint.engineExportEvidence(INSTANCE_ID)

        assertUnauthorized(query)
        assertUnauthorized(export)
        assertNull(query.toAuthoritativeRuntimeOrNull())
        assertNull(export.toEngineEvidenceOrNull())
        verify(exactly = 0) { engine.queryRuntimeState(any()) }
        verify(exactly = 0) { engine.exportEvidence(any()) }
    }

    @Test
    fun `proxy Activity slot endpoints reject a caller outside the host uid`() {
        val activityService = mockk<VirtualActivityService>(relaxed = true)
        val endpoint = endpoint(
            virtualizationEngine = null,
            callerUid = HOST_UID + 1,
            activityService = activityService
        )
        val key = proxyActivitySlotKey()

        val responses = listOf(
            endpoint.queryProxyActivitySlot(
                key.toProxyActivitySlotQueryIpcBundle(bundles::create)
            ),
            endpoint.reserveProxyActivitySlot(
                key.toProxyActivitySlotReserveIpcBundle(
                    listOf(PROXY_ACTIVITY_CLASS_NAME),
                    bundles::create
                )
            ),
            endpoint.compareAndSetProxyActivitySlot(
                key.toProxyActivitySlotCompareAndSetIpcBundle(
                    expectedProxyActivityClassName = PROXY_ACTIVITY_CLASS_NAME,
                    newProxyActivityClassName = null,
                    bundleFactory = bundles::create
                )
            )
        )

        responses.forEach(::assertUnauthorized)
        verify(exactly = 0) { activityService.queryProxyActivitySlot(any(), any()) }
        verify(exactly = 0) { activityService.reserveProxyActivitySlot(any(), any(), any()) }
        verify(exactly = 0) {
            activityService.compareAndSetProxyActivitySlot(any(), any(), any(), any())
        }
    }

    @Test
    fun `proxy Activity slot endpoints reject malformed Bundles without invoking service`() {
        val activityService = mockk<VirtualActivityService>(relaxed = true)
        val endpoint = endpoint(
            virtualizationEngine = null,
            activityService = activityService
        )

        assertInvalidProxySlotRequest(
            endpoint.queryProxyActivitySlot(bundles.create()),
            "invalid_proxy_activity_slot_query_request"
        )
        assertInvalidProxySlotRequest(
            endpoint.reserveProxyActivitySlot(bundles.create()),
            "invalid_proxy_activity_slot_reserve_request"
        )
        assertInvalidProxySlotRequest(
            endpoint.compareAndSetProxyActivitySlot(bundles.create()),
            "invalid_proxy_activity_slot_compare_and_set_request"
        )
        verify(exactly = 0) { activityService.queryProxyActivitySlot(any(), any()) }
        verify(exactly = 0) { activityService.reserveProxyActivitySlot(any(), any(), any()) }
        verify(exactly = 0) {
            activityService.compareAndSetProxyActivitySlot(any(), any(), any(), any())
        }
    }

    @Test
    fun `proxy Activity slot endpoint rejects an unknown instance through authoritative service`() {
        val registry = EngineRuntimeRegistry()
        val activityService = DefaultVirtualSystemServer(registry).activityService
        val endpoint = endpoint(
            virtualizationEngine = null,
            activityService = activityService
        )
        val key = proxyActivitySlotKey(instanceId = "missing-instance")

        val response = endpoint.queryProxyActivitySlot(
            key.toProxyActivitySlotQueryIpcBundle(bundles::create)
        )

        assertEquals(
            EngineResultStatus.FAIL.name,
            response.getString(EngineRuntimeIpcContract.KEY_VERDICT)
        )
        assertEquals(
            "missing-instance",
            response.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID)
        )
        assertEquals(
            "runtime_not_found:missing-instance",
            response.getString(EngineRuntimeIpcContract.KEY_MESSAGE)
        )
    }

    private fun endpoint(
        virtualizationEngine: VirtualizationEngine?,
        callerUid: Int = HOST_UID,
        callerProcessName: String? = HOST_PACKAGE,
        activityService: VirtualActivityService? = null,
        packageService: VirtualPackageService? = null,
        processIdentity: EngineProcessClientIdentity? = null
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
        setEndpointField(endpoint, "hostPackageName", HOST_PACKAGE)
        setEndpointField(endpoint, "hostUid", HOST_UID)
        setEndpointField(endpoint, "serverGenerationId", "server-generation-test")
        setEndpointField(endpoint, "virtualizationEngine", virtualizationEngine)
        setEndpointField(endpoint, "callingUid", { callerUid })
        setEndpointField(endpoint, "callingPid", { CALLING_PID })
        setEndpointField(endpoint, "callingProcessName", { _: Int -> callerProcessName })
        setEndpointField(endpoint, "callingProcessStartTicks", { _: Int -> 1L })
        setEndpointField(endpoint, "providerRouteTokens", EngineProviderRouteTokenRegistry())
        setEndpointField(
            endpoint,
            "processControlPlane",
            mockk<EngineProcessControlPlane> {
                every { authorize(any(), CALLING_PID) } returns EngineProcessAuthorityDecision(
                    allowed = true,
                    identity = processIdentity,
                    reason = "test_runtime_authorized"
                )
            }
        )
        activityService?.let { setEndpointField(endpoint, "activityService", it) }
        packageService?.let { setEndpointField(endpoint, "packageService", it) }
        return endpoint
    }

    private fun uriPermissionEndpoint(
        registry: EngineRuntimeRegistry,
        controlPlane: EngineProcessControlPlane,
        componentAuthority: EngineComponentProcessAuthority? = null,
        providerService: VirtualProviderService? = null,
        processName: (Int) -> String? = { null },
        processStartTicks: (Int) -> Long? = { null }
    ): EngineRuntimeBinderEndpoint = endpoint(virtualizationEngine = null).also { endpoint ->
        setEndpointField(endpoint, "registry", registry)
        setEndpointField(endpoint, "processControlPlane", controlPlane)
        setEndpointField(endpoint, "componentProcessAuthority", componentAuthority)
        providerService?.let { setEndpointField(endpoint, "providerService", it) }
        setEndpointField(endpoint, "callingProcessName", processName)
        setEndpointField(endpoint, "callingProcessStartTicks", processStartTicks)
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

    private fun assertUriPermissionTargetRejected(response: Bundle, expectedReason: String) {
        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY))
        assertEquals(expectedReason, response.getString(EngineRuntimeIpcContract.KEY_REASON))
    }

    private fun providerUriGrantRequestBundle(request: VirtualProviderUriGrantRequest) = Bundle().apply {
        putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, request.guestAuthority)
        putString(EngineRuntimeIpcContract.KEY_ENCODED_PATH, request.encodedPath)
        putInt(EngineRuntimeIpcContract.KEY_MODE_FLAGS, request.modeFlags)
        putString(EngineRuntimeIpcContract.KEY_OWNER_INSTANCE_ID, request.ownerInstanceId)
        putString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID, request.targetInstanceId)
        putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, request.targetPackageName)
        putInt(EngineRuntimeIpcContract.KEY_CALLING_UID, request.callingUid)
        putInt(EngineRuntimeIpcContract.KEY_CALLING_PID, request.callingPid)
        putInt(EngineRuntimeIpcContract.KEY_HOST_UID, request.hostUid)
    }

    private fun deniedProcessAuthority() = EngineProcessAuthorityDecision(
        allowed = false,
        identity = null,
        reason = "test_process_authority_denied"
    )

    private fun primaryProcessAuthority(
        runtime: VirtualInstanceRuntime
    ) = EngineProcessAuthorityDecision(
        allowed = true,
        identity = EngineProcessClientIdentity(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            processId = checkNotNull(runtime.processId)
        ),
        reason = "live_client_authority_confirmed"
    )

    private fun assertInvalidProxySlotRequest(response: Bundle, expectedReason: String) {
        assertEquals(
            EngineResultStatus.FAIL.name,
            response.getString(EngineRuntimeIpcContract.KEY_VERDICT)
        )
        assertEquals(expectedReason, response.getString(EngineRuntimeIpcContract.KEY_REASON))
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
        evidenceMode = EngineEvidenceMode.FULL,
        providerHookEnabled = true
    )

    private fun createRequest() = CreateInstanceRequest(
        creationRequestId = "create-request-1",
        install = EnginePackageInstallRequest(
            originPackageName = ORIGIN_PACKAGE,
            originApkPath = "/data/app/test/base.apk",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            packageLabel = "Test",
            requestedPermissions = listOf("android.permission.CAMERA"),
            splitApkPaths = listOf("/data/app/test/config.apk"),
            splitPublicSourceDirs = listOf("/data/app/test/config.apk"),
            splitNames = listOf("config")
        ),
        displayName = "Test Work",
        compatibilityMode = CompatibilityMode.LEGACY
    )

    private fun proxyActivitySlotKey(
        instanceId: String = INSTANCE_ID
    ) = ProxyActivitySlotKey(
        instanceId = instanceId,
        launchMode = null,
        taskKey = "$ORIGIN_PACKAGE:$instanceId"
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

    private fun runtimeFor(
        instanceId: String,
        processId: Int,
        processSlot: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): VirtualInstanceRuntime {
        val base = runtime()
        val virtualPackageName = "com.multiapp.instance.$instanceId"
        val dataRoot = "/data/user/0/$HOST_PACKAGE/files/instances/$instanceId"
        return base.copy(
            instanceId = instanceId,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            packageSnapshot = base.packageSnapshot.copy(
                instanceId = instanceId,
                virtualPackageName = virtualPackageName,
                dataDir = dataRoot
            ),
            processSlot = processSlot,
            proxySlot = "$HOST_PACKAGE.container.ProxyActivity$runtimeEpoch",
            evidenceSessionId = "evidence-$instanceId",
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processId = processId,
            processName = processSlot
        )
    }

    private fun componentProcessIdentity(
        runtime: VirtualInstanceRuntime,
        processId: Int,
        processSlot: String,
        processStartTicks: Long,
        effectiveGuestProcessName: String
    ) = EngineComponentProcessClientIdentity(
        instanceId = runtime.instanceId,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processEpoch = 1L,
        clientSessionId = "component-client-${runtime.instanceId}",
        effectiveGuestProcessName = effectiveGuestProcessName,
        processSlot = processSlot,
        processId = processId,
        processStartTicks = processStartTicks
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
            every { anyConstructed<Bundle>().getInt(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Int ?: secondArg<Int>()
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
            every { anyConstructed<Bundle>().putStringArrayList(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<ArrayList<String>?>()
            }
            every { anyConstructed<Bundle>().getStringArrayList(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                valuesFor(self as Bundle)[firstArg()] as? ArrayList<String>
            }
            every { anyConstructed<Bundle>().containsKey(any()) } answers {
                valuesFor(self as Bundle).containsKey(firstArg())
            }
            every { anyConstructed<Bundle>().get(any()) } answers {
                valuesFor(self as Bundle)[firstArg()]
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
            every { bundle.getInt(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] as? Int ?: secondArg<Int>()
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
            every { bundle.putStringArrayList(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<ArrayList<String>?>()
            }
            every { bundle.getStringArrayList(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                valuesFor(bundle)[firstArg()] as? ArrayList<String>
            }
            every { bundle.containsKey(any()) } answers {
                valuesFor(bundle).containsKey(firstArg())
            }
            every { bundle.get(any()) } answers { valuesFor(bundle)[firstArg()] }
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
        const val CALLING_PID = 4321
        const val PROXY_ACTIVITY_CLASS_NAME = "com.multiapp.app.container.ProxyActivity2"
    }
}
