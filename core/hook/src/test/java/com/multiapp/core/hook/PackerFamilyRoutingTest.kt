package com.multiapp.core.hook

import com.multiapp.core.hook.antidetection.PackerFamily
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase1 家族路由测试：
 * - [JiaguRuntime] 只声明支持 [PackerFamily.QIHOO_360]；
 * - [PackerRuntimeDispatcher] 对已知家族优先按 supportsFamily 路由到专用运行时；
 * - 家族未知时回退传统 detect() 顺序遍历。
 */
class PackerFamilyRoutingTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createZip(vararg entries: String): Path {
        val zipPath = tempDir.resolve("route_${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
            for (name in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.closeEntry()
            }
        }
        return zipPath
    }

    @Test
    fun `JiaguRuntime supports only QIHOO_360 family`() {
        val jiagu = JiaguRuntime()
        assertTrue(jiagu.supportsFamily(PackerFamily.QIHOO_360))
        assertFalse(jiagu.supportsFamily(PackerFamily.TENCENT_JIAGU))
        assertFalse(jiagu.supportsFamily(PackerFamily.IJIMAI))
        assertFalse(jiagu.supportsFamily(PackerFamily.BANGCLE))
        assertFalse(jiagu.supportsFamily(PackerFamily.ALIBABA))
        assertFalse(jiagu.supportsFamily(PackerFamily.OTHER))
        assertFalse(jiagu.supportsFamily(PackerFamily.UNKNOWN))
    }

    @Test
    fun `generic runtime does not declare family support`() {
        val generic = GenericPackerRuntime()
        assertFalse(generic.supportsFamily(PackerFamily.QIHOO_360))
        assertFalse(generic.supportsFamily(PackerFamily.UNKNOWN))
    }

    @Test
    fun `dispatcher family-routes QIHOO_360 apk to JiaguRuntime`() {
        val apkPath = createZip("lib/arm64-v8a/libjiagu.so").toString()
        val dispatcher = PackerRuntimeDispatcher.withRuntimesForTest(
            listOf(JiaguRuntime(), GenericPackerRuntime())
        )
        val runtime = dispatcher.detect(originLibDir = null, originApkPath = apkPath)
        assertNotNull(runtime)
        assertEquals("Jiagu360", runtime.name)
    }

    @Test
    fun `dispatcher family-routes slib variant apk to JiaguRuntime`() {
        // 微博 libslib.so -> 360 家族 -> 优先 JiaguRuntime，而非 Generic 兜底
        val apkPath = createZip("lib/arm64-v8a/libslib.so").toString()
        val dispatcher = PackerRuntimeDispatcher.withRuntimesForTest(
            listOf(JiaguRuntime(), GenericPackerRuntime())
        )
        val runtime = dispatcher.detect(originLibDir = null, originApkPath = apkPath)
        assertNotNull(runtime)
        assertEquals("Jiagu360", runtime.name)
    }

    @Test
    fun `dispatcher falls back to detect loop when family unknown`() {
        val apkPath = createZip("assets/config.json").toString()
        val dispatcher = PackerRuntimeDispatcher.withRuntimesForTest(
            listOf(JiaguRuntime(), GenericPackerRuntime())
        )
        // 无任何加固特征：家族 UNKNOWN，两个 runtime 的 detect() 均返回 false -> null
        assertNull(dispatcher.detect(originLibDir = null, originApkPath = apkPath))
    }
}
