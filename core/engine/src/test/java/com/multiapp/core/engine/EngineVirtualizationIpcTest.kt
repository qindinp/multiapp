package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EnginePrewarmPolicy
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.EngineTaskPolicy
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineVirtualizationIpcTest {
    @Test
    fun `launch request and evidence survive strict Bundle round trip`() {
        val bundles = MockBundleFactory()
        val request = LaunchInstanceRequest(
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
        val evidence = evidence()

        assertEquals(
            request,
            request.toEngineIpcBundle(bundles::create).toLaunchInstanceRequestOrNull()
        )
        assertEquals(
            evidence,
            evidence.toEngineEvidenceBundle(bundles::create).toEngineEvidenceOrNull()
        )
        assertNull(bundles.create().toLaunchInstanceRequestOrNull())
    }

    @Test
    fun `engine result round trip carries runtime identity without parceling package snapshot`() {
        val bundles = MockBundleFactory()
        val runtime = runtime()
        val remote = EngineResult.pass(
            operation = "launchInstance",
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            runtime = runtime,
            evidence = evidence()
        ).toEngineIpcBundle(bundles::create).toEngineRemoteResultOrNull()

        assertEquals(EngineResultStatus.PASS, remote?.result?.status)
        assertEquals(evidence(), remote?.result?.evidence)
        assertTrue(requireNotNull(remote?.runtimeIdentity).matches(runtime))
        assertNull(remote.result.runtime)
    }

    @Test
    fun `remote mutation is not retried when authority result is unknown`() {
        val remote = RecordingRemote()
        val engine = IpcVirtualizationEngineCore(remote) { runtime() }

        val result = engine.launchInstance(LaunchInstanceRequest(INSTANCE_ID))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("engine_authority_unavailable_or_unknown_result", result.message)
        assertEquals(1, remote.launchCalls)
    }

    @Test
    fun `remote runtime identity must match read only durable snapshot`() {
        val bundles = MockBundleFactory()
        val expected = runtime()
        val remote = RecordingRemote().apply {
            launchResult = EngineRemoteResult(
                result = EngineResult.pass(
                    operation = "launchInstance",
                    instanceId = INSTANCE_ID,
                    originPackageName = ORIGIN_PACKAGE
                ),
                runtimeIdentity = expected.toEngineRuntimeIdentityBundle(
                    bundles::create
                ).toEngineRuntimeIdentityOrNull()
            )
            queryIdentity = launchResult?.runtimeIdentity
        }
        val matchingEngine = IpcVirtualizationEngineCore(remote) { expected }
        val staleEngine = IpcVirtualizationEngineCore(remote) {
            expected.copy(engineSessionId = "stale-session")
        }

        val matching = matchingEngine.launchInstance(LaunchInstanceRequest(INSTANCE_ID))
        val stale = staleEngine.launchInstance(LaunchInstanceRequest(INSTANCE_ID))

        assertEquals(expected, matching.runtime)
        assertEquals(EngineResultStatus.FAIL, stale.status)
        assertEquals("authoritative_runtime_snapshot_mismatch", stale.message)
        assertEquals(expected, matchingEngine.queryRuntimeState(INSTANCE_ID))
        assertNull(staleEngine.queryRuntimeState(INSTANCE_ID))
    }

    private class RecordingRemote : EngineVirtualizationRemote {
        var launchCalls: Int = 0
        var launchResult: EngineRemoteResult? = null
        var queryIdentity: EngineRuntimeIdentity? = null

        override fun installOrRefreshPackage(originPackageName: String): EngineRemoteResult? = null

        override fun createInstance(originPackageName: String): EngineRemoteResult? = null

        override fun launchInstance(request: LaunchInstanceRequest): EngineRemoteResult? {
            launchCalls++
            return launchResult
        }

        override fun stopInstance(instanceId: String): EngineRemoteResult? = null

        override fun deleteInstance(instanceId: String): EngineRemoteResult? = null

        override fun queryRuntimeState(instanceId: String): EngineRuntimeIdentity? = queryIdentity

        override fun exportEvidence(instanceId: String): EngineEvidenceReport? = null
    }

    private class MockBundleFactory {
        fun create(): Bundle {
            val values = linkedMapOf<String, Any?>()
            return mockk<Bundle>().also { bundle ->
                every { bundle.putString(any(), any()) } answers {
                    values[firstArg()] = secondArg<String?>()
                }
                every { bundle.getString(any()) } answers { values[firstArg()] as? String }
                every { bundle.putBoolean(any(), any()) } answers {
                    values[firstArg()] = secondArg<Boolean>()
                }
                every { bundle.getBoolean(any()) } answers { values[firstArg()] as? Boolean ?: false }
                every { bundle.putInt(any(), any()) } answers {
                    values[firstArg()] = secondArg<Int>()
                }
                every { bundle.getInt(any()) } answers { values[firstArg()] as? Int ?: 0 }
                every { bundle.putLong(any(), any()) } answers {
                    values[firstArg()] = secondArg<Long>()
                }
                every { bundle.getLong(any()) } answers { values[firstArg()] as? Long ?: 0L }
                every { bundle.putBundle(any(), any()) } answers {
                    values[firstArg()] = secondArg<Bundle?>()
                }
                every { bundle.getBundle(any()) } answers { values[firstArg()] as? Bundle }
                every { bundle.containsKey(any()) } answers { values.containsKey(firstArg()) }
                every { bundle.keySet() } answers { values.keys }
            }
        }
    }

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
        profile = EngineProfile.BASELINE,
        entries = mapOf("loadedApk" to "PASS"),
        operationEvidence = mapOf(
            "provider" to mapOf(
                "query" to listOf(
                    EngineOperationEvidence(
                        component = "provider",
                        operation = "query",
                        verdict = EngineResultStatus.PASS,
                        entries = mapOf("authority" to "com.test.provider")
                    )
                )
            )
        ),
        subsystemVerdicts = mapOf(
            EngineSubsystem.PROVIDER to EngineResultStatus.PASS,
            EngineSubsystem.NATIVE to EngineResultStatus.UNSUPPORTED
        )
    )

    private companion object {
        const val INSTANCE_ID = "instance-test"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
    }
}
