package com.multiapp.app.container

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.multiapp.app.MultiAppProcessRole
import com.multiapp.app.MultiAppProcessRoles
import com.multiapp.core.engine.EngineRuntimeBinderEndpoint
import com.multiapp.core.engine.EngineRuntimeIpcContract
import com.multiapp.core.engine.EngineServerRuntime
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class EngineBinderProvider : ContentProvider() {
    private lateinit var endpoint: EngineRuntimeBinderEndpoint

    override fun onCreate(): Boolean {
        val hostContext = context?.applicationContext ?: context ?: return false
        val processName = MultiAppProcessRoles.currentProcessName()
        check(
            MultiAppProcessRoles.resolve(hostContext.packageName, processName) ==
                MultiAppProcessRole.ENGINE_SERVER
        ) {
            "EngineBinderProvider must run in ${EngineRuntimeIpcContract.engineProcessName(hostContext.packageName)}, " +
                "actual=$processName"
        }
        val owner = EntryPointAccessors.fromApplication(
            hostContext,
            EngineServerRuntimeEntryPoint::class.java
        ).engineServerRuntime()
        owner.runtimeRegistry.invalidateEphemeralProcessStates("engine_server_process_started")
        endpoint = EngineRuntimeBinderEndpoint(
            registry = owner.runtimeRegistry,
            hostUid = hostContext.applicationInfo.uid,
            serverGenerationId = owner.serverGenerationId,
            activityLaunchCapabilities = owner.activityLaunchCapabilities,
            activityService = owner.systemServer.activityService,
            activityOperationTransactions = owner.activityOperationTransactions,
            providerService = owner.systemServer.providerService,
            permissionService = owner.systemServer.permissionService,
            appOpsService = owner.systemServer.appOpsService,
            serviceService = owner.systemServer.serviceService,
            serviceOperationLeases = owner.serviceOperationLeases,
            broadcastService = owner.systemServer.broadcastService,
            packageService = owner.systemServer.packageService,
            virtualizationEngine = owner.virtualizationEngine,
            processControlPlane = owner.processControlPlane
        )
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val hostContext = context?.applicationContext ?: context ?: return Bundle.EMPTY
        if (
            method != EngineRuntimeIpcContract.METHOD_GET_BINDER ||
            !::endpoint.isInitialized ||
            Binder.getCallingUid() != hostContext.applicationInfo.uid
        ) {
            return Bundle.EMPTY
        }
        return Bundle().apply {
            putBinder(EngineRuntimeIpcContract.KEY_BINDER, endpoint)
            putString(
                EngineRuntimeIpcContract.KEY_SERVER_GENERATION_ID,
                endpoint.getServerGenerationId()
            )
            putInt(EngineRuntimeIpcContract.KEY_SERVER_PROCESS_ID, Process.myPid())
            putString(
                EngineRuntimeIpcContract.KEY_SERVER_PROCESS_NAME,
                MultiAppProcessRoles.currentProcessName()
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface EngineServerRuntimeEntryPoint {
    fun engineServerRuntime(): EngineServerRuntime
}
