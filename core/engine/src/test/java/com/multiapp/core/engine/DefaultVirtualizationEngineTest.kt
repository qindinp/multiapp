package com.multiapp.core.engine

import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.VirtualPackageRecord
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceDataRoot
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.ImportResult
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
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
        assertEquals(1, launches.size)
        assertEquals(instance.instanceId, launches.single().instanceId)
        assertEquals(EngineProfile.BASELINE, launches.single().profile)
        assertTrue(launches.single().providerRoutingEnabled)
        assertNotNull(registry.get(instance.instanceId))
        assertEquals(1, instances.launchUpdates)
    }

    @Test
    fun `launch report merges provider and native operation evidence`() {
        val instance = instance()
        val registry = EngineRuntimeRegistry()
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = "com.multiapp.app",
            instanceManager = FakeInstanceManager(instance),
            virtualInstallService = FakeVirtualInstallService(installRecord()),
            activityLauncher = EngineActivityLauncher { },
            runtimeRegistry = registry,
            evidenceSessionFactory = { "evidence-1" }
        )

        val launch = engine.launchInstance(LaunchInstanceRequest(instanceId = instance.instanceId))
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
        assertEquals(EngineResultStatus.PARTIAL, report.status)

        assertEquals(EngineResultStatus.PASS, engine.stopInstance(instance.instanceId).status)
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

    private fun installRecord() = InstallRecord(
        packageName = "com.test.app",
        originApkPath = File("build/tmp/test.apk").absolutePath,
        originApkSha256 = "sha256",
        originCertSha256 = "cert",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        applicationClassName = null,
        packageLabel = "Test",
        activities = listOf(ComponentInfo("com.test.app.MainActivity", exported = true)),
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
}
