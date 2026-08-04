package com.multiapp.core.engine

import android.content.pm.PackageManager
import android.os.IBinder
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.VirtualPackageRecord
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EnginePrewarmPolicy
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
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
import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualTaskRecord
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.every
import io.mockk.mockk
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
    fun `capability query rejects a blank instance id instead of returning the static catalog`() {
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance()),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = readyBootstrapper()
        )

        listOf("", " ", "\t").forEach { invalidInstanceId ->
            val report = engine.queryCapabilities(invalidInstanceId)

            assertEquals(EngineResultStatus.FAIL, report.status)
            assertNull(report.instanceId)
            assertEquals("invalid_instance_id", report.message)
        }

        val staticReport = engine.queryCapabilities(null)
        assertEquals("static engine capability catalog", staticReport.message)
        assertNull(staticReport.instanceId)
    }

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
            processBootstrapper = readyBootstrapper(),
            runtimeRegistry = registry,
            evidenceSessionFactory = { "evidence-1" }
        )

        val result = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(instance.instanceId, result.runtime?.instanceId)
        assertEquals("evidence-1", result.runtime?.evidenceSessionId)
        assertEquals("engine-evidence-1", result.runtime?.engineSessionId)
        assertEquals(VirtualRuntimeState.PREWARMED, result.runtime?.state)
        assertEquals(result.runtime?.processSlot, result.runtime?.processName)
        assertNotNull(result.runtime?.runtimeEpoch)
        assertEquals(1, launches.size)
        assertEquals(instance.instanceId, launches.single().instanceId)
        assertEquals(EngineProfile.BASELINE, launches.single().profile)
        assertEquals(EngineEvidenceMode.DEFAULT, launches.single().evidenceMode)
        assertEquals("com.test.app.MainActivity", launches.single().guestActivityClassName)
        assertEquals(true, result.runtime?.packageSnapshot?.debuggable)
        assertEquals("android.uid.shared", result.runtime?.packageSnapshot?.sharedUserId)
        assertEquals(0x7f01_0203, result.runtime?.packageSnapshot?.sharedUserLabel)
        assertEquals(result.runtime?.runtimeEpoch, launches.single().runtimeEpoch)
        assertEquals(result.runtime?.engineSessionId, launches.single().engineSessionId)
        assertEquals(EngineProcessBootstrapState.READY, launches.single().bootstrapState)
        assertTrue(launches.single().providerRoutingEnabled)
        assertFalse(launches.single().legacyProviderHookEnabled)
        assertEquals(0, installs.metadataImportCalls)
        assertNotNull(registry.get(instance.instanceId))
        assertEquals("engine-evidence-1", registry.evidence(instance.instanceId).entries["engineSessionId"])
        assertEquals("PREWARMED", registry.evidence(instance.instanceId).entries["runtimeState"])
        assertEquals("PASS", registry.evidence(instance.instanceId).entries["virtualSystemServerStatus"])
        assertEquals(
            "PROXY_LAUNCH_RETURNED",
            registry.evidence(instance.instanceId)
                .operationEntries("activity", "foreground-launch")
                .last()
                .entries["stage"]
        )
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
    fun `explicit provider hook is propagated to bootstrap and launch without compat profile`() {
        val instance = instance()
        val bootstrapRequests = mutableListOf<EngineProcessBootstrapRequest>()
        val launches = mutableListOf<EngineLaunchSpec>()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { launches += it },
            processBootstrapper = EngineProcessBootstrapper { request ->
                bootstrapRequests += request
                readyBootstrap(request)
            }
        )

        val result = engine.launchInstance(
            LaunchInstanceRequest(
                instanceId = instance.instanceId,
                providerHookEnabled = true
            )
        )

        assertEquals(EngineResultStatus.PASS, result.status)
        assertEquals(EngineProfile.BASELINE, result.runtime?.profile)
        assertTrue(bootstrapRequests.single().providerRoutingEnabled)
        assertTrue(bootstrapRequests.single().legacyProviderHookEnabled)
        assertTrue(launches.single().providerRoutingEnabled)
        assertTrue(launches.single().legacyProviderHookEnabled)
    }

    @Test
    fun `same instance launches cannot overlap runtime generations`() {
        val instance = instance()
        val firstBootstrapEntered = CountDownLatch(1)
        val secondBootstrapEntered = CountDownLatch(1)
        val releaseFirstBootstrap = CountDownLatch(1)
        val bootstrapCalls = AtomicInteger()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = EngineProcessBootstrapper { request ->
                when (bootstrapCalls.incrementAndGet()) {
                    1 -> {
                        firstBootstrapEntered.countDown()
                        check(releaseFirstBootstrap.await(5, TimeUnit.SECONDS))
                    }
                    2 -> secondBootstrapEntered.countDown()
                }
                readyBootstrap(request)
            },
            runtimeEpochFactory = { 1L }
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<EngineResult> {
                engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
            }
            assertTrue(firstBootstrapEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<EngineResult> {
                engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
            }

            assertFalse(secondBootstrapEntered.await(200, TimeUnit.MILLISECONDS))
            releaseFirstBootstrap.countDown()

            val firstResult = first.get(5, TimeUnit.SECONDS)
            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals(EngineResultStatus.PASS, firstResult.status)
            assertEquals(EngineResultStatus.PASS, secondResult.status)
            assertTrue(secondBootstrapEntered.await(5, TimeUnit.SECONDS))
            assertTrue(assertNotNull(secondResult.runtime).runtimeEpoch > assertNotNull(firstResult.runtime).runtimeEpoch)
        } finally {
            releaseFirstBootstrap.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stop waits for same instance launch to finish`() {
        val instance = instance()
        val bootstrapEntered = CountDownLatch(1)
        val releaseBootstrap = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = EngineProcessBootstrapper { request ->
                bootstrapEntered.countDown()
                check(releaseBootstrap.await(5, TimeUnit.SECONDS))
                readyBootstrap(request)
            }
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val launch = executor.submit<EngineResult> {
                engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
            }
            assertTrue(bootstrapEntered.await(5, TimeUnit.SECONDS))
            val stop = executor.submit<EngineResult> {
                stopAttempted.countDown()
                engine.stopInstance(instance.instanceId)
            }
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(stop.isDone)

            releaseBootstrap.countDown()

            assertEquals(EngineResultStatus.PASS, launch.get(5, TimeUnit.SECONDS).status)
            assertEquals(EngineResultStatus.PASS, stop.get(5, TimeUnit.SECONDS).status)
            assertNull(engine.queryRuntimeState(instance.instanceId))
        } finally {
            releaseBootstrap.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `launch blocks foreground proxy when process bootstrap fails`() {
        val instance = instance()
        val launches = mutableListOf<EngineLaunchSpec>()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { launches += it },
            processBootstrapper = EngineProcessBootstrapper { request ->
                EngineProcessBootstrapResult(
                    state = EngineProcessBootstrapState.FAILED,
                    verdict = EngineResultStatus.FAIL,
                    instanceId = request.runtime.instanceId,
                    runtimeEpoch = request.runtime.runtimeEpoch,
                    engineSessionId = request.runtime.engineSessionId,
                    processName = request.runtime.processSlot,
                    message = "guest Application did not become ready"
                )
            }
        )

        val result = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertTrue(launches.isEmpty())
        assertEquals(VirtualRuntimeState.CREATED, engine.queryRuntimeState(instance.instanceId)?.state)
        assertEquals(
            EngineResultStatus.FAIL,
            engine.exportEvidence(instance.instanceId)
                .operationEntries("runtime", "process-bootstrap")
                .single()
                .verdict
        )
    }

    @Test
    fun `launch rejects a disabled process bootstrap policy`() {
        val instance = instance()
        val launches = mutableListOf<EngineLaunchSpec>()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { launches += it }
        )

        val result = engine.launchInstance(
            LaunchInstanceRequest(
                instanceId = instance.instanceId,
                prewarmPolicy = EnginePrewarmPolicy.DISABLED
            )
        )

        assertEquals(EngineResultStatus.UNSUPPORTED, result.status)
        assertTrue(launches.isEmpty())
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
            processBootstrapper = readyBootstrapper(),
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
            processBootstrapper = readyBootstrapper(),
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
    fun `launch seeds source permission state once and preserves explicit instance decision`() {
        val instance = instance()
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(
                installRecord(
                    permissions = listOf(
                        "android.permission.CAMERA",
                        "android.permission.RECORD_AUDIO"
                    )
                )
            ),
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = readyBootstrapper(),
            runtimeRegistry = registry,
            permissionGrantSeeder = SourcePackagePermissionGrantSeeder { permissionName, _ ->
                if (permissionName == "android.permission.CAMERA") {
                    PackageManager.PERMISSION_GRANTED
                } else {
                    PackageManager.PERMISSION_DENIED
                }
            },
            systemServerFactory = { server }
        )

        val first = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
        server.permissionService.setPermissionGrant(
            instance.instanceId,
            "android.permission.CAMERA",
            granted = false,
            source = EnginePermissionGrantSource.USER_DECISION
        )
        val second = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))

        assertEquals(EngineResultStatus.PASS, first.status)
        assertTrue(
            first.evidence
                ?.operationEntries("permission", "seed")
                ?.single()
                ?.entries
                ?.get("mirroredGrantCount") == "1"
        )
        assertFalse(
            server.permissionService.checkPermission(
                instance.instanceId,
                "android.permission.CAMERA"
            ).granted
        )
        assertEquals(
            EnginePermissionGrantSource.USER_DECISION,
            server.permissionService.checkPermission(
                instance.instanceId,
                "android.permission.CAMERA"
            ).source
        )
        assertEquals(
            "2",
            second.evidence
                ?.operationEntries("permission", "seed")
                ?.last()
                ?.entries
                ?.get("preservedDecisionCount")
        )
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
            processBootstrapper = readyBootstrapper(),
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
            processBootstrapper = readyBootstrapper(),
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
            processBootstrapper = readyBootstrapper(),
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
                "activity:foreground-launch:PASS",
                "activity:foreground-launch:PASS",
                "activity:foreground-launch:PASS",
                "activity:foreground-launch:PASS",
                "activity:foreground-launch:PASS",
                "activity:task-state:PASS",
                "app-ops:runtime-state:PARTIAL",
                "broadcast:runtime-state:PARTIAL",
                "hook-profile:profile-gate:PASS",
                "native:path-redirect:PARTIAL",
                "permission:runtime-state:PARTIAL",
                "permission:seed:PASS",
                "provider:route-token:PASS",
                "provider:runtime-state:PARTIAL",
                "runtime:process-bootstrap:PASS",
                "runtime:process-token:PASS",
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
        val launches = mutableListOf<EngineLaunchSpec>()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { launches += it },
            processBootstrapper = readyBootstrapper(),
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
        assertTrue(launches.single().legacyProviderHookEnabled)
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
            processBootstrapper = readyBootstrapper(),
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
            processBootstrapper = readyBootstrapper(),
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

    @Test
    fun `launch reconciles stale proxy activity slots for deleted instances`(@TempDir tempDir: File) {
        val active = instance(instanceId = "instance-active")
        val staleKey = ProxyActivitySlotKey(
            instanceId = "instance-deleted",
            launchMode = "singleTop",
            taskKey = "com.test.app:instance-deleted"
        )
        val proxySlotStore = FileBackedProxyActivitySlotAssignmentStore(
            File(tempDir, "proxy_activity_slots.properties")
        )
        proxySlotStore.save(
            staleKey,
            EngineProxyActivitySlots.classNamesForProcessSlot(
                hostPackageName = "com.multiapp.app",
                processSlot = "com.multiapp.app:v2",
                launchMode = "singleTop"
            ).first()
        )
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(active),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = readyBootstrapper(),
            systemServerFactory = { registry ->
                DefaultVirtualSystemServer(
                    registry = registry,
                    proxyActivitySlotAssignmentStore = proxySlotStore
                )
            }
        )

        val result = engine.launchInstance(LaunchInstanceRequest(instanceId = active.instanceId))

        assertEquals(EngineResultStatus.PASS, result.status)
        assertNull(proxySlotStore.find(staleKey))
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

    private fun readyBootstrapper(): EngineProcessBootstrapper =
        EngineProcessBootstrapper(::readyBootstrap)

    private fun readyBootstrap(
        request: EngineProcessBootstrapRequest
    ): EngineProcessBootstrapResult = EngineProcessBootstrapResult.immediateReady(request).copy(
        clientToken = mockk<IBinder>(relaxed = true) {
            every { isBinderAlive } returns true
        }
    )

    private fun installRecord(
        originApkPath: String = File("build/tmp/test.apk").absolutePath,
        splitApkPaths: List<String> = emptyList(),
        splitPublicSourceDirs: List<String> = emptyList(),
        splitNames: List<String> = emptyList(),
        isolatedSplits: Boolean = false,
        activities: List<ComponentInfo> = listOf(ComponentInfo("com.test.app.MainActivity", exported = true)),
        providers: List<ComponentInfo> = emptyList(),
        permissions: List<String> = emptyList()
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
        permissions = permissions,
        debuggable = true,
        sharedUserId = "android.uid.shared",
        sharedUserLabel = 0x7f01_0203,
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
        var metadataImportCalls: Int = 0

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
        ): Result<ImportResult> {
            metadataImportCalls += 1
            return Result.failure(UnsupportedOperationException())
        }

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
