package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VirtualStorageManagerServiceProxyTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `guest cache and files paths map inside the instance data root`() {
        val route = route(File(tempDir, "instance-a"), "instance-a")

        val cache = assertIs<VirtualStoragePathResolution.Mapped>(
            route.resolveGuestAppScopedPath(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/cache/images"
            )
        )
        val files = assertIs<VirtualStoragePathResolution.Mapped>(
            route.resolveGuestAppScopedPath(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/Download"
            )
        )
        val obb = assertIs<VirtualStoragePathResolution.Mapped>(
            route.resolveGuestAppScopedPath(
                "/storage/self/primary/Android/obb/com.tencent.mobileqq/main.obb"
            )
        )

        assertEquals(
            File(route.dataRoot, "${VirtualContextStorage.EXTERNAL_CACHE_DIR}/images").canonicalFile,
            cache.target
        )
        assertEquals(
            File(route.dataRoot, "${VirtualContextStorage.EXTERNAL_FILES_DIR}/Download").canonicalFile,
            files.target
        )
        assertEquals(File(route.dataRoot, "obb/main.obb").canonicalFile, obb.target)
    }

    @Test
    fun `mkdirs creates the mapped directory without sending guest path to vold`() {
        val route = route(File(tempDir, "instance-a"), "instance-a")
        val method = StorageApi::class.java.getMethod(
            "mkdirs",
            String::class.java,
            String::class.java
        )

        val result = VirtualStorageManagerServiceProxy.interceptMkdirs(
            method,
            arrayOf("com.tencent.mobileqq", "/storage/emulated/0/Android/data/com.tencent.mobileqq/cache"),
            route
        )

        requireNotNull(result)
        assertTrue(result.success)
        assertEquals(0, result.returnValue)
        assertTrue(File(route.dataRoot, VirtualContextStorage.EXTERNAL_CACHE_DIR).isDirectory)
    }

    @Test
    fun `forged sibling package is not routed through the active instance`() {
        val result = route(File(tempDir, "instance-a"), "instance-a")
            .resolveGuestAppScopedPath(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq.sibling/cache"
            )

        assertIs<VirtualStoragePathResolution.NotGuest>(result)
    }

    @Test
    fun `path traversal is rejected before directory creation`() {
        val result = route(File(tempDir, "instance-a"), "instance-a")
            .resolveGuestAppScopedPath(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/cache/../../files"
            )

        val rejected = assertIs<VirtualStoragePathResolution.Rejected>(result)
        assertEquals("PATH_TRAVERSAL_REJECTED", rejected.reason)
        assertTrue(tempDir.walkTopDown().none { it.name == "files" })
    }

    @Test
    fun `same package instances map to different data roots`() {
        val first = assertIs<VirtualStoragePathResolution.Mapped>(
            route(File(tempDir, "instance-a"), "instance-a")
                .resolveGuestAppScopedPath(
                    "/storage/emulated/0/Android/data/com.tencent.mobileqq/cache"
                )
        )
        val second = assertIs<VirtualStoragePathResolution.Mapped>(
            route(File(tempDir, "instance-b"), "instance-b")
                .resolveGuestAppScopedPath(
                    "/storage/emulated/0/Android/data/com.tencent.mobileqq/cache"
                )
        )

        assertNotEquals(first.target, second.target)
        assertTrue(first.target.path.contains("instance-a"))
        assertTrue(second.target.path.contains("instance-b"))
    }

    private fun route(dataRoot: File, instanceId: String) = VirtualStorageManagerRoute(
        instanceId = instanceId,
        originPackageName = "com.tencent.mobileqq",
        virtualPackageName = "com.multiapp.instance.qq",
        hostPackageName = "com.multiapp.app",
        dataRoot = dataRoot.absolutePath,
        processSlot = "com.multiapp.app:v1"
    )

    private interface StorageApi {
        fun mkdirs(callingPackage: String, path: String): Int
    }
}
