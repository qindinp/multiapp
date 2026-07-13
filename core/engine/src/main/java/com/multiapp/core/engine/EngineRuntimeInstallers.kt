package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorders
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorder
import com.multiapp.core.loader.VirtualAmsComponentDispatchers
import com.multiapp.core.loader.VirtualAppOpsDispatchers
import com.multiapp.core.loader.VirtualBroadcastRecorders
import com.multiapp.core.loader.VirtualBroadcastRecorder
import com.multiapp.core.loader.VirtualContentResolverFactories
import com.multiapp.core.loader.VirtualContentServiceOperationRecorder
import com.multiapp.core.loader.VirtualContentServiceOperationRecorders
import com.multiapp.core.loader.VirtualContentServiceProxyInstaller
import com.multiapp.core.loader.VirtualInstrumentationInstaller
import com.multiapp.core.loader.VirtualPermissionCheckDispatchers
import com.multiapp.core.loader.ProxyActivitySlotAssignmentStoreProvider
import com.multiapp.core.loader.VirtualUriPermissionDispatcherFactories
import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxySlotContract
import java.io.File

data class EngineSystemServerHandle(
    val registry: EngineRuntimeRegistry,
    val server: VirtualSystemServer
)

data class EngineContentRuntimeInstallResult(
    val installed: Boolean,
    val status: String,
    val reason: String
)

object EngineRuntimeInstallers {
    @Volatile
    private var processHostContext: Context? = null

    fun installSystemServerClient(context: Context): Boolean {
        rememberProcessHostContext(context)
        ProxyActivitySlotAssignmentStoreProvider.install(
            IpcBackedProxyActivitySlotAssignmentStore()
        )
        val connected = EngineRuntimeIpcClients.install(context)
        if (connected) {
            EngineOperationEvidenceSinks.install(EngineRuntimeIpcClients.evidenceSink())
        }
        return connected
    }

    fun installAmsComponentDispatcher(context: Context) {
        rememberProcessHostContext(context)
        val systemServer = fileBackedSystemServer(context).server
        val activityService = IpcBackedVirtualActivityService(
            fallback = systemServer.activityService,
            localTaskSnapshot = { EngineHostedProcessRuntimeDefaults.activityRecordManager.exportTasks() }
        )
        val serviceService = IpcBackedVirtualServiceService(systemServer.serviceService)
        val broadcastService = IpcBackedVirtualBroadcastService(systemServer.broadcastService)
        val appOpsService = IpcBackedVirtualAppOpsService(systemServer.appOpsService)
        val permissionService = IpcBackedVirtualPermissionService(systemServer.permissionService)
        VirtualPermissionCheckDispatchers.install(
            EngineVirtualPermissionDispatcher(permissionService)
        )
        VirtualAppOpsDispatchers.install(
            EngineVirtualAppOpsDispatcher(
                service = appOpsService,
                hostUid = context.applicationInfo.uid
            )
        )
        VirtualAmsComponentDispatchers.install { request ->
            DefaultEngineAmsComponentDispatcher(
                fallback = request.fallback,
                instanceId = request.config.instanceId,
                activityLaunchCoordinator = EngineActivityLaunchCoordinator(
                    hostContext = request.hostContext,
                    processSlot = request.config.processSlot
                ),
                activityService = activityService,
                serviceService = serviceService,
                broadcastService = broadcastService
            )
        }
    }

    fun installContentResolver(): EngineContentRuntimeInstallResult {
        VirtualContentResolverFactories.install(EngineVirtualContentResolverFactory())
        VirtualUriPermissionDispatcherFactories.install(
            EngineVirtualUriPermissionDispatcherFactory()
        )
        VirtualContentServiceOperationRecorders.install(
            VirtualContentServiceOperationRecorder { record ->
                EngineOperationEvidenceSinks.global.record(
                    instanceId = record.instanceId,
                    evidence = com.multiapp.core.model.engine.EngineOperationEvidence(
                        component = "provider",
                        operation = record.operation,
                        verdict = if (record.success) {
                            com.multiapp.core.model.engine.EngineResultStatus.PARTIAL
                        } else {
                            com.multiapp.core.model.engine.EngineResultStatus.FAIL
                        },
                        entries = linkedMapOf(
                            "instanceId" to record.instanceId,
                            "operation" to record.operation,
                            "guestAuthority" to record.guestAuthority.orEmpty(),
                            "proxyAuthority" to record.proxyAuthority.orEmpty(),
                            "processSlot" to record.processSlot.orEmpty(),
                            "routedUriCount" to record.routedUriCount.toString(),
                            "contentServiceProxyVerdict" to if (record.success) "PARTIAL" else "FAIL",
                            "reason" to record.reason
                        )
                    )
                )
            }
        )
        return VirtualContentServiceProxyInstaller().install().let { result ->
            EngineContentRuntimeInstallResult(
                installed = result.installed,
                status = result.status,
                reason = result.reason
            )
        }
    }

