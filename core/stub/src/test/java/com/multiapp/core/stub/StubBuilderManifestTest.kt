package com.multiapp.core.stub

import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.StubConfig
import com.multiapp.core.manifest.DeviceIdentityConfig
import com.multiapp.core.model.CloneProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * StubBuilder rewriteManifest 测试
 *
 * 验证 S1-3 修复：
 * - rewriteManifest() 抛出 UnsupportedOperationException
 * - 异常消息包含正确的说明
 * - 方法现在是直接 throw 而非始终返回失败
 */
class StubBuilderManifestTest {

    private fun createTestConfig(): StubConfig {
        return StubConfig(
            instanceId = "test_manifest",
            stubPackageName = "com.test.stub",
            originalPackageName = "com.test.app",
            appLabel = "TestApp",
            launchActivity = "com.test.app.MainActivity",
            authorityMap = emptyMap(),
            originalSignatures = listOf("/fake/path.apk"),
            cloneProfile = CloneProfile.NORMAL,
            deviceIdentity = DeviceIdentityConfig(
                imei = "", androidId = "", macAddress = "", serial = "",
                buildModel = "", buildManufacturer = "", buildFingerprint = "",
                buildBrand = "", buildDevice = "", buildProduct = "",
                versionRelease = "", sdkInt = 34
            )
        )
    }

    /**
     * 通过反射调用 private rewriteManifest 方法
     */
    private fun callRewriteManifest(
        originApk: File,
        config: StubConfig,
        manifest: ManifestParser.ParsedManifest
    ): ByteArray {
        val builder = StubBuilder()
        val method = StubBuilder::class.java.getDeclaredMethod(
            "rewriteManifest",
            File::class.java,
            StubConfig::class.java,
            ManifestParser.ParsedManifest::class.java
        )
        method.isAccessible = true
        return method.invoke(builder, originApk, config, manifest) as ByteArray
    }

    @Test
    fun `rewriteManifest throws UnsupportedOperationException`() {
        val builder = StubBuilder()
        val config = createTestConfig()
        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.test.app",
            applicationClass = null,
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList(),
            permissions = emptyList(),
            minSdkVersion = 21,
            targetSdkVersion = 34
        )

        val exception = assertThrows(UnsupportedOperationException::class.java) {
            callRewriteManifest(File("/fake/origin.apk"), config, manifest)
        }

        assertNotNull(exception)
    }

    @Test
    fun `rewriteManifest exception message mentions ManifestRewriter disabled`() {
        val builder = StubBuilder()
        val config = createTestConfig()
        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.test.app",
            applicationClass = null,
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList(),
            permissions = emptyList(),
            minSdkVersion = 21,
            targetSdkVersion = 34
        )

        val exception = assertThrows(UnsupportedOperationException::class.java) {
            callRewriteManifest(File("/fake/origin.apk"), config, manifest)
        }

        val message = exception.message ?: ""
        assertTrue(message.contains("ManifestRewriter disabled")) {
            "Exception message should mention 'ManifestRewriter disabled', got: $message"
        }
    }

    @Test
    fun `rewriteManifest exception message mentions INSTALL_FAILED_DUPLICATE_PERMISSION`() {
        val builder = StubBuilder()
        val config = createTestConfig()
        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.test.app",
            applicationClass = null,
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList(),
            permissions = emptyList(),
            minSdkVersion = 21,
            targetSdkVersion = 34
        )

        val exception = assertThrows(UnsupportedOperationException::class.java) {
            callRewriteManifest(File("/fake/origin.apk"), config, manifest)
        }

        val message = exception.message ?: ""
        assertTrue(message.contains("INSTALL_FAILED_DUPLICATE_PERMISSION")) {
            "Exception message should mention 'INSTALL_FAILED_DUPLICATE_PERMISSION', got: $message"
        }
    }

    @Test
    fun `rewriteManifest exception message mentions BinaryXmlEncoder fallback`() {
        val builder = StubBuilder()
        val config = createTestConfig()
        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.test.app",
            applicationClass = null,
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList(),
            permissions = emptyList(),
            minSdkVersion = 21,
            targetSdkVersion = 34
        )

        val exception = assertThrows(UnsupportedOperationException::class.java) {
            callRewriteManifest(File("/fake/origin.apk"), config, manifest)
        }

        val message = exception.message ?: ""
        assertTrue(message.contains("BinaryXmlEncoder")) {
            "Exception message should mention 'BinaryXmlEncoder', got: $message"
        }
    }

    @Test
    fun `rewriteManifest is now a direct throw, not a try-catch fallback`() {
        // S1-3 修复：rewriteManifest 现在直接 throw
        // 而非返回错误或执行 catch 回退
        // 验证方法不会在内部捕获异常并吞掉
        val builder = StubBuilder()
        val config = createTestConfig()
        val manifest = ManifestParser.ParsedManifest(
            packageName = "com.test.app",
            applicationClass = null,
            activities = emptyList(),
            services = emptyList(),
            receivers = emptyList(),
            providers = emptyList(),
            permissions = emptyList(),
            minSdkVersion = 21,
            targetSdkVersion = 34
        )

        // 调用应直接抛出异常（不经过 try-catch 降级）
        assertThrows(UnsupportedOperationException::class.java) {
            callRewriteManifest(File("/fake/path.apk"), config, manifest)
        }
    }
}
