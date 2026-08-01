package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

class EngineProviderRouteTokenEndpointSecurityTest {
    private lateinit var bundles: BundleHarness

    @BeforeTest
    fun setUp() {
        bundles = BundleHarness().also(BundleHarness::installConstructorMock)
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun `engine chooses custom Provider target slot and caller slot cannot replace it`() {
        val fixture = endpointFixture()

        val issued = fixture.issue(requestedProcessSlot = null)
        val callerSlotRequest = fixture.issue(requestedProcessSlot = CALLER_PROCESS_SLOT)

        assertEquals("ISSUED", issued.status)
        val route = assertNotNull(issued.route)
        assertTrue(route.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(INSTANCE_ID, route.callerInstanceId)
        assertEquals(INSTANCE_ID, route.targetInstanceId)
        assertEquals(AUTHORITY, route.authority)
        assertEquals(OPERATION, route.operation)
        assertEquals(TARGET_PROCESS_SLOT, route.processSlot)
        assertEquals(CALLER_PROCESS_SLOT, route.callerProcessSlot)
        assertEquals(CALLER_PROCESS_ID, route.callerProcessId)

        assertEquals("PROCESS_SLOT_MISMATCH", callerSlotRequest.status)
        assertNull(callerSlotRequest.route)
    }

    @Test
    fun `token binds caller PID target slot authority operation and rejects replay`() {
        val fixture = endpointFixture()
        val route = assertNotNull(fixture.issue().route)
        fixture.callingPid.set(TARGET_PROCESS_ID)

        assertEquals(
            "PROCESS_SLOT_MISMATCH",
            fixture.consume(route, expectedProcessSlot = CALLER_PROCESS_SLOT).status
        )
        assertEquals(
            "AUTHORITY_MISMATCH",
            fixture.consume(route, guestAuthority = "$ORIGIN_PACKAGE.other").status
        )
        assertEquals(
            "OPERATION_MISMATCH",
            fixture.consume(route, operation = "insert").status
        )
        assertEquals(
            "CALLER_GENERATION_STALE",
            fixture.consume(route, providerCallingPid = CALLER_PROCESS_ID + 1).status
        )

        val consumed = fixture.consume(route)
        assertEquals("VALID", consumed.status)
        assertEquals(route, consumed.route)
        assertEquals("REPLAYED", fixture.consume(route).status)
    }

    @Test
    fun `missing and unknown tokens fail closed at the endpoint`() {
        // W1-7 端点级负测（2026-08-01）：端点层 fail-closed，不泄露内部路由状态。
        // 未知 token（长度合法）→ TARGET_UNAUTHORIZED（binding 不可解析）；
        // 非法长度 token → 请求构造阶段即抛 IllegalArgumentException（fail-closed 前置）。
        val fixture = endpointFixture()
        val route = assertNotNull(fixture.issue().route)

        val forged = "a".repeat(43)
        assertEquals("TARGET_UNAUTHORIZED", fixture.consume(route, tokenOverride = forged).status)
        assertNull(fixture.consume(route, tokenOverride = forged).route)

        val short = assertFailsWith<IllegalArgumentException> {
            fixture.consume(route, tokenOverride = "x")
        }
        assertTrue(short.message.orEmpty().contains("invalid length"))
    }

    @Test
    fun `endpoint rejects expired route tokens fail closed`() {
        // W1-7 端点级负测（2026-08-01）：TTL 过期后 targetBinding 经 prune 不可解析
        // → 端点层 TARGET_UNAUTHORIZED（fail-closed，先于 registry 细分 EXPIRED 状态）
        var nowNanos = 1_000_000_000L
        val fixture = endpointFixture(routeTokenClockNanos = { nowNanos })
        val route = assertNotNull(fixture.issue().route)
        assertTrue(route.expiresAtMillis > 0L)

        nowNanos += TimeUnit.MINUTES.toNanos(3)
        assertEquals("TARGET_UNAUTHORIZED", fixture.consume(route).status)
        assertNull(fixture.consume(route).route)
    }

    @Test
    fun `endpoint rejects foreign target instance fail closed`() {
        // W1-7 端点级负测（2026-08-01）：target 实例不一致在端点层先行拒绝
        // （TARGET_UNAUTHORIZED），不落到 registry 细分状态
        val fixture = endpointFixture()
        val route = assertNotNull(fixture.issue().route)

        assertEquals(
            "TARGET_UNAUTHORIZED",
            fixture.consume(route, targetInstanceId = "$INSTANCE_ID-other").status
        )
        assertNull(fixture.consume(route, targetInstanceId = "$INSTANCE_ID-other").route)
    }

    @Test
    fun `cold custom Provider is prepared first caller cannot consume and target stub binds PID`() {
        val fixture = endpointFixture(coldTarget = true)
        val route = assertNotNull(fixture.issue().route)

        assertEquals(TARGET_PROCESS_SLOT, route.processSlot)
        assertEquals(
            "PROCESS_SLOT_MISMATCH",
            fixture.consume(route).status,
            "the issuing caller must not consume a target-stub capability"
        )

        fixture.callingPid.set(TARGET_PROCESS_ID)
        assertEquals("VALID", fixture.consume(route).status)
        val bound = fixture.routeTokens.boundTargetForProcess(
            INSTANCE_ID,
            TARGET_PROCESS_ID,
            TARGET_PROCESS_SLOT,
            TARGET_PROCESS_START_TICKS
        )
        assertEquals(TARGET_PROCESS_SLOT, bound?.binding?.processSlot)
        assertTrue(checkNotNull(bound).processEpoch > 0L)
        verify(exactly = 1) {
            fixture.componentAuthority.prepare(INSTANCE_ID, TARGET_GUEST_PROCESS_NAME)
        }
    }

    @Test
    fun `consumed custom Provider token plans target component slot and first duplicate authority wins`() {
        val fixture = endpointFixture(coldTarget = true)
        val route = assertNotNull(fixture.issue().route)
        fixture.callingPid.set(TARGET_PROCESS_ID)
        assertEquals("VALID", fixture.consume(route).status)

        val request = VirtualProviderDispatchPlanRequest(
            operation = EngineProviderOperation.QUERY,
            guestAuthority = AUTHORITY,
            proxyAuthority = EngineProviderRouteSlots.stubAuthority(HOST_PACKAGE, TARGET_PROCESS_SLOT),
            processSlot = TARGET_PROCESS_SLOT,
            routeToken = route.token,
            routeTokenPresent = true
        )
        val plan = fixture.endpoint.planProvider(INSTANCE_ID, providerPlanBundle(request))

        assertEquals(
            EngineResultStatus.PARTIAL.name,
            plan.getString(EngineRuntimeIpcContract.KEY_VERDICT)
        )
        assertEquals(
            "provider_route_planned",
            plan.getString(EngineRuntimeIpcContract.KEY_MESSAGE)
        )
        val runtime = fixture.endpoint.queryRuntime(INSTANCE_ID)
        assertTrue(runtime.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY))
        assertEquals(
            TARGET_PROCESS_SLOT,
            runtime.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT)
        )
        verify(exactly = 1) {
            fixture.componentAuthority.attach(
                fixture.launchTicket.attachCapability,
                fixture.targetProcessToken,
                TARGET_PROCESS_ID,
                TARGET_PROCESS_SLOT,
                TARGET_PROCESS_START_TICKS
            )
        }
        verify(exactly = 1) {
            fixture.componentAuthority.prepare(INSTANCE_ID, TARGET_GUEST_PROCESS_NAME)
        }
        verify(exactly = 0) {
            fixture.componentAuthority.prepare(INSTANCE_ID, "$ORIGIN_PACKAGE:secondary")
        }
    }

