package com.multiapp.core.engine

import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.net.Uri
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualAmsComponentDispatcher
import com.multiapp.core.loader.VirtualBroadcastDispatchOptions
import com.multiapp.core.loader.VirtualBroadcastDispatchRequest
import com.multiapp.core.loader.VirtualBroadcastRecord
import com.multiapp.core.loader.VirtualBroadcastResult
import com.multiapp.core.loader.VirtualBroadcastResultCode
import com.multiapp.core.loader.VirtualContextWrapper
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceBindDispatchResult
import com.multiapp.core.loader.VirtualServiceLifecycleEvidence
import com.multiapp.core.loader.VirtualServiceIntentStore
import com.multiapp.core.loader.VirtualServiceManager
import com.multiapp.core.loader.VirtualServiceStartRequest
import com.multiapp.core.loader.VirtualServiceStopDispatchResult
import com.multiapp.core.loader.VirtualServiceStopRequest
import com.multiapp.core.loader.VirtualServiceUnbindDispatchResult
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.ProviderRouteContract
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.loader.toSummary
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineContainerDispatchersTest {

    @Test
    fun `provider dispatch request rejects blank host package`() {
        assertFailsWith<IllegalArgumentException> {
            EngineProviderDispatchRequest(
                hostPackageName = "",
                hostContext = mockk<Context>(relaxed = true),
                proxyUri = mockk<Uri>(relaxed = true),
                operationName = "query"
            )
        }
    }

    @Test
    fun `provider route slots map process slot to stub authority`() {
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub",
            EngineProviderRouteSlots.stubAuthority("com.multiapp.app", processSlot = null)
        )
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub.v3",
            EngineProviderRouteSlots.stubAuthority("com.multiapp.app", "com.multiapp.app:v3")
        )
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub",
            EngineProviderRouteSlots.stubAuthority("com.multiapp.app", "com.other:v3")
        )
    }

    @Test
    fun `broadcast dispatcher records engine plan and dispatch evidence`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                receivers = listOf(
                    ResolvedComponent(
                        name = "com.example.app.BootReceiver",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(actions = listOf("test.BOOT"))
                        )
                    )
                )
            )
        )
        val intent = broadcastIntent(action = "test.BOOT")
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        every {
            fallback.dispatchBroadcast(intent, any(), any())
        } returns VirtualBroadcastResult.Delivered(
            request = VirtualBroadcastDispatchRequest(
                instanceId = runtime.instanceId,
                originPackageName = runtime.originPackageName,
                receiverClassName = "com.example.app.BootReceiver",
                sourceIntent = intent,
                action = "test.BOOT",
                reason = "implicit"
            ),
            receiver = mockk(relaxed = true),
            record = VirtualBroadcastRecord(
                instanceId = runtime.instanceId,
                receiverClassName = "com.example.app.BootReceiver",
                action = "test.BOOT",
                result = VirtualBroadcastResultCode.Delivered
            )
        )
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchBroadcast(
            intent = intent,
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "plan")
            ?.single()
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "dispatch")
            ?.single()

        assertTrue(result is VirtualBroadcastResult.Delivered)
        assertEquals(EngineResultStatus.PARTIAL, planEvidence?.verdict)
        assertEquals("implicit_broadcast_route_planned", planEvidence?.entries?.get("broadcastPlanMessage"))
        assertEquals("com.example.app.BootReceiver", planEvidence?.entries?.get("targetReceivers"))
        assertEquals(EngineResultStatus.PASS, dispatchEvidence?.verdict)
        assertEquals("true", dispatchEvidence?.entries?.get("delivered"))
        assertEquals("loader_broadcast_delivered", dispatchEvidence?.entries?.get("message"))
    }

    @Test
    fun `broadcast dispatcher blocks fallback dispatch when engine plan fails`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val intent = broadcastIntent(action = "test.BOOT")
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = "missing-inst",
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchBroadcast(
            intent = intent,
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        assertTrue(result is VirtualBroadcastResult.NoPackageSnapshot)
        verify(exactly = 0) { fallback.dispatchBroadcast(any(), any(), any()) }
    }

    @Test
    fun `broadcast dispatcher forwards ordered sticky and permission semantics to engine gate`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val intent = broadcastIntent(action = "test.SECURE_EVENT")
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchBroadcast(
            intent = intent,
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader(),
            options = VirtualBroadcastDispatchOptions(
                ordered = true,
                sticky = true,
                expectsResultReceiver = true,
                receiverPermissions = setOf("com.example.RECEIVE_SECURE_EVENT"),
                receiverAppOp = "android:read_device_identifiers",
                asUserRequested = true,
                platformOptionsPresent = true
            )
        )
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "plan")
            ?.single()

        assertTrue(result is VirtualBroadcastResult.UnsupportedImplicit)
        assertEquals(EngineResultStatus.UNSUPPORTED, planEvidence?.verdict)
        assertEquals("true", planEvidence?.entries?.get("ordered"))
        assertEquals("true", planEvidence?.entries?.get("sticky"))
        assertEquals(
            "com.example.RECEIVE_SECURE_EVENT",
            planEvidence?.entries?.get("receiverPermissions")
        )
        assertEquals("android:read_device_identifiers", planEvidence?.entries?.get("receiverAppOp"))
        assertEquals("true", planEvidence?.entries?.get("asUserRequested"))
        assertEquals("true", planEvidence?.entries?.get("platformOptionsPresent"))
        assertTrue(
            planEvidence?.entries?.get("unsupportedOperations")
                .orEmpty()
                .contains("receiver-permission")
        )
        assertTrue(planEvidence?.entries?.get("unsupportedOperations").orEmpty().contains("as-user"))
        assertTrue(
            planEvidence?.entries?.get("unsupportedOperations")
                .orEmpty()
                .contains("broadcast-options")
        )
        verify(exactly = 0) { fallback.dispatchBroadcast(any(), any(), any()) }
        verify(exactly = 0) { fallback.dispatchBroadcast(any(), any(), any(), any()) }
    }

    @Test
    fun `ams activity start gate plans through engine before fallback remap`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                activities = listOf(
                    ResolvedComponent(
                        name = "com.example.app.MainActivity",
                        launchMode = "singleTop",
                        taskAffinity = "com.example.app",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(actions = listOf("test.OPEN"))
                        )
                    )
                )
            )
        )
        val intent = activitySourceIntent(action = "test.OPEN")
        val proxyIntent = mockk<Intent>(relaxed = true)
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        every {
            fallback.resolveStartActivityIntent(intent)
        } returns VirtualContextWrapper.StartActivityMappingResult.Remapped(
            sourceIntent = intent,
            proxyIntent = proxyIntent
        )
        val activityService = spyk(server.activityService)
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            activityService = activityService,
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.resolveStartActivityIntent(intent)
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("activity", "plan")
            ?.single()
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("activity", "dispatch")
            ?.single()

        assertTrue(result is VirtualContextWrapper.StartActivityMappingResult.Remapped)
        assertEquals(EngineResultStatus.PARTIAL, planEvidence?.verdict)
        assertEquals("implicit_activity_route_planned", planEvidence?.entries?.get("activityPlanMessage"))
        assertEquals("com.example.app.MainActivity", planEvidence?.entries?.get("targetActivities"))
        assertEquals(EngineResultStatus.PASS, dispatchEvidence?.verdict)
        assertEquals("true", dispatchEvidence?.entries?.get("remapped"))
        assertEquals("loader_activity_remapped", dispatchEvidence?.entries?.get("message"))
        verify(exactly = 1) { fallback.resolveStartActivityIntent(intent) }
        verify(exactly = 1) {
            activityService.syncActivityTaskState(runtime.instanceId, "start-activity-remapped")
        }
    }

    @Test
    fun `ams activity start gate blocks fallback when engine plan fails`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val intent = activitySourceIntent(action = "test.OPEN")
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = "missing-inst",
            activityService = server.activityService,
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.resolveStartActivityIntent(intent)

        assertTrue(result is VirtualContextWrapper.StartActivityMappingResult.Blocked)
        assertTrue(result.reason.contains("engine_activity_plan_fail"))
        verify(exactly = 0) { fallback.resolveStartActivityIntent(any()) }
    }

    @Test
    fun `ams service start gate plans through engine before fallback remap`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val intent = serviceSourceIntent(action = "test.SYNC")
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        every {
            fallback.resolveStartServiceIntent(intent, false)
        } returns VirtualContextWrapper.StartServiceMappingResult.Blocked(
            sourceIntent = intent,
            foreground = false,
            reason = "fallback-called"
        )
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = false)
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "plan")
            ?.single()

        assertTrue(result is VirtualContextWrapper.StartServiceMappingResult.Blocked)
        assertEquals("fallback-called", result.reason)
        assertEquals(EngineResultStatus.PARTIAL, planEvidence?.verdict)
        assertEquals("implicit_service_route_planned", planEvidence?.entries?.get("servicePlanMessage"))
        verify(exactly = 1) { fallback.resolveStartServiceIntent(intent, false) }
    }

    @Test
    fun `ams bind service gate dispatches same process bind and records runtime state`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val intent = serviceSourceIntent(action = "test.SYNC")
        val startRequest = VirtualServiceStartRequest(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            guestServiceClassName = "com.example.app.SyncService",
            sourceIntent = intent,
            reason = "implicitBind",
            processSlot = runtime.processSlot
        )
        val connection = mockk<ServiceConnection>(relaxed = true)
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        every {
            fallback.dispatchBindService(
                intent,
                any(),
                any(),
                connection,
                Context.BIND_AUTO_CREATE,
                null
            )
        } returns VirtualServiceBindDispatchResult.Bound(
            startRequest = startRequest,
            componentName = ComponentName(runtime.originPackageName, startRequest.guestServiceClassName),
            binder = null,
            cached = false,
            bindKey = "test.SYNC",
            flags = Context.BIND_AUTO_CREATE,
            bindCount = 1,
            activeConnectionCount = 1,
            reusedBinder = false,
            rebindDelivered = false
        )
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchBindService(
            intent = intent,
            virtualContext = mockk(relaxed = true),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            connection = connection,
            flags = Context.BIND_AUTO_CREATE,
            executor = null
        )
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "dispatch")
            ?.single()

        assertTrue(result is VirtualServiceBindDispatchResult.Bound)
        assertEquals(EngineResultStatus.PASS, dispatchEvidence?.verdict)
        assertEquals("BIND", dispatchEvidence?.entries?.get("operation"))
        assertEquals("true", dispatchEvidence?.entries?.get("bound"))
        val runtimeRecord = server.serviceService.queryServiceRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineServiceLifecycleState.BOUND, runtimeRecord.state)
        assertEquals(1, runtimeRecord.activeBindCount)
        verify(exactly = 1) {
            fallback.dispatchBindService(intent, any(), any(), connection, Context.BIND_AUTO_CREATE, null)
        }
    }

    @Test
    fun `ams stop service gate plans through engine and records fallback dispatch`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val intent = serviceSourceIntent(action = "test.SYNC")
        val stopRequest = VirtualServiceStopRequest(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            guestServiceClassName = "com.example.app.SyncService",
            sourceIntent = intent,
            reason = "implicitStop"
        )
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        every {
            fallback.dispatchStopService(intent)
        } returns VirtualServiceStopDispatchResult.ServiceNotFound(
            stopRequest = stopRequest,
            lifecycleEvidence = VirtualServiceLifecycleEvidence(
                instanceId = runtime.instanceId,
                guestServiceClassName = "com.example.app.SyncService",
                event = VirtualServiceLifecycleEvidence.Event.STOP_NOT_FOUND,
                success = false
            )
        )
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchStopService(intent)
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "plan")
            ?.single()
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "dispatch")
            ?.single()

        assertTrue(result is VirtualServiceStopDispatchResult.ServiceNotFound)
        assertEquals(EngineResultStatus.PARTIAL, planEvidence?.verdict)
        assertEquals("implicit_service_route_planned", planEvidence?.entries?.get("servicePlanMessage"))
        assertEquals(EngineResultStatus.PARTIAL, dispatchEvidence?.verdict)
        assertEquals("STOP", dispatchEvidence?.entries?.get("operation"))
        assertEquals("false", dispatchEvidence?.entries?.get("stopped"))
        assertEquals("loader_service_stop_not_found", dispatchEvidence?.entries?.get("message"))
        verify(exactly = 1) { fallback.dispatchStopService(intent) }
    }

    @Test
    fun `ams stop service gate blocks fallback when engine plan fails`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val intent = serviceSourceIntent(action = "test.SYNC")
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = "missing-inst",
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchStopService(intent)

        assertEquals(null, result)
        verify(exactly = 0) { fallback.dispatchStopService(any()) }
    }

    @Test
    fun `ams unbind service gate dispatches connection unbind and records stopped state`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val fallback = mockk<VirtualAmsComponentDispatcher>(relaxed = true)
        val connection = mockk<ServiceConnection>(relaxed = true)
        val intent = serviceSourceIntent(action = "test.SYNC")
        val startRequest = VirtualServiceStartRequest(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            guestServiceClassName = "com.example.app.SyncService",
            sourceIntent = intent,
            reason = "implicitBind",
            processSlot = runtime.processSlot
        )
        every { fallback.dispatchUnbindService(connection) } returns
            VirtualServiceUnbindDispatchResult.Unbound(
                startRequest = startRequest,
                destroyed = true,
                onUnbindResult = false,
                onUnbindCalled = true,
                bindKey = "test.SYNC",
                activeConnectionCount = 0,
                activeBindCount = 0
            )
        val dispatcher = DefaultEngineAmsComponentDispatcher(
            fallback = fallback,
            instanceId = runtime.instanceId,
            serviceService = server.serviceService,
            broadcastService = server.broadcastService
        )

        val result = dispatcher.dispatchUnbindService(connection)
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "dispatch")
            ?.single()

        assertTrue(result is VirtualServiceUnbindDispatchResult.Unbound)
        assertEquals(EngineResultStatus.PASS, dispatchEvidence?.verdict)
        assertEquals("UNBIND", dispatchEvidence?.entries?.get("operation"))
        assertEquals("true", dispatchEvidence?.entries?.get("unbound"))
        assertEquals("true", dispatchEvidence?.entries?.get("destroyed"))
        val runtimeRecord = server.serviceService.queryServiceRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineServiceLifecycleState.STOPPED, runtimeRecord.state)
        assertEquals(0, runtimeRecord.activeBindCount)
        verify(exactly = 1) { fallback.dispatchUnbindService(connection) }
    }

    @Test
    fun `provider dispatcher records engine plan and dispatch evidence`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                providers = listOf(
                    ResolvedComponent(
                        name = "com.example.app.DataProvider",
                        authorities = listOf("com.example.app.provider"),
                        processName = "com.example.app:provider",
                        readPermission = "com.example.app.READ_DATA",
                        writePermission = "com.example.app.WRITE_DATA",
                        grantUriPermissions = true
                    )
                )
            )
        )
        val proxyUri = providerProxyUri(
            instanceId = runtime.instanceId,
            guestAuthority = "com.example.app.provider",
            processSlot = runtime.processSlot
        )
        val dispatcher = DefaultEngineProviderDispatcher(
            providerService = server.providerService,
            loaderDispatch = {
                EngineProviderDispatchResult.ProviderNotFound(
                    instanceId = runtime.instanceId,
                    guestAuthority = "com.example.app.provider",
                    evidence = EngineProviderEvidence(
                        instanceId = runtime.instanceId,
                        guestAuthority = "com.example.app.provider",
                        proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
                        providerClassName = null,
                        operation = EngineProviderOperation.QUERY,
                        success = false,
                        reason = "loader_provider_not_found"
                    )
                )
            }
        )

        val result = dispatcher.dispatch(
            EngineProviderDispatchRequest(
                hostPackageName = "com.multiapp.app",
                hostContext = mockk(relaxed = true),
                proxyUri = proxyUri,
                operationName = "query",
                verifiedRoute = providerRoute(
                    callerInstanceId = runtime.instanceId,
                    targetInstanceId = runtime.instanceId,
                    guestAuthority = "com.example.app.provider",
                    operation = "query",
                    processSlot = runtime.processSlot
                ),
                providerCallingUid = 1000,
                providerCallingPid = 3001,
                hostUid = 1000,
                callerProcessSlot = runtime.processSlot
            )
        )
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("provider", "plan")
            ?.single()
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("provider", "dispatch")
            ?.single()

        assertTrue(result is EngineProviderDispatchResult.ProviderNotFound)
        assertEquals(EngineResultStatus.PARTIAL, planEvidence?.verdict)
        assertEquals("provider_route_planned", planEvidence?.entries?.get("providerPlanMessage"))
        assertEquals("com.example.app.DataProvider", planEvidence?.entries?.get("targetProviders"))
        assertEquals(runtime.processSlot, planEvidence?.entries?.get("processSlot"))
        assertEquals(EngineResultStatus.FAIL, dispatchEvidence?.verdict)
        assertEquals("QUERY", dispatchEvidence?.entries?.get("operation"))
        assertEquals("loader_provider_not_found", dispatchEvidence?.entries?.get("reason"))
    }

    @Test
    fun `provider dispatcher blocks unverified route before loader dispatch`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                providers = listOf(
                    ResolvedComponent(
                        name = "com.example.app.DataProvider",
                        authorities = listOf("com.example.app.provider")
                    )
                )
            )
        )
        var loaderCalled = false
        val dispatcher = DefaultEngineProviderDispatcher(
            providerService = server.providerService,
            loaderDispatch = {
                loaderCalled = true
                EngineProviderDispatchResult.InvalidProxyUri("should not dispatch")
            }
        )

        val result = dispatcher.dispatch(
            EngineProviderDispatchRequest(
                hostPackageName = "com.multiapp.app",
                hostContext = mockk(relaxed = true),
                proxyUri = providerProxyUri(
                    instanceId = runtime.instanceId,
                    guestAuthority = "com.example.app.provider",
                    processSlot = runtime.processSlot
                ),
                operationName = "query",
                providerCallingUid = 1000,
                providerCallingPid = 3001,
                hostUid = 1000
            )
        )

        assertTrue(result is EngineProviderDispatchResult.InvalidProxyUri)
        assertTrue(result.reason.contains("provider_route_token_unverified"))
        assertFalse(loaderCalled)
    }

    @Test
    fun `provider dispatcher blocks loader dispatch when engine plan fails`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        var loaderCalled = false
        val dispatcher = DefaultEngineProviderDispatcher(
            providerService = server.providerService,
            loaderDispatch = {
                loaderCalled = true
                EngineProviderDispatchResult.InvalidProxyUri("should not dispatch")
            }
        )

        val result = dispatcher.dispatch(
            EngineProviderDispatchRequest(
                hostPackageName = "com.multiapp.app",
                hostContext = mockk(relaxed = true),
                proxyUri = providerProxyUri(
                    instanceId = "missing-inst",
                    guestAuthority = "com.example.app.provider",
                    processSlot = "com.multiapp.app:v1"
                ),
                operationName = "query"
            )
        )

        assertTrue(result is EngineProviderDispatchResult.InstanceNotFound)
        assertFalse(loaderCalled)
    }

    @Test
    fun `service route rejects blank identity`() {
        assertFailsWith<IllegalArgumentException> {
            EngineServiceStartRoute.create(
                instanceId = "",
                originPackageName = "com.example.app",
                guestServiceClassName = "com.example.app.SyncService"
            )
        }
    }

    @Test
    fun `service router decodes route from proxy intent`() {
        val proxyToken = "token-001"
        val sourceIntent = mockk<Intent>(relaxed = true)
        VirtualServiceIntentStore.remember(proxyToken, sourceIntent)
        val intent = proxyIntent(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            guestServiceClassName = "com.example.app.SyncService",
            reason = "explicit",
            foreground = true,
            processSlot = "com.multiapp.app:v2",
            proxyToken = proxyToken
        )
        val router = DefaultEngineServiceRouter(
            processRuntime = DefaultEngineHostedProcessRuntime(VirtualProcessRuntime())
        )

        val route = try {
            assertNotNull(router.routeFromProxyIntent("com.multiapp.app", intent))
        } finally {
            VirtualServiceIntentStore.clear(proxyToken)
        }

        assertEquals("inst-001", route.instanceId)
        assertEquals("com.example.app", route.originPackageName)
        assertEquals("com.example.app.SyncService", route.guestServiceClassName)
        assertEquals("explicit", route.reason)
        assertTrue(route.foreground)
        assertEquals("com.multiapp.app:v2", route.processSlot)
    }

    @Test
    fun `service router builds launch info from extras when route is missing`() {
        val intent = proxyIntent(
            instanceId = "inst-002",
            originPackageName = "com.example.other",
            guestServiceClassName = "com.example.other.SyncService",
            reason = "legacy"
        )

        val info = DefaultEngineServiceRouter(
            processRuntime = DefaultEngineHostedProcessRuntime(VirtualProcessRuntime())
        ).launchInfo(intent, route = null)

        assertEquals("inst-002", info.instanceId)
        assertEquals("com.example.other", info.originPackageName)
        assertEquals("com.example.other.SyncService", info.guestServiceClassName)
        assertEquals("legacy", info.reason)
        assertFalse(info.foreground)
    }

    @Test
    fun `service router checks reusable runtime through injected runtime`() {
        val runtime = VirtualProcessRuntime()
        val router = DefaultEngineServiceRouter(
            processRuntime = DefaultEngineHostedProcessRuntime(runtime)
        )

        assertFalse(router.hasReusableRuntime("inst-001"))

        runtime.bindApplication("inst-001") { hostedResult("inst-001") }

        assertTrue(router.hasReusableRuntime("inst-001"))
    }

    @Test
    fun `service dispatch result exports evidence and keeps active service running`() {
        val result = EngineServiceDispatchResult.ServiceStarted(
            startRequest = serviceStartSnapshot(),
            cached = false,
            startCommandResult = 1,
            lifecycleEvidence = EngineServiceLifecycleEvidence(
                instanceId = "inst-001",
                guestServiceClassName = "com.example.app.SyncService",
                event = "CREATED_AND_STARTED",
                success = true,
                startCommandResult = 1,
                activeStartCount = 1
            )
        )

        val evidence = result.toEngineEvidenceFields(
            fallbackInstanceId = "",
            fallbackOriginPackageName = "",
            fallbackGuestServiceClassName = "",
            fallbackReason = "",
            fallbackForeground = false,
            foregroundStatus = "SKIPPED",
            runtimeBindEvidence = EngineServiceRuntimeBindEvidence(
                status = "BOUND",
                detail = "reusedRuntime",
                processSlot = "com.multiapp.app:v1",
                errorClassName = null,
                errorMessage = null
            ),
            startId = 42,
            defaultHostStartCommandResult = 2,
            undecidedHostReturnMode = "UNDECIDED"
        )
        val stopDecision = evidence.stubStopDecision(foregroundStartedStatus = "STARTED")

        assertEquals("STARTED", evidence.status)
        assertEquals("inst-001", evidence.instanceId)
        assertEquals("com.multiapp.app:v1", evidence.runtimeBindProcessSlot)
        assertEquals(1, evidence.startCommandResult)
        assertFalse(stopDecision.stop)
        assertEquals("KEEP_GUEST_SERVICE_ACTIVE", stopDecision.reason)
    }

    @Test
    fun `invalid service dispatch result uses fallback identity and stops stub`() {
        val result = EngineServiceDispatchResult.InvalidProxyIntent("missing service extras")

        val evidence = result.toEngineEvidenceFields(
            fallbackInstanceId = "fallback-inst",
            fallbackOriginPackageName = "com.example.app",
            fallbackGuestServiceClassName = "com.example.app.SyncService",
            fallbackReason = "legacy",
            fallbackForeground = false,
            foregroundStatus = "SKIPPED",
            runtimeBindEvidence = EngineServiceRuntimeBindEvidence(
                status = "NOT_REQUESTED",
                detail = "missing route",
                processSlot = null,
                errorClassName = null,
                errorMessage = null
            ),
            startId = 7,
            defaultHostStartCommandResult = 2,
            undecidedHostReturnMode = "UNDECIDED"
        )
        val stopDecision = evidence.stubStopDecision(foregroundStartedStatus = "STARTED")

        assertEquals("INVALID_PROXY_INTENT", evidence.status)
        assertEquals("fallback-inst", evidence.instanceId)
        assertEquals("missing service extras", evidence.detail)
        assertTrue(stopDecision.stop)
        assertEquals("STOP_DISPATCH_INVALID_PROXY_INTENT", stopDecision.reason)
    }

    @Test
    fun `service dispatcher records engine plan and dispatch evidence`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val sourceIntent = serviceSourceIntent(action = "test.SYNC")
        val route = EngineServiceStartRoute.create(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            guestServiceClassName = "com.example.app.SyncService",
            sourceIntent = sourceIntent,
            reason = "explicit",
            processSlot = runtime.processSlot
        )
        val dispatcher = DefaultEngineServiceDispatcher(
            serviceService = server.serviceService,
            loaderDispatch = {
                EngineServiceDispatchResult.ServiceStarted(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(route.startRequest),
                    cached = false,
                    startCommandResult = 2,
                    lifecycleEvidence = EngineServiceLifecycleEvidence(
                        instanceId = runtime.instanceId,
                        guestServiceClassName = "com.example.app.SyncService",
                        event = "CREATED_AND_STARTED",
                        success = true,
                        startCommandResult = 2,
                        activeStartCount = 1
                    )
                )
            }
        )

        val result = dispatcher.dispatch(
            EngineServiceDispatchRequest(
                hostContext = mockk(relaxed = true),
                proxyIntent = null,
                route = route,
                flags = 0,
                startId = 3
            )
        )
        val planEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "plan")
            ?.single()
        val dispatchEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "dispatch")
            ?.single()

        assertTrue(result is EngineServiceDispatchResult.ServiceStarted)
        assertEquals(EngineResultStatus.PARTIAL, planEvidence?.verdict)
        assertEquals("explicit_service_route_planned", planEvidence?.entries?.get("servicePlanMessage"))
        assertEquals("com.example.app.SyncService", planEvidence?.entries?.get("targetServices"))
        assertEquals(runtime.processSlot, planEvidence?.entries?.get("processSlot"))
        assertEquals(EngineResultStatus.PASS, dispatchEvidence?.verdict)
        assertEquals("true", dispatchEvidence?.entries?.get("started"))
        assertEquals("2", dispatchEvidence?.entries?.get("startCommandResult"))
        assertEquals("loader_service_started", dispatchEvidence?.entries?.get("message"))
    }

    @Test
    fun `service dispatcher blocks loader dispatch when engine plan fails`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        var loaderCalled = false
        val route = EngineServiceStartRoute.create(
            instanceId = "missing-inst",
            originPackageName = "com.example.app",
            guestServiceClassName = "com.example.app.SyncService",
            sourceIntent = serviceSourceIntent(action = "test.SYNC"),
            reason = "explicit",
            processSlot = "com.multiapp.app:v1"
        )
        val dispatcher = DefaultEngineServiceDispatcher(
            serviceService = server.serviceService,
            loaderDispatch = {
                loaderCalled = true
                EngineServiceDispatchResult.InvalidProxyIntent("should not dispatch")
            }
        )

        val result = dispatcher.dispatch(
            EngineServiceDispatchRequest(
                hostContext = mockk(relaxed = true),
                proxyIntent = null,
                route = route,
                flags = 0,
                startId = 4
            )
        )

        assertTrue(result is EngineServiceDispatchResult.RuntimeNotBound)
        assertFalse(loaderCalled)
    }

    private fun hostedResult(instanceId: String): HostedBootstrapResult = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/$instanceId",
        guestClassLoader = ClassLoader.getSystemClassLoader(),
        guestApplication = null,
        installRecord = null,
        packageSnapshot = null,
        launcherActivityClassName = "com.example.app.MainActivity",
        stageResults = emptyList(),
        summary = emptyList<BootstrapResult>().toSummary(),
        success = true,
        diagnostics = null
    )

    private fun serviceStartSnapshot(): EngineServiceStartRequestSnapshot = EngineServiceStartRequestSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        guestServiceClassName = "com.example.app.SyncService",
        reason = "explicit",
        foreground = false,
        proxyToken = "token-001",
        processSlot = "com.multiapp.app:v1"
    )

    private fun runtime(
        instanceId: String = "inst-001",
        activities: List<ResolvedComponent> = emptyList(),
        providers: List<ResolvedComponent> = emptyList(),
        receivers: List<ResolvedComponent> = emptyList()
    ): VirtualInstanceRuntime = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.virtual.$instanceId",
            applicationLabel = "Example",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/example.apk",
            dataDir = "build/tmp/$instanceId",
            activities = activities,
            services = listOf(
                ResolvedComponent(
                    name = "com.example.app.SyncService",
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(actions = listOf("test.SYNC"))
                    )
                )
            ),
            receivers = receivers,
            providers = providers
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v1",
        proxySlot = "com.multiapp.app.container.ProxyActivity1",
        evidenceSessionId = "evidence-$instanceId",
        runtimeEpoch = 11L,
        engineSessionId = "engine-$instanceId",
        processName = "com.multiapp.app:v1",
        state = VirtualRuntimeState.RUNNING
    )

    private fun serviceSourceIntent(action: String): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action
        every { intent.component } returns null
        every { intent.`package` } returns null
        every { intent.categories } returns null
        every { intent.data } returns null
        every { intent.type } returns null
        return intent
    }

    private fun activitySourceIntent(action: String): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action
        every { intent.component } returns null
        every { intent.`package` } returns null
        every { intent.categories } returns null
        every { intent.data } returns null
        every { intent.type } returns null
        every { intent.flags } returns 0
        return intent
    }

    private fun broadcastIntent(action: String): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.action } returns action
        every { intent.component } returns null
        every { intent.`package` } returns null
        every { intent.categories } returns null
        every { intent.data } returns null
        every { intent.type } returns null
        return intent
    }

    private fun providerProxyUri(
        instanceId: String,
        guestAuthority: String,
        processSlot: String?
    ): Uri = mockk(relaxed = true) {
        every { authority } returns "com.multiapp.app.multiapp.provider.stub.v1"
        every { getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID) } returns instanceId
        every { getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY) } returns guestAuthority
        every { getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT) } returns processSlot
        every { getQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN) } returns "route-token-1"
    }

    private fun providerRoute(
        callerInstanceId: String,
        targetInstanceId: String,
        guestAuthority: String,
        operation: String,
        processSlot: String?
    ) = EngineProviderRouteToken(
        token = "route-token-1",
        callerInstanceId = callerInstanceId,
        targetInstanceId = targetInstanceId,
        authority = guestAuthority,
        operation = operation,
        expiresAtMillis = Long.MAX_VALUE,
        processSlot = processSlot
    )

    private fun proxyIntent(
        instanceId: String,
        originPackageName: String,
        guestServiceClassName: String,
        reason: String,
        foreground: Boolean = false,
        processSlot: String? = null,
        proxyToken: String? = null
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns instanceId
        every { getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns originPackageName
        every { getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns guestServiceClassName
        every { getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns reason
        every { getStringExtra(VirtualServiceManager.EXTRA_PROCESS_SLOT) } returns processSlot
        every { getStringExtra(VirtualServiceManager.EXTRA_VIRTUAL_SERVICE_TOKEN) } returns proxyToken
        every { getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns foreground
    }
}
