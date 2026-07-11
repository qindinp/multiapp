package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameworkApplicationContextCompatTest {

    @Test
    fun `patchTarget preserves guest LoadedApk while rewriting binder caller identity`() {
        val target = FakeContextImpl()

        val result = FrameworkApplicationContextCompat.patchTarget(
            target = target,
            hostPackageName = "com.multiapp.app"
        )

        assertEquals("com.multiapp.app", target.mBasePackageName)
        assertEquals("com.multiapp.app", target.mOpPackageName)
        assertNull(target.mPackageManager)
        assertEquals("com.multiapp.app", target.mContentResolver.mPackageName)
        assertEquals("li.songe.gkd", target.mPackageInfo.packageName)
        assertTrue(result.binderIdentityReady)
        assertTrue(result.skippedFieldReasons.isEmpty())
    }

    @Test
    fun `patchTarget reports unsupported fields without inventing success`() {
        val result = FrameworkApplicationContextCompat.patchTarget(
            target = Any(),
            hostPackageName = "com.multiapp.app"
        )

        assertTrue(result.patchedFields.isEmpty())
        assertTrue(result.skippedFieldReasons.any { it.contains("mBasePackageName:FIELD_NOT_FOUND") })
        assertTrue(
            result.skippedFieldReasons.any {
                it == "ContentResolver.mPackageName:RESOLVER_UNAVAILABLE"
            }
        )
        assertTrue(!result.binderIdentityReady)
    }

    private class FakeContextImpl {
        @JvmField
        var mBasePackageName: String = "li.songe.gkd"

        @JvmField
        var mOpPackageName: String = "li.songe.gkd"

        @JvmField
        var mPackageManager: Any? = Any()

        @JvmField
        val mContentResolver = FakeContentResolver()

        @JvmField
        val mPackageInfo = FakeLoadedApk()
    }

    private class FakeContentResolver {
        @JvmField
        var mPackageName: String = "li.songe.gkd"
    }

    private class FakeLoadedApk {
        val packageName: String = "li.songe.gkd"
    }
}
