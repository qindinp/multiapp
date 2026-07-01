package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Wraps the host Context and overrides identity fields so the guest app
 * sees its own package name, data directories, source path, and ClassLoader.
 *
 * This is the core of the hosted container model: the guest code running
 * inside ContainerActivity gets a Context that reports the guest's identity,
 * not the host MultiApp identity.
 */
class VirtualContextWrapper(
    private val base: Context,
    private val config: VirtualContextConfig,
    private val guestClassLoader: ClassLoader,
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    private val servicePackageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime.global,
    private val broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
    private val dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry.global,
    private val serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { manager, request ->
        manager.createProxyIntent(request)
    }
) : ContextWrapper(base) {

    sealed class StartServiceMappingResult {
        abstract val sourceIntent: Intent
        abstract val foreground: Boolean

        data class Remapped(
            override val sourceIntent: Intent,
            override val foreground: Boolean,
            val startRequest: VirtualServiceStartRequest,
            val proxyIntent: Intent
        ) : StartServiceMappingResult()

        data class Fallback(
            override val sourceIntent: Intent,
            override val foreground: Boolean,
            val reason: String
        ) : StartServiceMappingResult()
    }

    sealed class BroadcastReceiverRegistrationResult {
        abstract val receiver: BroadcastReceiver?

        data class Registered(
            override val receiver: BroadcastReceiver,
            val instanceId: String,
            val filter: VirtualDynamicReceiverFilter
        ) : BroadcastReceiverRegistrationResult()

        data class Fallback(
            override val receiver: BroadcastReceiver?,
            val reason: String
        ) : BroadcastReceiverRegistrationResult()

        data class Unregistered(
            override val receiver: BroadcastReceiver
        ) : BroadcastReceiverRegistrationResult()
    }

    private val virtualResourceBundle: VirtualResourceBundle by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { VirtualResourcesManager(base).create(config) }.getOrElse {
            VirtualResourceBundle(
                applicationInfo = createFallbackApplicationInfo(),
                resources = runCatching { base.resources }.getOrNull() ?: Resources.getSystem(),
                source = ResourceSource.HOST_FALLBACK
            )
        }
    }

    private val guestResources: Resources get() = virtualResourceBundle.resources

    private val sharedPreferences = mutableMapOf<String, SharedPreferences>()

    private var lastStartServiceMappingResult: StartServiceMappingResult? = null

    private var lastStopServiceDispatchResult: VirtualServiceStopDispatchResult? = null

    private var lastBroadcastDispatchResult: VirtualBroadcastResult? = null

    private var lastBroadcastReceiverRegistrationResult: BroadcastReceiverRegistrationResult? = null

    private var lastStorageEvidence: VirtualStorageEvidence? = null

    private val virtualPackageManager: PackageManager? by lazy(LazyThreadSafetyMode.NONE) {
        config.packageSnapshot?.let { snapshot ->
            VirtualPackageManagerWrapper(base.packageManager, snapshot)
        }
    }

    private val proxyActivityRegistry by lazy(LazyThreadSafetyMode.NONE) {
        com.multiapp.core.model.virtual.ProxyActivityRegistry(
            listOf(
                "${base.packageName}.container.ProxyActivity0",
                "${base.packageName}.container.ProxyActivity1",
                "${base.packageName}.container.ProxyActivitySingleTop0",
                "${base.packageName}.container.ProxyActivitySingleTop1",
                "${base.packageName}.container.ProxyActivitySingleTask0",
                "${base.packageName}.container.ProxyActivitySingleTask1"
            ),
            mapOf(
                "${base.packageName}.container.ProxyActivity0" to null,
                "${base.packageName}.container.ProxyActivity1" to null,
                "${base.packageName}.container.ProxyActivitySingleTop0" to "singleTop",
                "${base.packageName}.container.ProxyActivitySingleTop1" to "singleTop",
                "${base.packageName}.container.ProxyActivitySingleTask0" to "singleTask",
                "${base.packageName}.container.ProxyActivitySingleTask1" to "singleTask"
            )
        )
    }

    private val guestTheme: Resources.Theme by lazy(LazyThreadSafetyMode.NONE) {
        guestResources.newTheme().apply {
            runCatching { base.theme }.getOrNull()?.let { baseTheme ->
                runCatching { setTo(baseTheme) }
            }
        }
    }

    override fun getPackageName(): String = config.virtualPackageName

    override fun getPackageManager(): PackageManager = virtualPackageManager ?: base.packageManager

    override fun startActivity(intent: Intent) {
        base.startActivity(remapStartActivityIntent(intent) ?: intent)
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        base.startActivity(remapStartActivityIntent(intent) ?: intent, options)
    }

    override fun startService(service: Intent): ComponentName? {
        return base.startService(resolveStartServiceIntent(service, foreground = false))
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        return base.startForegroundService(resolveStartServiceIntent(service, foreground = true))
    }

    override fun stopService(service: Intent): Boolean {
        val request = resolveStopServiceIntent(service) ?: return false.also {
            lastStopServiceDispatchResult = null
        }
        val result = VirtualServiceDispatcher(
            hostContext = base,
            packageRegistry = servicePackageRegistry,
            serviceRuntime = serviceRuntime
        ).dispatchStop(request)
        lastStopServiceDispatchResult = result
        return result is VirtualServiceStopDispatchResult.ServiceStopped
    }

    override fun sendBroadcast(intent: Intent) {
        val result = dispatchBroadcast(intent)
        lastBroadcastDispatchResult = result
        if (result is VirtualBroadcastResult.Delivered) return
        base.sendBroadcast(intent)
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        if (receiver == null || filter == null) {
            lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
                receiver = receiver,
                reason = "missingReceiverOrFilter"
            )
            return base.registerReceiver(receiver, filter)
        }
        val virtualFilter = filter.toVirtualDynamicReceiverFilter()
        dynamicReceiverRegistry.register(config.instanceId, receiver, virtualFilter)
        lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Registered(
            receiver = receiver,
            instanceId = config.instanceId,
            filter = virtualFilter
        )
        return null
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? {
        lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
            receiver = receiver,
            reason = "permissionOrSchedulerUnsupported"
        )
        return base.registerReceiver(receiver, filter, broadcastPermission, scheduler)
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        if (dynamicReceiverRegistry.unregister(receiver) == null) {
            lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
                receiver = receiver,
                reason = "receiverNotRegistered"
            )
            base.unregisterReceiver(receiver)
        } else {
            lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Unregistered(receiver)
        }
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return ApplicationInfo(virtualResourceBundle.applicationInfo)
    }

    override fun getPackageResourcePath(): String = config.sourceDir

    override fun getPackageCodePath(): String = config.sourceDir

    override fun getClassLoader(): ClassLoader = guestClassLoader

    override fun getResources(): Resources = guestResources

    override fun getAssets(): AssetManager = resources.assets

    override fun getTheme(): Resources.Theme = guestTheme

    override fun getDataDir(): File = recordStorage(StorageOperation.DATA_DIR, null, File(config.dataDir).apply { mkdirs() })

    override fun getFilesDir(): File = recordStorage(
        StorageOperation.FILES_DIR,
        null,
        VirtualContextStorage.filesDir(config.dataDir)
    )

    override fun getCacheDir(): File = recordStorage(
        StorageOperation.CACHE_DIR,
        null,
        VirtualContextStorage.cacheDir(config.dataDir)
    )

    override fun getCodeCacheDir(): File = recordStorage(
        StorageOperation.CODE_CACHE_DIR,
        null,
        VirtualContextStorage.codeCacheDir(config.dataDir)
    )

    override fun getNoBackupFilesDir(): File = recordStorage(
        StorageOperation.NO_BACKUP_DIR,
        null,
        VirtualContextStorage.noBackupFilesDir(config.dataDir)
    )

    override fun getFileStreamPath(name: String): File = recordStorage(
        StorageOperation.FILE_STREAM_PATH,
        name,
        VirtualContextStorage.fileStreamPath(config.dataDir, name)
    )

    override fun openFileInput(name: String): FileInputStream {
        val path = VirtualContextStorage.fileStreamPath(config.dataDir, name)
        recordStorage(StorageOperation.OPEN_FILE_INPUT, name, path)
        return FileInputStream(path)
    }

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val append = mode and Context.MODE_APPEND == Context.MODE_APPEND
        val path = VirtualContextStorage.fileStreamPath(config.dataDir, name)
        recordStorage(StorageOperation.OPEN_FILE_OUTPUT, name, path)
        return FileOutputStream(path, append)
    }

    override fun deleteFile(name: String): Boolean {
        val path = VirtualContextStorage.fileStreamPath(config.dataDir, name)
        recordStorage(StorageOperation.DELETE_FILE, name, path)
        return path.delete()
    }

    override fun fileList(): Array<String> {
        val path = VirtualContextStorage.filesDir(config.dataDir)
        recordStorage(StorageOperation.FILE_LIST, null, path)
        return VirtualContextStorage.listFileNames(path)
    }

    override fun getDatabasePath(name: String): File = recordStorage(
        StorageOperation.DATABASE_PATH,
        name,
        VirtualContextStorage.databasePath(config.dataDir, name)
    )

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        val path = VirtualContextStorage.databasePath(config.dataDir, name)
        recordStorage(StorageOperation.OPEN_OR_CREATE_DATABASE, name, path)
        path.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(path, factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        val path = VirtualContextStorage.databasePath(config.dataDir, name)
        recordStorage(StorageOperation.OPEN_OR_CREATE_DATABASE, name, path)
        path.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(path.absolutePath, factory, errorHandler)
    }

    override fun deleteDatabase(name: String): Boolean {
        val path = VirtualContextStorage.databasePath(config.dataDir, name)
        recordStorage(StorageOperation.DELETE_DATABASE, name, path)
        return path.delete()
    }

    override fun databaseList(): Array<String> {
        val path = VirtualContextStorage.databasesDir(config.dataDir)
        recordStorage(StorageOperation.DATABASE_LIST, null, path)
        return VirtualContextStorage.listFileNames(path)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val safeName = VirtualContextStorage.sanitizePathSegment(name.ifBlank { "default" })
        recordStorage(
            StorageOperation.SHARED_PREFERENCES,
            name,
            VirtualContextStorage.sharedPrefsPath(config.dataDir, safeName)
        )
        return synchronized(sharedPreferences) {
            sharedPreferences.getOrPut(safeName) {
                FileBackedSharedPreferences(VirtualContextStorage.sharedPrefsPath(config.dataDir, safeName))
            }
        }
    }

    override fun getDir(name: String, mode: Int): File {
        return recordStorage(
            StorageOperation.APP_DIR,
            name,
            VirtualContextStorage.appDir(config.dataDir, name)
        )
    }

    override fun getExternalFilesDir(type: String?): File? {
        return recordStorage(
            StorageOperation.EXTERNAL_FILES_DIR,
            type,
            VirtualContextStorage.externalFilesDir(config.dataDir, type)
        )
    }

    override fun getExternalCacheDir(): File? = recordStorage(
        StorageOperation.EXTERNAL_CACHE_DIR,
        null,
        VirtualContextStorage.externalCacheDir(config.dataDir)
    )

    internal fun resourceSource(): ResourceSource = virtualResourceBundle.source

    internal fun lastStartServiceMappingResult(): StartServiceMappingResult? = lastStartServiceMappingResult

    internal fun lastStopServiceDispatchResult(): VirtualServiceStopDispatchResult? = lastStopServiceDispatchResult

    internal fun lastBroadcastDispatchResult(): VirtualBroadcastResult? = lastBroadcastDispatchResult

    internal fun lastBroadcastReceiverRegistrationResult(): BroadcastReceiverRegistrationResult? =
        lastBroadcastReceiverRegistrationResult

    internal fun lastStorageEvidence(): VirtualStorageEvidence? = lastStorageEvidence

    private fun recordStorage(
        operation: StorageOperation,
        logicalName: String?,
        redirectedFile: File
    ): File {
        lastStorageEvidence = VirtualContextStorage.evidence(
            dataRoot = config.dataDir,
            operation = operation,
            logicalName = logicalName,
            redirectedFile = redirectedFile,
            nativeLibraryDir = config.nativeLibraryDir
        )
        return redirectedFile
    }

    private fun createFallbackApplicationInfo(): ApplicationInfo {
        val baseInfo = runCatching { super.getApplicationInfo() }.getOrNull() ?: ApplicationInfo()
        return ApplicationInfo(baseInfo).apply {
            packageName = config.originPackageName
            sourceDir = config.sourceDir
            publicSourceDir = config.sourceDir
            dataDir = config.dataDir
            nonLocalizedLabel = config.applicationLabel ?: config.originPackageName
            nativeLibraryDir = config.nativeLibraryDir
        }
    }

    private fun remapStartActivityIntent(intent: Intent): Intent? {
        val snapshot = config.packageSnapshot ?: return null
        val request = VirtualIntentResolver(snapshot).resolveActivity(intent) ?: return null
        val manager = VirtualActivityManager(
            context = base,
            proxyActivityRegistry = proxyActivityRegistry,
            hostPackageName = base.packageName,
            activityRecordManager = activityRecordManager
        )
        return manager.createProxyIntent(manager.allocateGuestActivity(request), request.sourceIntent)
    }

    private fun resolveStartServiceIntent(intent: Intent, foreground: Boolean): Intent {
        val snapshot = config.packageSnapshot ?: return intent.also {
            lastStartServiceMappingResult = StartServiceMappingResult.Fallback(
                sourceIntent = intent,
                foreground = foreground,
                reason = "missingPackageSnapshot"
            )
        }
        if (intent.component == null) {
            lastStartServiceMappingResult = StartServiceMappingResult.Fallback(
                sourceIntent = intent,
                foreground = foreground,
                reason = "implicitServiceIntent"
            )
            return intent
        }
        val manager = VirtualServiceManager(hostPackageName = base.packageName)
        val request = if (foreground) {
            manager.resolveStartForegroundService(snapshot, intent)
        } else {
            manager.resolveStartService(snapshot, intent)
        } ?: return intent.also {
            lastStartServiceMappingResult = StartServiceMappingResult.Fallback(
                sourceIntent = intent,
                foreground = foreground,
                reason = "unsupportedServiceIntent"
            )
        }
        val proxyIntent = serviceProxyIntentFactory(manager, request)
        lastStartServiceMappingResult = StartServiceMappingResult.Remapped(
            sourceIntent = intent,
            foreground = foreground,
            startRequest = request,
            proxyIntent = proxyIntent
        )
        return proxyIntent
    }

    private fun resolveStopServiceIntent(intent: Intent): VirtualServiceStopRequest? {
        val snapshot = config.packageSnapshot ?: return null
        val manager = VirtualServiceManager(hostPackageName = base.packageName)
        return manager.resolveStopService(snapshot, intent)
    }

    private fun dispatchBroadcast(intent: Intent): VirtualBroadcastResult {
        return broadcastManager.dispatch(
            instanceId = config.instanceId,
            snapshot = config.packageSnapshot,
            intent = intent,
            virtualContext = this,
            receiverClassLoader = guestClassLoader
        )
    }

    private fun IntentFilter.toVirtualDynamicReceiverFilter(): VirtualDynamicReceiverFilter {
        val actions = (0 until countActions()).mapNotNull { index -> getAction(index) }.toSet()
        val categories = (0 until countCategories()).mapNotNull { index -> getCategory(index) }.toSet()
        val schemes = (0 until countDataSchemes()).mapNotNull { index -> getDataScheme(index) }.toSet()
        return VirtualDynamicReceiverFilter(actions = actions, categories = categories, dataSchemes = schemes)
    }
}
