package com.multiapp.app.container

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.multiapp.core.engine.EngineRuntimeBinderEndpoint
import com.multiapp.core.engine.EngineRuntimeIpcContract
import com.multiapp.core.engine.EngineRuntimeInstallers

class EngineBinderProvider : ContentProvider() {
    private lateinit var endpoint: EngineRuntimeBinderEndpoint

    override fun onCreate(): Boolean {
        val hostContext = context?.applicationContext ?: context ?: return false
        val handle = EngineRuntimeInstallers.fileBackedSystemServer(hostContext)
        handle.registry.invalidateEphemeralProcessStates("engine_server_process_started")
        endpoint = EngineRuntimeBinderEndpoint(
            registry = handle.registry,
            hostUid = hostContext.applicationInfo.uid,
            activityService = handle.server.activityService,
            providerService = handle.server.providerService,
            permissionService = handle.server.permissionService,
            appOpsService = handle.server.appOpsService,
            serviceService = handle.server.serviceService,
            broadcastService = handle.server.broadcastService
        )
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != EngineRuntimeIpcContract.METHOD_GET_BINDER || !::endpoint.isInitialized) {
            return Bundle.EMPTY
        }
        return Bundle().apply {
            putBinder(EngineRuntimeIpcContract.KEY_BINDER, endpoint)
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
