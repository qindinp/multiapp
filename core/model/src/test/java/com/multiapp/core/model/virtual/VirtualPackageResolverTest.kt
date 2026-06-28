package com.multiapp.core.model.virtual

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualPackageResolverTest {

    @Test
    fun `ResolvedComponent data class default values`() {
        val component = ResolvedComponent(name = "com.example.MainActivity")

        assertEquals("com.example.MainActivity", component.name)
        assertFalse(component.exported)
        assertTrue(component.intentFilters.isEmpty())
        assertTrue(component.resolvedIntentFilters.isEmpty())
        assertNull(component.launchMode)
        assertNull(component.processName)
        assertNull(component.taskAffinity)
        assertEquals(0, component.themeId)
        assertNull(component.screenOrientation)
        assertNull(component.configChanges)
        assertNull(component.permission)
        assertTrue(component.metaData.isEmpty())
    }

    @Test
    fun `ResolvedComponent data class with custom values`() {
        val component = ResolvedComponent(
            name = "com.example.MainActivity",
            exported = true,
            intentFilters = listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER"),
            resolvedIntentFilters = listOf(
                ResolvedIntentFilter(
                    actions = listOf("android.intent.action.MAIN"),
                    categories = listOf("android.intent.category.LAUNCHER"),
                    dataSchemes = listOf("http")
                )
            ),
            launchMode = "singleTop",
            processName = ":remote",
            taskAffinity = "com.example.task",
            themeId = 0x7f010001,
            screenOrientation = "portrait",
            configChanges = "orientation|screenSize",
            permission = "com.example.permission.START",
            metaData = mapOf("feature" to "enabled")
        )

        assertEquals("com.example.MainActivity", component.name)
        assertTrue(component.exported)
        assertEquals(2, component.intentFilters.size)
        assertEquals("android.intent.action.MAIN", component.intentFilters[0])
        assertEquals("http", component.resolvedIntentFilters.single().dataSchemes.single())
        assertEquals("singleTop", component.launchMode)
        assertEquals(":remote", component.processName)
        assertEquals("com.example.task", component.taskAffinity)
        assertEquals(0x7f010001, component.themeId)
        assertEquals("portrait", component.screenOrientation)
        assertEquals("orientation|screenSize", component.configChanges)
        assertEquals("com.example.permission.START", component.permission)
        assertEquals("enabled", component.metaData["feature"])
    }

    @Test
    fun `ResolvedComponent immutable copy creates new instance`() {
        val original = ResolvedComponent(name = "com.example.Service")
        val copied = original.copy(exported = true)

        assertFalse(original.exported)
        assertTrue(copied.exported)
        assertEquals(original.name, copied.name)
    }

    @Test
    fun `ResolvedComponent equality works correctly`() {
        val comp1 = ResolvedComponent(
            name = "com.example.Activity",
            exported = true,
            intentFilters = listOf("ACTION")
        )
        val comp2 = ResolvedComponent(
            name = "com.example.Activity",
            exported = true,
            intentFilters = listOf("ACTION")
        )

        assertEquals(comp1, comp2)
        assertEquals(comp1.hashCode(), comp2.hashCode())
    }

    @Test
    fun `ResolvedPackage data class default values`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            targetSdk = 34,
            minSdk = 21
        )

        assertEquals("com.example.app", pkg.packageName)
        assertEquals(100L, pkg.versionCode)
        assertEquals("1.0.0", pkg.versionName)
        assertEquals(34, pkg.targetSdk)
        assertEquals(21, pkg.minSdk)
        assertNull(pkg.applicationClassName)
        assertNull(pkg.processName)
        assertNull(pkg.taskAffinity)
        assertEquals(0, pkg.themeId)
        assertTrue(pkg.metaData.isEmpty())
        assertNull(pkg.launcherActivityName)
        assertTrue(pkg.activities.isEmpty())
        assertTrue(pkg.services.isEmpty())
        assertTrue(pkg.receivers.isEmpty())
        assertTrue(pkg.providers.isEmpty())
        assertTrue(pkg.permissions.isEmpty())
        assertNull(pkg.nativeLibDir)
    }

    @Test
    fun `ResolvedPackage data class with all values`() {
        val activities = listOf(
            ResolvedComponent(
                name = "com.example.MainActivity",
                exported = true,
                intentFilters = listOf("android.intent.action.MAIN")
            ),
            ResolvedComponent(name = "com.example.SecondActivity")
        )
        val services = listOf(
            ResolvedComponent(name = "com.example.MyService", exported = false)
        )
        val receivers = listOf(
            ResolvedComponent(name = "com.example.BootReceiver", exported = true)
        )
        val providers = listOf(
            ResolvedComponent(name = "com.example.MyProvider", exported = true)
        )

        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            targetSdk = 34,
            minSdk = 21,
            applicationClassName = "com.example.MyApplication",
            processName = "com.example.app:remote",
            taskAffinity = "com.example.task",
            themeId = 0x7f010002,
            metaData = mapOf("appMeta" to "value"),
            launcherActivityName = "com.example.MainActivity",
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = listOf("android.permission.INTERNET", "android.permission.CAMERA"),
            nativeLibDir = "/data/app/com.example.app/lib/arm64"
        )

        assertEquals("com.example.app", pkg.packageName)
        assertEquals("com.example.MyApplication", pkg.applicationClassName)
        assertEquals("com.example.app:remote", pkg.processName)
        assertEquals("com.example.task", pkg.taskAffinity)
        assertEquals(0x7f010002, pkg.themeId)
        assertEquals("value", pkg.metaData["appMeta"])
        assertEquals("com.example.MainActivity", pkg.launcherActivityName)
        assertEquals(2, pkg.activities.size)
        assertEquals(1, pkg.services.size)
        assertEquals(1, pkg.receivers.size)
        assertEquals(1, pkg.providers.size)
        assertEquals(2, pkg.permissions.size)
        assertEquals("/data/app/com.example.app/lib/arm64", pkg.nativeLibDir)
    }

    @Test
    fun `ResolvedPackage immutable copy creates new instance`() {
        val original = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            targetSdk = 34,
            minSdk = 21
        )
        val copied = original.copy(versionCode = 200, versionName = "2.0.0")

        assertEquals(100L, original.versionCode)
        assertEquals("1.0.0", original.versionName)
        assertEquals(200L, copied.versionCode)
        assertEquals("2.0.0", copied.versionName)
        assertEquals(original.packageName, copied.packageName)
    }

    @Test
    fun `ResolvedPackage equality works correctly`() {
        val pkg1 = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            targetSdk = 34,
            minSdk = 21
        )
        val pkg2 = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            targetSdk = 34,
            minSdk = 21
        )

        assertEquals(pkg1, pkg2)
        assertEquals(pkg1.hashCode(), pkg2.hashCode())
    }

    @Test
    fun `VirtualPackageResolver interface exists and can be implemented`() {
        val resolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                return if (apkPath.endsWith(".apk")) {
                    ResolvedPackage(
                        packageName = "com.example.mock",
                        versionCode = 1,
                        versionName = "1.0",
                        targetSdk = 34,
                        minSdk = 21
                    )
                } else {
                    null
                }
            }
        }

        assertNotNull(resolver)
        assertNotNull(resolver.resolve("/path/to/app.apk"))
        assertNull(resolver.resolve("/path/to/file.txt"))
    }

    @Test
    fun `VirtualPackageResolver resolve returns null for non-existent path`() {
        val resolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? = null
        }

        assertNull(resolver.resolve("/non/existent/path.apk"))
    }

    @Test
    fun `ResolvedPackage destructuring works`() {
        val pkg = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 100,
            versionName = "1.0.0",
            targetSdk = 34,
            minSdk = 21
        )

        val (packageName, versionCode, versionName, targetSdk, minSdk) = pkg

        assertEquals("com.example.app", packageName)
        assertEquals(100L, versionCode)
        assertEquals("1.0.0", versionName)
        assertEquals(34, targetSdk)
        assertEquals(21, minSdk)
    }
}
