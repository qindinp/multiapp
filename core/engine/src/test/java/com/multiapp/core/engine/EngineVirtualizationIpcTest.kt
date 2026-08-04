package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineCapability
import com.multiapp.core.model.engine.EngineCapabilityReport
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
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
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.IconPolicy
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentAuthority
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedIntentPathPattern
import com.multiapp.core.model.virtual.ResolvedIntentPathPatternType
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualProviderPathPermission
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineVirtualizationIpcTest {
    @Test
    fun `instance list survives full Bundle round trip`() {
        val bundles = MockBundleFactory()
        val instances = listOf(instanceRecord())

        val decoded = instances.toInstanceListBundle(bundles::create).toInstanceListOrNull()

        assertEquals(instances, decoded)
    }

    @Test
    fun `instance list query fails closed when authority is unavailable or throws`() {
        val availableRemote = RecordingRemote().apply { instances = listOf(instanceRecord()) }
        val unavailableRemote = RecordingRemote()
        val throwingRemote = RecordingRemote().apply { listFailure = true }

        assertEquals(listOf(instanceRecord()), IpcVirtualizationEngineCore(availableRemote).listInstances())
        assertTrue(IpcVirtualizationEngineCore(unavailableRemote).listInstances().isEmpty())
        assertTrue(IpcVirtualizationEngineCore(throwingRemote).listInstances().isEmpty())
        assertEquals(1, availableRemote.listCalls)
        assertEquals(1, unavailableRemote.listCalls)
        assertEquals(1, throwingRemote.listCalls)
    }

    @Test
    fun `capability report survives strict Bundle round trip and rejects extra fields`() {
        val bundles = MockBundleFactory()
        val report = EngineCapabilityReport(
            instanceId = INSTANCE_ID,
            status = EngineResultStatus.PARTIAL,
            capabilities = listOf(
                EngineCapability(
                    id = "activity",
                    subsystem = EngineSubsystem.ACTIVITY,
                    status = EngineResultStatus.PARTIAL,
                    releaseCritical = true,
                    supportedOperations = setOf("launch", "result-record"),
                    unsupportedOperations = setOf("recents-device-proof"),
                    requiredDeviceEvidence = setOf("api-28-37-device-matrix"),
                    message = "device proof pending"
                )
            ),
            generatedAtMs = 123L,
            message = "runtime capability catalog"
        )

        val encoded = report.toEngineCapabilityReportBundle(bundles::create)

        assertEquals(report, encoded.toEngineCapabilityReportOrNull())
        encoded.putString("unexpected", "value")
        assertNull(encoded.toEngineCapabilityReportOrNull())
    }

    @Test
    fun `remote capability query delegates once and fails closed when authority is unavailable`() {
        val expected = EngineCapabilityReport(
            instanceId = INSTANCE_ID,
            status = EngineResultStatus.PARTIAL,
            capabilities = emptyList(),
            generatedAtMs = 123L,
            message = "runtime capability catalog"
        )
        val remote = RecordingRemote().apply { capabilityReport = expected }

        val available = IpcVirtualizationEngineCore(remote).queryCapabilities(INSTANCE_ID)
        val unavailable = IpcVirtualizationEngineCore(RecordingRemote()).queryCapabilities(INSTANCE_ID)
        val invalid = IpcVirtualizationEngineCore(remote).queryCapabilities(" ")

        assertEquals(expected, available)
        assertEquals(1, remote.capabilityQueryCalls)
        assertEquals(EngineResultStatus.FAIL, unavailable.status)
        assertTrue(unavailable.capabilities.isEmpty())
        assertEquals("engine_capability_authority_unavailable", unavailable.message)
        assertEquals(EngineResultStatus.FAIL, invalid.status)
        assertEquals("invalid_instance_id", invalid.message)
        assertEquals(1, remote.capabilityQueryCalls)
    }

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
            launchAction = "com.test.minimal.NEW_INTENT_PROBE",
            taskPolicy = EngineTaskPolicy.REUSE_EXISTING,
            prewarmPolicy = EnginePrewarmPolicy.REQUIRED,
            evidenceMode = EngineEvidenceMode.FULL,
            providerHookEnabled = true
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
    fun `create request preserves idempotency package and split metadata`() {
        val bundles = MockBundleFactory()
        val request = createRequest()

        val decoded = request.toEngineIpcBundle(bundles::create).toCreateInstanceRequestOrNull()

        assertEquals(request, decoded)
        assertNull(bundles.create().toCreateInstanceRequestOrNull())
    }

    @Test
    fun `proxy Activity slot requests preserve key candidates and nullable CAS values`() {
        val bundles = MockBundleFactory()
        val key = ProxyActivitySlotKey(
            instanceId = INSTANCE_ID,
            launchMode = null,
            taskKey = "task-1"
        )
        val candidates = listOf(
            "$HOST_PACKAGE.container.ProxyActivity0",
            "$HOST_PACKAGE.container.ProxyActivity1"
        )

        assertEquals(
            key,
            key.toProxyActivitySlotQueryIpcBundle(bundles::create)
                .toProxyActivitySlotQueryIpcKeyOrNull()
        )
        assertEquals(
            EngineProxyActivitySlotReserveIpcRequest(key, candidates),
            key.toProxyActivitySlotReserveIpcBundle(candidates, bundles::create)
                .toProxyActivitySlotReserveIpcRequestOrNull()
        )
        assertEquals(
            EngineProxyActivitySlotCompareAndSetIpcRequest(key, null, null),
            key.toProxyActivitySlotCompareAndSetIpcBundle(null, null, bundles::create)
                .toProxyActivitySlotCompareAndSetIpcRequestOrNull()
        )
    }

    @Test
    fun `proxy Activity slot result survives strict Bundle round trip`() {
        val bundles = MockBundleFactory()
        val key = ProxyActivitySlotKey(INSTANCE_ID, "standard", "task-1")
        val expected = VirtualProxyActivitySlotOperationResult(
            instanceId = INSTANCE_ID,
            operation = PROXY_ACTIVITY_SLOT_RESERVE_OPERATION,
            verdict = EngineResultStatus.PASS,
            key = key,
            proxyActivityClassName = "$HOST_PACKAGE.container.ProxyActivity0",
            matched = true,
            removedCount = 0,
            message = "proxy_activity_slot_reserved"
        )

        assertEquals(
            expected,
            expected.toProxyActivitySlotResultIpcBundle(bundles::create)
                .toProxyActivitySlotIpcResultOrNull()
        )
    }

    @Test
    fun `proxy Activity slot codec rejects extra fields budgets and identity mismatch`() {
        val bundles = MockBundleFactory()
        val key = ProxyActivitySlotKey(INSTANCE_ID, "standard", "task-1")
        val extraFieldRequest = key.toProxyActivitySlotQueryIpcBundle(bundles::create).apply {
            putString("unexpected", "value")
        }
        val missingNullableLaunchMode = bundles.create().apply {
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, INSTANCE_ID)
            putString(EngineRuntimeIpcContract.KEY_TASK_KEY, "task-1")
        }
        val overBudgetCandidates = List(
            EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_COUNT + 1
        ) { "$HOST_PACKAGE.container.ProxyActivity$it" }
        val mismatchedResult = VirtualProxyActivitySlotOperationResult(
            instanceId = INSTANCE_ID,
            operation = PROXY_ACTIVITY_SLOT_QUERY_OPERATION,
            verdict = EngineResultStatus.PASS,
            key = key,
            proxyActivityClassName = null,
            matched = false,
            removedCount = 0,
            message = "proxy_activity_slot_unassigned"
        ).toProxyActivitySlotResultIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, "instance-other")
        }

        assertNull(extraFieldRequest.toProxyActivitySlotQueryIpcKeyOrNull())
        assertNull(missingNullableLaunchMode.toProxyActivitySlotQueryIpcKeyOrNull())
        assertNull(
            key.toProxyActivitySlotReserveIpcBundle(overBudgetCandidates, bundles::create)
                .toProxyActivitySlotReserveIpcRequestOrNull()
        )
        assertNull(mismatchedResult.toProxyActivitySlotIpcResultOrNull())
    }

    @Test
    fun `engine result round trip carries lightweight authoritative runtime identity`() {
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
    fun `authoritative runtime codec preserves nested package facts and rejects tampering`() {
        val bundles = MockBundleFactory()
        val provider = ResolvedComponent(
            name = "com.test.Provider",
            exported = true,
            resolvedIntentFilters = listOf(
                ResolvedIntentFilter(
                    actions = listOf("com.test.QUERY"),
                    dataSchemes = listOf("content"),
                    dataAuthorities = listOf("com.test.provider"),
                    dataPaths = listOf("/books"),
                    priority = 9,
                    authorityEntries = listOf(
                        ResolvedIntentAuthority("com.test.provider", 8443),
                        ResolvedIntentAuthority("*.trusted.test")
                    ),
                    pathPatterns = ResolvedIntentPathPatternType.entries.map { type ->
                        ResolvedIntentPathPattern("/books/$type", type)
                    }
                )
            ),
            authorities = listOf("com.test.provider"),
            readPermission = "com.test.READ",
            grantUriPermissions = true,
            pathPermissions = listOf(
                VirtualProviderPathPermission(
                    pattern = VirtualProviderPathPattern(
                        "/books",
                        VirtualProviderPathPatternType.PREFIX
                    ),
                    readPermission = "com.test.READ"
                )
            ),
            uriPermissionPatterns = listOf(
                VirtualProviderPathPattern("/books/*", VirtualProviderPathPatternType.SIMPLE_GLOB)
            ),
            typedMetaData = mapOf("enabled" to VirtualMetaDataValue.boolean(true))
        )
        val expected = runtime().let { runtime ->
            runtime.copy(
                packageSnapshot = runtime.packageSnapshot.copy(
                    sourceSha256 = "a".repeat(64),
                    splitSourceDirs = listOf("/data/app/test/config.apk"),
                    splitSha256s = listOf("b".repeat(64)),
                    splitPublicSourceDirs = listOf("/data/app/test/config.apk"),
                    splitNames = listOf("config"),
                    nativeLibraries = listOf("libsample.so"),
                    abiList = listOf("arm64-v8a"),
                    typedMetaData = mapOf("answer" to VirtualMetaDataValue.int(42)),
                    debuggable = true,
                    sharedUserId = "android.uid.shared",
                    sharedUserLabel = 0x7f01_0203,
                    providers = listOf(provider)
                )
            )
        }
        val encoded = expected.toAuthoritativeRuntimeBundle(bundles::create)

        assertEquals(expected, encoded.toAuthoritativeRuntimeOrNull())

        encoded.putString("unexpected", "value")
        assertNull(encoded.toAuthoritativeRuntimeOrNull())

        val mismatched = expected.toAuthoritativeRuntimeBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, "instance-other")
        }
        assertNull(mismatched.toAuthoritativeRuntimeOrNull())
    }

    @Test
    fun `remote mutation is not retried when authority result is unknown`() {
        val remote = RecordingRemote()
        val engine = IpcVirtualizationEngineCore(remote)

        val result = engine.launchInstance(LaunchInstanceRequest(INSTANCE_ID))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("engine_authority_unavailable_or_unknown_result", result.message)
        assertEquals(1, remote.launchCalls)
    }

    @Test
    fun `remote mutation rejects mismatched operation and target without runtime identity`() {
        val remote = RecordingRemote().apply {
            stopResult = EngineRemoteResult(
                result = EngineResult.pass(
                    operation = "deleteInstance",
                    instanceId = "instance-other",
                    message = "wrong target"
                ),
                runtimeIdentity = null
            )
        }

        val result = IpcVirtualizationEngineCore(remote).stopInstance(INSTANCE_ID)

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("engine_authority_result_mismatch", result.message)
        assertEquals(INSTANCE_ID, result.instanceId)
    }

    @Test
    fun `remote clear data delegates exactly once`() {
        val expected = EngineResult.pass(
            operation = "clearInstanceData",
            instanceId = INSTANCE_ID,
            message = "cleared"
        )
        val remote = RecordingRemote().apply {
            clearResult = EngineRemoteResult(expected, runtimeIdentity = null)
        }

        val result = IpcVirtualizationEngineCore(remote).clearInstanceData(INSTANCE_ID)

        assertEquals(expected, result)
        assertEquals(1, remote.clearCalls)
    }

    @Test
    fun `remote create mutation with metadata is not retried when result is unknown`() {
        val remote = RecordingRemote()
        val engine = IpcVirtualizationEngineCore(remote)

        val result = engine.createInstance(createRequest())

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("engine_authority_unavailable_or_unknown_result", result.message)
        assertEquals(1, remote.createRequestCalls)
        assertEquals(0, remote.legacyCreateCalls)
    }

    @Test
    fun `remote runtime identity validates optional runtime delivered by authority`() {
        val bundles = MockBundleFactory()
        val expected = runtime()
        val remote = RecordingRemote().apply {
            launchResult = EngineRemoteResult(
                result = EngineResult.pass(
                    operation = "launchInstance",
                    instanceId = INSTANCE_ID,
                    originPackageName = ORIGIN_PACKAGE,
                    runtime = expected
                ),
                runtimeIdentity = expected.toEngineRuntimeIdentityBundle(
                    bundles::create
                ).toEngineRuntimeIdentityOrNull()
            )
            queryRuntime = expected
        }
        val matchingEngine = IpcVirtualizationEngineCore(remote)
        val staleEngine = IpcVirtualizationEngineCore(
            RecordingRemote().apply {
                launchResult = remote.launchResult?.copy(
                    runtimeIdentity = remote.launchResult?.runtimeIdentity?.copy(
                        engineSessionId = "stale-session"
                    )
                )
                queryRuntime = expected.copy(instanceId = "instance-other")
            }
        )

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
        var createRequestCalls: Int = 0
        var legacyCreateCalls: Int = 0
        var launchResult: EngineRemoteResult? = null
        var queryRuntime: VirtualInstanceRuntime? = null
        var capabilityReport: EngineCapabilityReport? = null
        var capabilityQueryCalls: Int = 0
        var stopResult: EngineRemoteResult? = null
        var clearResult: EngineRemoteResult? = null
        var clearCalls: Int = 0
        var instances: List<VirtualInstanceRecord>? = null
        var listCalls: Int = 0
        var listFailure: Boolean = false

        override fun listInstances(): List<VirtualInstanceRecord>? {
            listCalls += 1
            if (listFailure) error("authority failure")
            return instances
        }

        override fun installOrRefreshPackage(originPackageName: String): EngineRemoteResult? = null

        override fun createInstance(originPackageName: String): EngineRemoteResult? {
            legacyCreateCalls += 1
            return null
        }

        override fun createInstance(request: CreateInstanceRequest): EngineRemoteResult? {
            createRequestCalls += 1
            return null
        }

        override fun launchInstance(request: LaunchInstanceRequest): EngineRemoteResult? {
            launchCalls++
            return launchResult
        }

        override fun stopInstance(instanceId: String): EngineRemoteResult? = stopResult

        override fun deleteInstance(instanceId: String): EngineRemoteResult? = null

        override fun clearInstanceData(instanceId: String): EngineRemoteResult? {
            clearCalls += 1
            return clearResult
        }

        override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? = queryRuntime

        override fun queryCapabilities(instanceId: String?): EngineCapabilityReport? {
            capabilityQueryCalls += 1
            return capabilityReport
        }

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
                every { bundle.getInt(any(), any()) } answers {
                    values[firstArg()] as? Int ?: secondArg()
                }
                every { bundle.putLong(any(), any()) } answers {
                    values[firstArg()] = secondArg<Long>()
                }
                every { bundle.getLong(any()) } answers { values[firstArg()] as? Long ?: 0L }
                every { bundle.getLong(any(), any()) } answers {
                    values[firstArg()] as? Long ?: secondArg()
                }
                every { bundle.putBundle(any(), any()) } answers {
                    values[firstArg()] = secondArg<Bundle?>()
                }
                every { bundle.getBundle(any()) } answers { values[firstArg()] as? Bundle }
                every { bundle.putStringArrayList(any(), any()) } answers {
                    values[firstArg()] = secondArg<ArrayList<String>?>()
                }
                every { bundle.getStringArrayList(any()) } answers {
                    @Suppress("UNCHECKED_CAST")
                    (values[firstArg()] as? ArrayList<String>)
                }
                every { bundle.containsKey(any()) } answers { values.containsKey(firstArg()) }
                every { bundle.keySet() } answers { values.keys }
                @Suppress("DEPRECATION")
                every { bundle.get(any()) } answers { values[firstArg()] }
            }
        }
    }

    private fun instanceRecord() = VirtualInstanceRecord(
        schemaVersion = 2,
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = "com.multiapp.instance.test",
        displayName = "Test instance",
        iconPolicy = IconPolicy.CUSTOM,
        dataRoot = "/data/user/0/$HOST_PACKAGE/files/instances/$INSTANCE_ID",
        compatibilityMode = CompatibilityMode.LEGACY,
        protectedBaselinePolicy = "strict",
        createdAtMs = 100L,
        updatedAtMs = 200L,
        lastLaunchAtMs = 150L,
        launchCount = 3,
        state = InstanceState.STOPPED,
        creationRequestId = "create-request-1",
        creationRequestFingerprint = "fingerprint-1"
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

    private fun createRequest() = CreateInstanceRequest(
        creationRequestId = "create-request-1",
        install = EnginePackageInstallRequest(
            originPackageName = ORIGIN_PACKAGE,
            originApkPath = "/data/app/test/base.apk",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            applicationClassName = "com.test.App",
            packageLabel = "Test",
            requestedPermissions = listOf("android.permission.CAMERA"),
            activityClassNames = listOf("com.test.MainActivity"),
            nativeAbis = listOf("arm64-v8a"),
            splitApkPaths = listOf("/data/app/test/config.apk"),
            splitPublicSourceDirs = listOf("/data/app/test/config.apk"),
            splitNames = listOf("config"),
            isolatedSplits = true,
            debuggable = true,
            sharedUserId = "android.uid.shared",
            sharedUserLabel = 0x7f01_0203
        ),
        displayName = "Test Work",
        compatibilityMode = CompatibilityMode.LEGACY
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