    private fun endpointFixture(
        coldTarget: Boolean = false,
        routeTokenClockNanos: (() -> Long)? = null
    ): EndpointFixture {
        val runtime = runtime()
        val registry = EngineRuntimeRegistry().apply { register(runtime) }
        val callerIdentity = EngineProcessClientIdentity(
            instanceId = INSTANCE_ID,
            runtimeEpoch = RUNTIME_EPOCH,
            engineSessionId = ENGINE_SESSION_ID,
            processSlot = CALLER_PROCESS_SLOT,
            processId = CALLER_PROCESS_ID
        )
        val targetIdentity = EngineComponentProcessClientIdentity(
            instanceId = INSTANCE_ID,
            runtimeEpoch = RUNTIME_EPOCH,
            engineSessionId = ENGINE_SESSION_ID,
            processEpoch = TARGET_PROCESS_EPOCH,
            clientSessionId = TARGET_CLIENT_SESSION_ID,
            effectiveGuestProcessName = TARGET_GUEST_PROCESS_NAME,
            processSlot = TARGET_PROCESS_SLOT,
            processId = TARGET_PROCESS_ID,
            processStartTicks = TARGET_PROCESS_START_TICKS
        )
        val targetState = EngineComponentProcessState(
            instanceId = INSTANCE_ID,
            effectiveGuestProcessName = TARGET_GUEST_PROCESS_NAME,
            processSlot = TARGET_PROCESS_SLOT,
            processId = TARGET_PROCESS_ID,
            processEpoch = TARGET_PROCESS_EPOCH,
            live = true
        )
        val processControlPlane = mockk<EngineProcessControlPlane>()
        every { processControlPlane.authorize(INSTANCE_ID, any()) } answers {
            if (secondArg<Int>() == CALLER_PROCESS_ID) {
                EngineProcessAuthorityDecision(
                    allowed = true,
                    identity = callerIdentity,
                    reason = "live_runtime_authority_confirmed"
                )
            } else {
                EngineProcessAuthorityDecision(
                    allowed = false,
                    identity = null,
                    reason = "runtime_process_id_mismatch"
                )
            }
        }
        val componentAuthority = mockk<EngineComponentProcessAuthority>()
        val targetProcessToken = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } returns true
        }
        val targetAttached = AtomicBoolean(!coldTarget)
        val launchTicket = EngineComponentProcessLaunchTicket(
            instanceId = INSTANCE_ID,
            effectiveGuestProcessName = TARGET_GUEST_PROCESS_NAME,
            processSlot = TARGET_PROCESS_SLOT,
            attachCapability = "provider-attach-capability-${"x".repeat(32)}"
        )
        every { componentAuthority.prepare(INSTANCE_ID, TARGET_GUEST_PROCESS_NAME) } returns
            if (coldTarget) {
                EngineComponentProcessOperationResult(
                    operation = COMPONENT_PROCESS_PREPARE_OPERATION,
                    instanceId = INSTANCE_ID,
                    accepted = true,
                    idempotent = false,
                    alreadyRunning = false,
                    launchTicket = launchTicket,
                    processState = null,
                    reason = "component_process_launch_prepared"
                )
            } else {
                EngineComponentProcessOperationResult(
                    operation = COMPONENT_PROCESS_PREPARE_OPERATION,
                    instanceId = INSTANCE_ID,
                    accepted = true,
                    idempotent = true,
                    alreadyRunning = true,
                    launchTicket = null,
                    processState = targetState,
                    reason = "component_process_already_running"
                )
            }
        every { componentAuthority.query(INSTANCE_ID, TARGET_GUEST_PROCESS_NAME) } returns
            EngineComponentProcessOperationResult(
                operation = "query-component-process",
                instanceId = INSTANCE_ID,
                accepted = true,
                idempotent = true,
                alreadyRunning = true,
                launchTicket = null,
                processState = targetState,
                reason = "component_process_client_found"
            )
        every {
            componentAuthority.attach(
                launchTicket.attachCapability,
                targetProcessToken,
                TARGET_PROCESS_ID,
                TARGET_PROCESS_SLOT,
                TARGET_PROCESS_START_TICKS
            )
        } answers {
            targetAttached.set(true)
            EngineComponentProcessOperationResult(
                operation = COMPONENT_PROCESS_ATTACH_OPERATION,
                instanceId = INSTANCE_ID,
                accepted = true,
                idempotent = false,
                alreadyRunning = false,
                launchTicket = null,
                processState = targetState,
                reason = "component_process_client_attached"
            )
        }
        every {
            componentAuthority.authorizeCaller(
                INSTANCE_ID,
                TARGET_PROCESS_ID,
                TARGET_PROCESS_SLOT,
                TARGET_PROCESS_START_TICKS
            )
        } answers { targetIdentity.takeIf { targetAttached.get() } }

        val callingPid = AtomicInteger(CALLER_PROCESS_ID)
        val routeTokens = if (routeTokenClockNanos != null) {
            EngineProviderRouteTokenRegistry(clockNanos = routeTokenClockNanos)
        } else {
            EngineProviderRouteTokenRegistry()
        }
        val systemServer = DefaultVirtualSystemServer(registry)
        val endpoint = allocateEndpoint().apply {
            setEndpointField("registry", registry)
            setEndpointField("hostUid", HOST_UID)
            setEndpointField("callingUid", { HOST_UID })
            setEndpointField("callingPid", callingPid::get)
            setEndpointField("callingProcessName", { pid: Int ->
                when (pid) {
                    CALLER_PROCESS_ID -> CALLER_PROCESS_SLOT
                    TARGET_PROCESS_ID -> TARGET_PROCESS_SLOT
                    else -> null
                }
            })
            setEndpointField("callingProcessStartTicks", { pid: Int ->
                TARGET_PROCESS_START_TICKS.takeIf { pid == TARGET_PROCESS_ID }
            })
            setEndpointField("processControlPlane", processControlPlane)
            setEndpointField("componentProcessAuthority", componentAuthority)
            setEndpointField("providerService", systemServer.providerService)
            setEndpointField("packageService", systemServer.packageService)
            setEndpointField("providerRouteTokens", routeTokens)
            setEndpointField("ipcBundleFactory", bundles::create)
        }
        return EndpointFixture(
            endpoint = endpoint,
            callingPid = callingPid,
            routeTokens = routeTokens,
            componentAuthority = componentAuthority,
            launchTicket = launchTicket,
            targetProcessToken = targetProcessToken
        )
    }

    private fun allocateEndpoint(): EngineRuntimeBinderEndpoint {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, EngineRuntimeBinderEndpoint::class.java) as EngineRuntimeBinderEndpoint
    }

    private fun providerPlanBundle(request: VirtualProviderDispatchPlanRequest): Bundle =
        bundles.create().apply {
            putString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION, request.operation.name)
            putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, request.guestAuthority)
            putString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY, request.proxyAuthority)
            putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, request.processSlot)
            putString(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN, request.routeToken)
            putBoolean(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN_PRESENT, request.routeTokenPresent)
            putBoolean(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN_VERIFIED, request.routeTokenVerified)
            putString(EngineRuntimeIpcContract.KEY_CALLER_INSTANCE_ID, request.callerInstanceId)
            putString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID, request.targetInstanceId)
            putInt(EngineRuntimeIpcContract.KEY_CALLING_UID, request.callingUid)
            putInt(EngineRuntimeIpcContract.KEY_CALLING_PID, request.callingPid)
            putInt(EngineRuntimeIpcContract.KEY_HOST_UID, request.hostUid)
            putString(EngineRuntimeIpcContract.KEY_CALLER_PROCESS_SLOT, request.callerProcessSlot)
            putString(EngineRuntimeIpcContract.KEY_ACCESS_MODE, request.accessMode)
            putString(EngineRuntimeIpcContract.KEY_ENCODED_PATH, request.encodedPath)
            putBoolean(EngineRuntimeIpcContract.KEY_URI_GRANT_PRESENT, request.uriGrantPresent)
        }

    private fun EngineRuntimeBinderEndpoint.setEndpointField(name: String, value: Any?) {
        EngineRuntimeBinderEndpoint::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(this@setEndpointField, value)
        }
    }

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = "com.multiapp.instance.providerroute",
        dataRoot = "build/tmp/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = "com.multiapp.instance.providerroute",
            applicationLabel = "Provider route fixture",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/provider-route-fixture.apk",
            dataDir = "build/tmp/$INSTANCE_ID",
            providers = listOf(
                ResolvedComponent(
                    name = PROVIDER_CLASS,
                    authorities = listOf(AUTHORITY),
                    processName = TARGET_DECLARED_PROCESS
                ),
                ResolvedComponent(
                    name = "$ORIGIN_PACKAGE.SecondaryProvider",
                    authorities = listOf(AUTHORITY),
                    processName = ":secondary"
                )
            )
        ),
        profile = EngineProfile.BASELINE,
        processSlot = CALLER_PROCESS_SLOT,
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity0",
        evidenceSessionId = "provider-route-evidence",
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processId = CALLER_PROCESS_ID,
        processName = CALLER_PROCESS_SLOT,
        state = VirtualRuntimeState.RUNNING
    )

    private inner class EndpointFixture(
        val endpoint: EngineRuntimeBinderEndpoint,
        val callingPid: AtomicInteger,
        val routeTokens: EngineProviderRouteTokenRegistry,
        val componentAuthority: EngineComponentProcessAuthority,
        val launchTicket: EngineComponentProcessLaunchTicket,
        val targetProcessToken: IBinder
    ) {
        fun issue(
            requestedProcessSlot: String? = null
        ): EngineProviderRouteTokenAuthorityResult {
            val request = EngineProviderRouteTokenIssueRequest(
                targetInstanceId = INSTANCE_ID,
                guestAuthority = AUTHORITY,
                operation = OPERATION,
                requestedProcessSlot = requestedProcessSlot
            )
            return assertNotNull(
                endpoint.issueProviderRouteToken(
                    INSTANCE_ID,
                    request.toProviderRouteTokenIpcBundle(bundles::create)
                ).toProviderRouteTokenAuthorityResultOrNull()
            )
        }

        fun consume(
            route: EngineProviderRouteToken,
            expectedProcessSlot: String = TARGET_PROCESS_SLOT,
            guestAuthority: String = AUTHORITY,
            operation: String = OPERATION,
            providerCallingPid: Int = CALLER_PROCESS_ID,
            tokenOverride: String? = null,
            targetInstanceId: String = INSTANCE_ID
        ): EngineProviderRouteTokenAuthorityResult {
            val request = EngineProviderRouteTokenConsumeRequest(
                token = tokenOverride ?: route.token,
                targetInstanceId = targetInstanceId,
                guestAuthority = guestAuthority,
                operation = operation,
                expectedProcessSlot = expectedProcessSlot,
                providerCallingUid = HOST_UID,
                providerCallingPid = providerCallingPid,
                targetProcessToken = targetProcessToken
            )
            return assertNotNull(
                endpoint.validateAndConsumeProviderRouteToken(
                    INSTANCE_ID,
                    request.toProviderRouteTokenIpcBundle(bundles::create)
                ).toProviderRouteTokenAuthorityResultOrNull()
            )
        }
    }

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
            every { anyConstructed<Bundle>().putInt(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Int>()
            }
            every { anyConstructed<Bundle>().getInt(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Int ?: 0
            }
            every { anyConstructed<Bundle>().getInt(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Int ?: secondArg<Int>()
            }
            every { anyConstructed<Bundle>().putBoolean(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { anyConstructed<Bundle>().getBoolean(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Boolean ?: false
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
            every { anyConstructed<Bundle>().putBinder(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<IBinder?>()
            }
            every { anyConstructed<Bundle>().getBinder(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? IBinder
            }
            every {
                    anyConstructed<Bundle>().putParcelableArrayList(
                    any(),
                    any<ArrayList<out Parcelable>>()
                )
            } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<ArrayList<out Parcelable>>()
            }
            every { anyConstructed<Bundle>().putStringArrayList(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<ArrayList<String>?>()
            }
            every { anyConstructed<Bundle>().containsKey(any()) } answers {
                valuesFor(self as Bundle).containsKey(firstArg())
            }
            every { anyConstructed<Bundle>().keySet() } answers {
                valuesFor(self as Bundle).keys
            }
        }

        fun create(): Bundle = mockk<Bundle>().also(::install)

        private fun install(bundle: Bundle) {
            every { bundle.putString(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<String?>()
            }
            every { bundle.getString(any()) } answers { valuesFor(bundle)[firstArg()] as? String }
            every { bundle.putInt(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Int>()
            }
            every { bundle.getInt(any()) } answers { valuesFor(bundle)[firstArg()] as? Int ?: 0 }
            every { bundle.getInt(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] as? Int ?: secondArg<Int>()
            }
            every { bundle.putBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { bundle.getBoolean(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: false
            }
            every { bundle.putLong(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Long>()
            }
            every { bundle.getLong(any()) } answers { valuesFor(bundle)[firstArg()] as? Long ?: 0L }
            every { bundle.putBundle(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { bundle.getBundle(any()) } answers { valuesFor(bundle)[firstArg()] as? Bundle }
            every { bundle.putBinder(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<IBinder?>()
            }
            every { bundle.getBinder(any()) } answers { valuesFor(bundle)[firstArg()] as? IBinder }
            every {
                bundle.putParcelableArrayList(any(), any<ArrayList<out Parcelable>>())
            } answers {
                valuesFor(bundle)[firstArg()] = secondArg<ArrayList<out Parcelable>>()
            }
            every { bundle.putStringArrayList(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<ArrayList<String>?>()
            }
            every { bundle.containsKey(any()) } answers {
                valuesFor(bundle).containsKey(firstArg())
            }
            every { bundle.keySet() } answers { valuesFor(bundle).keys }
        }

        private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
            values.getOrPut(bundle) { linkedMapOf() }
    }

    private companion object {
        const val INSTANCE_ID = "instance-provider-route-endpoint"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val AUTHORITY = "$ORIGIN_PACKAGE.data"
        const val PROVIDER_CLASS = "$ORIGIN_PACKAGE.RemoteProvider"
        const val TARGET_DECLARED_PROCESS = ":provider"
        const val TARGET_GUEST_PROCESS_NAME = "$ORIGIN_PACKAGE$TARGET_DECLARED_PROCESS"
        const val CALLER_PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val TARGET_PROCESS_SLOT = "$HOST_PACKAGE:v4"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-provider-route"
        const val TARGET_PROCESS_EPOCH = 3L
        const val TARGET_CLIENT_SESSION_ID = "component-client-provider-route"
        const val CALLER_PROCESS_ID = 4100
        const val TARGET_PROCESS_ID = 4200
        const val TARGET_PROCESS_START_TICKS = 420_000L
        const val HOST_UID = 10123
        const val OPERATION = "query"
    }
}
