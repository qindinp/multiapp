package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.view.Display
import android.view.LayoutInflater
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executor

/**
 * Wraps the host Context and overrides identity fields so the guest app
 * sees its own package name, data directories, source path, and ClassLoader.
 *
 * This is the core of the hosted container model: the guest code running
 * inside ContainerActivity gets a Context that reports the guest's identity,
 * not the host MultiApp identity.
 */
open class VirtualContextWrapper(
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
    },
    private val amsDispatcher: VirtualAmsComponentDispatcher? = null
) : ContextWrapper(base) {

    sealed class StartActivityMappingResult {
        abstract val sourceIntent: Intent

        data class Remapped(
            override val sourceIntent: Intent,
            val proxyIntent: Intent
        ) : StartActivityMappingResult()

        data class Blocked(
            override val sourceIntent: Intent,
            val reason: String
        ) : StartActivityMappingResult()
    }

    sealed class StartServiceMappingResult {
        abstract val sourceIntent: Intent
        abstract val foreground: Boolean

        data class Remapped(
            override val sourceIntent: Intent,
            override val foreground: Boolean,
            val startRequest: VirtualServiceStartRequest,
            val proxyIntent: Intent
        ) : StartServiceMappingResult()

        data class Blocked(
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

    private var lastStartActivityMappingResult: StartActivityMappingResult? = null

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
            ProxyActivitySlots.classNames(base.packageName),
            ProxyActivitySlots.launchModeByClassName(base.packageName)
        )
    }

    private var guestThemeResId: Int = config.packageSnapshot?.themeId ?: 0
    private var guestTheme: Resources.Theme? = null

    private val defaultAmsDispatcher: VirtualAmsComponentDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        DefaultVirtualAmsComponentDispatcher(
            hostContext = base,
            hostPackageName = base.packageName,
            packageSnapshot = config.packageSnapshot,
            instanceId = config.instanceId,
            activityRecordManager = activityRecordManager,
            proxyActivityRegistry = proxyActivityRegistry,
            servicePackageRegistry = servicePackageRegistry,
            serviceRuntime = serviceRuntime,
            broadcastManager = broadcastManager,
            serviceProxyIntentFactory = serviceProxyIntentFactory
        )
    }

    override fun getPackageName(): String = config.virtualPackageName

    override fun getApplicationContext(): Context = this

    override fun getBaseContext(): Context = this

    override fun createContextForSplit(splitName: String?): Context = this

    override fun createPackageContext(packageName: String?, flags: Int): Context = this

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context = this

    override fun createDisplayContext(display: Display): Context = this

    override fun createDeviceProtectedStorageContext(): Context = this

    override fun createAttributionContext(attributionTag: String?): Context = this

    override fun createWindowContext(display: Display, type: Int, options: Bundle?): Context = this

    override fun createWindowContext(type: Int, options: Bundle?): Context = this

    override fun createDeviceContext(deviceId: Int): Context = this

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            return (base.getSystemService(name) as? LayoutInflater)?.cloneInContext(this)
        }
        return base.getSystemService(name)
    }

    override fun getPackageManager(): PackageManager = virtualPackageManager ?: base.packageManager

    override fun startActivity(intent: Intent) {
        when (val result = componentDispatcher().resolveStartActivityIntent(intent)) {
            is StartActivityMappingResult.Remapped -> {
                lastStartActivityMappingResult = result
                base.startActivity(result.proxyIntent)
            }
            is StartActivityMappingResult.Blocked -> lastStartActivityMappingResult = result
        }
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        when (val result = componentDispatcher().resolveStartActivityIntent(intent)) {
            is StartActivityMappingResult.Remapped -> {
                lastStartActivityMappingResult = result
                base.startActivity(result.proxyIntent, options)
            }
            is StartActivityMappingResult.Blocked -> lastStartActivityMappingResult = result
        }
    }

    override fun startActivities(intents: Array<Intent>) {
        startActivities(intents, null)
    }

    override fun startActivities(intents: Array<Intent>, options: Bundle?) {
        if (intents.isEmpty()) {
            lastStartActivityMappingResult = StartActivityMappingResult.Blocked(
                sourceIntent = Intent(),
                reason = "emptyActivityLaunchUnsupported"
            )
            return
        }
        val results = componentDispatcher().resolveStartActivityIntents(intents.toList())
        val blocked = results.filterIsInstance<StartActivityMappingResult.Blocked>().firstOrNull()
        if (blocked != null) {
            lastStartActivityMappingResult = blocked
            return
        }
        val remapped = results.mapNotNull { it as? StartActivityMappingResult.Remapped }
        if (remapped.size != intents.size) {
            lastStartActivityMappingResult = StartActivityMappingResult.Blocked(
                sourceIntent = intents.first(),
                reason = "incompleteActivityBatchMapping"
            )
            return
        }
        lastStartActivityMappingResult = remapped.last()
        base.startActivities(remapped.map { it.proxyIntent }.toTypedArray(), options)
    }

    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int
    ) {
        blockIntentSenderLaunch(fillInIntent)
    }

    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?
    ) {
        blockIntentSenderLaunch(fillInIntent)
    }

    private fun blockIntentSenderLaunch(fillInIntent: Intent?) {
        lastStartActivityMappingResult = StartActivityMappingResult.Blocked(
            sourceIntent = fillInIntent ?: Intent(),
            reason = "intentSenderLaunchUnsupported"
        )
    }

    override fun startService(service: Intent): ComponentName? {
        val result = componentDispatcher().resolveStartServiceIntent(service, foreground = false)
        lastStartServiceMappingResult = result
        return when (result) {
            is StartServiceMappingResult.Remapped -> base.startService(result.proxyIntent)
            is StartServiceMappingResult.Blocked -> null
        }
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        val result = componentDispatcher().resolveStartServiceIntent(service, foreground = true)
        lastStartServiceMappingResult = result
        return when (result) {
            is StartServiceMappingResult.Remapped -> base.startForegroundService(result.proxyIntent)
            is StartServiceMappingResult.Blocked -> null
        }
    }

    override fun stopService(service: Intent): Boolean {
        val result = componentDispatcher().dispatchStopService(service) ?: return false.also {
            lastStopServiceDispatchResult = null
        }
        lastStopServiceDispatchResult = result
        return result is VirtualServiceStopDispatchResult.ServiceStopped
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        return dispatchBindServiceIntent(service, api = "bindService:int")
    }

    override fun bindService(
        service: Intent,
        flags: Int,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindServiceIntent(service, api = "bindService:executor")
    }

    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
        user: UserHandle
    ): Boolean {
        return dispatchBindServiceIntent(service, api = "bindServiceAsUser:int")
    }

    override fun bindIsolatedService(
        service: Intent,
        flags: Int,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindServiceIntent(service, api = "bindIsolatedService:int")
    }

    override fun unbindService(conn: ServiceConnection) = Unit

    override fun updateServiceGroup(conn: ServiceConnection, group: Int, importance: Int) = Unit

    protected fun dispatchBindServiceIntent(service: Intent, api: String): Boolean {
        val result = componentDispatcher().resolveStartServiceIntent(service, foreground = false)
        lastStartServiceMappingResult = result
        recordBindServiceEvidence(api, result)
        return false
    }

    override fun sendBroadcast(intent: Intent) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendBroadcastAsUser(intent: Intent, user: UserHandle?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendBroadcastAsUser(intent: Intent, user: UserHandle?, receiverPermission: String?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        options: Bundle?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        receiverAppOp: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        initialCode: Int,
        receiverPermission: String?,
        receiverAppOp: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialData: String?,
        initialExtras: Bundle?,
        options: Bundle?
    ) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle?,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendStickyBroadcast(intent: Intent) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendStickyBroadcast(intent: Intent, options: Bundle?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendStickyBroadcastAsUser(intent: Intent, user: UserHandle?) {
        dispatchBroadcastIntent(intent)
    }

    override fun sendStickyOrderedBroadcast(
        intent: Intent,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        val result = dispatchBroadcastIntent(intent)
        recordStickyOrderedBroadcastEvidence("sendStickyOrderedBroadcast", result)
    }

    override fun sendStickyOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        val result = dispatchBroadcastIntent(intent)
        recordStickyOrderedBroadcastEvidence("sendStickyOrderedBroadcastAsUser", result)
    }

    override fun removeStickyBroadcast(intent: Intent) {
        dispatchBroadcastIntent(intent)
    }

    override fun removeStickyBroadcastAsUser(intent: Intent, user: UserHandle?) {
        dispatchBroadcastIntent(intent)
    }

    protected fun dispatchBroadcastIntent(intent: Intent): VirtualBroadcastResult {
        val result = componentDispatcher().dispatchBroadcast(
            intent = intent,
            virtualContext = this,
            receiverClassLoader = guestClassLoader
        )
        lastBroadcastDispatchResult = result
        return result
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        if (receiver == null || filter == null) {
            lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
                receiver = receiver,
                reason = "missingReceiverOrFilter"
            )
            recordRegisterReceiverEvidence(registered = false, reason = "missingReceiverOrFilter")
            return null
        }
        val virtualFilter = filter.toVirtualDynamicReceiverFilter()
        dynamicReceiverRegistry.register(config.instanceId, receiver, virtualFilter)
        lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Registered(
            receiver = receiver,
            instanceId = config.instanceId,
            filter = virtualFilter
        )
        recordRegisterReceiverEvidence(registered = true, reason = "")
        return null
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? {
        return registerReceiver(receiver, filter)
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? {
        if (broadcastPermission == null && scheduler == null) {
            return registerReceiver(receiver, filter)
        }
        lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
            receiver = receiver,
            reason = "permissionOrSchedulerUnsupported"
        )
        recordRegisterReceiverEvidence(registered = false, reason = "permissionOrSchedulerUnsupported")
        return null
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?,
        flags: Int
    ): Intent? {
        return if (broadcastPermission == null && scheduler == null) {
            registerReceiver(receiver, filter)
        } else {
            lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
                receiver = receiver,
                reason = "permissionOrSchedulerUnsupported"
            )
            recordRegisterReceiverEvidence(registered = false, reason = "permissionOrSchedulerUnsupported")
            null
        }
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        if (dynamicReceiverRegistry.unregister(receiver) == null) {
            lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Fallback(
                receiver = receiver,
                reason = "receiverNotRegistered"
            )
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

    override fun setTheme(resid: Int) {
        guestThemeResId = resid
        guestTheme = null
    }

    override fun getTheme(): Resources.Theme {
        val existing = guestTheme
        if (existing != null) return existing
        return guestResources.newTheme().apply {
            runCatching { base.theme }.getOrNull()?.let { baseTheme ->
                runCatching { setTo(baseTheme) }
            }
            if (guestThemeResId != 0) {
                applyStyle(guestThemeResId, true)
            }
            guestTheme = this
        }
    }

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

    internal fun lastStartActivityMappingResult(): StartActivityMappingResult? = lastStartActivityMappingResult

    internal fun lastStartServiceMappingResult(): StartServiceMappingResult? = lastStartServiceMappingResult

    internal fun lastStopServiceDispatchResult(): VirtualServiceStopDispatchResult? = lastStopServiceDispatchResult

    internal fun lastBroadcastDispatchResult(): VirtualBroadcastResult? = lastBroadcastDispatchResult

    internal fun lastBroadcastReceiverRegistrationResult(): BroadcastReceiverRegistrationResult? =
        lastBroadcastReceiverRegistrationResult

    internal fun lastStorageEvidence(): VirtualStorageEvidence? = lastStorageEvidence

    private fun recordRegisterReceiverEvidence(registered: Boolean, reason: String) {
        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER,
                instanceId = config.instanceId,
                originPackageName = config.originPackageName,
                virtualPackageName = config.virtualPackageName,
                api = "registerReceiver",
                status = if (registered) "DYNAMIC_RECEIVER_REGISTERED" else "DYNAMIC_RECEIVER_REJECTED",
                hostFallback = false,
                fields = linkedMapOf(
                    "registered" to registered,
                    "reason" to reason
                )
            )
        )
    }

    private fun recordStickyOrderedBroadcastEvidence(api: String, result: VirtualBroadcastResult) {
        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.STICKY_ORDERED_BROADCAST,
                instanceId = config.instanceId,
                originPackageName = config.originPackageName,
                virtualPackageName = config.virtualPackageName,
                api = api,
                status = "STICKY_ORDERED_INTERCEPTED",
                hostFallback = false,
                fields = linkedMapOf(
                    "dispatchStatus" to result.record.result.name,
                    "receiverClassName" to result.record.receiverClassName.orEmpty(),
                    "action" to result.record.action.orEmpty()
                )
            )
        )
    }

    protected fun recordBindServiceEvidence(api: String, result: StartServiceMappingResult) {
        val serviceResolved = result is StartServiceMappingResult.Remapped
        val reason = when (result) {
            is StartServiceMappingResult.Remapped -> result.startRequest.reason
            is StartServiceMappingResult.Blocked -> result.reason
        }
        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                instanceId = config.instanceId,
                originPackageName = config.originPackageName,
                virtualPackageName = config.virtualPackageName,
                api = api,
                status = "BIND_BLOCKED",
                hostFallback = false,
                fields = linkedMapOf(
                    "returnValue" to false,
                    "serviceResolved" to serviceResolved,
                    "reason" to reason
                )
            )
        )
    }

    private fun componentDispatcher(): VirtualAmsComponentDispatcher = amsDispatcher ?: defaultAmsDispatcher

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


    private fun IntentFilter.toVirtualDynamicReceiverFilter(): VirtualDynamicReceiverFilter {
        val actions = (0 until countActions()).mapNotNull { index -> getAction(index) }.toSet()
        val categories = (0 until countCategories()).mapNotNull { index -> getCategory(index) }.toSet()
        val schemes = (0 until countDataSchemes()).mapNotNull { index -> getDataScheme(index) }.toSet()
        return VirtualDynamicReceiverFilter(actions = actions, categories = categories, dataSchemes = schemes)
    }
}
