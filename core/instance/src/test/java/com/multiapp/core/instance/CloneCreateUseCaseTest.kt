package com.multiapp.core.instance

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CloneCreateUseCaseTest {

    private lateinit var virtualizationEngine: VirtualizationEngine
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        virtualizationEngine = mockk()
        every { virtualizationEngine.listInstances() } returns emptyList()
        every { virtualizationEngine.createInstance(any<CreateInstanceRequest>()) } returns EngineResult.pass(
            operation = "createInstance",
            instanceId = CREATED_INSTANCE_ID,
            originPackageName = "com.example.app"
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `create assigns numbered display name through engine request`() {
        val app = testApp()
        every { virtualizationEngine.listInstances() } returns listOf(testRecord("old", app.packageName, app.appName))

        val useCase = useCase()
        val result = useCase.create(app, useCase.prepareAttempt(app)).getOrThrow()

        assertEquals(CREATED_INSTANCE_ID, result.instanceId)
        verify {
            virtualizationEngine.createInstance(
                match<CreateInstanceRequest> {
                    it.displayName == "${app.appName} 2" && it.originPackageName == app.packageName
                }
            )
        }
    }

    @Test
    fun `create trims custom display name and supplies stable request id`() {
        val app = testApp()

        val useCase = useCase()
        val attempt = useCase.prepareAttempt(app, "  Work  ")

        useCase.create(app, attempt).getOrThrow()

        verify {
            virtualizationEngine.createInstance(
                match<CreateInstanceRequest> {
                    it.creationRequestId == CREATION_REQUEST_ID && it.displayName == "Work"
                }
            )
        }
    }

    @Test
    fun `create carries package and split metadata to engine authority`() {
        val app = testApp().copy(
            requestedPermissions = listOf("android.permission.CAMERA"),
            activities = listOf("com.example.app.MainActivity"),
            services = listOf("com.example.app.SyncService"),
            receivers = listOf("com.example.app.BootReceiver"),
            providers = listOf("com.example.app.DataProvider"),
            nativeAbis = listOf("arm64-v8a"),
            splitApkPaths = listOf("/tmp/config.arm64.apk"),
            splitPublicSourceDirs = listOf("/tmp/config.arm64.apk"),
            splitNames = listOf("config.arm64"),
            isolatedSplits = true,
            applicationClassName = "com.example.app.App"
        )

        val useCase = useCase()
        useCase.create(app, useCase.prepareAttempt(app)).getOrThrow()

        verify {
            virtualizationEngine.createInstance(
                match<CreateInstanceRequest> { request ->
                    request.install.originApkPath == app.apkPath &&
                        request.install.requestedPermissions == app.requestedPermissions &&
                        request.install.activityClassNames == app.activities &&
                        request.install.serviceClassNames == app.services &&
                        request.install.receiverClassNames == app.receivers &&
                        request.install.providerClassNames == app.providers &&
                        request.install.nativeAbis == app.nativeAbis &&
                        request.install.splitApkPaths == app.splitApkPaths &&
                        request.install.isolatedSplits
                }
            )
        }
    }

    @Test
    fun `create reports engine authority failure without local mutation fallback`() {
        every { virtualizationEngine.createInstance(any<CreateInstanceRequest>()) } returns EngineResult.fail(
            operation = "createInstance",
            originPackageName = "com.example.app",
            message = "engine authority unavailable"
        )

        val useCase = useCase()
        val app = testApp()
        val result = useCase.create(app, useCase.prepareAttempt(app))

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as CloneCreateFailureException
        assertEquals("create_failed", error.failureCode)
        assertEquals("engine_owned", error.cleanupStatus)
    }

    @Test
    fun `create reports latency from clock`() {
        every { virtualizationEngine.createInstance(any<CreateInstanceRequest>()) } answers {
            now = 1_075L
            EngineResult.pass("createInstance", instanceId = CREATED_INSTANCE_ID)
        }

        val useCase = useCase()
        val app = testApp()
        val result = useCase.create(app, useCase.prepareAttempt(app)).getOrThrow()

        assertEquals(75L, result.createLatencyMs)
    }

    @Test
    fun `post-commit clock failure does not delete engine-created instance`() {
        var clockCalls = 0
        val useCase = CloneCreateUseCase(
            virtualizationEngine = virtualizationEngine,
            clock = {
                clockCalls += 1
                if (clockCalls == 1) now else error("clock failed")
            },
            creationRequestIdFactory = { CREATION_REQUEST_ID }
        )

        val app = testApp()
        val result = useCase.create(app, useCase.prepareAttempt(app)).getOrThrow()

        assertEquals(0L, result.createLatencyMs)
        verify(exactly = 0) { virtualizationEngine.deleteInstance(any()) }
    }

    @Test
    fun `unknown engine result reuses creation request id for the same payload`() {
        var factoryCalls = 0
        val useCase = useCase {
            factoryCalls += 1
            "create-request-$factoryCalls"
        }
        every { virtualizationEngine.createInstance(any<CreateInstanceRequest>()) } returnsMany listOf(
            EngineResult.fail(
                operation = "createInstance",
                message = "engine_authority_unavailable_or_unknown_result"
            ),
            EngineResult.pass(operation = "createInstance", instanceId = CREATED_INSTANCE_ID)
        )
        val app = testApp()
        val firstAttempt = useCase.prepareAttempt(app, "Work")

        val firstResult = useCase.create(app, firstAttempt)
        val retryAttempt = useCase.prepareAttempt(app, " Work ", firstAttempt)
        val retryResult = useCase.create(app, retryAttempt)

        assertTrue(firstResult.isFailure)
        assertTrue(
            (firstResult.exceptionOrNull() as CloneCreateFailureException)
                .shouldRetainCreationRequestId
        )
        assertEquals(firstAttempt, retryAttempt)
        assertEquals(1, factoryCalls)
        assertEquals(CREATED_INSTANCE_ID, retryResult.getOrThrow().instanceId)
        verify(exactly = 2) {
            virtualizationEngine.createInstance(
                match<CreateInstanceRequest> { it.creationRequestId == firstAttempt.creationRequestId }
            )
        }
    }

    @Test
    fun `deterministic engine conflict and request validation do not retain request id`() {
        val useCase = useCase()
        val app = testApp()
        val conflictAttempt = useCase.prepareAttempt(app, "Work")
        every { virtualizationEngine.createInstance(any<CreateInstanceRequest>()) } returns EngineResult.fail(
            operation = "createInstance",
            message = "creation_request_id_conflict"
        )

        val conflict = useCase.create(app, conflictAttempt).exceptionOrNull() as CloneCreateFailureException
        val invalidAttempt = CloneCreateAttempt(
            creationRequestId = CREATION_REQUEST_ID,
            payloadFingerprint = "invalid-display-test",
            displayName = "x".repeat(257)
        )
        val validation = useCase.create(app, invalidAttempt).exceptionOrNull() as CloneCreateFailureException

        assertFalse(conflict.shouldRetainCreationRequestId)
        assertFalse(validation.shouldRetainCreationRequestId)
    }

    @Test
    fun `different package display version and split payloads do not reuse pending attempt`() {
        var requestNumber = 0
        val useCase = useCase { "create-request-${++requestNumber}" }
        val app = testApp()
        val pending = useCase.prepareAttempt(app)

        val changedAttempts = listOf(
            useCase.prepareAttempt(app.copy(packageName = "com.example.other"), pendingAttempt = pending),
            useCase.prepareAttempt(app, displayName = "Work", pendingAttempt = pending),
            useCase.prepareAttempt(
                app.copy(versionCode = 2L, versionName = "2.0"),
                pendingAttempt = pending
            ),
            useCase.prepareAttempt(
                app.copy(
                    splitApkPaths = listOf("/tmp/config.arm64.apk"),
                    splitPublicSourceDirs = listOf("/tmp/config.arm64.apk"),
                    splitNames = listOf("config.arm64")
                ),
                pendingAttempt = pending
            )
        )

        changedAttempts.forEach { changed ->
            assertNotEquals(pending.creationRequestId, changed.creationRequestId)
            assertNotEquals(pending.payloadFingerprint, changed.payloadFingerprint)
        }
        assertEquals(5, requestNumber)
    }

    @Test
    fun `transport exception does not fall back to local mutation and retains request id for retry`() {
        every { virtualizationEngine.createInstance(any<CreateInstanceRequest>()) } throws
            IllegalStateException("binder died")
        val useCase = useCase()
        val app = testApp()
        val attempt = useCase.prepareAttempt(app, "Work")

        val result = useCase.create(app, attempt)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as CloneCreateFailureException
        assertTrue(error.shouldRetainCreationRequestId)
        assertEquals("create_failed", error.failureCode)
        assertEquals("engine_owned", error.cleanupStatus)
        assertEquals("binder died", error.cause?.message)
    }

    private fun useCase(
        creationRequestIdFactory: () -> String = { CREATION_REQUEST_ID }
    ): CloneCreateUseCase = CloneCreateUseCase(
        virtualizationEngine = virtualizationEngine,
        clock = { now },
        creationRequestIdFactory = creationRequestIdFactory
    )

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
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        state = InstanceState.READY
    )

    private companion object {
        const val CREATION_REQUEST_ID = "create-request-1"
        const val CREATED_INSTANCE_ID = "instance-new"
    }
}
