package com.multiapp.core.instance

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ImportResult
import com.multiapp.core.model.installer.VirtualInstallService
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CloneCreateUseCaseTest {

    private lateinit var instanceManager: InstanceManager
    private lateinit var installService: VirtualInstallService
    private var now = 1000L

    @BeforeEach
    fun setUp() {
        instanceManager = mockk(relaxed = true)
        installService = mockk(relaxed = true)
        every { instanceManager.listInstances() } returns emptyList()
        every { installService.hasInstallRecord(any()) } returns true
        every { installService.ensureInstallRecord(any()) } returns Result.success(mockk<ImportResult>())
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `create assigns numbered display name for duplicate origin`() {
        val app = testApp()
        val existing = testRecord("old", app.packageName, app.appName)
        val created = testRecord("new", app.packageName, "${app.appName} 2")
        every { instanceManager.listInstances() } returns listOf(existing)
        every {
            instanceManager.createInstance(app.packageName, "${app.appName} 2", CompatibilityMode.DEFAULT)
        } returns Result.success(created)

        val result = useCase().create(app).getOrThrow()

        assertEquals(created, result.instance)
        verify {
            instanceManager.createInstance(app.packageName, "${app.appName} 2", CompatibilityMode.DEFAULT)
        }
    }

    @Test
    fun `create trims custom display name`() {
        val app = testApp()
        val created = testRecord("new", app.packageName, "Work")
        every {
            instanceManager.createInstance(app.packageName, "Work", CompatibilityMode.DEFAULT)
        } returns Result.success(created)

        useCase().create(app, "  Work  ").getOrThrow()

        verify {
            instanceManager.createInstance(app.packageName, "Work", CompatibilityMode.DEFAULT)
        }
    }

    @Test
    fun `create rolls back new install record when instance creation fails`() {
        val app = testApp()
        every { installService.hasInstallRecord(app.packageName) } returns false
        every {
            instanceManager.createInstance(app.packageName, app.appName, CompatibilityMode.DEFAULT)
        } returns Result.failure(IllegalStateException("create failed"))
        every { installService.deleteInstallRecord(app.packageName) } returns true

        val result = useCase().create(app)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as CloneCreateFailureException
        assertEquals("create_failed", error.failureCode)
        assertEquals("install_deleted", error.cleanupStatus)
        verify { installService.deleteInstallRecord(app.packageName) }
    }

    @Test
    fun `create keeps existing install record on failure`() {
        val app = testApp()
        every { installService.hasInstallRecord(app.packageName) } returns true
        every {
            instanceManager.createInstance(app.packageName, app.appName, CompatibilityMode.DEFAULT)
        } returns Result.failure(IllegalStateException("create failed"))

        val result = useCase().create(app)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as CloneCreateFailureException
        assertEquals("not_required", error.cleanupStatus)
        verify(exactly = 0) { installService.deleteInstallRecord(app.packageName) }
    }

    @Test
    fun `create reports latency from clock`() {
        val app = testApp()
        val created = testRecord("new", app.packageName, app.appName)
        every {
            instanceManager.createInstance(app.packageName, app.appName, CompatibilityMode.DEFAULT)
        } answers {
            now = 1075L
            Result.success(created)
        }

        val result = useCase().create(app).getOrThrow()

        assertEquals(75L, result.createLatencyMs)
    }

    private fun useCase(): CloneCreateUseCase {
        return CloneCreateUseCase(
            instanceManager = instanceManager,
            virtualInstallService = installService,
            clock = { now }
        )
    }

    private fun testApp(): VirtualApp = VirtualApp(
        packageName = "com.example.app",
        appName = "Example",
        versionName = "1.0",
        versionCode = 1L,
        apkPath = "/tmp/example.apk",
        instanceId = "",
        mainActivity = "com.example.app.MainActivity"
    )

    private fun testRecord(
        instanceId: String,
        originPackageName: String,
        displayName: String
    ): VirtualInstanceRecord = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = "com.multiapp.instance.$instanceId",
        displayName = displayName,
        dataRoot = "/tmp/$instanceId",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )
}
