package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualProviderPathPermission
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EngineRuntimeStateStoreTest {

    @Test
    fun `file backed runtime state restores engine runtime identity`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val runtime = runtime()

        FileBackedEngineRuntimeStateStore(file).put(EngineRuntimeStateRecord.from(runtime))
        val restored = FileBackedEngineRuntimeStateStore(file).get(runtime.instanceId)?.toRuntime()

        assertEquals(runtime.instanceId, restored?.instanceId)
        assertEquals(runtime.originPackageName, restored?.originPackageName)
        assertEquals(runtime.virtualPackageName, restored?.virtualPackageName)
        assertEquals(runtime.dataRoot, restored?.dataRoot)
        assertEquals(runtime.processSlot, restored?.processSlot)
        assertEquals(runtime.proxySlot, restored?.proxySlot)
        assertEquals(runtime.evidenceSessionId, restored?.evidenceSessionId)
        assertEquals(runtime.runtimeEpoch, restored?.runtimeEpoch)
        assertEquals(runtime.engineSessionId, restored?.engineSessionId)
        assertEquals(runtime.processName, restored?.processName)
        assertEquals(runtime.state, restored?.state)
        assertEquals(runtime.packageSnapshot.splitSourceDirs, restored?.packageSnapshot?.splitSourceDirs)
        assertEquals(runtime.packageSnapshot.splitPublicSourceDirs, restored?.packageSnapshot?.splitPublicSourceDirs)
        assertEquals(runtime.packageSnapshot.splitNames, restored?.packageSnapshot?.splitNames)
        assertEquals(runtime.packageSnapshot.nativeLibraryDir, restored?.packageSnapshot?.nativeLibraryDir)
        assertEquals(runtime.packageSnapshot.permissions, restored?.packageSnapshot?.permissions)
        assertEquals(runtime.packageSnapshot.metaData, restored?.packageSnapshot?.metaData)
        assertEquals(runtime.packageSnapshot.typedMetaData, restored?.packageSnapshot?.typedMetaData)
        assertEquals(runtime.packageSnapshot.signerSha256Digests, restored?.packageSnapshot?.signerSha256Digests)
        assertEquals(runtime.packageSnapshot.hasMultipleSigners, restored?.packageSnapshot?.hasMultipleSigners)
        assertEquals(runtime.packageSnapshot.activities, restored?.packageSnapshot?.activities)
        assertEquals(runtime.packageSnapshot.services, restored?.packageSnapshot?.services)
        assertEquals(runtime.packageSnapshot.receivers, restored?.packageSnapshot?.receivers)
        assertEquals(runtime.packageSnapshot.providers, restored?.packageSnapshot?.providers)
    }

    @Test
    fun `file backed runtime state remove clears restored record`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val store = FileBackedEngineRuntimeStateStore(file)
        val runtime = runtime()

        store.put(EngineRuntimeStateRecord.from(runtime))
        store.remove(runtime.instanceId)

        assertNull(FileBackedEngineRuntimeStateStore(file).get(runtime.instanceId))
    }

    @Test
    fun `file backed runtime state rejects stale epoch writes and removals`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val store = FileBackedEngineRuntimeStateStore(file)
        val newest = EngineRuntimeStateRecord.from(runtime(runtimeEpoch = 20L))
        val stale = EngineRuntimeStateRecord.from(runtime(runtimeEpoch = 10L))

        assertEquals(newest, store.putIfNewer(newest))
        assertEquals(newest, store.putIfNewer(stale))
        assertFalse(store.removeIfEpoch(newest.instanceId, stale.runtimeEpoch))
        assertEquals(newest, store.get(newest.instanceId))
        assertTrue(store.removeIfEpoch(newest.instanceId, newest.runtimeEpoch))
        assertNull(store.get(newest.instanceId))
    }

    @Test
    fun `file backed runtime state rejects different session at same epoch`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val store = FileBackedEngineRuntimeStateStore(file)
        val authoritative = EngineRuntimeStateRecord.from(
            runtime(runtimeEpoch = 20L, engineSessionId = "engine-current")
        )
        val colliding = EngineRuntimeStateRecord.from(
            runtime(runtimeEpoch = 20L, engineSessionId = "engine-stale")
        )

        assertEquals(authoritative, store.putIfNewer(authoritative))
        assertEquals(authoritative, store.putIfNewer(colliding))
        assertEquals(authoritative, store.get(authoritative.instanceId))
    }

    @Test
    fun `file backed runtime state CAS rejects concurrent state transition`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val store = FileBackedEngineRuntimeStateStore(file)
        val expected = EngineRuntimeStateRecord.from(runtime(runtimeEpoch = 20L))
        val concurrent = expected.copy(state = VirtualRuntimeState.DEAD, processId = null)
        val staleUpdate = expected.copy(state = VirtualRuntimeState.PREWARMED, processId = 4200)
        store.put(expected)
        store.put(concurrent)

        assertFalse(store.compareAndSet(expected, staleUpdate))
        assertEquals(concurrent, store.get(expected.instanceId))
    }

    @Test
    fun `independent runtime stores preserve concurrent instance writes`(@TempDir tempDir: File) {
        val file = File(tempDir, "engine_runtime_state.properties")
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val futures = (1..16).map { index ->
            executor.submit {
                start.await()
                FileBackedEngineRuntimeStateStore(file).put(
                    EngineRuntimeStateRecord.from(
                        runtime(
                            instanceId = "instance-$index",
                            runtimeEpoch = index.toLong(),
                            processSlot = "com.multiapp.app:v$index",
                            proxySlot = "com.multiapp.app.container.ProxyActivity$index"
                        )
                    )
                )
            }
        }

        start.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertEquals(
            (1..16).map { "instance-$it" }.sorted(),
            FileBackedEngineRuntimeStateStore(file).list().map { it.instanceId }
        )
    }

    private fun runtime(
        instanceId: String = "instance-1",
        runtimeEpoch: Long = 99L,
        processSlot: String = "com.multiapp.app:v0",
        proxySlot: String = "com.multiapp.app.container.ProxyActivity0",
        engineSessionId: String = "engine-evidence-1"
    ) = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.$instanceId",
            applicationLabel = "Test",
            versionCode = 7L,
            versionName = "7.0",
            targetSdk = 36,
            minSdk = 28,
            sourceDir = "build/tmp/base.apk",
            publicSourceDir = "build/tmp/public-base.apk",
            splitSourceDirs = listOf("build/tmp/split-feature.apk"),
            splitPublicSourceDirs = listOf("build/tmp/public-split-feature.apk"),
            splitNames = listOf("feature"),
            isolatedSplits = true,
            dataDir = "build/tmp/$instanceId",
            nativeLibraryDir = "build/tmp/$instanceId/lib",
            applicationClassName = "com.test.app.App",
            processName = "com.test.app",
            taskAffinity = "com.test.app.task",
            themeId = 42,
            metaData = mapOf(
                "analytics.channel" to "internal",
                "feature.reader" to "enabled"
            ),
            typedMetaData = mapOf(
                "feature.enabled" to VirtualMetaDataValue.boolean(true),
                "retry.count" to VirtualMetaDataValue.int(3)
            ),
            launcherActivityName = "com.test.app.MainActivity",
            activities = listOf(
                ResolvedComponent(
                    name = "com.test.app.MainActivity",
                    exported = true,
                    intentFilters = listOf("android.intent.action.MAIN"),
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("android.intent.action.MAIN"),
                            categories = listOf("android.intent.category.LAUNCHER"),
                            dataSchemes = listOf("app")
                        )
                    ),
                    launchMode = "singleTask",
                    processName = "com.test.app:ui",
                    taskAffinity = "com.test.app.task",
                    themeId = 42,
                    screenOrientation = "portrait",
                    configChanges = "keyboardHidden|orientation",
                    metaData = mapOf("activity.meta" to "value"),
                    typedMetaData = mapOf("activity.count" to VirtualMetaDataValue.int(2)),
                    targetActivityName = "com.test.app.AliasTargetActivity"
                )
            ),
            services = listOf(
                ResolvedComponent(
                    name = "com.test.app.SyncService",
                    exported = false,
                    permission = "com.test.app.permission.SYNC",
                    processName = "com.test.app:service",
                    metaData = mapOf("service.meta" to "value")
                )
            ),
            receivers = listOf(
                ResolvedComponent(
                    name = "com.test.app.BootReceiver",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(actions = listOf("android.intent.action.BOOT_COMPLETED"))
                    ),
                    permission = "android.permission.RECEIVE_BOOT_COMPLETED"
                )
            ),
            providers = listOf(
                ResolvedComponent(
                    name = "com.test.app.DataProvider",
                    exported = true,
                    authorities = listOf("com.test.app.provider", "com.test.app.files"),
                    permission = "com.test.app.permission.PROVIDER",
                    grantUriPermissions = false,
                    pathPermissions = listOf(
                        VirtualProviderPathPermission(
                            VirtualProviderPathPattern("/private", VirtualProviderPathPatternType.PREFIX),
                            readPermission = "com.test.app.permission.READ_PRIVATE"
                        )
                    ),
                    uriPermissionPatterns = listOf(
                        VirtualProviderPathPattern("/shared", VirtualProviderPathPatternType.PREFIX)
                    ),
                    processName = "com.test.app"
                )
            ),
            permissions = listOf("android.permission.INTERNET"),
            originCertSha256 = "cert-sha",
            signerSha256Digests = listOf("old-cert-sha", "cert-sha"),
            hasMultipleSigners = false
        ),
        profile = EngineProfile.BASELINE,
        processSlot = processSlot,
        proxySlot = proxySlot,
        evidenceSessionId = "evidence-1",
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processName = processSlot,
        state = VirtualRuntimeState.CREATED
    )
}
