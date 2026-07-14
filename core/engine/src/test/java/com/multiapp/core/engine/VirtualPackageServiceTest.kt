package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentAuthority
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedIntentPathPattern
import com.multiapp.core.model.virtual.ResolvedIntentPathPatternType
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class VirtualPackageServiceTest {

    @Test
    fun `missing runtime returns null snapshot and failed identity`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        val snapshot = server.packageService.queryPackageSnapshot("missing-instance")
        val identity = server.packageService.queryPackageIdentity("missing-instance")

        assertNull(snapshot)
        assertTrue(identity.isFailure)
        assertEquals("runtime_not_found:missing-instance", identity.exceptionOrNull()?.message)
        assertNull(
            server.packageService.queryComponent(
                instanceId = "missing-instance",
                type = VirtualPackageComponentType.ACTIVITY,
                className = "com.test.app.MainActivity"
            )
        )
        assertNull(server.packageService.queryProviderByAuthority("missing-instance", "com.test.app.provider"))
        assertEquals(
            emptyList(),
            server.packageService.resolveIntent(
                instanceId = "missing-instance",
                type = VirtualPackageComponentType.ACTIVITY,
                action = "android.intent.action.MAIN"
            )
        )
    }

    @Test
    fun `runtime backed package service returns snapshot identity split paths and permissions`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "engine_runtime_state.properties")
        val firstServer = DefaultVirtualSystemServer(
            EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(stateFile))
        )
        val runtime = runtime()
        firstServer.runtimeService.register(runtime)

        val restoredServer = DefaultVirtualSystemServer(
            EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(stateFile))
        )
        val snapshot = restoredServer.packageService.queryPackageSnapshot(runtime.instanceId)
        val identity = restoredServer.packageService.queryPackageIdentity(runtime.instanceId).getOrThrow()

        assertEquals(runtime.packageSnapshot.instanceId, snapshot?.instanceId)
        assertEquals(runtime.packageSnapshot.originPackageName, snapshot?.originPackageName)
        assertEquals(runtime.packageSnapshot.virtualPackageName, snapshot?.virtualPackageName)
        assertEquals(runtime.packageSnapshot.applicationLabel, snapshot?.applicationLabel)
        assertEquals(runtime.packageSnapshot.versionCode, snapshot?.versionCode)
        assertEquals(runtime.packageSnapshot.versionName, snapshot?.versionName)
        assertEquals(runtime.packageSnapshot.sourceDir, snapshot?.sourceDir)
        assertEquals(runtime.packageSnapshot.publicSourceDir, snapshot?.publicSourceDir)
        assertEquals(runtime.packageSnapshot.splitSourceDirs, snapshot?.splitSourceDirs)
        assertEquals(runtime.packageSnapshot.splitPublicSourceDirs, snapshot?.splitPublicSourceDirs)
        assertEquals(runtime.packageSnapshot.splitNames, snapshot?.splitNames)
        assertEquals(runtime.packageSnapshot.permissions, snapshot?.permissions)
        assertEquals(runtime.packageSnapshot.originCertSha256, snapshot?.originCertSha256)
        assertEquals(runtime.packageSnapshot.metaData, snapshot?.metaData)
        assertEquals(runtime.packageSnapshot.activities, snapshot?.activities)
        assertEquals(runtime.packageSnapshot.services, snapshot?.services)
        assertEquals(runtime.packageSnapshot.receivers, snapshot?.receivers)
        assertEquals(runtime.packageSnapshot.providers, snapshot?.providers)
        assertEquals(runtime.hostPackageName, identity.hostPackageName)
        assertEquals(runtime.packageSnapshot.originPackageName, identity.originPackageName)
        assertEquals(runtime.packageSnapshot.virtualPackageName, identity.virtualPackageName)
        assertEquals(runtime.packageSnapshot.applicationLabel, identity.applicationLabel)
        assertEquals(runtime.packageSnapshot.versionCode, identity.versionCode)
        assertEquals(runtime.packageSnapshot.versionName, identity.versionName)
    }

    @Test
    fun `runtime backed package service resolves components and intent filters after restore`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "engine_runtime_state.properties")
        val firstServer = DefaultVirtualSystemServer(
            EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(stateFile))
        )
        val runtime = runtime()
        firstServer.runtimeService.register(runtime)

        val restoredPackageService = DefaultVirtualSystemServer(
            EngineRuntimeRegistry(FileBackedEngineRuntimeStateStore(stateFile))
        ).packageService

        val launcher = restoredPackageService.queryComponent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            className = "com.test.app.MainActivity"
        )
        val aliasTarget = restoredPackageService.queryComponent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            className = "com.test.app.AliasTargetActivity"
        )
        val provider = restoredPackageService.queryProviderByAuthority(
            instanceId = runtime.instanceId,
            authority = "com.test.app.provider"
        )
        val dataMatches = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT"),
            dataScheme = "https",
            dataMimeType = "image/png",
            dataAuthority = "example.com",
            dataPath = "/deeplink"
        )
        val launchMatches = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.MAIN",
            categories = setOf("android.intent.category.LAUNCHER")
        )
        val serviceMatches = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.SERVICE,
            action = "com.test.app.action.SYNC",
            dataScheme = "content"
        )
        val receiverMatches = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.RECEIVER,
            action = "android.intent.action.BOOT_COMPLETED"
        )
        val wrongCategoryMatches = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.MAIN",
            categories = setOf("android.intent.category.DEFAULT")
        )
        val structuredMatches = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT"),
            dataScheme = "https",
            dataMimeType = "image/jpeg",
            dataAuthority = "secure.example.com:8443",
            dataPath = "/secure/document/42"
        )
        val wrongStructuredPort = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT"),
            dataScheme = "https",
            dataMimeType = "image/jpeg",
            dataAuthority = "secure.example.com:443",
            dataPath = "/secure/document/42"
        )
        val malformedAuthority = restoredPackageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT"),
            dataScheme = "https",
            dataMimeType = "image/jpeg",
            dataAuthority = "secure.example.com:not-a-port",
            dataPath = "/secure/document/42"
        )

        assertEquals("com.test.app.MainActivity", launcher?.name)
        assertEquals("singleTask", launcher?.launchMode)
        assertEquals("com.test.app.AliasActivity", aliasTarget?.name)
        assertEquals("com.test.app.DataProvider", provider?.name)
        assertEquals(true, provider?.grantUriPermissions)
        assertEquals("com.test.app.READ_DATA", provider?.readPermission)
        assertEquals("com.test.app.WRITE_DATA", provider?.writePermission)
        assertEquals(
            listOf("com.test.app.HighPriorityDeepLinkActivity", "com.test.app.DeepLinkActivity"),
            dataMatches.map { it.name }
        )
        assertEquals(listOf("com.test.app.MainActivity"), launchMatches.map { it.name })
        assertEquals(listOf("com.test.app.SyncService"), serviceMatches.map { it.name })
        assertEquals(listOf("com.test.app.BootReceiver"), receiverMatches.map { it.name })
        assertEquals(emptyList(), wrongCategoryMatches)
        assertEquals(listOf("com.test.app.StructuredDeepLinkActivity"), structuredMatches.map { it.name })
        assertEquals(emptyList(), wrongStructuredPort)
        assertEquals(emptyList(), malformedAuthority)
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
            applicationLabel = "Test App",
            versionCode = 42L,
            versionName = "4.2.0",
            targetSdk = 36,
            minSdk = 28,
            sourceDir = "build/tmp/base.apk",
            publicSourceDir = "build/tmp/public-base.apk",
            splitSourceDirs = listOf(
                "build/tmp/split-feature.apk",
                "build/tmp/split-config.zh.apk"
            ),
            splitPublicSourceDirs = listOf(
                "build/tmp/public-split-feature.apk",
                "build/tmp/public-split-config.zh.apk"
            ),
            splitNames = listOf("feature", "config.zh"),
            isolatedSplits = true,
            dataDir = "build/tmp/instance-1",
            nativeLibraryDir = "build/tmp/instance-1/lib",
            applicationClassName = "com.test.app.App",
            processName = "com.test.app",
            taskAffinity = "com.test.app.task",
            themeId = 42,
            metaData = mapOf("engine.snapshot" to "vpms"),
            launcherActivityName = "com.test.app.MainActivity",
            activities = listOf(
                ResolvedComponent(
                    name = "com.test.app.MainActivity",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("android.intent.action.MAIN"),
                            categories = listOf("android.intent.category.LAUNCHER")
                        )
                    ),
                    launchMode = "singleTask",
                    taskAffinity = "com.test.app.task",
                    themeId = 42
                ),
                ResolvedComponent(
                    name = "com.test.app.AliasActivity",
                    exported = true,
                    targetActivityName = "com.test.app.AliasTargetActivity",
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("com.test.app.action.ALIAS"),
                            categories = listOf("android.intent.category.DEFAULT")
                        )
                    )
                ),
                ResolvedComponent(
                    name = "com.test.app.DeepLinkActivity",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("android.intent.action.VIEW"),
                            categories = listOf("android.intent.category.DEFAULT"),
                            dataSchemes = listOf("https"),
                            dataMimeTypes = listOf("image/*"),
                            dataAuthorities = listOf("example.com"),
                            dataPaths = listOf("/deeplink"),
                            priority = 10
                        )
                    )
                ),
                ResolvedComponent(
                    name = "com.test.app.HighPriorityDeepLinkActivity",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("android.intent.action.VIEW"),
                            categories = listOf("android.intent.category.DEFAULT"),
                            dataSchemes = listOf("https"),
                            dataMimeTypes = listOf("image/png"),
                            dataAuthorities = listOf("example.com"),
                            dataPaths = listOf("/deeplink"),
                            priority = 20
                        )
                    )
                ),
                ResolvedComponent(
                    name = "com.test.app.StructuredDeepLinkActivity",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("android.intent.action.VIEW"),
                            categories = listOf("android.intent.category.DEFAULT"),
                            dataSchemes = listOf("https"),
                            dataMimeTypes = listOf("image/*"),
                            priority = 30,
                            authorityEntries = listOf(
                                ResolvedIntentAuthority("secure.example.com", 8443)
                            ),
                            pathPatterns = listOf(
                                ResolvedIntentPathPattern(
                                    "/secure",
                                    ResolvedIntentPathPatternType.PREFIX
                                )
                            )
                        )
                    )
                )
            ),
            services = listOf(
                ResolvedComponent(
                    name = "com.test.app.SyncService",
                    exported = false,
                    processName = "com.test.app:service",
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("com.test.app.action.SYNC"),
                            categories = listOf("android.intent.category.DEFAULT"),
                            dataSchemes = listOf("content")
                        )
                    )
                )
            ),
            receivers = listOf(
                ResolvedComponent(
                    name = "com.test.app.BootReceiver",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(actions = listOf("android.intent.action.BOOT_COMPLETED"))
                    )
                )
            ),
            providers = listOf(
                ResolvedComponent(
                    name = "com.test.app.DataProvider",
                    exported = true,
                    authorities = listOf("com.test.app.provider"),
                    readPermission = "com.test.app.READ_DATA",
                    writePermission = "com.test.app.WRITE_DATA",
                    grantUriPermissions = true
                )
            ),
            permissions = listOf(
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS"
            ),
            originCertSha256 = "cert-sha-256"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1",
        runtimeEpoch = 7L,
        engineSessionId = "engine-evidence-1",
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.CREATED
    )
}
