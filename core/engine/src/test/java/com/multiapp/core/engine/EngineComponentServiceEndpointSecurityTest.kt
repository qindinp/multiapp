package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.IdentityHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineComponentServiceEndpointSecurityTest {
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
    fun `attached component caller can plan START and foreground without primary lease`() {
        val fixture = endpoint(componentIdentity())

        val start = fixture.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.START, REMOTE_SERVICE, leaseRequested = true)
        )
        assertComponentStartPlan(start, VirtualServiceOperation.START, foreground = false)
        val foreground = fixture.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(
                VirtualServiceOperation.START_FOREGROUND,
                REMOTE_SERVICE,
                leaseRequested = true
            )
        )

        assertComponentStartPlan(
            foreground,
            VirtualServiceOperation.START_FOREGROUND,
            foreground = true
        )
    }

    @Test
    fun `component Service planner rejects unattached cross-instance and wrong process callers`() {
        val unattached = endpoint(componentIdentity = null)
        val attached = endpoint(componentIdentity())

        val unattachedPlan = unattached.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.START, REMOTE_SERVICE)
        )
        val crossInstance = attached.endpoint.planService(
            OTHER_INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.START, REMOTE_SERVICE)
        )
        val primaryService = attached.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.START, PRIMARY_SERVICE)
        )
        val otherCustomService = attached.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.START, OTHER_REMOTE_SERVICE)
        )

        assertEquals(
            "service_process_authority_missing",
            unattachedPlan.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
        assertEquals(
            "service_process_authority_missing",
            crossInstance.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
        assertEquals(
            "component_service_process_authority_mismatch",
            primaryService.getString(EngineRuntimeIpcContract.KEY_MESSAGE)
        )
        assertEquals(
            "component_service_process_authority_mismatch",
            otherCustomService.getString(EngineRuntimeIpcContract.KEY_MESSAGE)
        )
    }

    @Test
    fun `custom component bind and unbind stay unsupported`() {
        val fixture = endpoint(componentIdentity())

        val bind = fixture.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.BIND, REMOTE_SERVICE, leaseRequested = true)
        )
        assertEquals(EngineResultStatus.UNSUPPORTED.name, bind.getString(EngineRuntimeIpcContract.KEY_VERDICT))
        assertEquals("component_process_bind_unsupported", bind.getString(EngineRuntimeIpcContract.KEY_MESSAGE))
        val unbind = fixture.endpoint.planService(
            INSTANCE_ID,
            servicePlanRequest(VirtualServiceOperation.UNBIND, REMOTE_SERVICE, leaseRequested = true)
        )

        assertEquals(EngineResultStatus.UNSUPPORTED.name, unbind.getString(EngineRuntimeIpcContract.KEY_VERDICT))
        assertEquals("component_process_unbind_unsupported", unbind.getString(EngineRuntimeIpcContract.KEY_MESSAGE))
    }

    @Test
    fun `component dispatch record is scoped to attached slot and manifest Service process`() {
        val fixture = endpoint(componentIdentity())

        val startAccepted = fixture.endpoint.recordServiceDispatch(
            INSTANCE_ID,
            serviceResult(VirtualServiceOperation.START, REMOTE_SERVICE, PROCESS_SLOT, foreground = false)
        )
        val foregroundAccepted = fixture.endpoint.recordServiceDispatch(
            INSTANCE_ID,
            serviceResult(
                VirtualServiceOperation.START_FOREGROUND,
                REMOTE_SERVICE,
                PROCESS_SLOT,
                foreground = true
            )
        )
        val wrongSlot = fixture.endpoint.recordServiceDispatch(
            INSTANCE_ID,
            serviceResult(VirtualServiceOperation.START, REMOTE_SERVICE, WRONG_PROCESS_SLOT, foreground = false)
        )
        val wrongServiceProcess = fixture.endpoint.recordServiceDispatch(
            INSTANCE_ID,
            serviceResult(VirtualServiceOperation.START, PRIMARY_SERVICE, PROCESS_SLOT, foreground = false)
        )
        val bind = fixture.endpoint.recordServiceDispatch(
            INSTANCE_ID,
            serviceResult(VirtualServiceOperation.BIND, REMOTE_SERVICE, PROCESS_SLOT, foreground = false)
        )

        assertTrue(startAccepted)
        assertTrue(foregroundAccepted)
        assertFalse(wrongSlot)
        assertFalse(wrongServiceProcess)
        assertFalse(bind)
    }

    @Test
    fun `component authority does not unlock Service runtime control API`() {
        val fixture = endpoint(componentIdentity())

        val response = fixture.endpoint.queryServiceRuntimeState(INSTANCE_ID)

        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(
            "runtime_process_id_mismatch",
            response.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
    }

    @Test
    fun `component caller can prepare only its own guest process`() {
        val identity = componentIdentity()
        val fixture = endpoint(identity)
        every {
            fixture.componentAuthority.prepare(INSTANCE_ID, ":remote")
        } returns EngineComponentProcessOperationResult(
            operation = COMPONENT_PROCESS_PREPARE_OPERATION,
            instanceId = INSTANCE_ID,
            accepted = true,
            idempotent = true,
            alreadyRunning = true,
            launchTicket = null,
            processState = identity.toPublicComponentProcessState(),
            reason = "component_process_already_running"
        )

        val ownProcess = fixture.endpoint.prepareComponentProcess(INSTANCE_ID, ":remote")
        val otherProcess = fixture.endpoint.prepareComponentProcess(INSTANCE_ID, ":other")

        assertTrue(ownProcess.getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED))
        assertEquals(
            "component_process_prepare_identity_mismatch",
            otherProcess.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
    }

    private fun assertComponentStartPlan(
        response: Bundle,
        operation: VirtualServiceOperation,
        foreground: Boolean
    ) {
        assertEquals(EngineResultStatus.PARTIAL.name, response.getString(EngineRuntimeIpcContract.KEY_VERDICT))
        val targets = assertNotNull(
            response.getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TARGETS)
        )
        val target = targets.single()
        assertEquals(operation.name, target.getString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION))
        assertEquals(REMOTE_SERVICE, target.getString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME))
        assertEquals(EFFECTIVE_GUEST_PROCESS_NAME, target.getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME))
        assertEquals(PROCESS_SLOT, target.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT))
        assertFalse(target.getBoolean(EngineRuntimeIpcContract.KEY_SAME_PROCESS, true))
        assertEquals(foreground, target.getBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND))
        assertEquals(null, target.getBundle(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE))
    }

    private fun endpoint(
        componentIdentity: EngineComponentProcessClientIdentity?
    ): EndpointFixture {
        val registry = EngineRuntimeRegistry().apply { register(runtime()) }
        val server = DefaultVirtualSystemServer(registry)
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(any(), CALLING_PID) } returns EngineProcessAuthorityDecision(
            allowed = false,
            identity = null,
            reason = "runtime_process_id_mismatch"
        )
        val componentAuthority = mockk<EngineComponentProcessAuthority>()
        every {
            componentAuthority.authorizeCaller(any(), CALLING_PID, PROCESS_SLOT, PROCESS_START_TICKS)
        } answers {
            componentIdentity?.takeIf { identity -> identity.instanceId == firstArg<String>() }
        }

        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        val endpoint = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, EngineRuntimeBinderEndpoint::class.java) as EngineRuntimeBinderEndpoint
        setEndpointField(endpoint, "hostUid", HOST_UID)
        setEndpointField(endpoint, "callingUid", { HOST_UID })
        setEndpointField(endpoint, "callingPid", { CALLING_PID })
        setEndpointField(endpoint, "callingProcessName", { _: Int -> PROCESS_SLOT })
        setEndpointField(endpoint, "callingProcessStartTicks", { _: Int -> PROCESS_START_TICKS })
        setEndpointField(endpoint, "registry", registry)
        setEndpointField(endpoint, "processControlPlane", controlPlane)
        setEndpointField(endpoint, "componentProcessAuthority", componentAuthority)
        setEndpointField(endpoint, "serviceService", server.serviceService)
        setEndpointField(endpoint, "ipcBundleFactory", bundles::create)
        return EndpointFixture(endpoint, componentAuthority)
    }

    private fun setEndpointField(endpoint: EngineRuntimeBinderEndpoint, name: String, value: Any?) {
        EngineRuntimeBinderEndpoint::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(endpoint, value)
        }
    }

    private fun servicePlanRequest(
        operation: VirtualServiceOperation,
        serviceClassName: String,
        leaseRequested: Boolean = false
    ): Bundle = bundles.create().apply {
        putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation.name)
        putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, serviceClassName)
        putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, ORIGIN_PACKAGE_NAME)
        putStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES, arrayListOf())
        putStringArrayList(
            EngineRuntimeIpcContract.KEY_REQUESTED_FOREGROUND_SERVICE_TYPES,
            arrayListOf()
        )
        putBoolean(EngineRuntimeIpcContract.KEY_STICKY_RESTART_REQUESTED, false)
        putBoolean(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE_REQUESTED, leaseRequested)
    }

    private fun serviceResult(
        operation: VirtualServiceOperation,
        serviceClassName: String,
        processSlot: String,
        foreground: Boolean
    ): Bundle = bundles.create().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, INSTANCE_ID)
        putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation.name)
        putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, serviceClassName)
        putString(EngineRuntimeIpcContract.KEY_VERDICT, EngineResultStatus.PASS.name)
        putString(EngineRuntimeIpcContract.KEY_REASON, "service_started")
        putBoolean(EngineRuntimeIpcContract.KEY_STARTED, true)
        putBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND, foreground)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
        putInt(EngineRuntimeIpcContract.KEY_ACTIVE_START_COUNT, 1)
        putString(EngineRuntimeIpcContract.KEY_MESSAGE, "loader_service_started")
    }

    private fun componentIdentity() = EngineComponentProcessClientIdentity(
        instanceId = INSTANCE_ID,
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processEpoch = 3L,
        clientSessionId = "component-service-session-3",
        effectiveGuestProcessName = EFFECTIVE_GUEST_PROCESS_NAME,
        processSlot = PROCESS_SLOT,
        processId = CALLING_PID,
        processStartTicks = PROCESS_START_TICKS
    )

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE_NAME,
        originPackageName = ORIGIN_PACKAGE_NAME,
        virtualPackageName = "com.multiapp.instance.service",
        dataRoot = "build/tmp/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE_NAME,
            virtualPackageName = "com.multiapp.instance.service",
            applicationLabel = "Service fixture",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/service-fixture.apk",
            dataDir = "build/tmp/$INSTANCE_ID",
            services = listOf(
                ResolvedComponent(name = PRIMARY_SERVICE),
                ResolvedComponent(name = REMOTE_SERVICE, processName = ":remote"),
                ResolvedComponent(name = OTHER_REMOTE_SERVICE, processName = ":other")
            )
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PRIMARY_PROCESS_SLOT,
        proxySlot = "$HOST_PACKAGE_NAME.container.ProxyActivity0",
        evidenceSessionId = "evidence-service-component",
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processId = 4100,
        processName = PRIMARY_PROCESS_SLOT,
        state = VirtualRuntimeState.RUNNING
    )

    private data class EndpointFixture(
        val endpoint: EngineRuntimeBinderEndpoint,
        val componentAuthority: EngineComponentProcessAuthority
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
            every { anyConstructed<Bundle>().getBoolean(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Boolean ?: secondArg<Boolean>()
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
            every { anyConstructed<Bundle>().putStringArrayList(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<ArrayList<String>?>()
            }
            every { anyConstructed<Bundle>().getStringArrayList(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                valuesFor(self as Bundle)[firstArg()] as? ArrayList<String>
            }
            every { anyConstructed<Bundle>().putParcelableArrayList(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<ArrayList<Bundle>?>()
            }
            every { anyConstructed<Bundle>().getParcelableArrayList<Bundle>(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                valuesFor(self as Bundle)[firstArg()] as? ArrayList<Bundle>
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

        fun create(): Bundle = mockk<Bundle>().also(::install)

        private fun install(bundle: Bundle) {
            every { bundle.putString(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<String?>()
            }
            every { bundle.getString(any()) } answers { valuesFor(bundle)[firstArg()] as? String }
            every { bundle.putBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { bundle.getBoolean(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: false
            }
            every { bundle.getBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: secondArg<Boolean>()
            }
            every { bundle.putInt(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Int>()
            }
            every { bundle.getInt(any()) } answers { valuesFor(bundle)[firstArg()] as? Int ?: 0 }
            every { bundle.putLong(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Long>()
            }
            every { bundle.getLong(any()) } answers { valuesFor(bundle)[firstArg()] as? Long ?: 0L }
            every { bundle.putBundle(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { bundle.getBundle(any()) } answers { valuesFor(bundle)[firstArg()] as? Bundle }
            every { bundle.putStringArrayList(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<ArrayList<String>?>()
            }
            every { bundle.getStringArrayList(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                valuesFor(bundle)[firstArg()] as? ArrayList<String>
            }
            every { bundle.putParcelableArrayList(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<ArrayList<Bundle>?>()
            }
            every { bundle.getParcelableArrayList<Bundle>(any()) } answers {
                @Suppress("UNCHECKED_CAST")
                valuesFor(bundle)[firstArg()] as? ArrayList<Bundle>
            }
            every { bundle.containsKey(any()) } answers {
                valuesFor(bundle).containsKey(firstArg())
            }
            every { bundle.get(any()) } answers { valuesFor(bundle)[firstArg()] }
            every { bundle.keySet() } answers { valuesFor(bundle).keys }
        }

        private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
            values.getOrPut(bundle) { linkedMapOf() }
    }

    private companion object {
        const val INSTANCE_ID = "instance-component-service"
        const val OTHER_INSTANCE_ID = "instance-component-service-other"
        const val HOST_PACKAGE_NAME = "com.multiapp.app"
        const val ORIGIN_PACKAGE_NAME = "com.test"
        const val PRIMARY_SERVICE = "com.test.PrimaryService"
        const val REMOTE_SERVICE = "com.test.RemoteService"
        const val OTHER_REMOTE_SERVICE = "com.test.OtherRemoteService"
        const val EFFECTIVE_GUEST_PROCESS_NAME = "com.test:remote"
        const val PRIMARY_PROCESS_SLOT = "com.multiapp.app:v0"
        const val PROCESS_SLOT = "com.multiapp.app:v3"
        const val WRONG_PROCESS_SLOT = "com.multiapp.app:v4"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-service-42"
        const val HOST_UID = 10123
        const val CALLING_PID = 4242
        const val PROCESS_START_TICKS = 424_200L
    }
}
