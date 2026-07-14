package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EnginePackageEnabledStateServiceTest {
    @Test
    fun `application and typed component states are isolated per instance`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        registry.register(runtime("instance-one", runtimeEpoch = 1L))
        registry.register(runtime("instance-two", runtimeEpoch = 2L))

        val application = server.packageService.setApplicationEnabledState(
            "instance-one",
            EnginePackageEnabledStates.DISABLED_USER
        )
        val activity = server.packageService.setComponentEnabledState(
            "instance-one",
            VirtualPackageComponentType.ACTIVITY,
            ".SharedComponent",
            EnginePackageEnabledStates.DISABLED
        )

        assertEquals(EngineResultStatus.PASS, application.verdict)
        assertEquals("com.test.app.SharedComponent", activity.className)
        assertEquals(
            EnginePackageEnabledStates.DISABLED_USER,
            server.packageService.queryApplicationEnabledState("instance-one").enabledState
        )
        assertEquals(
            EnginePackageEnabledStates.DEFAULT,
            server.packageService.queryApplicationEnabledState("instance-two").enabledState
        )
        assertEquals(
            EnginePackageEnabledStates.DISABLED,
            server.packageService.queryComponentEnabledState(
                "instance-one",
                VirtualPackageComponentType.ACTIVITY,
                "SharedComponent"
            ).enabledState
        )
        assertEquals(
            EnginePackageEnabledStates.DEFAULT,
            server.packageService.queryComponentEnabledState(
                "instance-one",
                VirtualPackageComponentType.SERVICE,
                "com.test.app.SharedComponent"
            ).enabledState
        )
        assertEquals(
            EnginePackageEnabledStates.DEFAULT,
            server.packageService.queryComponentEnabledState(
                "instance-two",
                VirtualPackageComponentType.ACTIVITY,
                "com.test.app.SharedComponent"
            ).enabledState
        )
    }

    @Test
    fun `enabled states and package digests survive engine restart`(@TempDir tempDir: File) {
        val runtimeStateFile = File(tempDir, EngineRuntimeStateFiles.DEFAULT_FILE_NAME)
        val packageStateFile = File(tempDir, EnginePackageEnabledStateFiles.DEFAULT_FILE_NAME)
        val firstRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(runtimeStateFile))
        val firstServer = DefaultVirtualSystemServer(
            registry = firstRegistry,
            packageEnabledStateStore = FileBackedEnginePackageEnabledStateStore(packageStateFile)
        )
        val runtime = runtime("instance-restart", sourceSha256 = "1".repeat(64))
        firstRegistry.register(runtime)
        firstServer.packageService.setApplicationEnabledState(
            runtime.instanceId,
            EnginePackageEnabledStates.DISABLED_UNTIL_USED
        )
        firstServer.packageService.setComponentEnabledState(
            runtime.instanceId,
            VirtualPackageComponentType.SERVICE,
            ".SyncService",
            EnginePackageEnabledStates.DISABLED
        )

        val restoredRegistry = EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(runtimeStateFile))
        val restoredServer = DefaultVirtualSystemServer(
            registry = restoredRegistry,
            packageEnabledStateStore = FileBackedEnginePackageEnabledStateStore(packageStateFile)
        )

        assertEquals(
            runtime.packageSnapshot.sourceSha256,
            restoredRegistry.get(runtime.instanceId)?.packageSnapshot?.sourceSha256
        )
        assertEquals(
            EnginePackageEnabledStates.DISABLED_UNTIL_USED,
            restoredServer.packageService.queryApplicationEnabledState(runtime.instanceId).enabledState
        )
        assertEquals(
            EnginePackageEnabledStates.DISABLED,
            restoredServer.packageService.queryComponentEnabledState(
                runtime.instanceId,
                VirtualPackageComponentType.SERVICE,
                "com.test.app.SyncService"
            ).enabledState
        )
    }

    @Test
    fun `new package generation does not inherit old enabled state`() {
        val registry = EngineRuntimeRegistry()
        val store = InMemoryEnginePackageEnabledStateStore()
        val server = DefaultVirtualSystemServer(registry, packageEnabledStateStore = store)
        val old = runtime("instance-upgrade", versionCode = 1L, sourceSha256 = "1".repeat(64))
        registry.register(old)
        server.packageService.setApplicationEnabledState(
            old.instanceId,
            EnginePackageEnabledStates.DISABLED_USER
        )

        val successor = runtime(
            instanceId = old.instanceId,
            versionCode = 2L,
            sourceSha256 = "2".repeat(64),
            runtimeEpoch = old.runtimeEpoch + 1L,
            engineSessionId = "engine-successor"
        )
        registry.register(successor)

        assertEquals(
            EnginePackageEnabledStates.DEFAULT,
            server.packageService.queryApplicationEnabledState(old.instanceId).enabledState
        )
        assertNull(store.read(successor.toPackageGenerationIdentityOrNull()!!))
    }

    @Test
    fun `generation race removes only expected generation and preserves successor state`() {
        val registry = EngineRuntimeRegistry()
        val delegate = InMemoryEnginePackageEnabledStateStore()
        val old = runtime("instance-race", versionCode = 1L, sourceSha256 = "1".repeat(64))
        val successor = runtime(
            instanceId = old.instanceId,
            versionCode = 2L,
            sourceSha256 = "2".repeat(64),
            runtimeEpoch = old.runtimeEpoch + 1L,
            engineSessionId = "engine-successor"
        )
        registry.register(old)
        val racingStore = CallbackPackageStateStore(delegate) { expectedGeneration ->
            registry.register(successor)
            val successorGeneration = successor.toPackageGenerationIdentityOrNull()!!
            assertTrue(successorGeneration != expectedGeneration)
            delegate.setApplicationState(
                successorGeneration,
                EnginePackageEnabledStates.DISABLED_USER
            )
        }
        val server = DefaultVirtualSystemServer(
            registry = registry,
            packageEnabledStateStore = racingStore
        )

        val staleSet = server.packageService.setApplicationEnabledState(
            old.instanceId,
            EnginePackageEnabledStates.ENABLED
        )

        assertEquals(EngineResultStatus.FAIL, staleSet.verdict)
        assertEquals("package_generation_changed_during_set", staleSet.message)
        assertEquals(
            EnginePackageEnabledStates.DISABLED_USER,
            server.packageService.queryApplicationEnabledState(old.instanceId).enabledState
        )
        assertNotNull(delegate.read(successor.toPackageGenerationIdentityOrNull()!!))
    }

    @Test
    fun `unknown malicious and illegal enabled-state requests fail closed`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        registry.register(runtime("instance-validation"))

        val unknown = server.packageService.queryComponentEnabledState(
            "instance-validation",
            VirtualPackageComponentType.ACTIVITY,
            ".MissingActivity"
        )
        val malicious = server.packageService.setComponentEnabledState(
            "instance-validation",
            VirtualPackageComponentType.ACTIVITY,
            "../MainActivity",
            EnginePackageEnabledStates.DISABLED
        )
        val invalidApplication = server.packageService.setApplicationEnabledState(
            "instance-validation",
            5
        )
        val invalidUserComponent = server.packageService.setComponentEnabledState(
            "instance-validation",
            VirtualPackageComponentType.ACTIVITY,
            ".MainActivity",
            EnginePackageEnabledStates.DISABLED_USER
        )
        val invalidUntilUsedComponent = server.packageService.setComponentEnabledState(
            "instance-validation",
            VirtualPackageComponentType.ACTIVITY,
            ".MainActivity",
            EnginePackageEnabledStates.DISABLED_UNTIL_USED
        )

        assertFalse(unknown.found)
        assertEquals("component_not_found", unknown.message)
        assertEquals("invalid_component_class_name", malicious.message)
        assertEquals("invalid_application_enabled_state:5", invalidApplication.message)
        assertEquals("invalid_component_enabled_state:3", invalidUserComponent.message)
        assertEquals("invalid_component_enabled_state:4", invalidUntilUsedComponent.message)
        listOf(unknown, malicious, invalidApplication, invalidUserComponent, invalidUntilUsedComponent)
            .forEach { result -> assertEquals(EngineResultStatus.FAIL, result.verdict) }
    }

    @Test
    fun `concurrent idempotent sets and queries remain linearizable`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        registry.register(runtime("instance-concurrent"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(12)
        try {
            val operations = buildList<Callable<VirtualPackageEnabledStateResult>> {
                repeat(16) {
                    add(Callable {
                        start.await()
                        server.packageService.setApplicationEnabledState(
                            "instance-concurrent",
                            EnginePackageEnabledStates.DISABLED_USER
                        )
                    })
                    add(Callable {
                        start.await()
                        server.packageService.queryApplicationEnabledState("instance-concurrent")
                    })
                }
            }
            val futures = operations.map(executor::submit)
            start.countDown()
            val results = futures.map { future -> future.get(10, TimeUnit.SECONDS) }

            assertTrue(results.all { result -> result.verdict == EngineResultStatus.PASS })
            assertTrue(results.mapNotNull { result -> result.enabledState }.all { state ->
                state == EnginePackageEnabledStates.DEFAULT ||
                    state == EnginePackageEnabledStates.DISABLED_USER
            })
            assertEquals(
                EnginePackageEnabledStates.DISABLED_USER,
                server.packageService.queryApplicationEnabledState("instance-concurrent").enabledState
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runtime(
        instanceId: String,
        versionCode: Long = 1L,
        sourceSha256: String = "0".repeat(64),
        runtimeEpoch: Long = 7L,
        engineSessionId: String = "engine-$instanceId"
    ) = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.${instanceId.replace('-', '_')}",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.${instanceId.replace('-', '_')}",
            applicationLabel = "Test App",
            versionCode = versionCode,
            versionName = "$versionCode.0",
            targetSdk = 36,
            minSdk = 28,
            sourceDir = "build/tmp/$instanceId/base-$versionCode.apk",
            sourceSha256 = sourceSha256,
            dataDir = "build/tmp/$instanceId",
            activities = listOf(
                ResolvedComponent(name = "com.test.app.MainActivity", exported = true),
                ResolvedComponent(name = "com.test.app.SharedComponent", exported = false)
            ),
            services = listOf(
                ResolvedComponent(name = "com.test.app.SyncService", exported = false),
                ResolvedComponent(name = "com.test.app.SharedComponent", exported = false)
            ),
            receivers = listOf(
                ResolvedComponent(name = "com.test.app.BootReceiver", exported = true)
            ),
            providers = listOf(
                ResolvedComponent(
                    name = "com.test.app.DataProvider",
                    exported = false,
                    authorities = listOf("com.test.app.provider")
                )
            ),
            originCertSha256 = "f".repeat(64),
            signerSha256Digests = listOf("f".repeat(64))
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-$instanceId",
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = 4321,
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.RUNNING
    )

    private class CallbackPackageStateStore(
        private val delegate: EnginePackageEnabledStateStore,
        private val afterFirstApplicationSet: (EnginePackageGenerationIdentity) -> Unit
    ) : EnginePackageEnabledStateStore by delegate {
        private var invoked = false

        override fun setApplicationState(
            generation: EnginePackageGenerationIdentity,
            state: Int
        ): EnginePackageEnabledStateMutation {
            val mutation = delegate.setApplicationState(generation, state)
            if (!invoked) {
                invoked = true
                afterFirstApplicationSet(generation)
            }
            return mutation
        }
    }
}