    fun installInstrumentation(context: Context): Result<Unit> {
        rememberProcessHostContext(context)
        return VirtualInstrumentationInstaller.install(
            processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
            activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
            activityOperations = EngineVirtualActivityOperationsFactory.hotPath(context.filesDir)
        )
    }

    fun installBroadcastRecorder(recorder: EngineBroadcastRecorder) {
        VirtualBroadcastRecorders.install(
            VirtualBroadcastRecorder { record -> recorder.record(record.toEngineRecord()) }
        )
    }

    fun installAmsApiEvidenceRecorder(recorder: EngineAmsApiEvidenceRecorder) {
        VirtualAmsApiEvidenceRecorders.install(
            VirtualAmsApiEvidenceRecorder { record -> recorder.record(record.toEngineRecord()) }
        )
    }

    fun fileBackedSystemServer(context: Context): EngineSystemServerHandle {
        val filesDir = resolveProcessHostContext(context).filesDir
        val registry = EngineRuntimeRegistry.global.attachStateStore(
            FileBackedEngineRuntimeStateStore(
                File(filesDir, EngineRuntimeStateFiles.DEFAULT_FILE_NAME)
            )
        )
        val server = DefaultVirtualSystemServer(
            registry = registry,
            activityTaskStateStore = FileBackedEngineActivityTaskStateStore(
                File(filesDir, EngineActivityTaskStateFiles.DEFAULT_FILE_NAME)
            ),
            activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
            serviceRuntimeStateStore = FileBackedEngineServiceRuntimeStateStore(
                File(filesDir, EngineServiceRuntimeStateFiles.DEFAULT_FILE_NAME)
            ),
            providerRuntimeStateStore = FileBackedEngineProviderRuntimeStateStore(
                File(filesDir, EngineProviderRuntimeStateFiles.DEFAULT_FILE_NAME)
            ),
            providerUriGrantStore = FileBackedEngineProviderUriGrantStore(
                File(filesDir, EngineProviderUriGrantFiles.DEFAULT_FILE_NAME)
            ),
            permissionGrantStore = FileBackedEnginePermissionGrantStore(
                File(filesDir, EnginePermissionGrantFiles.DEFAULT_FILE_NAME)
            ),
            appOpsStateStore = FileBackedEngineAppOpsStateStore(
                File(filesDir, EngineAppOpsStateFiles.DEFAULT_FILE_NAME)
            ),
            broadcastRuntimeStateStore = FileBackedEngineBroadcastRuntimeStateStore(
                File(filesDir, EngineBroadcastRuntimeStateFiles.DEFAULT_FILE_NAME)
            ),
            proxyActivitySlotAssignmentStore = FileBackedProxyActivitySlotAssignmentStore(
                File(filesDir, ProxySlotContract.SLOT_ASSIGNMENT_FILE)
            )
        )
        return EngineSystemServerHandle(registry = registry, server = server)
    }

    private fun rememberProcessHostContext(context: Context) {
        val applicationContext = runCatching { context.applicationContext }.getOrNull()
        processHostContext = applicationContext ?: context
    }

    private fun resolveProcessHostContext(context: Context): Context {
        processHostContext?.let { return it }
        val applicationContext = runCatching { context.applicationContext }.getOrNull()
        return applicationContext
            ?: throw IllegalStateException(
                "Engine process host Context is unavailable during guest component attachment"
            )
    }

    internal fun rememberProcessHostContextForTest(context: Context) {
        rememberProcessHostContext(context)
    }

    internal fun resolveProcessHostContextForTest(context: Context): Context =
        resolveProcessHostContext(context)

    internal fun clearProcessHostContextForTest() {
        processHostContext = null
    }
}
