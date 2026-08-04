package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.UserHandle
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.Executor

/**
 * Wraps the guest Application base context to redirect external storage paths
 * into the instance sandbox, avoiding EACCES when guest code tries to write to
 * the real app's external data directory (owned by a different UID), and to
 * route guest Service calls (bindService/startService/stopService) through the
 * virtual AMS dispatcher.
 *
 * Without the service routing, a guest app that binds its own non-exported
 * service (e.g. WeChat ProcessService$MMProcessService) is denied by the real
 * ActivityManager because the host UID does not own the guest package
 * ("Permission Denial: ... not exported from uid <guest>").
 *
 * Only the methods that resolve to Android/data/<package>/... are overridden
 * for storage:
 * - [getExternalFilesDir]
 * - [getExternalCacheDir]
 * - [getObbDir]
 * - [getExternalFilesDirs]
 * - [getExternalCacheDirs]
 * - [getExternalMediaDirs]
 */
internal class ExternalStorageRedirectContextWrapper(
    base: Context,
    private val dataRoot: String,
    private val serviceDispatcher: VirtualAmsComponentDispatcher? = null,
    private val guestClassLoader: ClassLoader? = null
) : ContextWrapper(base) {

    override fun getExternalFilesDir(type: String?): File? {
        return VirtualContextStorage.externalFilesDir(dataRoot, type)
    }

    override fun getExternalCacheDir(): File? {
        return VirtualContextStorage.externalCacheDir(dataRoot)
    }

    override fun getObbDir(): File? {
        return VirtualContextStorage.externalFilesDir(dataRoot, "obb")
    }

    override fun getExternalFilesDirs(type: String?): Array<File> {
        val dir = getExternalFilesDir(type) ?: return emptyArray()
        return arrayOf(dir)
    }

    override fun getExternalCacheDirs(): Array<File> {
        val dir = getExternalCacheDir() ?: return emptyArray()
        return arrayOf(dir)
    }

    override fun getExternalMediaDirs(): Array<File> {
        return arrayOf(VirtualContextStorage.externalFilesDir(dataRoot, "media"))
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        return dispatchBindService(service, conn, flags, executor = null) {
            super.bindService(service, conn, flags)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun bindService(
        service: Intent,
        flags: Int,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindService(service, conn, flags, executor = executor) {
            super.bindService(service, flags, executor, conn)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
        user: UserHandle
    ): Boolean {
        return dispatchBindService(service, conn, flags, executor = null) {
            super.bindServiceAsUser(service, conn, flags, user)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindService(service, conn, flags, executor = executor) {
            super.bindIsolatedService(service, flags, instanceName, executor, conn)
        }
    }

    override fun startService(service: Intent): ComponentName? {
        val dispatcher = serviceDispatcher ?: return super.startService(service)
        return when (val mapping = dispatcher.resolveStartServiceIntent(service, foreground = false)) {
            is VirtualContextWrapper.StartServiceMappingResult.Remapped -> {
                runCatching { super.startService(mapping.proxyIntent) }.getOrNull()
            }
            is VirtualContextWrapper.StartServiceMappingResult.Blocked -> null
            is VirtualContextWrapper.StartServiceMappingResult.SystemPassthrough -> {
                runCatching { super.startService(service) }.getOrNull()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun startForegroundService(service: Intent): ComponentName? {
        val dispatcher = serviceDispatcher ?: return super.startForegroundService(service)
        return when (val mapping = dispatcher.resolveStartServiceIntent(service, foreground = true)) {
            is VirtualContextWrapper.StartServiceMappingResult.Remapped -> {
                runCatching { super.startForegroundService(mapping.proxyIntent) }.getOrNull()
            }
            is VirtualContextWrapper.StartServiceMappingResult.Blocked -> null
            is VirtualContextWrapper.StartServiceMappingResult.SystemPassthrough -> {
                runCatching { super.startForegroundService(service) }.getOrNull()
            }
        }
    }

    override fun stopService(service: Intent): Boolean {
        val dispatcher = serviceDispatcher ?: return super.stopService(service)
        if (dispatcher.shouldDispatchServiceToSystem(service)) {
            return runCatching { super.stopService(service) }.getOrDefault(false)
        }
        return dispatcher.dispatchStopService(service) is VirtualServiceStopDispatchResult.ServiceStopped
    }

    private fun dispatchBindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
        executor: Executor?,
        systemBind: () -> Boolean
    ): Boolean {
        val dispatcher = serviceDispatcher
        val loader = guestClassLoader
        if (dispatcher == null || loader == null) {
            return systemBind()
        }
        return when (val mapping = dispatcher.resolveStartServiceIntent(service, foreground = false)) {
            is VirtualContextWrapper.StartServiceMappingResult.SystemPassthrough -> systemBind()
            is VirtualContextWrapper.StartServiceMappingResult.Blocked -> false
            is VirtualContextWrapper.StartServiceMappingResult.Remapped -> {
                val result = dispatcher.dispatchBindService(
                    intent = service,
                    virtualContext = this,
                    guestClassLoader = loader,
                    connection = conn,
                    flags = flags,
                    executor = executor
                )
                result is VirtualServiceBindDispatchResult.Bound
            }
        }
    }

    companion object {
        /**
         * Replaces the Application base context with this wrapper so that
         * [Context.getExternalFilesDir] and friends return sandbox paths and
         * guest Service calls are dispatched through the virtual AMS.
         *
         * Must be called after LoadedApk.makeApplication (attachBaseContext already
         * consumed by the framework), before Application.onCreate.
         *
         * @return true if the redirect was applied, false if the base context is
         *         unavailable (e.g. in a JVM unit test without Android mocking).
         */
        fun redirectApplicationContext(
            application: android.app.Application,
            dataRoot: String,
            serviceDispatcher: VirtualAmsComponentDispatcher? = null,
            guestClassLoader: ClassLoader? = null
        ): Boolean {
            return runCatching {
                val originalBase = application.baseContext
                val wrapper = ExternalStorageRedirectContextWrapper(
                    base = originalBase,
                    dataRoot = dataRoot,
                    serviceDispatcher = serviceDispatcher,
                    guestClassLoader = guestClassLoader
                )
                val mBaseField = ContextWrapper::class.java.getDeclaredField("mBase")
                mBaseField.isAccessible = true
                mBaseField.set(application, wrapper)
                true
            }.getOrDefault(false)
        }
    }
}