package com.multiapp.core.engine

import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.VirtualPackageRecord
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceDataRoot
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.ImportResult
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualTaskRecord
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DefaultVirtualizationEngineTest {

    @Test
    fun `launch registers runtime and dispatches container launch spec`() {
        val instance = instance()
        val installs = FakeVirtualInstallService(installRecord())
        val instances = FakeInstanceManager(instance)
        val launches = mutableListOf<EngineLaunchSpec>()
        val registry = EngineRuntimeRegistry()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = instances,
            virtualInstallService = installs,
            activityLauncher = EngineActivityLauncher { launches += it },
            runtimeRegistry = registry,
            evidenceSessionFactory = { "evidence-1" }
        )

        val result = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(instance.instanceId, result.runtime?.instanceId)
        assertEquals("evidence-1", result.runtime?.evidenceSessionId)
        assertEquals("engine-evidence-1", result.runtime?.engineSessionId)
        assertEquals(VirtualRuntimeState.CREATED, result.runtime?.state)
        assertEquals(result.runtime?.processSlot, result.runtime?.processName)
        assertNotNull(result.runtime?.runtimeEpoch)
        assertEquals(1, launches.size)
        assertEquals(instance.instanceId, launches.single().instanceId)
        assertEquals(EngineProfile.BASELINE, launches.single().profile)
        assertTrue(launches.single().providerRoutingEnabled)
        assertNotNull(registry.get(instance.instanceId))
        assertEquals("engine-evidence-1", registry.evidence(instance.instanceId).entries["engineSessionId"])
        assertEquals("CREATED", registry.evidence(instance.instanceId).entries["runtimeState"])
        assertEquals("PASS", registry.evidence(instance.instanceId).entries["virtualSystemServerStatus"])
        assertEquals(
            "BASELINE",
            registry.evidence(instance.instanceId)
                .operationEntries("hook-profile", "profile-gate")
                .single()
                .entries["profile"]
        )
        assertEquals(
            "false",
            registry.evidence(instance.instanceId)
                .operationEntries("hook-profile", "profile-gate")
                .single()
                .entries["lsplantEnabled"]
        )
        assertEquals(1, instances.launchUpdates)
    }

    @Test
    fun `launch snapshot preserves base split and native library runtime paths`() {
        val instance = instance()
        val baseApk = File("build/tmp/base.apk").absolutePath
        val splitArm64 = File("build/tmp/split_config.arm64_v8a.apk").absolutePath
        val splitFeature = File("build/tmp/split_feature.reader.apk").absolutePath
        val publicSplitArm64 = File("build/tmp/public/split_config.arm64_v8a.apk").absolutePath
        val publicSplitFeature = File("build/tmp/public/split_feature.reader.apk").absolutePath
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(
                installRecord(
                    originApkPath = baseApk,
                    splitApkPaths = listOf(splitArm64, splitFeature),
                    splitPublicSourceDirs = listOf(publicSplitArm64, publicSplitFeature),
                    splitNames = listOf("config.arm64_v8a", "feature.reader"),
                    isolatedSplits = true
                )
            ),
            activityLauncher = EngineActivityLauncher { },
            evidenceSessionFactory = { "evidence-splits" }
        )

        val runtime = assertNotNull(
            engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId)).runtime
        )
        val snapshot = runtime.packageSnapshot

        assertEquals(baseApk, snapshot.sourceDir)
        assertEquals(baseApk, snapshot.publicSourceDir)
        assertEquals(listOf(splitArm64, splitFeature), snapshot.splitSourceDirs)
        assertEquals(listOf(publicSplitArm64, publicSplitFeature), snapshot.splitPublicSourceDirs)
        assertEquals(listOf("config.arm64_v8a", "feature.reader"), snapshot.splitNames)
        assertTrue(snapshot.isolatedSplits)
        assertEquals(listOf(baseApk, splitArm64, splitFeature), snapshot.codeSourceDirs)
        assertEquals(listOf(baseApk, publicSplitArm64, publicSplitFeature), snapshot.publicResourceDirs)
        assertEquals(File(instance.dataRoot, "lib").absolutePath, snapshot.nativeLibraryDir)
    }

    @Test
    fun `launch snapshot preserves distinct Provider read and write permissions`() {
        val instance = instance()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(
                installRecord(
                    providers = listOf(
                        ComponentInfo(
                            name = "com.test.app.DataProvider",
                            permission = "com.test.app.PROVIDER",
                            readPermission = "com.test.app.READ_PROVIDER",
                            writePermission = "com.test.app.WRITE_PROVIDER",
                            grantUriPermissions = true
                        )
                    )
                )
            ),
            activityLauncher = EngineActivityLauncher { },
            evidenceSessionFactory = { "evidence-provider-permissions" }
        )

        val provider = assertNotNull(
            engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
                .runtime
                ?.packageSnapshot
                ?.providers
                ?.single()
        )

        assertEquals("com.test.app.PROVIDER", provider.permission)
        assertEquals("com.test.app.READ_PROVIDER", provider.readPermission)
        assertEquals("com.test.app.WRITE_PROVIDER", provider.writePermission)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun `launch snapshot preserves typed meta-data and signer identity`() {
        val instance = instance()
        val installRecord = installRecord().copy(
            applicationMetaData = mapOf(
                "feature.enabled" to VirtualMetaDataValue.boolean(true),
                "retry.count" to VirtualMetaDataValue.int(3)
            ),
            signerSha256Digests = listOf("old-signer", "current-signer"),
            hasMultipleSigners = false,
            activities = listOf(
                ComponentInfo(
                    name = "com.test.app.MainActivity",
                    exported = true,
                    metaData = mapOf("mode" to VirtualMetaDataValue.string("main"))
                )
            )
        )
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord),
            activityLauncher = EngineActivityLauncher { },
            evidenceSessionFactory = { "evidence-package-identity" }
        )

        val snapshot = assertNotNull(
            engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId)).runtime
        ).packageSnapshot

        assertEquals(installRecord.applicationMetaData, snapshot.typedMetaData)
        assertEquals("true", snapshot.metaData["feature.enabled"])
        assertEquals(installRecord.signerSha256Digests, snapshot.signerSha256Digests)
        assertFalse(snapshot.hasMultipleSigners)
        assertEquals(
            "main",
            snapshot.activities.single().typedMetaData.getValue("mode").encodedValue
        )
    }

    @Test
    fun `launch assigns proxy slot matching launcher launch mode`() {
        val instance = instance()
        val launches = mutableListOf<EngineLaunchSpec>()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(
                installRecord(
                    activities = listOf(
                        ComponentInfo(
                            name = "com.test.app.MainActivity",
                            exported = true,
                            launchMode = "singleTop",
                            taskAffinity = "com.test.app.main"
                        )
                    )
                )
            ),
            activityLauncher = EngineActivityLauncher { launches += it },
            evidenceSessionFactory = { "evidence-single-top" }
        )

        val runtime = assertNotNull(
            engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId)).runtime
        )

        assertTrue(runtime.proxySlot.contains(".container.ProxyActivitySingleTop"))
        assertTrue(launches.single().proxySlot.contains(".container.ProxyActivitySingleTop"))
        assertEquals(
            ProxyActivitySlots.processNameForClassName("com.multiapp.app", runtime.proxySlot),
            runtime.processSlot
        )
        assertEquals(runtime.processSlot, launches.single().processSlot)
        assertEquals("singleTop", runtime.packageSnapshot.activities.single().launchMode)
        assertEquals("com.test.app.main", runtime.packageSnapshot.activities.single().taskAffinity)
    }

    @Test
    fun `launch report merges provider and native operation evidence`() {
        val instance = instance()
        val registry = EngineRuntimeRegistry()
        val taskStore = InMemoryEngineActivityTaskStateStore()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = registry,
            evidenceSessionFactory = { "evidence-1" },
            systemServerFactory = { runtimeRegistry ->
                DefaultVirtualSystemServer(
                    registry = runtimeRegistry,
                    activityTaskStateStore = taskStore
                )
            }
        )

        val launch = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
        taskStore.save(
            EngineActivityTaskStateSnapshot(
                tasks = listOf(
                    VirtualTaskRecord(
                        taskId = 9,
                        affinity = "${instance.originPackageName}:${instance.instanceId}",
                        activities = listOf(
                            VirtualActivityRecord(
                                token = "token-main",
                                activityId = "activity-main",
                                instanceId = instance.instanceId,
                                originPackageName = instance.originPackageName,
                                guestActivityClassName = "com.test.app.MainActivity",
                                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                                state = VirtualActivityState.RESUMED,
                                taskId = 9,
                                taskAffinity = "${instance.originPackageName}:${instance.instanceId}"
                            )
                        )
                    )
                )
            )
        )
        val providerAccepted = registry.registerOperationEvidence(
            instance.instanceId,
            EngineOperationEvidence(
                component = "provider",
                operation = "route-token",
                verdict = EngineResultStatus.PASS,
                entries = mapOf(
                    "authority" to "com.test.app.provider",
                    "routeToken" to "route-token-1"
                )
            )
        )
        val nativeAccepted = registry.registerOperationEvidence(
            instance.instanceId,
            EngineOperationEvidence(
                component = "native",
                operation = "path-redirect",
                verdict = EngineResultStatus.PARTIAL,
                entries = mapOf(
                    "requestedPath" to "/data/data/com.test.app/lib/libfoo.so",
                    "redirectedPath" to File(instance.dataRoot, "lib/libfoo.so").absolutePath
                )
            )
        )

        val report = engine.exportEvidence(instance.instanceId)
        val flattenedEvidence = report.flattenedOperationEvidence()

        assertEquals(EngineResultStatus.PASS, launch.status)
        assertTrue(providerAccepted)
        assertTrue(nativeAccepted)
        assertEquals("com.multiapp.app", report.entries["hostPackageName"])
        assertEquals(instance.dataRoot, report.entries["dataRoot"])
        assertEquals("<redacted>", report.operationEntries("provider", "route-token").single().entries["routeToken"])
        assertEquals(
            File(instance.dataRoot, "lib/libfoo.so").absolutePath,
            report.operationEntries("native", "path-redirect").single().entries["redirectedPath"]
        )
        assertEquals("1", report.operationEntries("activity", "task-state").single().entries["taskCount"])
        assertEquals(
            "com.test.app.MainActivity",
            report.operationEntries("activity", "task-state").single().entries["topActivityClassName"]
        )
        assertEquals(EngineResultStatus.PARTIAL, report.status)
        assertEquals(
            listOf(
                "activity:task-state:PASS",
                "app-ops:runtime-state:PARTIAL",
                "broadcast:runtime-state:PARTIAL",
                "hook-profile:profile-gate:PASS",
                "native:path-redirect:PARTIAL",
                "provider:route-token:PASS",
                "provider:runtime-state:PARTIAL",
                "service:runtime-state:PARTIAL"
            ),
            flattenedEvidence.map { evidence -> "${evidence.component}:${evidence.operation}:${evidence.verdict}" }
        )
        assertEquals(flattenedEvidence, report.flattenedOperationEvidence())

        val missingReport = engine.exportEvidence("missing-instance")
        assertEquals(EngineResultStatus.FAIL, missingReport.status)
        assertEquals("runtime_not_found", missingReport.entries["reason"])
        assertTrue(missingReport.operationEvidence.isEmpty())
        assertTrue(missingReport.flattenedOperationEvidence().isEmpty())

        assertEquals(EngineResultStatus.PASS, engine.stopInstance(instance.instanceId).status)
        val stoppedReport = engine.exportEvidence(instance.instanceId)
        assertEquals(EngineResultStatus.FAIL, stoppedReport.status)
        assertEquals("runtime_not_found", stoppedReport.entries["reason"])
        assertTrue(stoppedReport.operationEvidence.isEmpty())
        assertFalse(
            registry.registerOperationEvidence(
                instance.instanceId,
                EngineOperationEvidence(
                    component = "provider",
                    operation = "route-token",
                    verdict = EngineResultStatus.PASS,
                    entries = mapOf("routeToken" to "after-stop")
                )
            )
        )
    }

    @Test
    fun `compat hook launch is rejected without allow list`() {
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance()),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { error("launch should be blocked") }
        )

        val result = engine.launchInstance(
            LaunchInstanceRequest(instanceId = "instance-1", profile = EngineProfile.COMPAT_HOOK)
        )

        assertEquals(EngineResultStatus.UNSUPPORTED, result.status)
        assertNull(engine.queryRuntimeState("instance-1"))
    }

    @Test
    fun `compat hook launch uses engine hook runtime when allow listed`() {
        val instance = instance()
        val decisions = mutableListOf<EngineProfileDecision>()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            profilePolicy = CompatibilityProfilePolicy(
                allowList = setOf(
                    EngineProfileAllowKey(
                        originPackageName = instance.originPackageName,
                        instanceId = instance.instanceId,
                        profile = EngineProfile.COMPAT_HOOK
                    )
                )
            ),
            hookRuntime = RecordingHookRuntime(decisions),
            evidenceSessionFactory = { "compat-evidence" }
        )

        val result = engine.launchInstance(
            LaunchInstanceRequest(instanceId = instance.instanceId, profile = EngineProfile.COMPAT_HOOK)
        )
        val hookEvidence = result.evidence
            ?.operationEntries("hook-profile", "profile-gate")
            ?.single()

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(listOf(EngineProfile.COMPAT_HOOK), decisions.map { it.profile })
        assertEquals(EngineResultStatus.PARTIAL, hookEvidence?.verdict)
        assertEquals("true", hookEvidence?.entries?.get("lsplantEnabled"))
        assertEquals("core:engine-test", hookEvidence?.entries?.get("hookRuntimeOwner"))
    }

    @Test
    fun `same-origin instance launches keep isolated runtime state and distinct slots`() {
        val first = instance(instanceId = "instance-1")
        val second = instance(instanceId = "instance-2")
        val instances = FakeInstanceManager(first, second)
        val slotStore = InMemoryEngineRuntimeSlotStore()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = instances,
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            slotStore = slotStore,
            evidenceSessionFactory = { "evidence" }
        )

        val firstLaunch = engine.launchInstance(LaunchInstanceRequest(instanceId = "instance-1"))
        val secondLaunch = engine.launchInstance(LaunchInstanceRequest(instanceId = "instance-2"))

        assertEquals(EngineResultStatus.PASS, firstLaunch.status)
        assertEquals(EngineResultStatus.PASS, secondLaunch.status)
        val firstRuntime = assertNotNull(firstLaunch.runtime)
        val secondRuntime = assertNotNull(secondLaunch.runtime)

        assertEquals(first.instanceId, firstRuntime.instanceId)
        assertEquals(second.instanceId, secondRuntime.instanceId)
        assertEquals(first.virtualPackageName, firstRuntime.virtualPackageName)
        assertEquals(second.virtualPackageName, secondRuntime.virtualPackageName)
        assertEquals(first.dataRoot, firstRuntime.dataRoot)
        assertEquals(second.dataRoot, secondRuntime.dataRoot)
        assertNotEquals(firstRuntime.virtualPackageName, secondRuntime.virtualPackageName)
        assertNotEquals(firstRuntime.dataRoot, secondRuntime.dataRoot)
        assertEquals(firstRuntime, engine.queryRuntimeState(first.instanceId))
        assertEquals(secondRuntime, engine.queryRuntimeState(second.instanceId))

        assertNotEquals(firstRuntime.processSlot, secondRuntime.processSlot)
        assertNotEquals(firstRuntime.proxySlot, secondRuntime.proxySlot)
        assertEquals(firstRuntime.processSlot, slotStore.get(first.instanceId)?.processSlot)
        assertEquals(secondRuntime.processSlot, slotStore.get(second.instanceId)?.processSlot)
        assertEquals(firstRuntime.proxySlot, slotStore.get(first.instanceId)?.proxySlot)
        assertEquals(secondRuntime.proxySlot, slotStore.get(second.instanceId)?.proxySlot)
    }

    @Test
    fun `same-origin runtime slots survive engine rebuild`(@TempDir tempDir: File) {
        val first = instance(instanceId = "instance-1")
        val second = instance(instanceId = "instance-2")
        val instances = FakeInstanceManager(first, second)
        val slotFile = File(tempDir, "engine_runtime_slots.properties")
        fun newEngine() = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = instances,
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            slotStore = FileBackedEngineRuntimeSlotStore(slotFile),
            runtimeRegistry = EngineRuntimeRegistry(),
            evidenceSessionFactory = { "evidence" }
        )

        val firstEngine = newEngine()
        val firstInitialRuntime = assertNotNull(
            firstEngine.launchInstance(LaunchInstanceRequest(instanceId = first.instanceId)).runtime
        )
        val secondInitialRuntime = assertNotNull(
            firstEngine.launchInstance(LaunchInstanceRequest(instanceId = second.instanceId)).runtime
        )

        val rebuiltEngine = newEngine()
        val firstRebuiltRuntime = assertNotNull(
            rebuiltEngine.launchInstance(LaunchInstanceRequest(instanceId = first.instanceId)).runtime
        )
        val secondRebuiltRuntime = assertNotNull(
            rebuiltEngine.launchInstance(LaunchInstanceRequest(instanceId = second.instanceId)).runtime
        )

        assertEquals(firstInitialRuntime.processSlot, firstRebuiltRuntime.processSlot)
        assertEquals(firstInitialRuntime.proxySlot, firstRebuiltRuntime.proxySlot)
        assertEquals(secondInitialRuntime.processSlot, secondRebuiltRuntime.processSlot)
        assertEquals(secondInitialRuntime.proxySlot, secondRebuiltRuntime.proxySlot)
        assertNotEquals(firstRebuiltRuntime.processSlot, secondRebuiltRuntime.processSlot)
        assertNotEquals(firstRebuiltRuntime.proxySlot, secondRebuiltRuntime.proxySlot)
        assertEquals(first.instanceId, firstRebuiltRuntime.instanceId)
        assertEquals(second.instanceId, secondRebuiltRuntime.instanceId)
        assertEquals(first.virtualPackageName, firstRebuiltRuntime.virtualPackageName)
        assertEquals(second.virtualPackageName, secondRebuiltRuntime.virtualPackageName)
        assertEquals(first.dataRoot, firstRebuiltRuntime.dataRoot)
        assertEquals(second.dataRoot, secondRebuiltRuntime.dataRoot)
    }

    private fun instance(instanceId: String = "instance-1") = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        displayName = "Test",
        dataRoot = File("build/tmp/$instanceId").absolutePath,
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun installRecord(
        originApkPath: String = File("build/tmp/test.apk").absolutePath,
        splitApkPaths: List<String> = emptyList(),
        splitPublicSourceDirs: List<String> = emptyList(),
        splitNames: List<String> = emptyList(),
        isolatedSplits: Boolean = false,
        activities: List<ComponentInfo> = listOf(ComponentInfo("com.test.app.MainActivity", exported = true)),
        providers: List<ComponentInfo> = emptyList()
    ) = InstallRecord(
        packageName = "com.test.app",
        originApkPath = originApkPath,
        originApkSha256 = "sha256",
        originCertSha256 = "cert",
        splitApkPaths = splitApkPaths,
        splitPublicSourceDirs = splitPublicSourceDirs,
        splitNames = splitNames,
        splitApkSha256s = splitApkPaths.mapIndexed { index, _ -> "split-sha-$index" },
        isolatedSplits = isolatedSplits,
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        applicationClassName = null,
        packageLabel = "Test",
        activities = activities,
        providers = providers,
        installTimeMs = 1L
    )

    private class FakeInstanceManager(
        private vararg val instances: VirtualInstanceRecord
    ) : InstanceManager {
        var launchUpdates = 0

        private val instance: VirtualInstanceRecord = instances.first()

        override fun createInstance(
            originPackageName: String,
            displayName: String,
            compatibilityMode: CompatibilityMode
        ): Result<VirtualInstanceRecord> = Result.success(instance)

        override fun getInstance(instanceId: String): VirtualInstanceRecord? =
            instances.firstOrNull { it.instanceId == instanceId }

        override fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord> =
            instances.filter { it.originPackageName == originPackageName }

        override fun listInstances(): List<VirtualInstanceRecord> = instances.toList()

        override fun deleteInstance(instanceId: String): Boolean = instance.instanceId == instanceId

        override fun updateLaunchState(instanceId: String): VirtualInstanceRecord? {
            launchUpdates += 1
            return instances.firstOrNull { it.instanceId == instanceId }
        }

        override fun getDataRoot(instanceId: String): InstanceDataRoot? = null
    }

    private class FakeVirtualInstallService(
        private val record: InstallRecord
    ) : VirtualInstallService {
        override suspend fun importFromInstalledPackage(packageName: String): Result<ImportResult> =
            Result.failure(UnsupportedOperationException())

        override fun importFromMetadata(
            packageName: String,
            originApkPath: String,
            versionCode: Long,
            versionName: String,
            targetSdk: Int,
            minSdk: Int,
            applicationClassName: String?,
            packageLabel: String?
        ): Result<ImportResult> = Result.failure(UnsupportedOperationException())

        override fun ensureInstallRecord(app: VirtualApp): Result<ImportResult> =
            Result.failure(UnsupportedOperationException())

        override fun getInstallRecord(packageName: String): InstallRecord? =
            record.takeIf { it.packageName == packageName }

        override fun listInstallRecords(): List<InstallRecord> = listOf(record)

        override fun deleteInstallRecord(packageName: String): Boolean = false

        override fun hasInstallRecord(packageName: String): Boolean = record.packageName == packageName
    }

    private class RecordingHookRuntime(
        private val decisions: MutableList<EngineProfileDecision>
    ) : EngineHookRuntime {
        override fun profileEvidence(decision: EngineProfileDecision): EngineOperationEvidence {
            decisions += decision
            val evidence = EngineHookRuntimeEvidence.profileEvidence(
                decision = decision,
                hookEngineTouched = decision.lsplantEnabled,
                hookCount = "0"
            )
            return evidence.copy(entries = evidence.entries + ("hookRuntimeOwner" to "core:engine-test"))
        }
    }
}
