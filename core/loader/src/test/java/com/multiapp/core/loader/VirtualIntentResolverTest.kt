package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VirtualIntentResolverTest {

    @Test
    fun `resolve explicit guest Activity intent`() {
        val resolver = VirtualIntentResolver(snapshot())

        val request = resolver.resolveExplicitActivity(
            packageName = "com.multiapp.instance.abc",
            className = "com.test.minimal.SecondActivity",
            sourceIntent = mockk(relaxed = true)
        )

        assertEquals("inst-001", request?.instanceId)
        assertEquals("com.test.minimal", request?.originPackageName)
        assertEquals("com.test.minimal.SecondActivity", request?.guestActivityClassName)
        assertEquals("explicit", request?.reason)
        assertEquals("singleTop", request?.launchMode)
        assertEquals("com.test.minimal.secondary:inst-001", request?.taskAffinity)
    }

    @Test
    fun `resolve launcher intent to snapshot launcher`() {
        val resolver = VirtualIntentResolver(snapshot())
        val intent = mockk<Intent> {
            every { component } returns null
            every { `package` } returns null
            every { action } returns Intent.ACTION_MAIN
            every { categories } returns setOf(Intent.CATEGORY_LAUNCHER)
            every { scheme } returns null
        }

        val request = resolver.resolveActivity(intent)

        assertEquals("com.test.minimal.MainActivity", request?.guestActivityClassName)
        assertEquals("launcher", request?.reason)
    }

    @Test
    fun `resolve launcher alias intent to target activity`() {
        val resolver = VirtualIntentResolver(aliasSnapshot())
        val intent = intent(
            action = Intent.ACTION_MAIN,
            categories = setOf(Intent.CATEGORY_LAUNCHER)
        )

        val request = resolver.resolveActivity(intent)

        assertEquals("com.test.minimal.MainActivity", request?.guestActivityClassName)
        assertEquals("launcher", request?.reason)
    }

    @Test
    fun `launcher intent does not fall back to first exported activity without launcher metadata`() {
        val resolver = VirtualIntentResolver(
            snapshot().copy(
                launcherActivityName = null,
                activities = listOf(
                    ResolvedComponent("com.test.minimal.InternalActivity", exported = false),
                    ResolvedComponent("com.test.minimal.ExportedActivity", exported = true)
                )
            )
        )
        val intent = intent(
            action = Intent.ACTION_MAIN,
            categories = setOf(Intent.CATEGORY_LAUNCHER)
        )

        assertNull(resolver.resolveActivity(intent))
    }

    @Test
    fun `resolve explicit alias activity to target activity`() {
        val resolver = VirtualIntentResolver(aliasSnapshot())

        val request = resolver.resolveExplicitActivity(
            packageName = "com.test.minimal",
            className = "com.test.minimal.launcher4",
            sourceIntent = mockk(relaxed = true)
        )

        assertEquals("com.test.minimal.MainActivity", request?.guestActivityClassName)
        assertEquals("explicit", request?.reason)
    }

    @Test
    fun `resolve implicit view intent using same filter matcher`() {
        val resolver = VirtualIntentResolver(snapshot())
        val intent = intent(
            action = Intent.ACTION_VIEW,
            categories = setOf(Intent.CATEGORY_DEFAULT),
            scheme = "http"
        )

        val request = resolver.resolveActivity(intent)

        assertEquals("com.test.minimal.ViewActivity", request?.guestActivityClassName)
        assertEquals("implicit", request?.reason)
    }

    @Test
    fun `explicit component wins over mismatched implicit filter`() {
        val resolver = VirtualIntentResolver(snapshot())
        val intent = intent(
            action = "com.test.UNRELATED",
            component = component("com.test.minimal.ViewActivity")
        )

        val request = resolver.resolveActivity(intent)

        assertEquals("com.test.minimal.ViewActivity", request?.guestActivityClassName)
        assertEquals("explicit", request?.reason)
    }

    @Test
    fun `ignore explicit Activity outside virtual package`() {
        val resolver = VirtualIntentResolver(snapshot())

        assertNull(
            resolver.resolveExplicitActivity(
                packageName = "com.android.settings",
                className = "com.android.settings.Settings",
                sourceIntent = mockk(relaxed = true)
            )
        )
    }

    private fun component(
        className: String,
        packageName: String = "com.test.minimal"
    ) = mockk<ComponentName> {
        every { this@mockk.packageName } returns packageName
        every { this@mockk.className } returns className
    }

    private fun intent(
        action: String,
        categories: Set<String> = emptySet(),
        component: ComponentName? = null,
        scheme: String? = null,
        packageName: String? = null
    ) = mockk<Intent> {
        every { this@mockk.component } returns component
        every { this@mockk.`package` } returns packageName
        every { this@mockk.action } returns action
        every { this@mockk.categories } returns categories
        every { this@mockk.scheme } returns scheme
    }

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/minimal.apk",
        dataDir = "/data/inst",
        launcherActivityName = "com.test.minimal.MainActivity",
        activities = listOf(
            ResolvedComponent(
                "com.test.minimal.MainActivity",
                exported = true,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf(Intent.ACTION_MAIN),
                        categories = listOf(Intent.CATEGORY_LAUNCHER)
                    )
                )
            ),
            ResolvedComponent(
                "com.test.minimal.SecondActivity",
                exported = false,
                launchMode = "singleTop",
                taskAffinity = "com.test.minimal.secondary"
            ),
            ResolvedComponent(
                "com.test.minimal.ViewActivity",
                exported = true,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf(Intent.ACTION_VIEW),
                        categories = listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE),
                        dataSchemes = listOf("http")
                    )
                )
            )
        )
    )

    private fun aliasSnapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/minimal.apk",
        dataDir = "/data/inst",
        launcherActivityName = "com.test.minimal.MainActivity",
        activities = listOf(
            ResolvedComponent(
                name = "com.test.minimal.launcher4",
                exported = true,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(
                        actions = listOf(Intent.ACTION_MAIN),
                        categories = listOf(Intent.CATEGORY_LAUNCHER)
                    )
                ),
                targetActivityName = "com.test.minimal.MainActivity"
            ),
            ResolvedComponent("com.test.minimal.MainActivity", exported = false)
        )
    )
}
