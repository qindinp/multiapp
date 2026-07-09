package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = "instance-1",
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.instance-1",
        dataRoot = "build/tmp/instance-1",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "instance-1",
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.instance-1",
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
            dataDir = "build/tmp/instance-1",
            nativeLibraryDir = "build/tmp/instance-1/lib",
            applicationClassName = "com.test.app.App",
            processName = "com.test.app",
            taskAffinity = "com.test.app.task",
            themeId = 42,
            metaData = mapOf(
                "analytics.channel" to "internal",
                "feature.reader" to "enabled"
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
                    grantUriPermissions = true,
                    processName = "com.test.app"
                )
            ),
            permissions = listOf("android.permission.INTERNET"),
            originCertSha256 = "cert-sha"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1",
        runtimeEpoch = 99L,
        engineSessionId = "engine-evidence-1",
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.CREATED
    )
}
