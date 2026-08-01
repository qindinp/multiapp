package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.InMemoryProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualProviderPathPermission
import com.multiapp.core.model.virtual.VirtualTaskRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualSystemServerTest {

    @Test
    fun `default system server exposes commercial engine subsystem boundaries`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        assertEquals(EngineSubsystem.PACKAGE, server.packageService.subsystem)
        assertEquals(EngineSubsystem.ACTIVITY, server.activityService.subsystem)
        assertEquals(EngineSubsystem.PROVIDER, server.providerService.subsystem)
        assertEquals(EngineSubsystem.PERMISSION, server.permissionService.subsystem)
        assertEquals(EngineSubsystem.APP_OPS, server.appOpsService.subsystem)
        assertEquals(EngineSubsystem.SERVICE, server.serviceService.subsystem)
        assertEquals(EngineSubsystem.BROADCAST, server.broadcastService.subsystem)
        assertEquals(EngineSubsystem.STORAGE, server.storageService.subsystem)
        assertEquals(EngineSubsystem.NATIVE, server.nativeService.subsystem)
        assertEquals(EngineSubsystem.EVIDENCE, server.evidenceService.subsystem)
    }

    @Test
    fun `runtime service is the single facade over runtime registry`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val runtime = runtime()

        val registered = server.runtimeService.register(runtime)
        val accepted = server.runtimeService.registerOperationEvidence(
            runtime.instanceId,
            EngineOperationEvidence(
                component = "provider",
                operation = "query",
                verdict = EngineResultStatus.PASS
            )
        )

        assertSame(runtime, registered)
        assertEquals(runtime, server.runtimeService.get(runtime.instanceId))
        assertTrue(accepted)
        assertEquals(EngineResultStatus.PASS, server.runtimeService.evidence(runtime.instanceId).status)
        assertEquals(EngineResultStatus.PASS, server.runtimeService.evidence(runtime.instanceId).subsystemVerdicts[EngineSubsystem.RUNTIME])
        assertTrue(server.runtimeService.stop(runtime.instanceId))
        assertEquals(EngineResultStatus.FAIL, server.runtimeService.evidence(runtime.instanceId).status)
    }

    @Test
    fun `subsystem services expose runtime binding without overstating unsupported semantics`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(registry)
        val runtime = server.runtimeService.register(runtime())

        val activity = server.activityService.queryRuntimeBinding(runtime.instanceId)
        val provider = server.providerService.queryRuntimeBinding(runtime.instanceId)
        val permission = server.permissionService.queryRuntimeBinding(runtime.instanceId)
        val appOps = server.appOpsService.queryRuntimeBinding(runtime.instanceId)
        val service = server.serviceService.queryRuntimeBinding(runtime.instanceId)
        val broadcast = server.broadcastService.queryRuntimeBinding(runtime.instanceId)
        val storage = server.storageService.queryRuntimeBinding(runtime.instanceId)
        val native = server.nativeService.queryRuntimeBinding(runtime.instanceId)

        assertEquals(EngineResultStatus.PARTIAL, activity.verdict)
        assertEquals(runtime.processSlot, activity.processSlot)
        assertEquals(runtime.proxySlot, activity.proxySlot)
        assertEquals(runtime.runtimeEpoch, activity.runtimeEpoch)
        assertTrue("launch" in activity.supportedOperations)
        assertTrue("proxy-process-death-recovery-evidence" in activity.supportedOperations)
        assertTrue("task-state-persistence" in activity.supportedOperations)
        assertTrue("lifecycle-state-persistence" in activity.supportedOperations)
        assertTrue("finish-record" in activity.supportedOperations)
        assertTrue("result-record" in activity.supportedOperations)
        assertTrue("on-new-intent-record" in activity.supportedOperations)
        assertTrue("back-stack-state" in activity.supportedOperations)
        assertTrue("recents-device-proof" in activity.unsupportedOperations)

        assertEquals(EngineSubsystem.PROVIDER, provider.subsystem)
        assertTrue("same-process-preinstall" in provider.supportedOperations)
        assertTrue("custom-process-provider" in provider.supportedOperations)
        assertTrue("external-uri-grant" in provider.unsupportedOperations)

        assertEquals(EngineSubsystem.PERMISSION, permission.subsystem)
        assertTrue("check-permission" in permission.supportedOperations)
        assertTrue("runtime-permission-dialog" in permission.unsupportedOperations)

        assertEquals(EngineSubsystem.APP_OPS, appOps.subsystem)
        assertTrue("check-operation" in appOps.supportedOperations)
        assertTrue("note-operation" in appOps.supportedOperations)
        assertFalse("note-operation" in appOps.unsupportedOperations)

        assertEquals(EngineSubsystem.SERVICE, service.subsystem)
        assertTrue("manifest-route-plan" in service.supportedOperations)
        assertTrue("explicit-service-route" in service.supportedOperations)
        assertTrue("implicit-service-route" in service.supportedOperations)
        assertTrue("stop-service-route" in service.supportedOperations)
        assertTrue("on-start-command-result" in service.supportedOperations)
        assertTrue("bind-service" in service.supportedOperations)
        assertTrue("unbind-service" in service.supportedOperations)
        assertTrue("cross-process-service" in service.supportedOperations)
        assertTrue("binder-death-rebind" in service.supportedOperations)

        assertEquals(EngineSubsystem.BROADCAST, broadcast.subsystem)
        assertTrue("manifest-route-plan" in broadcast.supportedOperations)
        assertTrue("explicit-receiver-route" in broadcast.supportedOperations)
        assertTrue("implicit-receiver-route" in broadcast.supportedOperations)
        assertTrue("ordered-dispatch" in broadcast.supportedOperations)
        assertTrue("receiver-permission-filter" in broadcast.supportedOperations)
        assertTrue("receiver-app-op" in broadcast.supportedOperations)
        assertTrue("abort" in broadcast.supportedOperations)
        assertTrue("result-receiver" in broadcast.supportedOperations)
        assertTrue("sticky" in broadcast.supportedOperations)

        assertEquals(EngineSubsystem.STORAGE, storage.subsystem)
        assertTrue("canonical-containment" in storage.supportedOperations)

        assertEquals(EngineSubsystem.NATIVE, native.subsystem)
        assertTrue("runtime-native-load" in native.unsupportedOperations)
    }

    @Test
    fun `activity service rejects unsupported launch modes before proxy allocation`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.app.IsolatedActivity",
                        launchMode = "singleInstance"
                    )
                )
            )
        )

        val plan = server.activityService.planActivity(
            runtime.instanceId,
            VirtualActivityDispatchPlanRequest(
                activityClassName = "com.test.app.IsolatedActivity",
                targetPackageName = runtime.originPackageName
            )
        )

        assertEquals(EngineResultStatus.UNSUPPORTED, plan.verdict)
        assertTrue(plan.targets.isEmpty())
        assertTrue("launch-mode:singleInstance" in plan.unsupportedOperations)
        assertEquals("activity_launch_mode_unsupported:singleInstance", plan.message)
    }

    @Test
    fun `activity service inherits alias target launch mode before planning`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                activities = listOf(
                    ResolvedComponent(
                        name = "com.test.app.LauncherAlias",
                        exported = true,
                        targetActivityName = "com.test.app.IsolatedActivity"
                    ),
                    ResolvedComponent(
                        name = "com.test.app.IsolatedActivity",
                        launchMode = "singleInstance"
                    )
                )
            )
        )

        val plan = server.activityService.planActivity(
            runtime.instanceId,
            VirtualActivityDispatchPlanRequest(
                activityClassName = "com.test.app.LauncherAlias",
                targetPackageName = runtime.originPackageName
            )
        )

        assertEquals(EngineResultStatus.UNSUPPORTED, plan.verdict)
        assertTrue(plan.targets.isEmpty())
        assertTrue("launch-mode:singleInstance" in plan.unsupportedOperations)
        assertEquals("activity_launch_mode_unsupported:singleInstance", plan.message)
    }

    @Test
    fun `activity service syncs hot loader records into engine task store`() {
        val taskStore = InMemoryEngineActivityTaskStateStore()
        val recordManager = VirtualActivityRecordManager()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            activityTaskStateStore = taskStore,
            activityRecordManager = recordManager
        )
        val runtime = server.runtimeService.register(runtime(instanceId = "instance-1"))
        taskStore.save(
            EngineActivityTaskStateSnapshot(
                listOf(
                    VirtualTaskRecord(
                        taskId = 9,
                        affinity = "com.test.app:other-instance",
                        activities = listOf(
                            activityRecord(
                                instanceId = "other-instance",
                                token = "token-other",
                                activityClassName = "com.test.app.OtherActivity",
                                state = VirtualActivityState.RESUMED
                            )
                        )
                    )
                )
            )
        )
        recordManager.restoreTasks(
            listOf(
                VirtualTaskRecord(
                    taskId = 7,
                    affinity = "com.test.app:instance-1",
                    activities = listOf(
                        activityRecord(
                            instanceId = runtime.instanceId,
                            token = "token-main",
                            activityClassName = "com.test.app.MainActivity",
                            state = VirtualActivityState.CREATED
                        )
                    )
                )
            )
        )

        val result = server.activityService.syncActivityTaskState(
            runtime.instanceId,
            reason = "start-activity-remapped"
        )
        val taskState = server.activityService.queryTaskState(runtime.instanceId)

        assertEquals(EngineResultStatus.PASS, result.verdict)
        assertEquals("sync-task-state", result.operation)
        assertEquals("activity_task_state_synced:start-activity-remapped", result.message)
        assertEquals(2, taskStore.load().activityCount)
        assertEquals(
            setOf("token-main", "token-other"),
            taskStore.load().tasks.flatMap { it.activities }.map { it.token }.toSet()
        )
        assertEquals(1, taskState.activityCount)
        assertEquals("com.test.app.MainActivity", taskState.topActivityClassName)
    }

    @Test
    fun `activity service exposes persisted task state for runtime instance only`() {
        val taskStore = InMemoryEngineActivityTaskStateStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            activityTaskStateStore = taskStore,
            activityRecordManager = VirtualActivityRecordManager()
        )
        val runtime = server.runtimeService.register(runtime(instanceId = "instance-1"))
        taskStore.save(
            EngineActivityTaskStateSnapshot(
                tasks = listOf(
                    VirtualTaskRecord(
                        taskId = 7,
                        affinity = "com.test.app:instance-1",
                        activities = listOf(
                            activityRecord(
                                instanceId = runtime.instanceId,
                                token = "token-root",
                                activityClassName = "com.test.app.RootActivity",
                                state = VirtualActivityState.STOPPED
                            ),
                            activityRecord(
                                instanceId = runtime.instanceId,
                                token = "token-detail",
                                activityClassName = "com.test.app.DetailActivity",
                                state = VirtualActivityState.RESUMED
                            ),
                            activityRecord(
                                instanceId = "other-instance",
                                token = "token-other",
                                activityClassName = "com.test.app.OtherActivity",
                                state = VirtualActivityState.RESUMED
                            )
                        )
                    )
                )
            )
        )

        val taskState = server.activityService.queryTaskState(runtime.instanceId)

        assertEquals(EngineResultStatus.PARTIAL, taskState.verdict)
        assertEquals(1, taskState.taskCount)
        assertEquals(2, taskState.activityCount)
        assertEquals(7, taskState.topTaskId)
        assertEquals("com.test.app.DetailActivity", taskState.topActivityClassName)
        assertEquals(VirtualActivityState.RESUMED, taskState.topActivityState)
        assertEquals(listOf("token-root", "token-detail"), taskState.tasks.single().activities.map { it.token })
        assertTrue("task-state-persistence" in taskState.supportedOperations)
        assertTrue("recents-device-proof" in taskState.unsupportedOperations)

        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("activity", "task-state")
            ?.single()
        assertEquals(EngineResultStatus.PASS, evidence?.verdict)
        assertEquals("PARTIAL", evidence?.entries?.get("taskStateVerdict"))
        assertEquals("1", evidence?.entries?.get("taskCount"))
        assertEquals("2", evidence?.entries?.get("activityCount"))
        assertEquals("com.test.app.DetailActivity", evidence?.entries?.get("topActivityClassName"))
        assertTrue(evidence?.entries?.get("supportedOperations").orEmpty().contains("task-state-persistence"))
    }

    @Test
    fun `activity service task state fails closed when runtime is missing`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        val taskState = server.activityService.queryTaskState("missing-instance")

        assertEquals(EngineResultStatus.FAIL, taskState.verdict)
        assertEquals("runtime_not_found:missing-instance", taskState.message)
        assertEquals(0, taskState.taskCount)
        assertEquals(0, taskState.activityCount)
    }

    @Test
    fun `activity service mutates persisted records and consumes queued events for runtime instance`() {
        val taskStore = InMemoryEngineActivityTaskStateStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            activityTaskStateStore = taskStore,
            activityRecordManager = VirtualActivityRecordManager()
        )
        val runtime = server.runtimeService.register(runtime(instanceId = "instance-1"))
        taskStore.save(
            EngineActivityTaskStateSnapshot(
                tasks = listOf(
                    VirtualTaskRecord(
                        taskId = 3,
                        affinity = "com.test.app:instance-1",
                        activities = listOf(
                            activityRecord(
                                instanceId = runtime.instanceId,
                                token = "token-root",
                                activityClassName = "com.test.app.RootActivity",
                                state = VirtualActivityState.RESUMED,
                                pendingNewIntents = listOf(
                                    VirtualActivityPendingNewIntent(
                                        eventId = 11L,
                                        sourceToken = "token-relaunch",
                                        intentFlags = 7,
                                        dataIntent = VirtualIntentSnapshot(action = "test.RELAUNCH")
                                    )
                                )
                            ),
                            activityRecord(
                                instanceId = "other-instance",
                                token = "token-other",
                                activityClassName = "com.test.app.OtherActivity",
                                state = VirtualActivityState.RESUMED
                            )
                        )
                    )
                )
            )
        )

        val stateUpdate = server.activityService.markActivityState(
            instanceId = runtime.instanceId,
            token = "token-root",
            state = VirtualActivityState.STOPPED
        )
        val resultUpdate = server.activityService.setActivityResult(
            instanceId = runtime.instanceId,
            token = "token-root",
            resultCode = 201,
            dataIntent = VirtualIntentSnapshot(action = "test.RESULT")
        )
        val consumedResult = server.activityService.consumeActivityResult(runtime.instanceId, "token-root")
        val consumedPending = server.activityService.consumePendingNewIntent(runtime.instanceId, "token-root")
        val mismatch = server.activityService.markActivityState(
            instanceId = runtime.instanceId,
            token = "token-other",
            state = VirtualActivityState.STOPPED
        )
        val finish = server.activityService.finishActivity(runtime.instanceId, "token-root")
        val persistedActivities = taskStore.load().tasks.flatMap { it.activities }

        assertEquals(EngineResultStatus.PASS, stateUpdate.verdict)
        assertEquals(VirtualActivityState.STOPPED, stateUpdate.state)
        assertEquals(EngineResultStatus.PASS, resultUpdate.verdict)
        assertEquals(201, consumedResult?.resultCode)
        assertEquals("test.RESULT", consumedResult?.dataIntent?.action)
        assertNull(server.activityService.consumeActivityResult(runtime.instanceId, "token-root"))
        assertEquals(11L, consumedPending?.eventId)
        assertEquals("test.RELAUNCH", consumedPending?.dataIntent?.action)
        assertNull(server.activityService.consumePendingNewIntent(runtime.instanceId, "token-root"))
        assertEquals(EngineResultStatus.FAIL, mismatch.verdict)
        assertEquals("activity_record_instance_mismatch:token-other", mismatch.message)
        assertEquals(EngineResultStatus.PASS, finish.verdict)
        assertEquals(VirtualActivityState.FINISHED, finish.state)
        assertNull(persistedActivities.singleOrNull { it.token == "token-root" })
        assertEquals(
            VirtualActivityState.RESUMED,
            persistedActivities.single { it.token == "token-other" }.state
        )
    }

    @Test
    fun `activity service records finish result to source Activity atomically`() {
        val taskStore = InMemoryEngineActivityTaskStateStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            activityTaskStateStore = taskStore,
            activityRecordManager = VirtualActivityRecordManager()
        )
        val runtime = server.runtimeService.register(runtime(instanceId = "instance-1"))
        taskStore.save(
            EngineActivityTaskStateSnapshot(
                tasks = listOf(
                    VirtualTaskRecord(
                        taskId = 7,
                        affinity = "com.test.app:instance-1",
                        activities = listOf(
                            activityRecord(
                                instanceId = runtime.instanceId,
                                token = "token-root",
                                activityClassName = "com.test.app.RootActivity",
                                state = VirtualActivityState.PAUSED
                            ),
                            activityRecord(
                                instanceId = runtime.instanceId,
                                token = "token-detail",
                                activityClassName = "com.test.app.DetailActivity",
                                state = VirtualActivityState.RESUMED,
                                resultToToken = "token-root",
                                resultRequestCode = 9
                            )
                        )
                    )
                )
            )
        )

        val update = server.activityService.recordActivityResultForFinish(
            instanceId = runtime.instanceId,
            token = "token-detail",
            resultCode = 202,
            dataIntent = VirtualIntentSnapshot(action = "test.DETAIL_RESULT")
        )
        val consumed = server.activityService.consumeActivityResult(runtime.instanceId, "token-root")

        assertEquals(EngineResultStatus.PASS, update.verdict)
        assertEquals("token-root", update.token)
        assertEquals(9, update.requestCode)
        assertEquals(202, update.resultCode)
        assertEquals("test.DETAIL_RESULT", update.dataIntent?.action)
        assertEquals(202, consumed?.resultCode)
        assertEquals(9, consumed?.requestCode)
        assertEquals("test.DETAIL_RESULT", consumed?.dataIntent?.action)
    }

    @Test
    fun `provider service fails closed when runtime is missing`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        val plan = server.providerService.planProvider(
            instanceId = "missing-instance",
            request = VirtualProviderDispatchPlanRequest(
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.provider"
            )
        )

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("runtime_not_found:missing-instance", plan.message)
        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `provider service plans authority route with process slot evidence`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.DataProvider",
                        authorities = listOf("com.test.app.provider"),
                        processName = "com.test.app:provider",
                        permission = "com.test.app.PROVIDER",
                        readPermission = "com.test.app.READ_PROVIDER",
                        writePermission = "com.test.app.WRITE_PROVIDER",
                        grantUriPermissions = true
                    )
                )
            )
        )
        val providerProcessSlot = "${runtime.hostPackageName}:v1"

        val plan = server.providerService.planProvider(
            instanceId = runtime.instanceId,
            request = authorizedProviderRequest(
                target = runtime,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.provider",
                proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v0",
                processSlot = providerProcessSlot
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("provider_route_planned", plan.message)
        assertEquals(1, plan.targets.size)
        assertEquals("com.test.app.DataProvider", plan.targets.single().providerClassName)
        assertEquals(providerProcessSlot, plan.targets.single().processSlot)
        assertEquals("com.test.app:provider", plan.targets.single().processName)
        assertTrue(plan.targets.single().grantUriPermissions)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("provider", "plan")
            ?.single()
        assertEquals(EngineResultStatus.PARTIAL, evidence?.verdict)
        assertEquals("1", evidence?.entries?.get("targetCount"))
        assertEquals("com.test.app.DataProvider", evidence?.entries?.get("targetProviders"))
        assertEquals("true", evidence?.entries?.get("routeTokenPresent"))
        assertEquals("true", evidence?.entries?.get("routeTokenVerified"))
        assertEquals("READ", evidence?.entries?.get("providerAccessType"))
        assertEquals("SELF_INSTANCE_BYPASS", evidence?.entries?.get("providerPermissionVerdict"))
    }

    @Test
    fun `provider service rejects forged external uid and mismatched caller pid`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                processId = 4001,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.DataProvider",
                        authorities = listOf("com.test.app.provider")
                    )
                )
            )
        )

        val uidMismatch = server.providerService.planProvider(
            runtime.instanceId,
            authorizedProviderRequest(
                target = runtime,
                operation = EngineProviderOperation.QUERY,
                callingUid = 2000,
                callingPid = 4001
            )
        )
        val pidMismatch = server.providerService.planProvider(
            runtime.instanceId,
            authorizedProviderRequest(
                target = runtime,
                operation = EngineProviderOperation.QUERY,
                callingPid = 4999
            )
        )

        assertEquals(EngineResultStatus.FAIL, uidMismatch.verdict)
        assertTrue(uidMismatch.message.startsWith("provider_caller_uid_mismatch:"))
        assertEquals(EngineResultStatus.FAIL, pidMismatch.verdict)
        assertTrue(pidMismatch.message.startsWith("provider_caller_pid_mismatch:"))
    }

    @Test
    fun `provider service blocks non exported and permission protected cross instance access`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val caller = server.runtimeService.register(runtime(instanceId = "caller", processId = 4101))
        val internalTarget = server.runtimeService.register(
            runtime(
                instanceId = "internal-target",
                processId = 4102,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.InternalProvider",
                        authorities = listOf("com.test.app.internal"),
                        exported = false
                    )
                )
            )
        )
        val protectedTarget = server.runtimeService.register(
            runtime(
                instanceId = "protected-target",
                processId = 4103,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.ProtectedProvider",
                        authorities = listOf("com.test.app.protected"),
                        exported = true,
                        readPermission = "com.test.app.READ_PROVIDER"
                    )
                )
            )
        )

        val internalPlan = server.providerService.planProvider(
            internalTarget.instanceId,
            authorizedProviderRequest(
                target = internalTarget,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.internal",
                callingPid = 4101
            )
        )
        val protectedPlan = server.providerService.planProvider(
            protectedTarget.instanceId,
            authorizedProviderRequest(
                target = protectedTarget,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.protected",
                callingPid = 4101
            )
        )

        assertEquals(EngineResultStatus.FAIL, internalPlan.verdict)
        assertEquals("provider_cross_instance_not_exported", internalPlan.message)
        assertTrue(internalPlan.targets.isEmpty())
        assertEquals(EngineResultStatus.FAIL, protectedPlan.verdict)
        assertTrue(protectedPlan.message.startsWith("provider_permission_denied:"))
        assertTrue(protectedPlan.targets.isEmpty())
    }

    @Test
    fun `provider service classifies file write and anonymous type access`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val caller = server.runtimeService.register(runtime(instanceId = "caller", processId = 4201))
        val target = server.runtimeService.register(
            runtime(
                instanceId = "target",
                processId = 4202,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.FileProvider",
                        authorities = listOf("com.test.app.files"),
                        exported = true,
                        readPermission = "com.test.app.READ_FILES",
                        writePermission = "com.test.app.WRITE_FILES"
                    )
                )
            )
        )

        val writePlan = server.providerService.planProvider(
            target.instanceId,
            authorizedProviderRequest(
                target = target,
                caller = caller,
                operation = EngineProviderOperation.OPEN_FILE,
                guestAuthority = "com.test.app.files",
                callingPid = 4201,
                accessMode = "rw"
            )
        )
        val typePlan = server.providerService.planProvider(
            target.instanceId,
            authorizedProviderRequest(
                target = target,
                caller = caller,
                operation = EngineProviderOperation.GET_TYPE,
                guestAuthority = "com.test.app.files",
                callingPid = 4201
            )
        )

        assertEquals(EngineResultStatus.FAIL, writePlan.verdict)
        assertTrue(writePlan.message.contains("com.test.app.WRITE_FILES:access=WRITE"))
        assertEquals(EngineResultStatus.PARTIAL, typePlan.verdict)
        assertEquals("provider_route_planned", typePlan.message)
        val evidence = server.evidenceService.exportReport(target.instanceId)
            ?.operationEntries("provider", "plan")
            .orEmpty()
        assertTrue(evidence.any { it.entries["providerAccessType"] == "WRITE" })
        assertTrue(evidence.any { it.entries["providerAccessType"] == "NONE" })
    }

    @Test
    fun `provider service rejects mismatched process slot before loader dispatch`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.DataProvider",
                        authorities = listOf("com.test.app.provider")
                    )
                )
            )
        )

        val plan = server.providerService.planProvider(
            instanceId = runtime.instanceId,
            request = VirtualProviderDispatchPlanRequest(
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.provider",
                processSlot = "com.multiapp.app:v9"
            )
        )

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertTrue(plan.message.startsWith("provider_process_slot_mismatch:"))
        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `provider service reports unsupported unknown operation`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val plan = server.providerService.planProvider(
            instanceId = runtime.instanceId,
            request = VirtualProviderDispatchPlanRequest(
                operation = EngineProviderOperation.UNKNOWN,
                guestAuthority = "com.test.app.provider"
            )
        )

        assertEquals(EngineResultStatus.UNSUPPORTED, plan.verdict)
        assertEquals("provider_operation_unsupported:UNKNOWN", plan.message)
        assertTrue("unknown-operation" in plan.unsupportedOperations)
    }

    @Test
    fun `provider service fails closed for unimplemented uri-grant control semantics`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.DataProvider",
                        authorities = listOf("com.test.app.provider"),
                        grantUriPermissions = true
                    )
                )
            )
        )
        val operations = linkedMapOf(
            EngineProviderOperation.GRANT_URI_PERMISSION to "uri-grant",
            EngineProviderOperation.REVOKE_URI_PERMISSION to "uri-grant"
        )

        operations.forEach { (operation, semantic) ->
            val plan = server.providerService.planProvider(
                instanceId = runtime.instanceId,
                request = VirtualProviderDispatchPlanRequest(
                    operation = operation,
                    guestAuthority = "com.test.app.provider",
                    processSlot = runtime.processSlot,
                    routeTokenPresent = true
                )
            )

            assertEquals(EngineResultStatus.UNSUPPORTED, plan.verdict)
            assertTrue(plan.targets.isEmpty())
            assertEquals(setOf(semantic), plan.unsupportedOperations)
        }
    }

    @Test
    fun `provider service allows notify-change and content-observer through plan`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.DataProvider",
                        authorities = listOf("com.test.app.provider"),
                        grantUriPermissions = true
                    )
                )
            )
        )
        val operations = listOf(
            EngineProviderOperation.NOTIFY_CHANGE,
            EngineProviderOperation.REGISTER_CONTENT_OBSERVER,
            EngineProviderOperation.UNREGISTER_CONTENT_OBSERVER
        )

        operations.forEach { operation ->
            val plan = server.providerService.planProvider(
                instanceId = runtime.instanceId,
                request = VirtualProviderDispatchPlanRequest(
                    operation = operation,
                    guestAuthority = "com.test.app.provider",
                    processSlot = runtime.processSlot,
                    routeTokenPresent = true,
                    routeTokenVerified = true,
                    callerInstanceId = runtime.instanceId,
                    targetInstanceId = runtime.instanceId,
                    callingUid = 1000,
                    callingPid = 12345,
                    hostUid = 1000,
                    engineCallingUid = 1000,
                    engineCallingPid = 12345
                )
            )

            assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
            assertFalse("notify-change" in plan.unsupportedOperations)
            assertFalse("content-observer" in plan.unsupportedOperations)
        }
    }
    @Test
    fun `provider service records engine dispatch evidence without exposing loader result types`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val accepted = server.providerService.recordProviderDispatch(
            instanceId = runtime.instanceId,
            result = VirtualProviderOperationResult(
                instanceId = runtime.instanceId,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.provider",
                proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v0",
                providerClassName = "com.test.app.DataProvider",
                verdict = EngineResultStatus.PASS,
                reason = "provider_ready",
                ready = true,
                cached = false,
                message = "loader_provider_ready"
            )
        )

        assertTrue(accepted)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("provider", "dispatch")
            ?.single()
        assertEquals(EngineResultStatus.PASS, evidence?.verdict)
        assertEquals("QUERY", evidence?.entries?.get("operation"))
        assertEquals("true", evidence?.entries?.get("ready"))
        assertEquals("loader_provider_ready", evidence?.entries?.get("message"))
        val firstState = server.providerService.queryProviderRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineProviderLifecycleState.READY, firstState.state)
        assertEquals(EngineProviderOperation.QUERY, firstState.lastOperation)
        assertEquals(1L, firstState.operationCount)
        assertEquals(runtime.runtimeEpoch, firstState.runtimeEpoch)

        server.providerService.recordProviderDispatch(
            instanceId = runtime.instanceId,
            result = VirtualProviderOperationResult(
                instanceId = runtime.instanceId,
                operation = EngineProviderOperation.OPEN_FILE,
                guestAuthority = "com.test.app.provider",
                proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v0",
                providerClassName = "com.test.app.DataProvider",
                verdict = EngineResultStatus.PASS,
                reason = "provider_cached",
                ready = true,
                cached = true,
                message = "loader_provider_cached"
            )
        )
        val secondState = server.providerService.queryProviderRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineProviderOperation.OPEN_FILE, secondState.lastOperation)
        assertEquals(2L, secondState.operationCount)
        assertTrue(secondState.cached)

        server.runtimeService.register(
            runtime.copy(
                runtimeEpoch = runtime.runtimeEpoch + 1,
                engineSessionId = "engine-evidence-2",
                evidenceSessionId = "evidence-2"
            )
        )

        assertTrue(server.providerService.queryProviderRuntimeState(runtime.instanceId).records.isEmpty())
    }

    @Test
    fun `provider URI grant allows only granted instance path and mode`() {
        val store = InMemoryEngineProviderUriGrantStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            providerUriGrantStore = store
        )
        val caller = server.runtimeService.register(runtime(instanceId = "caller", processId = 4301))
        val owner = server.runtimeService.register(
            runtime(
                instanceId = "owner",
                processId = 4302,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.InternalProvider",
                        authorities = listOf("com.test.app.internal"),
                        exported = false,
                        grantUriPermissions = true
                    )
                )
            )
        )

        val grant = server.providerService.grantUriPermission(
            owner.instanceId,
            VirtualProviderUriGrantRequest(
                guestAuthority = "com.test.app.internal",
                encodedPath = "/books/7",
                modeFlags = EngineProviderUriGrantModes.READ,
                targetInstanceId = caller.instanceId,
                callingUid = 1000,
                callingPid = 4302,
                hostUid = 1000
            )
        )
        val exactRead = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.internal",
                callingPid = 4301,
                encodedPath = "/books/7"
            )
        )
        val otherPath = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.internal",
                callingPid = 4301,
                encodedPath = "/books/8"
            )
        )
        val write = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.UPDATE,
                guestAuthority = "com.test.app.internal",
                callingPid = 4301,
                encodedPath = "/books/7"
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, grant.verdict)
        assertTrue(grant.granted)
        assertEquals("provider_route_planned_with_uri_grant", exactRead.message)
        assertEquals(EngineResultStatus.PARTIAL, exactRead.verdict)
        assertEquals("provider_cross_instance_not_exported", otherPath.message)
        assertEquals(EngineResultStatus.FAIL, otherPath.verdict)
        assertEquals("provider_cross_instance_not_exported", write.message)
        assertEquals(EngineResultStatus.FAIL, write.verdict)
    }

    @Test
    fun `provider persistable URI take and release are target owned and keep transient access`() {
        val store = InMemoryEngineProviderUriGrantStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            providerUriGrantStore = store
        )
        val target = server.runtimeService.register(runtime(instanceId = "target", processId = 4311))
        val owner = server.runtimeService.register(
            runtime(
                instanceId = "owner",
                processId = 4312,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.DocumentsProvider",
                        authorities = listOf("com.test.app.documents"),
                        exported = false,
                        grantUriPermissions = true
                    )
                )
            )
        )
        server.providerService.grantUriPermission(
            owner.instanceId,
            VirtualProviderUriGrantRequest(
                guestAuthority = "com.test.app.documents",
                encodedPath = "/documents/7",
                modeFlags = EngineProviderUriGrantModes.READ or
                    EngineProviderUriGrantModes.PERSISTABLE,
                targetInstanceId = target.instanceId,
                callingUid = 1000,
                callingPid = 4312,
                hostUid = 1000
            )
        )
        val persistRequest = VirtualProviderUriGrantRequest(
            guestAuthority = "com.test.app.documents",
            encodedPath = "/documents/7",
            modeFlags = EngineProviderUriGrantModes.READ,
            ownerInstanceId = owner.instanceId,
            targetInstanceId = target.instanceId,
            callingUid = 1000,
            callingPid = 4311,
            hostUid = 1000
        )

        val taken = server.providerService.takePersistableUriPermission(
            target.instanceId,
            persistRequest
        )
        val released = server.providerService.releasePersistableUriPermission(
            target.instanceId,
            persistRequest
        )
        val transientCheck = server.providerService.checkUriPermission(
            target.instanceId,
            persistRequest.copy(callingPid = 4311)
        )

        assertEquals(EngineResultStatus.PARTIAL, taken.verdict)
        assertEquals(EngineProviderUriGrantModes.READ, taken.persistedModeFlags)
        assertEquals(0, released.persistedModeFlags)
        assertTrue(store.listPersistedForTarget(target.instanceId).isEmpty())
        assertTrue(transientCheck.granted)
    }

    @Test
    fun `provider path permission overrides unguarded provider permission by URI path`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val caller = server.runtimeService.register(runtime(instanceId = "caller", processId = 4351))
        val owner = server.runtimeService.register(
            runtime(
                instanceId = "owner",
                processId = 4352,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.PathProvider",
                        authorities = listOf("com.test.app.paths"),
                        exported = true,
                        pathPermissions = listOf(
                            VirtualProviderPathPermission(
                                VirtualProviderPathPattern(
                                    "/private",
                                    VirtualProviderPathPatternType.PREFIX
                                ),
                                readPermission = "com.test.app.READ_PRIVATE"
                            )
                        )
                    )
                )
            )
        )

        val protectedPath = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.paths",
                callingPid = 4351,
                encodedPath = "/private/book/7"
            )
        )
        val publicPath = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.paths",
                callingPid = 4351,
                encodedPath = "/public/book/7"
            )
        )

        assertEquals(EngineResultStatus.FAIL, protectedPath.verdict)
        assertTrue(protectedPath.message.contains("com.test.app.READ_PRIVATE:access=READ"))
        assertEquals(EngineResultStatus.PARTIAL, publicPath.verdict)
        assertEquals("provider_route_planned", publicPath.message)
    }

    @Test
    fun `explicit virtual permission grant allows protected cross instance provider path`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val permission = "com.test.app.READ_PRIVATE"
        val caller = server.runtimeService.register(
            runtime(
                instanceId = "caller",
                processId = 4353,
                permissions = listOf(permission)
            )
        )
        val owner = server.runtimeService.register(
            runtime(
                instanceId = "owner",
                processId = 4354,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.ProtectedProvider",
                        authorities = listOf("com.test.app.protected"),
                        exported = true,
                        readPermission = permission
                    )
                )
            )
        )
        server.permissionService.setPermissionGrant(
            caller.instanceId,
            permission,
            granted = true,
            source = EnginePermissionGrantSource.USER_DECISION
        )

        val plan = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                guestAuthority = "com.test.app.protected",
                callingPid = 4353
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("provider_route_planned_with_virtual_permission", plan.message)
        assertEquals(1, plan.targets.size)
        val evidence = server.runtimeService.evidence(owner.instanceId)
            .operationEntries("provider", "plan")
            .last()
        assertEquals("VIRTUAL_PERMISSION_GRANTED:USER_DECISION", evidence.entries["providerPermissionVerdict"])
    }

    @Test
    fun `provider URI grant patterns do not expand to the whole authority`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val caller = server.runtimeService.register(runtime(instanceId = "caller", processId = 4361))
        val owner = server.runtimeService.register(
            runtime(
                instanceId = "owner",
                processId = 4362,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.SharedProvider",
                        authorities = listOf("com.test.app.shared"),
                        exported = false,
                        grantUriPermissions = false,
                        uriPermissionPatterns = listOf(
                            VirtualProviderPathPattern(
                                "/shared",
                                VirtualProviderPathPatternType.PREFIX
                            )
                        )
                    )
                )
            )
        )

        val granted = server.providerService.grantUriPermission(
            owner.instanceId,
            VirtualProviderUriGrantRequest(
                guestAuthority = "com.test.app.shared",
                encodedPath = "/shared/book/7",
                modeFlags = EngineProviderUriGrantModes.READ,
                targetInstanceId = caller.instanceId,
                callingUid = 1000,
                callingPid = 4362,
                hostUid = 1000
            )
        )
        val blocked = server.providerService.grantUriPermission(
            owner.instanceId,
            VirtualProviderUriGrantRequest(
                guestAuthority = "com.test.app.shared",
                encodedPath = "/private/book/7",
                modeFlags = EngineProviderUriGrantModes.READ,
                targetInstanceId = caller.instanceId,
                callingUid = 1000,
                callingPid = 4362,
                hostUid = 1000
            )
        )

        assertTrue(granted.granted)
        assertEquals(EngineResultStatus.PARTIAL, granted.verdict)
        assertFalse(blocked.granted)
        assertEquals("provider_uri_grant_not_allowed", blocked.message)
    }

    @Test
    fun `provider URI grant claim without durable record fails closed`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val caller = server.runtimeService.register(runtime(instanceId = "caller", processId = 4401))
        val owner = server.runtimeService.register(
            runtime(
                instanceId = "owner",
                processId = 4402,
                providers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.Provider",
                        authorities = listOf("com.test.app.provider"),
                        exported = true,
                        grantUriPermissions = true
                    )
                )
            )
        )

        val plan = server.providerService.planProvider(
            owner.instanceId,
            authorizedProviderRequest(
                target = owner,
                caller = caller,
                operation = EngineProviderOperation.QUERY,
                callingPid = 4401,
                encodedPath = "/private/7",
                uriGrantPresent = true
            )
        )

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("provider_uri_grant_claim_unverified", plan.message)
        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `provider authority resolver prefers self and uses URI grant to disambiguate clones`() {
        val store = InMemoryEngineProviderUriGrantStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            providerUriGrantStore = store
        )
        val provider = ResolvedComponent(
            name = "com.test.app.Provider",
            authorities = listOf("com.test.shared"),
            exported = false,
            grantUriPermissions = true
        )
        val caller = server.runtimeService.register(runtime("caller", 4501))
        val firstOwner = server.runtimeService.register(
            runtime("owner-1", 4502, providers = listOf(provider))
        )
        val secondOwner = server.runtimeService.register(
            runtime("owner-2", 4503, providers = listOf(provider))
        )

        val ambiguous = server.providerService.resolveProviderAuthority(
            caller.instanceId,
            VirtualProviderAuthorityResolveRequest(
                guestAuthority = "com.test.shared",
                operation = EngineProviderOperation.QUERY,
                encodedPath = "/books/7"
            )
        )
        server.providerService.grantUriPermission(
            secondOwner.instanceId,
            VirtualProviderUriGrantRequest(
                guestAuthority = "com.test.shared",
                encodedPath = "/books/7",
                modeFlags = EngineProviderUriGrantModes.READ,
                targetInstanceId = caller.instanceId,
                callingUid = 1000,
                callingPid = 4503,
                hostUid = 1000
            )
        )
        val granted = server.providerService.resolveProviderAuthority(
            caller.instanceId,
            VirtualProviderAuthorityResolveRequest(
                guestAuthority = "com.test.shared",
                operation = EngineProviderOperation.QUERY,
                encodedPath = "/books/7"
            )
        )
        val self = server.providerService.resolveProviderAuthority(
            firstOwner.instanceId,
            VirtualProviderAuthorityResolveRequest(
                guestAuthority = "com.test.shared",
                operation = EngineProviderOperation.QUERY
            )
        )
        val system = server.providerService.resolveProviderAuthority(
            caller.instanceId,
            VirtualProviderAuthorityResolveRequest(
                guestAuthority = "settings",
                operation = EngineProviderOperation.QUERY
            )
        )

        assertEquals(EngineResultStatus.UNSUPPORTED, ambiguous.verdict)
        assertTrue(ambiguous.virtualAuthority)
        assertNull(ambiguous.targetInstanceId)
        assertEquals(secondOwner.instanceId, granted.targetInstanceId)
        assertEquals("provider_authority_resolved_by_uri_grant", granted.message)
        assertEquals(firstOwner.instanceId, self.targetInstanceId)
        assertEquals("provider_authority_resolved_self", self.message)
        assertFalse(system.virtualAuthority)
    }

    @Test
    fun `service service fails closed when runtime is missing`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        val plan = server.serviceService.planService(
            instanceId = "missing-instance",
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.START,
                action = "test.SYNC"
            )
        )

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("runtime_not_found:missing-instance", plan.message)
        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `service service plans explicit start route with process slot evidence`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                services = listOf(
                    ResolvedComponent(
                        name = "com.test.app.SyncService"
                    )
                )
            )
        )

        val plan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.START,
                action = "test.SYNC",
                serviceClassName = ".SyncService"
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("explicit_service_route_planned", plan.message)
        assertEquals(1, plan.targets.size)
        assertEquals("com.test.app.SyncService", plan.targets.single().serviceClassName)
        assertEquals("explicit", plan.targets.single().reason)
        assertEquals(VirtualServiceOperation.START, plan.targets.single().operation)
        assertEquals(runtime.processSlot, plan.targets.single().processSlot)
        assertEquals(runtime.originPackageName, plan.targets.single().processName)
        assertTrue(plan.targets.single().sameProcess)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "plan")
            ?.single()
        assertEquals(EngineResultStatus.PARTIAL, evidence?.verdict)
        assertEquals("1", evidence?.entries?.get("targetCount"))
        assertEquals("com.test.app.SyncService", evidence?.entries?.get("targetServices"))
        assertEquals(runtime.processSlot, evidence?.entries?.get("processSlot"))
    }

    @Test
    fun `service service plans implicit route to highest priority service`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                services = listOf(
                    ResolvedComponent(
                        name = "com.test.app.LowPrioritySyncService",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(
                                actions = listOf("test.SYNC"),
                                categories = listOf("test.CATEGORY"),
                                priority = 10
                            )
                        )
                    ),
                    ResolvedComponent(
                        name = "com.test.app.HighPrioritySyncService",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(
                                actions = listOf("test.SYNC"),
                                categories = listOf("test.CATEGORY"),
                                priority = 20
                            )
                        )
                    )
                )
            )
        )

        val plan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.START,
                action = "test.SYNC",
                categories = setOf("test.CATEGORY")
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("implicit_service_route_planned", plan.message)
        assertEquals(listOf("com.test.app.HighPrioritySyncService"), plan.targets.map { it.serviceClassName })
        assertEquals(listOf(20), plan.targets.map { it.priority })
        assertEquals("implicit", plan.targets.single().reason)
    }

    @Test
    fun `service service plans explicit stop route without claiming lifecycle completion`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                services = listOf(ResolvedComponent(name = "com.test.app.SyncService"))
            )
        )

        val plan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.STOP,
                serviceClassName = "SyncService"
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("explicit_service_stop_route_planned", plan.message)
        assertEquals("explicitStop", plan.targets.single().reason)
        assertFalse("cross-process-service" in plan.unsupportedOperations)
        assertFalse("binder-death-rebind" in plan.unsupportedOperations)
        assertFalse("sticky-restart" in plan.unsupportedOperations)
    }

    @Test
    fun `service service reports unsupported foreground type and sticky restart semantics`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val plan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.BIND,
                action = "test.SYNC",
                requestedForegroundServiceTypes = setOf("location"),
                stickyRestartRequested = true
            )
        )

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertTrue(plan.targets.isEmpty())
        assertFalse("foreground-service-type" in plan.unsupportedOperations)
        assertFalse("sticky-restart" in plan.unsupportedOperations)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "plan")
            ?.single()
        assertEquals(EngineResultStatus.FAIL, evidence?.verdict)
        assertFalse(evidence?.entries?.get("unsupportedOperations").orEmpty().contains("foreground-service-type"))
    }

    @Test
    fun `service service plans same process bind and connection unbind`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                services = listOf(
                    ResolvedComponent(
                        name = "com.test.app.SyncService",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(actions = listOf("test.SYNC"))
                        )
                    )
                )
            )
        )

        val bindPlan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.BIND,
                action = "test.SYNC"
            )
        )
        assertTrue(
            server.serviceService.recordServiceDispatch(
                runtime.instanceId,
                VirtualServiceOperationResult(
                    instanceId = runtime.instanceId,
                    operation = VirtualServiceOperation.BIND,
                    serviceClassName = bindPlan.targets.single().serviceClassName,
                    action = "test.SYNC",
                    verdict = EngineResultStatus.PASS,
                    reason = "implicitBind",
                    bound = true,
                    processSlot = runtime.processSlot,
                    activeBindCount = 1,
                    message = "service_bound"
                )
            )
        )
        val unbindPlan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(operation = VirtualServiceOperation.UNBIND)
        )

        assertEquals(EngineResultStatus.PARTIAL, bindPlan.verdict)
        assertEquals("implicit_service_route_planned", bindPlan.message)
        assertTrue(bindPlan.targets.single().sameProcess)
        assertEquals("implicitBind", bindPlan.targets.single().reason)
        assertEquals(EngineResultStatus.PARTIAL, unbindPlan.verdict)
        assertEquals("service_unbind_connection_route_planned", unbindPlan.message)
        assertEquals("com.test.app.SyncService", unbindPlan.targets.single().serviceClassName)
    }

    @Test
    fun `service service plans custom process starts and rejects custom binds`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                services = listOf(
                    ResolvedComponent(
                        name = "com.test.app.RemoteService",
                        processName = ":remote"
                    )
                )
            )
        )

        val startPlan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.START,
                serviceClassName = "RemoteService"
            )
        )
        val foregroundPlan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.START_FOREGROUND,
                serviceClassName = "RemoteService"
            )
        )
        val bindPlan = server.serviceService.planService(
            instanceId = runtime.instanceId,
            request = VirtualServiceDispatchPlanRequest(
                operation = VirtualServiceOperation.BIND,
                serviceClassName = "RemoteService"
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, startPlan.verdict)
        assertEquals("com.test.app:remote", startPlan.targets.single().processName)
        assertFalse(startPlan.targets.single().sameProcess)
        assertEquals(runtime.processSlot, startPlan.targets.single().processSlot)
        assertEquals(EngineResultStatus.PARTIAL, foregroundPlan.verdict)
        assertTrue(foregroundPlan.targets.single().foreground)
        assertEquals(EngineResultStatus.PARTIAL, bindPlan.verdict)
        assertFalse(bindPlan.targets.isEmpty())
        assertFalse("cross-process-service" in bindPlan.unsupportedOperations)
    }

    @Test
    fun `service service records engine dispatch evidence without exposing loader result types`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val accepted = server.serviceService.recordServiceDispatch(
            instanceId = runtime.instanceId,
            result = VirtualServiceOperationResult(
                instanceId = runtime.instanceId,
                operation = VirtualServiceOperation.START,
                serviceClassName = "com.test.app.SyncService",
                action = "test.SYNC",
                verdict = EngineResultStatus.PASS,
                reason = "explicit",
                started = true,
                startCommandResult = 2,
                processSlot = runtime.processSlot,
                activeStartCount = 2,
                cached = true,
                message = "loader_service_started"
            )
        )

        assertTrue(accepted)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("service", "dispatch")
            ?.single()
        assertEquals(EngineResultStatus.PASS, evidence?.verdict)
        assertEquals("true", evidence?.entries?.get("started"))
        assertEquals("2", evidence?.entries?.get("startCommandResult"))
        assertEquals("loader_service_started", evidence?.entries?.get("message"))
        val startedState = server.serviceService.queryServiceRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineServiceLifecycleState.STARTED, startedState.state)
        assertEquals(2, startedState.activeStartCount)
        assertTrue(startedState.cached)

        val stopped = server.serviceService.recordServiceDispatch(
            instanceId = runtime.instanceId,
            result = VirtualServiceOperationResult(
                instanceId = runtime.instanceId,
                operation = VirtualServiceOperation.STOP,
                serviceClassName = "com.test.app.SyncService",
                action = "test.SYNC",
                verdict = EngineResultStatus.PASS,
                reason = "explicitStop",
                stopped = true,
                message = "loader_service_stopped"
            )
        )
        val stoppedState = server.serviceService.queryServiceRuntimeState(runtime.instanceId)
            .records
            .single()

        assertTrue(stopped)
        assertEquals(EngineServiceLifecycleState.STOPPED, stoppedState.state)
        assertEquals(0, stoppedState.activeStartCount)
        assertEquals(runtime.runtimeEpoch, stoppedState.runtimeEpoch)

        server.runtimeService.register(
            runtime.copy(
                runtimeEpoch = runtime.runtimeEpoch + 1,
                engineSessionId = "engine-evidence-2",
                evidenceSessionId = "evidence-2"
            )
        )

        assertTrue(server.serviceService.queryServiceRuntimeState(runtime.instanceId).records.isEmpty())
    }

    @Test
    fun `broadcast service fails closed when runtime is missing`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        val plan = server.broadcastService.planBroadcast(
            instanceId = "missing-instance",
            request = VirtualBroadcastDispatchPlanRequest(action = "test.ACTION")
        )

        assertEquals(EngineResultStatus.FAIL, plan.verdict)
        assertEquals("runtime_not_found:missing-instance", plan.message)
        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `broadcast service plans explicit receiver route with process slot evidence`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                receivers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.BootReceiver",
                        processName = "com.test.app:receiver"
                    )
                )
            )
        )

        val plan = server.broadcastService.planBroadcast(
            instanceId = runtime.instanceId,
            request = VirtualBroadcastDispatchPlanRequest(
                action = "android.intent.action.BOOT_COMPLETED",
                receiverClassName = ".BootReceiver"
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("explicit_broadcast_route_planned", plan.message)
        assertEquals(1, plan.targets.size)
        assertEquals("com.test.app.BootReceiver", plan.targets.single().receiverClassName)
        assertEquals("explicit", plan.targets.single().reason)
        assertEquals(runtime.processSlot, plan.targets.single().processSlot)
        assertEquals("com.test.app:receiver", plan.targets.single().processName)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "plan")
            ?.single()
        assertEquals(EngineResultStatus.PARTIAL, evidence?.verdict)
        assertEquals("1", evidence?.entries?.get("targetCount"))
        assertEquals("com.test.app.BootReceiver", evidence?.entries?.get("targetReceivers"))
        assertEquals(runtime.processSlot, evidence?.entries?.get("processSlot"))
    }

    @Test
    fun `broadcast service plans implicit receiver routes in manifest priority order`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            runtime(
                receivers = listOf(
                    ResolvedComponent(
                        name = "com.test.app.LowPriorityReceiver",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(
                                actions = listOf("test.ACTION"),
                                categories = listOf("test.CATEGORY"),
                                priority = 10
                            )
                        )
                    ),
                    ResolvedComponent(
                        name = "com.test.app.HighPriorityReceiver",
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(
                                actions = listOf("test.ACTION"),
                                categories = listOf("test.CATEGORY"),
                                priority = 20
                            )
                        )
                    )
                )
            )
        )

        val plan = server.broadcastService.planBroadcast(
            instanceId = runtime.instanceId,
            request = VirtualBroadcastDispatchPlanRequest(
                action = "test.ACTION",
                categories = setOf("test.CATEGORY")
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertEquals("implicit_broadcast_route_planned", plan.message)
        assertEquals(
            listOf("com.test.app.HighPriorityReceiver", "com.test.app.LowPriorityReceiver"),
            plan.targets.map { it.receiverClassName }
        )
        assertEquals(listOf(20, 10), plan.targets.map { it.priority })
        assertTrue(plan.targets.all { it.reason == "implicit" })
    }

    @Test
    fun `broadcast service reports unsupported ordered sticky and result semantics`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val plan = server.broadcastService.planBroadcast(
            instanceId = runtime.instanceId,
            request = VirtualBroadcastDispatchPlanRequest(
                action = "test.ACTION",
                ordered = true,
                sticky = true,
                expectsResultReceiver = true,
                abortSupportedRequested = true,
                receiverPermissions = setOf("com.test.RECEIVE_EVENT"),
                receiverAppOp = "android:read_device_identifiers",
                asUserRequested = true,
                platformOptionsPresent = true
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertTrue(plan.targets.isEmpty())
        assertFalse("as-user" in plan.unsupportedOperations)
        assertFalse("broadcast-options" in plan.unsupportedOperations)
        assertFalse("sticky" in plan.unsupportedOperations)
        assertFalse("result-receiver" in plan.unsupportedOperations)
        assertFalse("abort" in plan.unsupportedOperations)
        assertFalse("receiver-app-op" in plan.unsupportedOperations)
        assertTrue("sticky" in plan.supportedOperations)
        assertTrue("result-receiver" in plan.supportedOperations)
        assertTrue("abort" in plan.supportedOperations)
        assertTrue("receiver-app-op" in plan.supportedOperations)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "plan")
            ?.single()
        assertEquals(EngineResultStatus.PARTIAL, evidence?.verdict)
        assertFalse(evidence?.entries?.get("unsupportedOperations").orEmpty().contains("as-user"))
    }

    @Test
    fun `broadcast service plans sticky result-receiver abort and receiver-app-op as supported operations`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val plan = server.broadcastService.planBroadcast(
            instanceId = runtime.instanceId,
            request = VirtualBroadcastDispatchPlanRequest(
                action = "test.ACTION",
                sticky = true,
                expectsResultReceiver = true,
                abortSupportedRequested = true,
                receiverAppOp = "android:read_device_identifiers"
            )
        )

        assertEquals(EngineResultStatus.PARTIAL, plan.verdict)
        assertTrue("sticky" in plan.supportedOperations)
        assertTrue("result-receiver" in plan.supportedOperations)
        assertTrue("abort" in plan.supportedOperations)
        assertTrue("receiver-app-op" in plan.supportedOperations)
        assertTrue("sticky" !in plan.unsupportedOperations)
        assertTrue("result-receiver" !in plan.unsupportedOperations)
        assertTrue("abort" !in plan.unsupportedOperations)
        assertTrue("receiver-app-op" !in plan.unsupportedOperations)
    }

    @Test
    fun `broadcast service records engine dispatch evidence without exposing loader result types`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val accepted = server.broadcastService.recordBroadcastDispatch(
            instanceId = runtime.instanceId,
            result = VirtualBroadcastOperationResult(
                instanceId = runtime.instanceId,
                receiverClassName = "com.test.app.BootReceiver",
                action = "android.intent.action.BOOT_COMPLETED",
                verdict = EngineResultStatus.PASS,
                reason = "explicit",
                delivered = true,
                message = "loader_dispatch_delivered"
            )
        )

        assertTrue(accepted)
        val evidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "dispatch")
            ?.single()
        assertEquals(EngineResultStatus.PASS, evidence?.verdict)
        assertEquals("true", evidence?.entries?.get("delivered"))
        assertEquals("loader_dispatch_delivered", evidence?.entries?.get("message"))
        val runtimeRecord = server.broadcastService.queryBroadcastRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineBroadcastDeliveryState.DELIVERED, runtimeRecord.state)
        assertEquals(1L, runtimeRecord.dispatchCount)
        assertEquals(1L, runtimeRecord.deliveredCount)
        assertEquals(runtime.processSlot, runtimeRecord.processSlot)
        assertEquals(runtime.runtimeEpoch, runtimeRecord.runtimeEpoch)
        val runtimeEvidence = server.evidenceService.exportReport(runtime.instanceId)
            ?.operationEntries("broadcast", "runtime-state")
            ?.single()
        assertEquals("1", runtimeEvidence?.entries?.get("broadcastRecordCount"))

        server.broadcastService.recordBroadcastDispatch(
            instanceId = runtime.instanceId,
            result = VirtualBroadcastOperationResult(
                instanceId = runtime.instanceId,
                receiverClassName = "com.test.app.BootReceiver",
                action = "android.intent.action.BOOT_COMPLETED",
                verdict = EngineResultStatus.FAIL,
                reason = "on_receive_failed",
                message = "loader_dispatch_failed"
            )
        )
        val failedRecord = server.broadcastService.queryBroadcastRuntimeState(runtime.instanceId)
            .records
            .single()
        assertEquals(EngineBroadcastDeliveryState.FAILED, failedRecord.state)
        assertEquals(2L, failedRecord.dispatchCount)
        assertEquals(1L, failedRecord.failureCount)

        server.runtimeService.register(
            runtime.copy(
                runtimeEpoch = runtime.runtimeEpoch + 1,
                engineSessionId = "engine-broadcast-2",
                evidenceSessionId = "evidence-broadcast-2"
            )
        )

        assertTrue(server.broadcastService.queryBroadcastRuntimeState(runtime.instanceId).records.isEmpty())
    }

    @Test
    fun `activity service owns query reserve compare and set and lifecycle release`() {
        val store = InMemoryProxyActivitySlotAssignmentStore()
        val server = DefaultVirtualSystemServer(
            registry = EngineRuntimeRegistry(),
            proxyActivitySlotAssignmentStore = store
        )
        val runtime = server.runtimeService.register(runtime())
        val key = ProxyActivitySlotKey(runtime.instanceId, null, "task-main")
        val candidate = "${runtime.hostPackageName}.container.ProxyActivity0"

        val empty = server.activityService.queryProxyActivitySlot(runtime.instanceId, key)
        val reserved = server.activityService.reserveProxyActivitySlot(
            runtime.instanceId,
            key,
            listOf(candidate)
        )
        val queried = server.activityService.queryProxyActivitySlot(runtime.instanceId, key)
        val staleExpected = server.activityService.compareAndSetProxyActivitySlot(
            runtime.instanceId,
            key,
            expected = null,
            new = candidate
        )
        val removed = server.activityService.compareAndSetProxyActivitySlot(
            runtime.instanceId,
            key,
            expected = candidate,
            new = null
        )
        val restored = server.activityService.compareAndSetProxyActivitySlot(
            runtime.instanceId,
            key,
            expected = null,
            new = candidate
        )

        assertEquals(EngineResultStatus.PASS, empty.verdict)
        assertFalse(empty.matched)
        assertNull(empty.proxyActivityClassName)
        assertEquals("reserveProxyActivitySlot", reserved.operation)
        assertEquals(EngineResultStatus.PASS, reserved.verdict)
        assertTrue(reserved.matched)
        assertEquals(candidate, reserved.proxyActivityClassName)
        assertEquals(candidate, queried.proxyActivityClassName)
        assertFalse(staleExpected.matched)
        assertEquals(EngineResultStatus.FAIL, staleExpected.verdict)
        assertEquals(candidate, staleExpected.proxyActivityClassName)
        assertTrue(removed.matched)
        assertEquals(1, removed.removedCount)
        assertNull(removed.proxyActivityClassName)
        assertTrue(restored.matched)
        assertEquals(candidate, restored.proxyActivityClassName)

        assertEquals(1, server.instanceLifecycleService.releaseInstanceSlots(runtime.instanceId))
        assertFalse(server.activityService.queryProxyActivitySlot(runtime.instanceId, key).matched)
    }

    @Test
    fun `activity service rejects invalid proxy slot requests without writing`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val key = ProxyActivitySlotKey(runtime.instanceId, null, "task-main")
        val valid = "${runtime.hostPackageName}.container.ProxyActivity0"
        val wrongProcess = "${runtime.hostPackageName}.container.ProxyActivity1"
        val wrongMode = "${runtime.hostPackageName}.container.ProxyActivitySingleTop0"

        val mixedCandidates = server.activityService.reserveProxyActivitySlot(
            runtime.instanceId,
            key,
            listOf(valid, wrongProcess)
        )
        val modeMismatch = server.activityService.reserveProxyActivitySlot(
            runtime.instanceId,
            key,
            listOf(wrongMode)
        )
        val invalidExpected = server.activityService.compareAndSetProxyActivitySlot(
            runtime.instanceId,
            key,
            expected = wrongProcess,
            new = null
        )
        val invalidNew = server.activityService.compareAndSetProxyActivitySlot(
            runtime.instanceId,
            key,
            expected = null,
            new = wrongMode
        )
        val instanceMismatch = server.activityService.queryProxyActivitySlot(
            runtime.instanceId,
            ProxyActivitySlotKey("another-instance", null, "task-main")
        )
        val unnormalizedMode = server.activityService.queryProxyActivitySlot(
            runtime.instanceId,
            ProxyActivitySlotKey(runtime.instanceId, "standard", "task-main")
        )
        val missingRuntime = server.activityService.reserveProxyActivitySlot(
            "missing-instance",
            ProxyActivitySlotKey("missing-instance", null, "task-main"),
            listOf(valid)
        )

        assertEquals(EngineResultStatus.FAIL, mixedCandidates.verdict)
        assertTrue(mixedCandidates.message.startsWith("proxy_activity_slot_process_mismatch:"))
        assertEquals(EngineResultStatus.FAIL, modeMismatch.verdict)
        assertTrue(modeMismatch.message.startsWith("proxy_activity_slot_launch_mode_mismatch:"))
        assertEquals(EngineResultStatus.FAIL, invalidExpected.verdict)
        assertEquals(EngineResultStatus.FAIL, invalidNew.verdict)
        assertEquals(EngineResultStatus.FAIL, instanceMismatch.verdict)
        assertEquals(EngineResultStatus.FAIL, unnormalizedMode.verdict)
        assertEquals(EngineResultStatus.FAIL, missingRuntime.verdict)
        assertFalse(server.activityService.queryProxyActivitySlot(runtime.instanceId, key).matched)
    }

    @Test
    fun `concurrent proxy slot reservations keep one owner per proxy class`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())
        val candidate = "${runtime.hostPackageName}.container.ProxyActivity0"
        val keys = (0 until 12).map { index ->
            ProxyActivitySlotKey(runtime.instanceId, null, "task-$index")
        }
        val ready = CountDownLatch(keys.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(keys.size)
        val futures = keys.map { key ->
            executor.submit<VirtualProxyActivitySlotOperationResult> {
                ready.countDown()
                start.await()
                server.activityService.reserveProxyActivitySlot(
                    runtime.instanceId,
                    key,
                    listOf(candidate)
                )
            }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { future -> future.get(5, TimeUnit.SECONDS) }
            val owners = keys.map { key ->
                server.activityService.queryProxyActivitySlot(runtime.instanceId, key)
            }

            assertEquals(1, results.count { it.matched })
            assertEquals(1, owners.count { it.matched })
            assertEquals(setOf(candidate), owners.mapNotNull { it.proxyActivityClassName }.toSet())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `subsystem services fail closed when runtime is missing`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())

        val binding = server.providerService.queryRuntimeBinding("missing-instance")

        assertEquals(EngineSubsystem.PROVIDER, binding.subsystem)
        assertEquals(EngineResultStatus.FAIL, binding.verdict)
        assertEquals("runtime_not_found:missing-instance", binding.message)
        assertNull(binding.processSlot)
        assertTrue(binding.supportedOperations.isEmpty())
        assertTrue(binding.unsupportedOperations.isEmpty())
    }

    @Test
    fun `evidence service exports report only for known runtime`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime())

        val report = server.evidenceService.exportReport(runtime.instanceId)

        assertEquals(runtime.instanceId, report?.instanceId)
        assertEquals(runtime.evidenceSessionId, report?.evidenceSessionId)
        assertNull(server.evidenceService.exportReport("missing-instance"))
    }

    private fun runtime(
        instanceId: String = "instance-1",
        processId: Int? = null,
        activities: List<ResolvedComponent> = emptyList(),
        services: List<ResolvedComponent> = emptyList(),
        receivers: List<ResolvedComponent> = emptyList(),
        providers: List<ResolvedComponent> = emptyList(),
        permissions: List<String> = emptyList()
    ) = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.$instanceId",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/$instanceId",
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = permissions
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1",
        runtimeEpoch = 42L,
        engineSessionId = "engine-evidence-1",
        processId = processId,
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.CREATED
    )

    private fun authorizedProviderRequest(
        target: VirtualInstanceRuntime,
        caller: VirtualInstanceRuntime = target,
        operation: EngineProviderOperation,
        guestAuthority: String = "com.test.app.provider",
        proxyAuthority: String = "com.multiapp.app.multiapp.provider.stub.v0",
        callingUid: Int = 1000,
        callingPid: Int = caller.processId ?: 4998,
        accessMode: String? = null,
        encodedPath: String = "/",
        uriGrantPresent: Boolean = false,
        processSlot: String = target.processSlot
    ) = VirtualProviderDispatchPlanRequest(
        operation = operation,
        guestAuthority = guestAuthority,
        proxyAuthority = proxyAuthority,
        processSlot = processSlot,
        routeTokenPresent = true,
        routeTokenVerified = true,
        callerInstanceId = caller.instanceId,
        targetInstanceId = target.instanceId,
        callingUid = callingUid,
        callingPid = callingPid,
        hostUid = 1000,
        callerProcessSlot = caller.processSlot,
        accessMode = accessMode,
        encodedPath = encodedPath,
        uriGrantPresent = uriGrantPresent
    )

    private fun activityRecord(
        instanceId: String,
        token: String,
        activityClassName: String,
        state: VirtualActivityState,
        pendingNewIntents: List<VirtualActivityPendingNewIntent> = emptyList(),
        result: VirtualActivityResult? = null,
        resultToToken: String? = null,
        resultRequestCode: Int = -1
    ): VirtualActivityRecord = VirtualActivityRecord(
        token = token,
        activityId = token,
        instanceId = instanceId,
        originPackageName = "com.test.app",
        guestActivityClassName = activityClassName,
        proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
        state = state,
        taskId = 7,
        taskAffinity = "com.test.app:$instanceId",
        pendingNewIntents = pendingNewIntents,
        resultToToken = resultToToken,
        resultRequestCode = resultRequestCode,
        result = result
    )
}
