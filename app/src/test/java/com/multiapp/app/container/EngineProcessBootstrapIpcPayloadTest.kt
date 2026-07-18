package com.multiapp.app.container

import android.os.Bundle
import android.os.IBinder
import com.multiapp.core.engine.EngineComponentProcessLaunchTicket
import com.multiapp.core.engine.EngineProcessBootstrapKind
import com.multiapp.core.engine.EngineProcessBootstrapRequest
import com.multiapp.core.engine.EngineProcessBootstrapResult
import com.multiapp.core.engine.EngineProcessBootstrapState
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.IdentityHashMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EngineProcessBootstrapIpcPayloadTest {
    private lateinit var bundles: BundleHarness

    @BeforeEach
    fun setUp() {
        bundles = BundleHarness().also(BundleHarness::installConstructorMock)
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun `custom process bootstrap payload preserves endpoint Binder and target identity`() {
        val request = componentRequest()
        val envelope = EngineProcessBootstrapIpc.requestEnvelope(request)
        val endpointBinder = mockk<IBinder> {
            every { isBinderAlive } returns true
        }
        val expectedResult = EngineProcessBootstrapResult(
            state = EngineProcessBootstrapState.READY,
            verdict = EngineResultStatus.PASS,
            instanceId = INSTANCE_ID,
            runtimeEpoch = RUNTIME_EPOCH,
            engineSessionId = ENGINE_SESSION_ID,
            clientToken = endpointBinder,
            processId = TARGET_PROCESS_ID,
            processStartTicks = TARGET_PROCESS_START_TICKS,
            processName = TARGET_PROCESS_SLOT,
            applicationStatus = "PASS",
            providerPreinstallStatus = "PASS",
            systemServiceProxyStatus = "SUCCESS",
            message = "custom Provider process is READY",
            evidence = mapOf(
                "bootstrapKind" to EngineProcessBootstrapKind.COMPONENT_RUNTIME.name,
                "effectiveGuestProcessName" to TARGET_GUEST_PROCESS_NAME
            )
        )

        val decodedResult = EngineProcessBootstrapIpc.result(
            EngineProcessBootstrapIpc.resultBundle(expectedResult)
        )

        assertEquals(EngineProcessBootstrapKind.COMPONENT_RUNTIME, envelope.kind)
        assertEquals(request.componentLaunchTicket, envelope.componentLaunchTicket)
        assertEquals(EngineProcessBootstrapState.READY, decodedResult?.state)
        assertEquals(INSTANCE_ID, decodedResult?.instanceId)
        assertEquals(RUNTIME_EPOCH, decodedResult?.runtimeEpoch)
        assertEquals(ENGINE_SESSION_ID, decodedResult?.engineSessionId)
        assertSame(endpointBinder, decodedResult?.clientToken)
        assertEquals(TARGET_PROCESS_ID, decodedResult?.processId)
        assertEquals(TARGET_PROCESS_START_TICKS, decodedResult?.processStartTicks)
        assertEquals(TARGET_PROCESS_SLOT, decodedResult?.processName)
        assertTrue(decodedResult?.validates(request) == true)
    }

    private fun componentRequest(): EngineProcessBootstrapRequest {
        val ticket = EngineComponentProcessLaunchTicket(
            instanceId = INSTANCE_ID,
            effectiveGuestProcessName = TARGET_GUEST_PROCESS_NAME,
            processSlot = TARGET_PROCESS_SLOT,
            attachCapability = "provider-bootstrap-capability-${"x".repeat(32)}"
        )
        return EngineProcessBootstrapRequest(
            runtime = VirtualInstanceRuntime(
                instanceId = INSTANCE_ID,
                hostPackageName = HOST_PACKAGE,
                originPackageName = ORIGIN_PACKAGE,
                virtualPackageName = "com.multiapp.instance.providerbootstrap",
                dataRoot = "build/tmp/$INSTANCE_ID",
                packageSnapshot = VirtualPackageSnapshot(
                    instanceId = INSTANCE_ID,
                    originPackageName = ORIGIN_PACKAGE,
                    virtualPackageName = "com.multiapp.instance.providerbootstrap",
                    applicationLabel = "Provider bootstrap fixture",
                    versionCode = 1L,
                    versionName = "1.0",
                    targetSdk = 35,
                    minSdk = 28,
                    sourceDir = "build/tmp/provider-bootstrap-fixture.apk",
                    dataDir = "build/tmp/$INSTANCE_ID"
                ),
                profile = EngineProfile.BASELINE,
                processSlot = TARGET_PROCESS_SLOT,
                proxySlot = "$HOST_PACKAGE.container.ProxyActivity4",
                evidenceSessionId = "provider-bootstrap-evidence",
                runtimeEpoch = RUNTIME_EPOCH,
                engineSessionId = ENGINE_SESSION_ID,
                processName = TARGET_GUEST_PROCESS_NAME
            ),
            providerRoutingEnabled = true,
            legacyProviderHookEnabled = false,
            evidenceMode = EngineEvidenceMode.MINIMAL,
            kind = EngineProcessBootstrapKind.COMPONENT_RUNTIME,
            componentLaunchTicket = ticket
        )
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
            every { anyConstructed<Bundle>().putBinder(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<IBinder?>()
            }
            every { anyConstructed<Bundle>().getBinder(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? IBinder
            }
            every { anyConstructed<Bundle>().containsKey(any()) } answers {
                valuesFor(self as Bundle).containsKey(firstArg())
            }
            every { anyConstructed<Bundle>().keySet() } answers {
                valuesFor(self as Bundle).keys
            }
        }

        private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
            values.getOrPut(bundle) { linkedMapOf() }
    }

    private companion object {
        const val INSTANCE_ID = "instance-provider-bootstrap"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val TARGET_GUEST_PROCESS_NAME = "$ORIGIN_PACKAGE:provider"
        const val TARGET_PROCESS_SLOT = "$HOST_PACKAGE:v4"
        const val TARGET_PROCESS_ID = 4200
        const val TARGET_PROCESS_START_TICKS = 420_000L
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-provider-bootstrap"
    }
}
