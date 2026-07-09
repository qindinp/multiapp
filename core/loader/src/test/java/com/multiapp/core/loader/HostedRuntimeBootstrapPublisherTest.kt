package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class HostedRuntimeBootstrapPublisherTest {

    @Test
    fun `attachAndLaunch publishes runtime through injected publisher`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B))
        }
        val mockContext: Context = mockk(relaxed = true)
        every { mockContext.packageName } returns "com.multiapp.app"
        val published = mutableListOf<Pair<String, HostedBootstrapResult>>()
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord())
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = mockContext,
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            applicationClassNameResolver = { _, _ -> null },
            guestApplicationCreator = jvmApplicationCreator(),
            packageManagerProxyInstaller = successfulPackageManagerProxyInstaller(),
            runtimeUidProvider = { 42420 },
            runtimePublisher = { instanceId, result ->
                published += instanceId to result
            }
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        assertNotNull(result.guestApplication)
        assertEquals(1, published.size)
        val (publishedInstanceId, publishedResult) = published.single()
        assertEquals("inst-001", publishedInstanceId)
        assertEquals("inst-001", publishedResult.instanceId)
        assertEquals("com.example.app", publishedResult.originPackageName)
        assertNotNull(publishedResult.guestApplication)
    }

    private fun instanceRecord(
        instanceId: String = "inst-001",
        originPackageName: String = "com.example.app"
    ) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = "/data/instances/$instanceId",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun installRecord(
        packageName: String = "com.example.app",
        originApkPath: String
    ) = InstallRecord(
        packageName = packageName,
        originApkPath = originApkPath,
        originApkSha256 = "abc123",
        originCertSha256 = "def456",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        installTimeMs = 500L
    )

    private fun successfulPackageManagerProxyInstaller() =
        VirtualPackageManagerGlobalInstallAction { _, snapshot, runtimeUid ->
            VirtualPackageManagerGlobalInstallResult(
                status = VirtualPackageManagerGlobalInstallStatus.INSTALLED,
                instanceId = snapshot.instanceId,
                originPackageName = snapshot.originPackageName,
                virtualPackageName = snapshot.virtualPackageName,
                runtimeUid = runtimeUid,
                sPackageManagerRead = true,
                sPackageManagerPatched = true,
                ipackageManagerInterface = "fake.IPackageManager",
                originalPackageManagerClass = "fake.Pms",
                proxyClass = "fake.Proxy",
                applicationPackageManagerPatchResults = listOf(
                    ActivityThreadPackageManagerPatchResult("hostContext.packageManager", patched = true)
                )
            )
        }

    private fun jvmApplicationCreator() = GuestApplicationCreator { request ->
        GuestApplicationCreateResult(
            application = FakeApplication(),
            attachedContextPackageName = request.virtualContextConfig.originPackageName,
            evidence = listOf(BootstrapEvidence("applicationCreator", "TEST_DEFAULT_APPLICATION"))
        )
    }

    private class FakeApplication : Application() {
        override fun attachBaseContext(base: Context?) = Unit
        override fun onCreate() = Unit
    }

    private class FakeInstanceManager(
        private val records: Map<String, VirtualInstanceRecord>
    ) : InstanceManager {
        override fun createInstance(
            originPackageName: String,
            displayName: String,
            compatibilityMode: CompatibilityMode
        ): Result<VirtualInstanceRecord> = Result.failure(UnsupportedOperationException())

        override fun getInstance(instanceId: String): VirtualInstanceRecord? = records[instanceId]
        override fun getInstanceByOrigin(originPackageName: String): List<VirtualInstanceRecord> =
            records.values.filter { it.originPackageName == originPackageName }

        override fun listInstances(): List<VirtualInstanceRecord> = records.values.toList()
        override fun deleteInstance(instanceId: String): Boolean = false
        override fun updateLaunchState(instanceId: String): VirtualInstanceRecord? = null
        override fun getDataRoot(instanceId: String) = null
    }

    private class FakeInstallRecordStore(
        private val records: Map<String, InstallRecord>
    ) : InstallRecordStore {
        override fun save(record: InstallRecord): Result<String> =
            Result.failure(UnsupportedOperationException())

        override fun load(packageName: String): InstallRecord? = records[packageName]
        override fun listAll(): List<InstallRecord> = records.values.toList()
        override fun delete(packageName: String): Boolean = false
    }
}
