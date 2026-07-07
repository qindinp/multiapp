package com.multiapp.core.loader

import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualPackageResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherActivityStageTest {

    @Test
    fun `execute resolves launcher from resolved package before install record fallback`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { "java.lang.Integer" },
            clock = fixedClock(100L, 106L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(launcherActivityName = "java.lang.String")
            )
        )

        assertEquals(RuntimeStage.LAUNCHER_ACTIVITY, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(6L, output.result.durationMs)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("java.lang.String", evidence["launcherActivityClass"])
        assertEquals("VirtualPackageResolver", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute resolves launcher from package resolver before install record fallback`() {
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                assertEquals("/artifact/com.example.app.apk", apkPath)
                return resolvedPackage(launcherActivityName = "java.lang.StringBuilder")
            }
        }
        val stage = LauncherActivityStage(
            packageResolver = packageResolver,
            launcherActivityResolver = { "java.lang.Integer" },
            clock = fixedClock(200L, 207L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.StringBuilder", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("VirtualPackageResolver", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
    }

    @Test
    fun `execute falls back to install record resolver`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { "java.lang.Integer" },
            clock = fixedClock(300L, 309L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.Integer", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("InstallRecord", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
    }

    @Test
    fun `execute falls back to install record activities when resolver is empty`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(325L, 333L)
        )

        val output = stage.execute(
            stageInput(
                installRecord = installRecord(
                    activities = listOf(
                        ComponentInfo(name = "com.example.InternalActivity", exported = false),
                        ComponentInfo(name = "java.lang.String", exported = true)
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("InstallRecordFallback", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
    }

    @Test
    fun `execute skips non loadable install record candidates and uses first loadable activity`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(335L, 344L)
        )

        val output = stage.execute(
            stageInput(
                installRecord = installRecord(
                    activities = listOf(
                        ComponentInfo(name = "com.example.DoesNotExist", exported = true),
                        ComponentInfo(name = "java.lang.StringBuilder", exported = false)
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.StringBuilder", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("InstallRecordFallback", evidence["resolver"])
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("com.example.DoesNotExist"))
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("java.lang.StringBuilder"))
    }

    @Test
    fun `execute maps activity alias launcher to loadable target class`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(350L, 361L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "com.example.alias.launcher4",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.example.alias.launcher4",
                            targetActivityName = "java.lang.String"
                        )
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("java.lang.String", evidence["launcherActivityClass"])
        assertEquals("com.example.alias.launcher4", evidence["requestedLauncherActivityClass"])
        assertEquals("java.lang.String", evidence["aliasTargetActivityClass"])
        assertEquals("com.example.alias.launcher4", evidence["resolvedPackageLauncherActivityName"])
        assertEquals("", evidence["packageSnapshotLauncherActivityName"])
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("VirtualPackageResolver:com.example.alias.launcher4"))
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("VirtualPackageResolver:java.lang.String"))
        assertEquals("true", evidence["loadable"])
    }

    @Test
    fun `execute keeps fallback alias evidence while loading target activity`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(352L, 363L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = null,
                    activities = listOf(
                        ResolvedComponent(
                            name = "java.lang.Integer",
                            exported = true,
                            intentFilters = listOf(
                                "android.intent.action.MAIN",
                                "android.intent.category.LAUNCHER"
                            ),
                            targetActivityName = "java.lang.String"
                        )
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("java.lang.Integer", evidence["requestedLauncherActivityClass"])
        assertEquals("java.lang.String", evidence["aliasTargetActivityClass"])
        assertEquals("java.lang.Integer", evidence["resolvedPackageLauncherActivityName"])
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("VirtualPackageResolverFallback:java.lang.Integer"))
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("VirtualPackageResolverFallback:java.lang.String"))
    }

    @Test
    fun `execute maps package resolver alias launcher to loadable target class`() {
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage {
                return resolvedPackage(
                    launcherActivityName = "com.example.alias.Launcher",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.example.alias.Launcher",
                            targetActivityName = "java.lang.StringBuilder"
                        )
                    )
                )
            }
        }
        val stage = LauncherActivityStage(
            packageResolver = packageResolver,
            launcherActivityResolver = { null },
            clock = fixedClock(365L, 374L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.StringBuilder", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.example.alias.Launcher", evidence["requestedLauncherActivityClass"])
        assertEquals("java.lang.StringBuilder", evidence["aliasTargetActivityClass"])
        assertEquals("true", evidence["loadable"])
        assertEquals("false", evidence["preflightBypassed"])
    }

    @Test
    fun `execute records package snapshot launcher and maps snapshot alias target`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(366L, 375L)
        )

        val output = stage.execute(
            stageInput(
                packageSnapshot = packageSnapshot(
                    launcherActivityName = "com.example.alias.SnapshotLauncher",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.example.alias.SnapshotLauncher",
                            exported = true,
                            targetActivityName = "java.lang.StringBuilder"
                        )
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.StringBuilder", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("", evidence["resolvedPackageLauncherActivityName"])
        assertEquals("com.example.alias.SnapshotLauncher", evidence["packageSnapshotLauncherActivityName"])
        assertEquals("com.example.alias.SnapshotLauncher", evidence["requestedLauncherActivityClass"])
        assertEquals("java.lang.StringBuilder", evidence["aliasTargetActivityClass"])
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("PackageSnapshot:com.example.alias.SnapshotLauncher"))
        assertTrue(evidence["candidateLauncherActivities"].orEmpty().contains("PackageSnapshot:java.lang.StringBuilder"))
    }

    @Test
    fun `execute prefers explicit alias target even when alias class is loadable`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(367L, 376L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "java.lang.Integer",
                    activities = listOf(
                        ResolvedComponent(
                            name = "java.lang.Integer",
                            targetActivityName = "java.lang.String"
                        )
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("java.lang.Integer", evidence["requestedLauncherActivityClass"])
        assertEquals("java.lang.String", evidence["aliasTargetActivityClass"])
    }

    @Test
    fun `execute prefers alias target from package resolver over stale snapshot candidate`() {
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage {
                return resolvedPackage(
                    launcherActivityName = "com.example.alias.Launcher",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.example.alias.Launcher",
                            targetActivityName = "java.lang.String"
                        )
                    )
                )
            }
        }
        val stage = LauncherActivityStage(
            packageResolver = packageResolver,
            launcherActivityResolver = { null },
            clock = fixedClock(370L, 379L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "com.example.alias.Launcher",
                    activities = listOf(ResolvedComponent(name = "com.example.alias.Launcher"))
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("java.lang.String", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("java.lang.String", evidence["aliasTargetActivityClass"])
    }

    @Test
    fun `execute falls back from non loadable clone launcher to loadable sibling splash activity`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(372L, 381L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
                            exported = true,
                            intentFilters = listOf(
                                "android.intent.action.MAIN",
                                "android.intent.category.LAUNCHER"
                            )
                        )
                    )
                ),
                guestClassLoader = SelectiveClassLoader(
                    loadableClassNames = setOf("com.qq.reader.activity.launch.SplashActivity")
                )
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("com.qq.reader.activity.launch.SplashActivity", output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.qq.reader.activity.launch.SplashActivity", evidence["launcherActivityClass"])
        assertEquals(
            "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
            evidence["requestedLauncherActivityClass"]
        )
        assertEquals("VirtualPackageResolverClassNameFallback", evidence["resolver"])
        assertEquals("true", evidence["loadable"])
        assertEquals("false", evidence["preflightBypassed"])
        assertTrue(
            evidence["attemptedLauncherActivities"]
                .orEmpty()
                .contains("VirtualPackageResolverClassNameFallback:com.qq.reader.activity.launch.SplashActivity")
        )
    }

    @Test
    fun `execute skips resolved package activities when launcher metadata is missing`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(375L, 386L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = null,
                    activities = listOf(
                        ResolvedComponent(name = "com.example.Internal", exported = false),
                        ResolvedComponent(name = "java.lang.StringBuilder", exported = true)
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("NONE", evidence["resolver"])
        assertEquals("", evidence["candidateLauncherActivities"])
    }

    @Test
    fun `execute does not use first exported activity when declared launcher is not loadable`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(390L, 401L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "com.example.MissingLauncher",
                    activities = listOf(
                        ResolvedComponent(name = "com.example.MissingLauncher", exported = true),
                        ResolvedComponent(name = "java.lang.StringBuilder", exported = true)
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.example.MissingLauncher", evidence["launcherActivityClass"])
        assertFalse(evidence["attemptedLauncherActivities"].orEmpty().contains("java.lang.StringBuilder"))
    }

    @Test
    fun `execute fails non terminal and clears launcher when preflight is not loadable`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { "com.example.DoesNotExist" },
            clock = fixedClock(400L, 413L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.LAUNCHER_ACTIVITY, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals(
            "Launcher Activity preflight not loadable; no loadable fallback for com.example.DoesNotExist",
            output.result.message
        )
        assertEquals(13L, output.result.durationMs)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.example.DoesNotExist", evidence["launcherActivityClass"])
        assertEquals("false", evidence["loadable"])
        assertEquals("true", evidence["preflightBypassed"])
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute fails to alias target when target is not loadable during preflight`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(425L, 436L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "com.example.alias.Launcher",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.example.alias.Launcher",
                            targetActivityName = "com.example.shell.RealLauncher"
                        )
                    )
                )
            )
        )

        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.example.alias.Launcher", evidence["requestedLauncherActivityClass"])
        assertEquals("com.example.shell.RealLauncher", evidence["aliasTargetActivityClass"])
        assertEquals("false", evidence["loadable"])
        assertEquals("true", evidence["preflightBypassed"])
    }

    @Test
    fun `execute does not derive sibling fallback from non launcher activity components`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(450L, 461L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = null,
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.example.HiddenAliasSplashActivity",
                            exported = true
                        )
                    )
                ),
                guestClassLoader = SelectiveClassLoader(
                    loadableClassNames = setOf("com.example.SplashActivity")
                )
            )
        )

        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("NONE", evidence["resolver"])
        assertFalse(
            evidence["candidateLauncherActivities"]
                .orEmpty()
                .contains("VirtualPackageResolverClassNameFallback:com.example.SplashActivity")
        )
        assertFalse(
            evidence["attemptedLauncherActivities"]
                .orEmpty()
                .contains("VirtualPackageResolverClassNameFallback:com.example.SplashActivity")
        )
    }

    @Test
    fun `execute does not derive sibling fallback when alias target is explicit`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(475L, 486L)
        )

        val output = stage.execute(
            stageInput(
                resolvedPackage = resolvedPackage(
                    launcherActivityName = "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
                    activities = listOf(
                        ResolvedComponent(
                            name = "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
                            exported = true,
                            intentFilters = listOf(
                                "android.intent.action.MAIN",
                                "android.intent.category.LAUNCHER"
                            ),
                            targetActivityName = "com.qq.reader.activity.launch.RealShellActivity"
                        )
                    )
                ),
                guestClassLoader = SelectiveClassLoader(
                    loadableClassNames = setOf("com.qq.reader.activity.launch.SplashActivity")
                )
            )
        )

        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("com.qq.reader.activity.launch.RealShellActivity", evidence["aliasTargetActivityClass"])
        assertFalse(
            evidence["attemptedLauncherActivities"]
                .orEmpty()
                .contains("VirtualPackageResolverClassNameFallback:com.qq.reader.activity.launch.SplashActivity")
        )
    }

    @Test
    fun `execute skips non terminal when no launcher can be resolved`() {
        val stage = LauncherActivityStage(
            packageResolver = null,
            launcherActivityResolver = { null },
            clock = fixedClock(500L, 503L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.LAUNCHER_ACTIVITY, output.result.stage)
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals("No launcher Activity resolved from manifest or InstallRecord", output.result.message)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.launcherActivityClassName)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("NONE", evidence["resolver"])
        assertEquals("", evidence["resolvedPackageLauncherActivityName"])
        assertEquals("", evidence["packageSnapshotLauncherActivityName"])
        assertEquals("", evidence["aliasTargetActivityClass"])
        assertEquals("", evidence["candidateLauncherActivities"])
        assertEquals("0", evidence["installRecordActivityCount"])
        assertFalse(output.isTerminalFailure)
    }

    private fun stageInput(
        resolvedPackage: ResolvedPackage? = null,
        installRecord: InstallRecord = installRecord(),
        packageSnapshot: VirtualPackageSnapshot? = null,
        guestClassLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    ) = BootstrapStageInput(
        instanceId = "inst-001",
        installRecord = installRecord,
        originApkPath = installRecord.originApkPath,
        resolvedPackage = resolvedPackage,
        packageSnapshot = packageSnapshot,
        guestClassLoader = guestClassLoader
    )

    private fun installRecord(
        activities: List<ComponentInfo> = emptyList()
    ) = InstallRecord(
        packageName = "com.example.app",
        originApkPath = "/artifact/com.example.app.apk",
        originApkSha256 = "sha256",
        originCertSha256 = "cert-sha256",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        activities = activities,
        installTimeMs = 500L
    )

    private fun resolvedPackage(
        launcherActivityName: String?,
        activities: List<ResolvedComponent> = emptyList()
    ) = ResolvedPackage(
        packageName = "com.example.app",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        launcherActivityName = launcherActivityName,
        activities = activities
    )

    private fun packageSnapshot(
        launcherActivityName: String?,
        activities: List<ResolvedComponent> = emptyList()
    ) = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "Example",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        sourceDir = "/artifact/com.example.app.apk",
        dataDir = "/data/user/0/com.multiapp.app/files/instances/inst-001",
        launcherActivityName = launcherActivityName,
        activities = activities
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }

    private class SelectiveClassLoader(
        private val loadableClassNames: Set<String>
    ) : ClassLoader(null) {
        override fun loadClass(name: String): Class<*> {
            if (name in loadableClassNames) return String::class.java
            throw ClassNotFoundException(name)
        }
    }
}
