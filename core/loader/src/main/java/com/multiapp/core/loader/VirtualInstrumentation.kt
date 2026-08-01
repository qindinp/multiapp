@file:Suppress("DEPRECATION")

package com.multiapp.core.loader

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Fragment
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.hook.NativeHookPolicyResolver
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.PreassignedProxyActivitySlotStore
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class VirtualInstrumentation(
    protected val base: Instrumentation,
    private val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    private val activityOperations: VirtualActivityOperations =
        ManagerBackedVirtualActivityOperations(activityRecordManager),
    processHostContext: Context? = null
) : Instrumentation() {

    private val hostedRuntimeCache = ConcurrentHashMap<String, HostedActivityRuntime>()
    private val instrumentationFallbackIdentities =
        ConcurrentHashMap<String, VirtualActivityLaunchIdentity>()

    @Volatile
    private var processHostContext: Context? = processHostContext?.stableApplicationContext()

    private sealed class StartActivityIntentDecision {
        data class Launch(val intent: Intent) : StartActivityIntentDecision()
        data class Blocked(val result: ActivityResult?) : StartActivityIntentDecision()
    }

    private sealed class StartActivitiesIntentDecision {
        data class Launch(val intents: Array<Intent>) : StartActivitiesIntentDecision()
        data object Blocked : StartActivitiesIntentDecision()
    }

    private data class UnmappedStartActivityBlock(
        val filesDir: File,
        val instanceId: String,
        val reason: String
    )

    private data class ActivityResultRoute(
        val resultToToken: String,
        val requestCode: Int
    )

    companion object {
        private const val TAG = "VirtualInstrumentation"
        private const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        private const val EXTRA_VIRTUAL_ACTIVITY_TOKEN = "multiapp.virtualActivityToken"
        private const val EXTRA_ORIGIN_PACKAGE_NAME = "multiapp.originPackageName"
        private const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.guestActivityClassName"
        private const val EXTRA_HOST_PACKAGE_NAME = "multiapp.hostPackageName"
        private const val EXTRA_ORIGINAL_GUEST_INTENT = "multiapp.originalGuestIntent"
        private const val EXTRA_GUEST_ACTIVITY_LAUNCH_MODE = "multiapp.guestActivityLaunchMode"
        private const val EXTRA_GUEST_TASK_AFFINITY = "multiapp.guestTaskAffinity"
        private const val EVIDENCE_DIR = "hosted_launch_evidence"

        internal fun shouldBlockForegroundRuntimeBootstrap(
            isMainThread: Boolean,
            hasReusableProcessRuntime: Boolean
        ): Boolean = isMainThread && !hasReusableProcessRuntime

        internal fun hostedTaskDescriptionLabel(originPackageName: String, instanceId: String): String {
            val shortInstanceId = instanceId.take(8).ifBlank { instanceId }
            return "$originPackageName #$shortInstanceId"
        }

        internal fun canReuseHostedRuntimeCache(
            cached: HostedBootstrapResult,
            current: HostedBootstrapResult?
        ): Boolean = current != null && cached.hasSameRuntimeIdentity(current)
    }

    internal fun bindProcessHostContext(context: Context?) {
        if (context == null || processHostContext != null) return
        val candidate = context.stableApplicationContext()
        synchronized(this) {
            if (processHostContext == null) {
                processHostContext = candidate
            }
        }
    }

    internal fun resolveProcessHostContext(
        currentApplicationProvider: () -> Application = ActivityThreadCompat::currentApplication
    ): Context = processHostContext ?: currentApplicationProvider()

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        val applicationContext = hostedApplicationContextForFrameworkApp(context, "newApplication")
        return base.newApplication(cl, className, applicationContext)
    }

    override fun callApplicationOnCreate(app: Application) {
        injectHostedApplicationContextIfNeeded(app)
        base.callApplicationOnCreate(app)
    }

    override fun newActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent
    ): Activity {
        Log.d(TAG, "newActivity className=$className")
        createHostedGuestActivity(className, intent)?.let { return it }
        return base.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        Log.d(TAG, "callActivityOnCreate activity=${activity.javaClass.name}")
        injectHostedActivityContextIfNeeded(activity)
        rememberActivityThreadToken(activity)
        ensureActivityResultBaselineEvidence(activity)
        base.callActivityOnCreate(activity, icicle)
        writeLifecycleEvidence(activity, "onCreate")
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) {
        Log.d(TAG, "callActivityOnCreate persistent activity=${activity.javaClass.name}")
        injectHostedActivityContextIfNeeded(activity)
        rememberActivityThreadToken(activity)
        ensureActivityResultBaselineEvidence(activity)
        base.callActivityOnCreate(activity, icicle, persistentState)
        writeLifecycleEvidence(activity, "onCreate")
    }

    override fun callActivityOnPostCreate(activity: Activity, icicle: Bundle?) {
        injectHostedActivityContextIfNeeded(
            activity = activity,
            injectionPhase = "preOnPostCreate",
            allowHostAppCompatFallback = true
        )
        base.callActivityOnPostCreate(activity, icicle)
    }

    override fun callActivityOnPostCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) {
        injectHostedActivityContextIfNeeded(
            activity = activity,
            injectionPhase = "preOnPostCreate",
            allowHostAppCompatFallback = true
        )
        base.callActivityOnPostCreate(activity, icicle, persistentState)
    }

    override fun callActivityOnStart(activity: Activity) {
        base.callActivityOnStart(activity)
        writeLifecycleEvidence(activity, "onStart")
    }

    override fun callActivityOnResume(activity: Activity) {
        rememberActivityThreadToken(activity)
        base.callActivityOnResume(activity)
        VirtualActivityLaunchAuthority.notifyResumeCompleted(activity)
        writeLifecycleEvidence(activity, "onResume")
    }

    override fun callActivityOnPause(activity: Activity) {
        writeLifecycleEvidence(activity, "onPause")
        base.callActivityOnPause(activity)
    }

    override fun callActivityOnStop(activity: Activity) {
        writeLifecycleEvidence(activity, "onStop")
        base.callActivityOnStop(activity)
    }

    override fun callActivityOnDestroy(activity: Activity) {
        writeLifecycleEvidence(activity, "onDestroy")
        markActivityFinishedIfNeeded(activity)
        base.callActivityOnDestroy(activity)
        forgetActivityThreadToken(activity)
        forgetInstrumentationFallbackCapability(activity)
    }

    override fun callActivityOnNewIntent(activity: Activity, intent: Intent) {
        dispatchHostedNewIntent(activity, intent) { guestIntent ->
            base.callActivityOnNewIntent(activity, guestIntent)
        }
    }

    @Suppress("unused")
    fun callActivityOnActivityResult(
        activity: Activity,
        id: String?,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        val baseCallbackInvoked = invokeBaseCallActivityOnActivityResult(activity, id, requestCode, resultCode, data)
        writeDeliveredActivityResultEvidence(
            activity = activity,
            requestCode = requestCode,
            resultCode = resultCode,
            data = data,
            baseCallbackInvoked = baseCallbackInvoked
        )
    }

    private fun invokeBaseCallActivityOnActivityResult(
        activity: Activity,
        id: String?,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        val parameterTypes = arrayOf(
            Activity::class.java,
            String::class.java,
            Integer.TYPE,
            Integer.TYPE,
            Intent::class.java
        )
        var clazz: Class<*>? = base.javaClass
        while (clazz != null) {
            runCatching {
                val method = clazz.getDeclaredMethod("callActivityOnActivityResult", *parameterTypes)
                method.isAccessible = true
                method.invoke(base, activity, id, requestCode, resultCode, data)
                return true
            }
            clazz = clazz.superclass
        }
        return runCatching {
            val method = Instrumentation::class.java.getDeclaredMethod("callActivityOnActivityResult", *parameterTypes)
            method.isAccessible = true
            method.invoke(base, activity, id, requestCode, resultCode, data)
            true
        }.getOrDefault(false)
    }

    protected fun dispatchHostedNewIntent(
        activity: Activity,
        intent: Intent,
        callBase: (Intent) -> Unit
    ) {
        val guestIntent = buildHostedNewIntent(activity, intent)
        callBase(guestIntent)
        activity.setIntent(guestIntent)
        writeLifecycleEvidence(activity, "onNewIntent")
        writeNewIntentEvidence(activity, guestIntent)
    }


    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val decision = startActivityIntentDecision(
            target = target,
            who = who,
            intent = intent,
            api = "execStartActivity:activity-options",
            requestCode = requestCode
        )
        if (decision is StartActivityIntentDecision.Blocked) return decision.result
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = (decision as StartActivityIntentDecision.Launch).intent,
            requestCode = requestCode,
            options = options
        )
    }

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int
    ): ActivityResult? {
        val decision = startActivityIntentDecision(
            target = target,
            who = who,
            intent = intent,
            api = "execStartActivity:activity",
            requestCode = requestCode
        )
        if (decision is StartActivityIntentDecision.Blocked) return decision.result
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = (decision as StartActivityIntentDecision.Launch).intent,
            requestCode = requestCode,
            options = null
        )
    }

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val decision = startActivityIntentDecision(
            target = hostedStartActivitySource(who = who),
            who = who,
            intent = intent,
            api = "execStartActivity:string-options",
            requestCode = requestCode
        )
        if (decision is StartActivityIntentDecision.Blocked) return decision.result
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = (decision as StartActivityIntentDecision.Launch).intent,
            requestCode = requestCode,
            options = options
        )
    }

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: String?,
        intent: Intent,
        requestCode: Int
    ): ActivityResult? = execStartActivity(who, contextThread, token, target, intent, requestCode, null)

    @Suppress("unused", "DEPRECATION")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Fragment?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val sourceActivity = target?.activity ?: hostedStartActivitySource(who = who)
        val decision = startActivityIntentDecision(
            target = sourceActivity,
            who = who,
            intent = intent,
            api = "execStartActivity:fragment-options",
            requestCode = requestCode
        )
        if (decision is StartActivityIntentDecision.Blocked) return decision.result
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = (decision as StartActivityIntentDecision.Launch).intent,
            requestCode = requestCode,
            options = options
        )
    }

    @Suppress("unused", "DEPRECATION")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Fragment?,
        intent: Intent,
        requestCode: Int
    ): ActivityResult? = execStartActivity(who, contextThread, token, target, intent, requestCode, null)

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
        user: UserHandle?
    ): ActivityResult? {
        val decision = startActivityIntentDecision(
            target = target,
            who = who,
            intent = intent,
            api = "execStartActivity:activity-user",
            requestCode = requestCode
        )
        if (decision is StartActivityIntentDecision.Blocked) return decision.result
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = (decision as StartActivityIntentDecision.Launch).intent,
            requestCode = requestCode,
            options = options,
            user = user
        )
    }

    @Suppress("unused")
    fun execStartActivities(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intents: Array<Intent>,
        options: Bundle?
    ) {
        val decision = startActivitiesIntentDecision(
            target = target,
            who = who,
            intents = intents,
            api = "execStartActivities:activity-options"
        )
        if (decision is StartActivitiesIntentDecision.Blocked) return
        invokeBaseExecStartActivities(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intents = (decision as StartActivitiesIntentDecision.Launch).intents,
            options = options
        )
    }

    @Suppress("unused")
    fun execStartActivitiesAsUser(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intents: Array<Intent>,
        options: Bundle?,
        userId: Int
    ) {
        val decision = startActivitiesIntentDecision(
            target = target,
            who = who,
            intents = intents,
            api = "execStartActivitiesAsUser:activity-options"
        )
        if (decision is StartActivitiesIntentDecision.Blocked) return
        invokeBaseExecStartActivitiesAsUser(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intents = (decision as StartActivitiesIntentDecision.Launch).intents,
            options = options,
            userId = userId
        )
    }

    private fun startActivityIntentDecision(
        target: Activity?,
        who: Context,
        intent: Intent,
        api: String,
        requestCode: Int
    ): StartActivityIntentDecision {
        val remapped = remapStartActivityIntent(
            target = target,
            who = who,
            intent = intent,
            api = api,
            requestCode = requestCode
        )
        if (remapped != null) return StartActivityIntentDecision.Launch(remapped)
        unmappedStartActivityBlock(target, who, intent)?.let { block ->
            writeRemapBlockedEvidence(
                filesDir = block.filesDir,
                instanceId = block.instanceId,
                reason = block.reason,
                api = api,
                requestCode = requestCode,
                intent = intent
            )
            return StartActivityIntentDecision.Blocked(blockedStartActivityResult(requestCode))
        }
        return StartActivityIntentDecision.Launch(intent)
    }

    private fun startActivitiesIntentDecision(
        target: Activity?,
        who: Context,
        intents: Array<Intent>,
        api: String
    ): StartActivitiesIntentDecision {
        val remapped = remapStartActivityIntents(
            target = target,
            who = who,
            intents = intents,
            api = api
        )
        if (remapped != null) return StartActivitiesIntentDecision.Launch(remapped)
        unmappedStartActivitiesBlock(target, who, intents)?.let { block ->
            writeRemapBlockedEvidence(
                filesDir = block.filesDir,
                instanceId = block.instanceId,
                reason = block.reason,
                api = api,
                requestCode = -1,
                intent = intents.firstOrNull() ?: Intent()
            )
            return StartActivitiesIntentDecision.Blocked
        }
        return StartActivitiesIntentDecision.Launch(intents)
    }

    private fun blockedStartActivityResult(requestCode: Int): ActivityResult? =
        if (requestCode >= 0) ActivityResult(Activity.RESULT_CANCELED, null) else null

    private fun unmappedStartActivityBlock(
        target: Activity?,
        who: Context,
        intent: Intent
    ): UnmappedStartActivityBlock? {
        if (intent.component?.className?.contains(".container.ProxyActivity") == true) return null
        val instanceId = hostedStartActivityInstanceId(target = target, who = who, intent = intent) ?: return null
        val runtime = runCatching { createHostedRuntime(instanceId) }.getOrNull() ?: return null
        val snapshot = runtime.result.packageSnapshot ?: return null
        val guestPackages = setOf(snapshot.originPackageName, snapshot.virtualPackageName)
        return if (intentTargetsGuestPackage(intent, guestPackages) || intentLooksGuestPrivate(intent)) {
            UnmappedStartActivityBlock(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "UNRESOLVED_GUEST_ACTIVITY_INTENT"
            )
        } else {
            null
        }
    }

    private fun unmappedStartActivitiesBlock(
        target: Activity?,
        who: Context,
        intents: Array<Intent>
    ): UnmappedStartActivityBlock? {
        val firstIntent = intents.firstOrNull() ?: return null
        if (intents.any { it.component?.className?.contains(".container.ProxyActivity") == true }) return null
        val instanceId = hostedStartActivityInstanceId(target = target, who = who, intent = firstIntent) ?: return null
        val runtime = runCatching { createHostedRuntime(instanceId) }.getOrNull() ?: return null
        val snapshot = runtime.result.packageSnapshot ?: return null
        val guestPackages = setOf(snapshot.originPackageName, snapshot.virtualPackageName)
        return if (intents.any { intentTargetsGuestPackage(it, guestPackages) || intentLooksGuestPrivate(it) }) {
            UnmappedStartActivityBlock(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "UNRESOLVED_GUEST_ACTIVITY_INTENT_BATCH"
            )
        } else {
            null
        }
    }

    internal fun intentLooksGuestPrivate(intent: Intent): Boolean {
        if (runCatching { intent.component }.getOrNull() != null) return false
        if (runCatching { intent.`package` }.getOrNull() != null) return false
        runCatching { intent.selector }.getOrNull()?.let { selector ->
            return intentLooksGuestPrivate(selector)
        }
        val action = runCatching { intent.action }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        if (action.startsWith("android.intent.action.") || action.startsWith("android.settings.")) {
            return false
        }
        val categories = runCatching { intent.categories.orEmpty() }.getOrDefault(emptySet())
        if (Intent.CATEGORY_BROWSABLE in categories) return false
        val dataString = runCatching { intent.dataString }.getOrNull()
        if (!dataString.isNullOrBlank()) return false
        return true
    }

    internal fun intentTargetsGuestPackage(intent: Intent, guestPackages: Set<String>): Boolean {
        val componentPackage = runCatching { intent.component?.packageName }.getOrNull()
        val explicitPackage = runCatching { intent.`package` }.getOrNull()
        val selector = runCatching { intent.selector }.getOrNull()
        return componentPackage in guestPackages ||
            explicitPackage in guestPackages ||
            selector?.let { intentTargetsGuestPackage(it, guestPackages) } == true
    }

    internal fun remapStartActivityIntent(
        target: Activity?,
        who: Context,
        intent: Intent,
        api: String = "execStartActivity:activity",
        requestCode: Int = -1
    ): Intent? {
        if (intent.component?.className?.contains(".container.ProxyActivity") == true) return null
        val instanceId = hostedStartActivityInstanceId(target = target, who = who, intent = intent)
            ?: return null

        val runtime = runCatching { createHostedRuntime(instanceId) }
            .onFailure { error ->
                writeRemapFailureEvidence(
                    filesDir = currentFilesDirOrNull(),
                    instanceId = instanceId,
                    reason = "RUNTIME_BOOTSTRAP_FAILED",
                    api = api,
                    requestCode = requestCode,
                    error = error
                )
            }
            .getOrNull()
            ?: return null

        val snapshot = runtime.result.packageSnapshot
        if (snapshot == null) {
            writeRemapSkippedEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "PACKAGE_SNAPSHOT_MISSING",
                api = api,
                requestCode = requestCode,
                intent = intent
            )
            return null
        }

        val request = VirtualIntentResolver(snapshot).resolveActivity(intent)
        if (request == null) {
            writeRemapSkippedEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "INTENT_NOT_RESOLVED",
                api = api,
                requestCode = requestCode,
                intent = intent
            )
            return null
        }
        val resultRoute = activityResultRouteFor(target, requestCode)
        val launchRequest = request.copy(
            resultToToken = resultRoute?.resultToToken,
            resultRequestCode = resultRoute?.requestCode ?: -1
        )

        return runCatching {
            val processSlot = checkNotNull(runtime.result.processSlot?.takeIf { it.isNotBlank() }) {
                "authoritative runtime is missing processSlot for instance $instanceId"
            }
            val allocationProvider = VirtualActivityLaunchAllocationProviders.requireProvider()
            val allocation = allocationProvider.allocate(
                VirtualActivityLaunchAllocationRequest(
                    instanceId = instanceId,
                    originPackageName = launchRequest.originPackageName,
                    guestActivityClassName = launchRequest.guestActivityClassName,
                    processSlot = processSlot,
                    launchMode = launchRequest.launchMode,
                    taskAffinity = launchRequest.taskAffinity
                )
            )
            check(allocation.accepted) { allocation.reason }
            val identity = checkNotNull(allocation.launchIdentity)
            val proxyClassName = checkNotNull(allocation.proxyActivityClassName)
            val assignmentStore = PreassignedProxyActivitySlotStore(
                launchRequest.proxyActivitySlotKey(),
                proxyClassName
            )
            val registry = ProxyActivityRegistry(
                listOf(proxyClassName),
                ProxyActivitySlots.launchModeByClassName(runtime.hostApplication.packageName),
                assignmentStore
            )
            val manager = VirtualActivityManager(
                context = who,
                proxyActivityRegistry = registry,
                hostPackageName = runtime.hostApplication.packageName,
                activityRecordManager = activityRecordManager
            )
            val activityStateSnapshot = activityRecordManager.snapshotState()
            try {
                val record = manager.allocateGuestActivity(launchRequest)
                val committed = allocationProvider.commit(
                    VirtualActivityLaunchCommitRequest(
                        allocation = allocation,
                        record = record,
                        intentFlags = runCatching { launchRequest.sourceIntent.flags }.getOrDefault(0),
                        dataIntent = launchRequest.sourceIntent.toVirtualIntentSnapshot()
                    )
                )
                check(committed.accepted) { committed.reason }
                val committedRecord = checkNotNull(committed.activity) {
                    "accepted Activity launch commit is missing its Activity record"
                }
                check(committedRecord.token == record.token) {
                    "engine Activity launch token diverged from loader record"
                }
                manager.createProxyIntent(
                    committedRecord,
                    launchRequest.sourceIntent,
                    engineLaunchIdentity = identity
                ).apply {
                    flags = flags or (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
                    writeRemapEvidence(
                        filesDir = runtime.hostApplication.filesDir,
                        instanceId = instanceId,
                        guestActivityClassName = launchRequest.guestActivityClassName,
                        proxyActivityClassName = committedRecord.proxyActivityClassName,
                        api = api,
                        requestCode = requestCode,
                        reason = launchRequest.reason,
                        launchMode = committedRecord.launchMode,
                        resultRoute = resultRoute
                    )
                }
            } catch (error: Throwable) {
                activityRecordManager.restoreState(activityStateSnapshot)
                allocationProvider.release(allocation)
                throw error
            }
        }.onSuccess { proxyIntent ->
            Log.i(TAG, "Remapped guest startActivity to proxy: ${intent.component} -> ${proxyIntent.component}")
        }.onFailure { error ->
            Log.w(TAG, "Unable to remap guest startActivity intent: $intent", error)
            writeRemapFailureEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "PROXY_INTENT_CREATE_FAILED",
                api = api,
                requestCode = requestCode,
                error = error
            )
        }.getOrNull()
    }

    internal fun remapStartActivityIntents(
        target: Activity?,
        who: Context,
        intents: Array<Intent>,
        api: String = "execStartActivities:activity-options"
    ): Array<Intent>? {
        if (intents.isEmpty()) return null
        if (intents.any { it.component?.className?.contains(".container.ProxyActivity") == true }) return null
        val instanceId = hostedStartActivityInstanceId(target = target, who = who, intent = intents.first())
            ?: return null

        val runtime = runCatching { createHostedRuntime(instanceId) }
            .onFailure { error ->
                writeRemapFailureEvidence(
                    filesDir = currentFilesDirOrNull(),
                    instanceId = instanceId,
                    reason = "RUNTIME_BOOTSTRAP_FAILED",
                    api = api,
                    requestCode = -1,
                    error = error
                )
            }
            .getOrNull()
            ?: return null

        val snapshot = runtime.result.packageSnapshot
        if (snapshot == null) {
            writeRemapSkippedEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "PACKAGE_SNAPSHOT_MISSING",
                api = api,
                requestCode = -1,
                intent = intents.first()
            )
            return null
        }

        val resolver = VirtualIntentResolver(snapshot)
        val requests = intents.map { intent -> resolver.resolveActivity(intent) }
        if (requests.any { it == null }) {
            writeRemapSkippedEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "INTENT_BATCH_NOT_RESOLVED",
                api = api,
                requestCode = -1,
                intent = intents.first()
            )
            return null
        }

        return runCatching {
            val processSlot = checkNotNull(runtime.result.processSlot?.takeIf { it.isNotBlank() }) {
                "authoritative runtime is missing processSlot for instance $instanceId"
            }
            val allocationProvider = VirtualActivityLaunchAllocationProviders.requireProvider()
            val activityStateSnapshot = activityRecordManager.snapshotState()
            val allocations = mutableListOf<VirtualActivityLaunchAllocation>()
            val proxyIntents = try {
                requests.filterNotNull().map { request ->
                    val allocation = allocationProvider.allocate(
                        VirtualActivityLaunchAllocationRequest(
                            instanceId = instanceId,
                            originPackageName = request.originPackageName,
                            guestActivityClassName = request.guestActivityClassName,
                            processSlot = processSlot,
                            launchMode = request.launchMode,
                            taskAffinity = request.taskAffinity
                        )
                    )
                    check(allocation.accepted) { allocation.reason }
                    allocations += allocation
                    val identity = checkNotNull(allocation.launchIdentity)
                    val proxyClassName = checkNotNull(allocation.proxyActivityClassName)
                    val manager = VirtualActivityManager(
                        context = who,
                        proxyActivityRegistry = ProxyActivityRegistry(
                            listOf(proxyClassName),
                            ProxyActivitySlots.launchModeByClassName(runtime.hostApplication.packageName),
                            PreassignedProxyActivitySlotStore(request.proxyActivitySlotKey(), proxyClassName)
                        ),
                        hostPackageName = runtime.hostApplication.packageName,
                        activityRecordManager = activityRecordManager
                    )
                    val record = manager.allocateGuestActivity(request)
                    val committed = allocationProvider.commit(
                        VirtualActivityLaunchCommitRequest(
                            allocation = allocation,
                            record = record,
                            intentFlags = runCatching { request.sourceIntent.flags }.getOrDefault(0),
                            dataIntent = request.sourceIntent.toVirtualIntentSnapshot()
                        )
                    )
                    check(committed.accepted) { committed.reason }
                    val committedRecord = checkNotNull(committed.activity) {
                        "accepted Activity launch commit is missing its Activity record"
                    }
                    check(committedRecord.token == record.token) {
                        "engine Activity launch token diverged from loader record"
                    }
                    manager.createProxyIntent(
                        committedRecord,
                        request.sourceIntent,
                        engineLaunchIdentity = identity
                    ).apply {
                        flags = flags or (request.sourceIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("multiapp.activityBatchSize", intents.size)
                    } to (request to committedRecord)
                }
            } catch (error: Throwable) {
                activityRecordManager.restoreState(activityStateSnapshot)
                allocations.asReversed().forEach(allocationProvider::release)
                throw error
            }
            writeRemapBatchEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                api = api,
                guestActivityClassNames = proxyIntents.map { it.second.first.guestActivityClassName },
                proxyActivityClassNames = proxyIntents.map { it.second.second.proxyActivityClassName },
                reasons = proxyIntents.map { it.second.first.reason },
                launchModes = proxyIntents.map { it.second.second.launchMode.orEmpty() }
            )
            proxyIntents.map { it.first }.toTypedArray()
        }.onSuccess { proxyIntents ->
            Log.i(TAG, "Remapped guest startActivities batch: size=${intents.size} proxies=${proxyIntents.size}")
        }.onFailure { error ->
            Log.w(TAG, "Unable to remap guest startActivities intents", error)
            writeRemapFailureEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                reason = "PROXY_INTENT_BATCH_CREATE_FAILED",
                api = api,
                requestCode = -1,
                error = error
            )
        }.getOrNull()
    }

    internal fun hostedStartActivitySource(
        target: Activity? = null,
        who: Context
    ): Activity? = target ?: who as? Activity

    private fun activityResultRouteFor(target: Activity?, requestCode: Int): ActivityResultRoute? {
        if (requestCode < 0) return null
        val sourceIntent = target?.intent ?: return null
        val sourceToken = sourceIntent
            .getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return ActivityResultRoute(
            resultToToken = sourceToken,
            requestCode = requestCode
        )
    }

    internal fun hostedStartActivityInstanceId(
        target: Activity? = null,
        who: Context,
        intent: Intent
    ): String? {
        hostedStartActivitySource(target = target, who = who)
            ?.intent
            ?.getStringExtra(EXTRA_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val contextPackageName = runCatching { who.packageName }.getOrNull()
        VirtualPackageRegistry.global.getByPackageName(contextPackageName)
            ?.let { return it.instanceId }

        val selector = runCatching { intent.selector }.getOrNull()
        val candidatePackages = listOfNotNull(
            runCatching { intent.component?.packageName }.getOrNull(),
            runCatching { intent.`package` }.getOrNull(),
            runCatching { selector?.component?.packageName }.getOrNull(),
            runCatching { selector?.`package` }.getOrNull()
        )
        for (packageName in candidatePackages) {
            VirtualPackageRegistry.global.getByPackageName(packageName)
                ?.let { return it.instanceId }
        }
        return null
    }

    private fun injectHostedActivityContextIfNeeded(
        activity: Activity,
        injectionPhase: String = "preOnCreate",
        allowHostAppCompatFallback: Boolean = false
    ) {
        val instanceId = activity.intent?.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }
            ?: return
        val guestActivityClassName = activity.intent
            ?.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return

        runCatching {
            val runtime = createHostedRuntime(instanceId)
            val hostPackageName = activity.intent?.getStringExtra(EXTRA_HOST_PACKAGE_NAME)?.takeIf { it.isNotBlank() }
            val config = buildVirtualContextConfig(runtime)
            val taskDescriptionLabel = hostedTaskDescriptionLabel(
                originPackageName = config.originPackageName,
                instanceId = instanceId
            )
            applyHostedTaskDescription(activity, taskDescriptionLabel)
            val injection = HostedActivityContextInjector.inject(
                activity = activity,
                hostContext = runtime.hostApplication,
                hostPackageName = hostPackageName,
                config = config,
                guestApplication = runtime.result.guestApplication,
                guestClassLoader = requireNotNull(runtime.result.guestClassLoader) {
                    "Hosted bootstrap returned null guestClassLoader"
                },
                processRuntime = processRuntime,
                activityRecordManager = activityRecordManager,
                injectionPhase = injectionPhase,
                allowHostAppCompatFallback = allowHostAppCompatFallback
            )
            writeActivityContextEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                guestActivityClassName = guestActivityClassName,
                injection = injection,
                taskDescriptionLabel = taskDescriptionLabel
            )
            Log.i(
                TAG,
                "Injected guest Activity context: activity=$guestActivityClassName, " +
                    "context=${injection.contextInjected}, app=${injection.applicationInjected}, " +
                    "package=${injection.packageName}, dataDir=${injection.dataDir}, " +
                    "loadedApkFields=${injection.loadedApkPatchedFields.size}, " +
                    "loadedApkAliases=${injection.loadedApkInstalledAliasCount}, " +
                    "loadedApkSkip=${injection.loadedApkSkippedReason.orEmpty()}"
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to inject hosted Activity context: $guestActivityClassName", error)
            writeSubstitutionFailureEvidence(instanceId, error)
        }
    }

    private fun applyHostedTaskDescription(activity: Activity, label: String) {
        runCatching {
            activity.setTaskDescription(ActivityManager.TaskDescription(label))
        }.onFailure { error ->
            Log.w(TAG, "Unable to set hosted task description: $label", error)
        }
    }

    private fun createHostedGuestActivity(
        proxyClassName: String,
        intent: Intent
    ): Activity? {
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }
            ?: return null
        val guestActivityClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val effectiveProxyClassName = proxyClassName.takeIf {
            it.contains(".container.ProxyActivity")
        } ?: intent.getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME)
            ?.takeIf { it.contains(".container.ProxyActivity") }
            ?: return null

        if (!effectiveProxyClassName.contains(".container.ProxyActivity")) {
            return null
        }

        return runCatching {
            val launchPreflight = validateProxyActivityLaunchBeforeBootstrap(
                proxyClassName = effectiveProxyClassName,
                proxyIntent = intent,
                instanceId = instanceId,
                guestActivityClassName = guestActivityClassName
            )
            require(!launchPreflight.isRejected) {
                "Proxy Activity launch preflight rejected: ${launchPreflight.skippedReason}"
            }
            val runtime = createHostedRuntime(instanceId)
            val result = runtime.result
            require(result.success) {
                "Hosted bootstrap failed before guest Activity substitution: " +
                    (result.summary.failureReason ?: "unknown")
            }
            val guestClassLoader = requireNotNull(result.guestClassLoader) {
                "Hosted bootstrap returned null guestClassLoader"
            }
            check(Thread.currentThread().contextClassLoader === guestClassLoader) {
                "Guest Activity thread context ClassLoader mismatch; process runtime binding is invalid"
            }
            val guestIntent = buildGuestActivityIntent(intent, instanceId, guestActivityClassName)
            val recovery = restoreActivityRecordFromProxyIntentIfMissing(
                proxyClassName = effectiveProxyClassName,
                proxyIntent = intent,
                guestIntent = guestIntent,
                instanceId = instanceId,
                guestActivityClassName = guestActivityClassName,
                fallbackOriginPackageName = result.originPackageName ?: result.packageSnapshot?.originPackageName,
                authorizedPreflight = launchPreflight
            )
            require(!recovery.isRejected) {
                "Proxy Activity record ownership rejected: ${recovery.skippedReason}"
            }
            val activity = base.newActivity(
                guestClassLoader,
                guestActivityClassName,
                guestIntent
            )
            writeSubstitutionEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                proxyClassName = effectiveProxyClassName,
                guestActivityClassName = guestActivityClassName,
                recovery = recovery
            )
            Log.i(
                TAG,
                "Substituted proxy Activity: proxy=$effectiveProxyClassName, guest=$guestActivityClassName, " +
                    "instanceId=$instanceId, activityRecordRecovered=${recovery.activityRecordRecovered}, " +
                    "activityRecordFound=${recovery.activityRecordFound}, recoveryReason=${recovery.skippedReason}"
            )
            activity
        }.onFailure { error ->
            Log.e(
                TAG,
                "Unable to substitute hosted guest Activity: proxy=$effectiveProxyClassName, " +
                    "guest=$guestActivityClassName, instanceId=$instanceId",
                error
            )
            writeSubstitutionFailureEvidence(instanceId, error)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun buildGuestActivityIntent(
        proxyIntent: Intent,
        instanceId: String,
        guestActivityClassName: String,
        virtualActivityToken: String? = proxyIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN),
        hostPackageName: String? = proxyIntent.getStringExtra(EXTRA_HOST_PACKAGE_NAME)
    ): Intent {
        val guestIntent = VirtualActivityIntentStore.find(virtualActivityToken)
            ?: legacyOriginalGuestIntent(proxyIntent)
            ?: Intent(proxyIntent)
        return guestIntent.apply {
            proxyIntent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME)
                ?.takeIf { it.isNotBlank() }
                ?.let { originPackageName ->
                    component = ComponentName(originPackageName, guestActivityClassName)
                }
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME, guestActivityClassName)
            virtualActivityToken?.takeIf { it.isNotBlank() }?.let { token ->
                putExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN, token)
            }
            hostPackageName?.takeIf { it.isNotBlank() }?.let { packageName ->
                putExtra(EXTRA_HOST_PACKAGE_NAME, packageName)
            }
            copyEngineLaunchIdentity(proxyIntent, this)
        }
    }

    private fun buildHostedNewIntent(activity: Activity, proxyIntent: Intent): Intent {
        val currentIntent = activity.intent
        val instanceId = proxyIntent.getStringExtra(EXTRA_INSTANCE_ID)
            ?: currentIntent?.getStringExtra(EXTRA_INSTANCE_ID)
            ?: return proxyIntent
        val guestActivityClassName = proxyIntent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?: currentIntent?.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?: return proxyIntent
        val virtualActivityToken = proxyIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
            ?: currentIntent?.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
        val hostPackageName = proxyIntent.getStringExtra(EXTRA_HOST_PACKAGE_NAME)
            ?: currentIntent?.getStringExtra(EXTRA_HOST_PACKAGE_NAME)
        return buildGuestActivityIntent(
            proxyIntent = proxyIntent,
            instanceId = instanceId,
            guestActivityClassName = guestActivityClassName,
            virtualActivityToken = virtualActivityToken,
            hostPackageName = hostPackageName
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyOriginalGuestIntent(proxyIntent: Intent): Intent? =
        runCatching {
            proxyIntent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_GUEST_INTENT)
                ?.let { Intent(it) }
        }.onFailure { error ->
            Log.w(TAG, "Unable to read legacy original guest Activity intent extra", error)
        }.getOrNull()

    private fun createHostedRuntime(instanceId: String): HostedActivityRuntime {
        val reusableResult = processRuntime.reusableResult(instanceId)
        hostedRuntimeCache[instanceId]?.let { cached ->
            if (canReuseHostedRuntimeCache(cached.result, reusableResult)) {
                return cached
            }
            hostedRuntimeCache.remove(instanceId, cached)
        }

        val hostApplication = resolveProcessHostContext()
        if (shouldBlockForegroundRuntimeBootstrap(isMainThread(), reusableResult != null)) {
            writeForegroundBootstrapBlockedEvidence(
                filesDir = hostApplication.filesDir,
                instanceId = instanceId,
                threadName = Thread.currentThread().name
            )
            throw ForegroundBootstrapBlockedException(instanceId)
        }
        if (reusableResult != null) {
            writeProtectedDiagnosticsEvidence(hostApplication.filesDir, reusableResult)
            require(reusableResult.success) {
                "Hosted bootstrap failed: " + (reusableResult.summary.failureReason ?: "unknown")
            }
            requireNotNull(reusableResult.guestClassLoader) { "Hosted bootstrap returned null guestClassLoader" }
            return HostedActivityRuntime(hostApplication, reusableResult).also {
                hostedRuntimeCache[instanceId] = it
            }
        }

        val filesDir = hostApplication.filesDir
        writeCacheMissFailClosedEvidence(filesDir, instanceId)
        throw IllegalStateException(
            "GUEST_RUNTIME_CACHE_MISS_FAIL_CLOSED: no authoritative process runtime binding " +
                "for instance $instanceId; host must prewarm via ContainerActivity"
        )
    }

    private fun writeCacheMissFailClosedEvidence(filesDir: File, instanceId: String) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.instrumentation(instanceId)).writeText(
                listOf(
                    "status=FAIL",
                    "stage=ACTIVITY_INSTRUMENTATION",
                    "detail=GUEST_RUNTIME_CACHE_MISS_FAIL_CLOSED",
                    "runtimeCacheHit=false",
                    "processRuntimeReusable=false",
                    "authoritativeRuntimeBinding=false",
                    "failClosed=true",
                    "reason=NO_AUTHORITY_TO_BOOTSTRAP_GUEST_RUNTIME_FROM_LOADER"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write cache miss fail-closed evidence for instanceId=$instanceId", error)
        }
    }

    private fun isMainThread(): Boolean =
        Looper.myLooper() == Looper.getMainLooper()

    private fun buildVirtualContextConfig(runtime: HostedActivityRuntime): VirtualContextConfig {
        val result = runtime.result
        return VirtualContextConfig(
            instanceId = result.instanceId,
            originPackageName = requireNotNull(result.originPackageName) {
                "originPackageName is required for hosted Activity context"
            },
            virtualPackageName = requireNotNull(result.virtualPackageName) {
                "virtualPackageName is required for hosted Activity context"
            },
            dataDir = requireNotNull(result.dataRoot) {
                "dataRoot is required for hosted Activity context"
            },
            sourceDir = requireNotNull(result.originApkPath) {
                "originApkPath is required for hosted Activity context"
            },
            nativeLibraryDir = result.packageSnapshot?.nativeLibraryDir
                ?: result.dataRoot?.let { NativeLibraryPaths.resolveAndExtract(null, it).nativeLibraryDir },
            classLoader = requireNotNull(result.guestClassLoader),
            applicationLabel = result.packageSnapshot?.applicationLabel ?: result.applicationLabel,
            packageSnapshot = result.packageSnapshot,
            splitSourceDirs = result.packageSnapshot?.splitSourceDirs.orEmpty(),
            splitPublicSourceDirs = result.packageSnapshot?.splitPublicSourceDirs.orEmpty(),
            splitNames = result.packageSnapshot?.splitNames.orEmpty(),
            isolatedSplits = result.packageSnapshot?.isolatedSplits ?: false,
            processSlot = result.processSlot
        )
    }

    private fun injectHostedApplicationContextIfNeeded(application: Application) {
        val currentBase = runCatching { application.baseContext }.getOrNull() ?: return
        val frameworkBase = if (currentBase is VirtualContextWrapper) {
            currentBase.baseContext
        } else {
            currentBase
        }
        val preparedContext = hostedApplicationContextForFrameworkApp(
            context = frameworkBase,
            phase = "callApplicationOnCreate"
        )
        if (preparedContext === currentBase) return
        replaceContextWrapperBase(application, preparedContext)
    }

    private fun hostedApplicationContextForFrameworkApp(
        context: Context,
        phase: String
    ): Context {
        val packageNames = listOfNotNull(
            runCatching { context.packageName }.getOrNull(),
            runCatching { context.applicationInfo?.packageName }.getOrNull()
        ).filter { it.isNotBlank() }.distinct()
        val snapshot = packageNames.firstNotNullOfOrNull { packageName ->
            VirtualPackageRegistry.global.getByPackageName(packageName)
        } ?: return context
        val hostContext = hostContextForFrameworkApplication(context, snapshot) ?: run {
            Log.w(
                TAG,
                "Unable to virtualize framework Application context at $phase: host context unavailable, " +
                    "observedPackages=${packageNames.joinToString(",")}, instanceId=${snapshot.instanceId}"
            )
            return context
        }
        val hostPackageName = runCatching { hostContext.packageName }.getOrNull().orEmpty()
        val contextPatch = FrameworkApplicationContextCompat.prepare(context, hostPackageName)
        Log.i(
            TAG,
            "Prepared framework Application context at $phase: instanceId=${snapshot.instanceId}, " +
                "origin=${snapshot.originPackageName}, virtual=${snapshot.virtualPackageName}, " +
                "host=$hostPackageName, target=${contextPatch.targetClassName}, " +
                "wrapperDepth=${contextPatch.wrapperDepth}, cycleDetected=${contextPatch.cycleDetected}, " +
                "binderIdentityReady=${contextPatch.binderIdentityReady}, " +
                "patchedFields=${contextPatch.patchedFields.joinToString(",")}, " +
                "skippedFields=${contextPatch.skippedFieldReasons.joinToString(",")}"
        )
        // Application.attachBaseContext() must receive the framework ContextImpl.
        // Hot-fix frameworks and AndroidX inspect mPackageInfo/mOuterContext directly.
        // Binder-facing identity is patched in place above; wrapping is reserved for
        // Activity and component surfaces where the framework Context ABI is not replaced.
        return context
    }

    private fun hostContextForFrameworkApplication(
        context: Context,
        snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot
    ): Context? {
        processHostContext?.let { capturedHostContext ->
            if (!snapshot.matchesPackageName(
                    runCatching { capturedHostContext.packageName }.getOrNull()
                )
            ) {
                return capturedHostContext
            }
        }
        val currentApplication = runCatching { ActivityThreadCompat.currentApplication() }.getOrNull()
        if (currentApplication != null && !snapshot.matchesPackageName(
                runCatching { currentApplication.packageName }.getOrNull()
            )
        ) {
            return currentApplication
        }
        val applicationContext = runCatching { context.applicationContext }.getOrNull()
        if (applicationContext != null && !snapshot.matchesPackageName(
                runCatching { applicationContext.packageName }.getOrNull()
            )
        ) {
            return applicationContext
        }
        return null
    }

    private fun replaceContextWrapperBase(target: ContextWrapper, context: Context): Boolean {
        return runCatching {
            val field = findContextWrapperBaseField()
                ?: error("ContextWrapper.mBase not found")
            field.set(target, context)
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to replace Application base context: ${target.javaClass.name}", error)
        }.getOrDefault(false)
    }

    private fun findContextWrapperBaseField(): java.lang.reflect.Field? {
        var current: Class<*>? = ContextWrapper::class.java
        while (current != null) {
            runCatching {
                return current.getDeclaredField("mBase").apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun writeProtectedDiagnosticsEvidence(filesDir: File, result: HostedBootstrapResult) {
        runCatching {
            ProtectedDiagnosticsEvidenceWriter.writeIfAllowed(
                filesDir = filesDir,
                result = result,
                policy = NativeHookPolicyResolver.resolveProtectedRuntimePolicy()
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write protected diagnostics evidence for instanceId=${result.instanceId}", error)
        }
    }

    private fun invokeBaseExecStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val method = findExecStartActivityMethod(Activity::class.java, preferOptionsSignature = true)
        method.isAccessible = true
        return invokeExecStartActivityMethod(method, who, contextThread, token, target, intent, requestCode, options)
    }

    private fun invokeBaseExecStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
        user: UserHandle?
    ): ActivityResult? {
        val method = findExecStartActivityWithUserMethod(Activity::class.java)
        method.isAccessible = true
        return method.invoke(base, who, contextThread, token, target, intent, requestCode, options, user) as? ActivityResult
    }

    private fun invokeBaseExecStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val method = findExecStartActivityMethod(String::class.java, preferOptionsSignature = true)
        method.isAccessible = true
        return invokeExecStartActivityMethod(method, who, contextThread, token, target, intent, requestCode, options)
    }

    @Suppress("DEPRECATION")
    private fun invokeBaseExecStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Fragment?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val method = findExecStartActivityMethod(Fragment::class.java, preferOptionsSignature = true)
        method.isAccessible = true
        return invokeExecStartActivityMethod(method, who, contextThread, token, target, intent, requestCode, options)
    }

    private fun invokeExecStartActivityMethod(
        method: java.lang.reflect.Method,
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Any?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        return if (method.parameterTypes.size == 7) {
            method.invoke(base, who, contextThread, token, target, intent, requestCode, options) as? ActivityResult
        } else {
            method.invoke(base, who, contextThread, token, target, intent, requestCode) as? ActivityResult
        }
    }

    private fun invokeBaseExecStartActivities(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intents: Array<Intent>,
        options: Bundle?
    ) {
        val method = findExecStartActivitiesMethod()
        method.isAccessible = true
        method.invoke(base, who, contextThread, token, target, intents, options)
    }

    private fun invokeBaseExecStartActivitiesAsUser(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intents: Array<Intent>,
        options: Bundle?,
        userId: Int
    ) {
        val method = findExecStartActivitiesAsUserMethod()
        method.isAccessible = true
        method.invoke(base, who, contextThread, token, target, intents, options, userId)
    }

    private fun findExecStartActivityMethod(
        targetType: Class<*>,
        preferOptionsSignature: Boolean
    ): java.lang.reflect.Method {
        val withOptions = arrayOf(
            arrayOf(
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                targetType,
                Intent::class.java,
                Integer.TYPE,
                Bundle::class.java
            )
        )
        val withoutOptions = arrayOf(
            arrayOf(
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                targetType,
                Intent::class.java,
                Integer.TYPE
            )
        )
        val candidates = if (preferOptionsSignature) withOptions + withoutOptions else withoutOptions + withOptions
        var clazz: Class<*>? = base.javaClass
        while (clazz != null) {
            for (parameterTypes in candidates) {
                runCatching { return clazz.getDeclaredMethod("execStartActivity", *parameterTypes) }
            }
            clazz = clazz.superclass
        }
        for (parameterTypes in candidates) {
            runCatching { return Instrumentation::class.java.getDeclaredMethod("execStartActivity", *parameterTypes) }
        }
        throw NoSuchMethodException(
            "android.app.Instrumentation.execStartActivity target=${targetType.name} " +
                "candidates=${candidates.joinToString { it.joinToString(prefix = "(", postfix = ")") { type -> type.name } }}"
        )
    }

    private fun findExecStartActivitiesMethod(): java.lang.reflect.Method {
        val parameterTypes = arrayOf(
            Context::class.java,
            IBinder::class.java,
            IBinder::class.java,
            Activity::class.java,
            Array<Intent>::class.java,
            Bundle::class.java
        )
        var clazz: Class<*>? = base.javaClass
        while (clazz != null) {
            runCatching { return clazz.getDeclaredMethod("execStartActivities", *parameterTypes) }
            clazz = clazz.superclass
        }
        runCatching { return Instrumentation::class.java.getDeclaredMethod("execStartActivities", *parameterTypes) }
        throw NoSuchMethodException(
            "android.app.Instrumentation.execStartActivities " +
                "candidate=${parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }}"
        )
    }

    @Suppress("DEPRECATION")
    internal fun validateProxyActivityLaunchBeforeBootstrap(
        proxyClassName: String,
        proxyIntent: Intent,
        instanceId: String,
        guestActivityClassName: String,
        fallbackOriginPackageName: String? = null,
        activityRecordManager: VirtualActivityRecordManager = this.activityRecordManager
    ): ActivityRecordRecoveryResult {
        val token = proxyIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return ActivityRecordRecoveryResult(
                skippedReason = "TOKEN_MISSING",
                isRejected = true
            )
        val originPackageName = proxyIntent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackOriginPackageName?.takeIf { it.isNotBlank() }

        val existing = activityRecordManager.resolve(token)
        existing?.let {
            if (!existing.matchesRecordOwner(
                    instanceId = instanceId,
                    guestActivityClassName = guestActivityClassName,
                    proxyActivityClassName = proxyClassName,
                    originPackageName = originPackageName
                )
            ) {
                return ActivityRecordRecoveryResult(
                    record = existing,
                    skippedReason = "TOKEN_OWNER_MISMATCH",
                    isRejected = true
                )
            }
        }

        val launchIdentity = proxyIntent.toVirtualActivityLaunchIdentity(proxyClassName)
            ?: return ActivityRecordRecoveryResult(
                skippedReason = "ENGINE_LAUNCH_IDENTITY_MISSING",
                isRejected = true
            )
        if (originPackageName == null) {
            return ActivityRecordRecoveryResult(
                skippedReason = "ORIGIN_PACKAGE_MISSING",
                isRejected = true
            )
        }
        if (
            existing != null &&
            ActivityThreadLaunchRecordPatcher.consumePrepatchedLaunchIdentity(token, launchIdentity)
        ) {
            return ActivityRecordRecoveryResult(
                record = existing,
                activityRecordFound = true,
                skippedReason = "ACTIVITY_THREAD_RECORD_PATCHER_AUTHORIZED"
            )
        }
        instrumentationFallbackIdentities[token]?.let { ownedIdentity ->
            if (ownedIdentity != launchIdentity) {
                return ActivityRecordRecoveryResult(
                    record = existing,
                    activityRecordFound = existing != null,
                    skippedReason = "INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER_MISMATCH",
                    isRejected = true
                )
            }
            return ActivityRecordRecoveryResult(
                record = existing,
                activityRecordFound = existing != null,
                skippedReason = if (existing != null) "ALREADY_REGISTERED" else "ENGINE_LAUNCH_AUTHORIZED"
            )
        }
        val authorization = VirtualActivityLaunchAuthority.authorize(launchIdentity)
        if (!authorization.accepted) {
            return ActivityRecordRecoveryResult(
                skippedReason = "ENGINE_LAUNCH_REJECTED:${authorization.reason}",
                isRejected = true
            )
        }
        val previousOwner = instrumentationFallbackIdentities.putIfAbsent(
            token,
            launchIdentity
        )
        if (previousOwner != null && previousOwner != launchIdentity) {
            return ActivityRecordRecoveryResult(
                record = existing,
                activityRecordFound = existing != null,
                skippedReason = "INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER_RACE",
                isRejected = true
            )
        }
        return ActivityRecordRecoveryResult(
            record = existing,
            activityRecordFound = existing != null,
            skippedReason = if (existing != null) "ALREADY_REGISTERED" else "ENGINE_LAUNCH_AUTHORIZED"
        )
    }

    @Suppress("DEPRECATION")
    internal fun restoreActivityRecordFromProxyIntentIfMissing(
        proxyClassName: String,
        proxyIntent: Intent,
        guestIntent: Intent,
        instanceId: String,
        guestActivityClassName: String,
        fallbackOriginPackageName: String? = null,
        activityRecordManager: VirtualActivityRecordManager = this.activityRecordManager,
        authorizedPreflight: ActivityRecordRecoveryResult? = null
    ): ActivityRecordRecoveryResult {
        val token = proxyIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: guestIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
                ?.takeIf { it.isNotBlank() }
            ?: return ActivityRecordRecoveryResult(
                skippedReason = "TOKEN_MISSING",
                isRejected = true
            )
        val launchPreflight = authorizedPreflight ?: validateProxyActivityLaunchBeforeBootstrap(
            proxyClassName = proxyClassName,
            proxyIntent = proxyIntent,
            instanceId = instanceId,
            guestActivityClassName = guestActivityClassName,
            fallbackOriginPackageName = fallbackOriginPackageName,
            activityRecordManager = activityRecordManager
        )
        if (launchPreflight.isRejected || launchPreflight.activityRecordFound) return launchPreflight

        val originPackageName = proxyIntent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackOriginPackageName?.takeIf { it.isNotBlank() }
        if (originPackageName.isNullOrBlank()) return ActivityRecordRecoveryResult(
            skippedReason = "ORIGIN_PACKAGE_MISSING",
            isRejected = true
        )

        return runCatching {
            val originalGuestIntent = VirtualActivityIntentStore.find(token)
                ?: legacyOriginalGuestIntent(proxyIntent)
            val sourceIntent = originalGuestIntent ?: guestIntent
            val resultToToken = proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_RESULT_TO_TOKEN)
                ?.takeIf { it.isNotBlank() }
            val record = VirtualActivityRecord(
                token = token,
                instanceId = instanceId,
                originPackageName = originPackageName,
                guestActivityClassName = guestActivityClassName,
                proxyActivityClassName = proxyClassName,
                launchMode = proxyIntent.getStringExtra(EXTRA_GUEST_ACTIVITY_LAUNCH_MODE)?.takeIf { it.isNotBlank() },
                taskAffinity = proxyIntent.getStringExtra(EXTRA_GUEST_TASK_AFFINITY)?.takeIf { it.isNotBlank() },
                resultToToken = resultToToken,
                resultRequestCode = if (resultToToken == null) {
                    -1
                } else {
                    proxyIntent.getIntExtra(VirtualActivityManager.EXTRA_RESULT_REQUEST_CODE, -1)
                },
                state = VirtualActivityState.RESUMED
            )
            activityRecordManager.conflictingProxyOwner(record)?.let { owner ->
                return ActivityRecordRecoveryResult(
                    record = owner,
                    activityRecordFound = false,
                    skippedReason = "PROXY_SLOT_ALREADY_OWNED",
                    isRejected = true
                )
            }
            val launched = activityRecordManager.registerLaunch(
                record = record,
                intentFlags = sourceIntent.safeFlags(),
                dataIntent = sourceIntent.toVirtualIntentSnapshot()
            ).activity
            ActivityRecordRecoveryResult(
                record = launched,
                activityRecordFound = false,
                activityRecordRecovered = true
            )
        }.getOrElse { error ->
            Log.w(
                TAG,
                "Unable to recover Activity record from proxy intent: " +
                    "token=${EvidenceSanitizer.redactTokenForEvidence(token)}",
                error
            )
            ActivityRecordRecoveryResult(
                skippedReason = "RECOVERY_FAILED:${error.javaClass.name}",
                isRejected = true
            )
        }
    }

    private fun VirtualActivityRecord.matchesRecordOwner(
        instanceId: String,
        originPackageName: String?,
        guestActivityClassName: String,
        proxyActivityClassName: String
    ): Boolean =
        this.instanceId == instanceId &&
            this.originPackageName == originPackageName &&
            this.guestActivityClassName == guestActivityClassName &&
            this.proxyActivityClassName == proxyActivityClassName

    internal data class ActivityRecordRecoveryResult(
        val record: VirtualActivityRecord? = null,
        val activityRecordFound: Boolean = false,
        val activityRecordRecovered: Boolean = false,
        val skippedReason: String = "",
        val isRejected: Boolean = false
    )

    private fun findExecStartActivityWithUserMethod(targetType: Class<*>): java.lang.reflect.Method {
        val parameterTypes = arrayOf(
            Context::class.java,
            IBinder::class.java,
            IBinder::class.java,
            targetType,
            Intent::class.java,
            Integer.TYPE,
            Bundle::class.java,
            UserHandle::class.java
        )
        var clazz: Class<*>? = base.javaClass
        while (clazz != null) {
            runCatching { return clazz.getDeclaredMethod("execStartActivity", *parameterTypes) }
            clazz = clazz.superclass
        }
        runCatching { return Instrumentation::class.java.getDeclaredMethod("execStartActivity", *parameterTypes) }
        throw NoSuchMethodException(
            "android.app.Instrumentation.execStartActivity target=${targetType.name} " +
                "with user candidate=${parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }}"
        )
    }

    private fun findExecStartActivitiesAsUserMethod(): java.lang.reflect.Method {
        val parameterTypes = arrayOf(
            Context::class.java,
            IBinder::class.java,
            IBinder::class.java,
            Activity::class.java,
            Array<Intent>::class.java,
            Bundle::class.java,
            Integer.TYPE
        )
        var clazz: Class<*>? = base.javaClass
        while (clazz != null) {
            runCatching { return clazz.getDeclaredMethod("execStartActivitiesAsUser", *parameterTypes) }
            clazz = clazz.superclass
        }
        runCatching { return Instrumentation::class.java.getDeclaredMethod("execStartActivitiesAsUser", *parameterTypes) }
        throw NoSuchMethodException(
            "android.app.Instrumentation.execStartActivitiesAsUser " +
                "candidate=${parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }}"
        )
    }

    private fun writeSubstitutionEvidence(
        filesDir: File,
        instanceId: String,
        proxyClassName: String,
        guestActivityClassName: String,
        recovery: ActivityRecordRecoveryResult = ActivityRecordRecoveryResult()
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.instrumentation(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_SUBSTITUTED",
                    "stage=ACTIVITY_INSTRUMENTATION",
                    "proxyActivityClassName=$proxyClassName",
                    "guestActivityClassName=$guestActivityClassName",
                    "originPackageName=${recovery.record?.originPackageName.orEmpty()}",
                    "token=${recovery.record?.token?.redactTokenForEvidence().orEmpty()}",
                    "activityRecordFound=${recovery.activityRecordFound}",
                    "activityRecordRecovered=${recovery.activityRecordRecovered}",
                    "activityRecordRecoveryReason=${recovery.skippedReason}",
                    "activityRecordTaskId=${recovery.record?.taskId ?: 0}",
                    "activityRecordIntentFlags=${recovery.record?.intentFlags ?: 0}",
                    "activityRecordLaunchMode=${recovery.record?.launchMode.orEmpty()}",
                    "activityRecordTaskAffinity=${recovery.record?.taskAffinity.orEmpty()}",
                    "taskDescriptionLabel=${recovery.record?.let { hostedTaskDescriptionLabel(it.originPackageName, it.instanceId) }.orEmpty()}"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write substitution evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeRemapEvidence(
        filesDir: File,
        instanceId: String,
        guestActivityClassName: String,
        proxyActivityClassName: String,
        api: String,
        requestCode: Int,
        reason: String,
        launchMode: String?,
        resultRoute: ActivityResultRoute? = null
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            val resultRequested = requestCode >= 0
            val resultRouteRecorded = resultRoute != null
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP",
                    "stage=ACTIVITY_START_REMAP",
                    "api=$api",
                    "hostFallback=false",
                    "requestCode=$requestCode",
                    "resultRequested=$resultRequested",
                    "activityResultRouteRecorded=$resultRouteRecorded",
                    "activityResultToToken=${resultRoute?.resultToToken?.redactTokenForEvidence().orEmpty()}",
                    "activityResultRecordRequestCode=${resultRoute?.requestCode ?: -1}",
                    "activityResultVerdict=${when {
                        !resultRequested -> "NOT_REQUESTED"
                        resultRouteRecorded -> "PARTIAL"
                        else -> "UNSUPPORTED"
                    }}",
                    "activityResultVerdictReason=${when {
                        !resultRequested -> ""
                        resultRouteRecorded -> "HOST_PROXY_RESULT_ROUTE_RECORDED_DELIVERY_PENDING"
                        else -> "HOST_PROXY_RESULT_SOURCE_TOKEN_MISSING"
                    }}",
                    "guestActivityClassName=$guestActivityClassName",
                    "proxyActivityClassName=$proxyActivityClassName",
                    "reason=$reason",
                    "launchMode=${launchMode.orEmpty()}"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write startActivity remap evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeRemapBatchEvidence(
        filesDir: File,
        instanceId: String,
        api: String,
        guestActivityClassNames: List<String>,
        proxyActivityClassNames: List<String>,
        reasons: List<String>,
        launchModes: List<String>
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP",
                    "stage=ACTIVITY_START_REMAP",
                    "api=$api",
                    "hostFallback=false",
                    "requestCode=-1",
                    "resultRequested=false",
                    "activityResultVerdict=NOT_REQUESTED",
                    "activityResultVerdictReason=",
                    "batchSize=${guestActivityClassNames.size}",
                    "guestActivityClassNames=${guestActivityClassNames.joinToString(",")}",
                    "proxyActivityClassNames=${proxyActivityClassNames.joinToString(",")}",
                    "reasons=${reasons.joinToString(",")}",
                    "launchModes=${launchModes.joinToString(",")}"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write startActivities remap evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeRemapSkippedEvidence(
        filesDir: File,
        instanceId: String,
        reason: String,
        api: String,
        requestCode: Int,
        intent: Intent
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP_SKIPPED",
                    "stage=ACTIVITY_START_REMAP",
                    "api=$api",
                    "hostFallback=true",
                    "requestCode=$requestCode",
                    "resultRequested=${requestCode >= 0}",
                    "activityResultVerdict=${if (requestCode >= 0) "UNSUPPORTED" else "NOT_REQUESTED"}",
                    "activityResultVerdictReason=${if (requestCode >= 0) "HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED" else ""}",
                    "reason=$reason",
                    "intentAction=${intent.action.orEmpty()}",
                    "intentComponent=${intent.component?.flattenToShortString().orEmpty()}",
                    "intentData=${intent.dataString?.redactUriForEvidence().orEmpty()}"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write skipped remap evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeRemapBlockedEvidence(
        filesDir: File,
        instanceId: String,
        reason: String,
        api: String,
        requestCode: Int,
        intent: Intent
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP_BLOCKED",
                    "stage=ACTIVITY_START_REMAP",
                    "api=$api",
                    "hostFallback=false",
                    "requestCode=$requestCode",
                    "resultRequested=${requestCode >= 0}",
                    "activityResultVerdict=${if (requestCode >= 0) "CANCELED" else "NOT_REQUESTED"}",
                    "activityResultVerdictReason=${if (requestCode >= 0) "UNRESOLVED_GUEST_ACTIVITY_INTENT" else ""}",
                    "reason=$reason",
                    "intentAction=${intent.action.orEmpty()}",
                    "intentComponent=${intent.component?.flattenToShortString().orEmpty()}",
                    "intentPackage=${intent.`package`.orEmpty()}",
                    "intentData=${intent.dataString?.redactUriForEvidence().orEmpty()}"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write blocked remap evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeRemapFailureEvidence(
        filesDir: File?,
        instanceId: String,
        reason: String,
        api: String,
        requestCode: Int,
        error: Throwable
    ) {
        val dir = filesDir ?: return
        runCatching {
            val evidenceDir = File(dir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP_FAILED",
                    "stage=ACTIVITY_START_REMAP",
                    "api=$api",
                    "hostFallback=true",
                    "requestCode=$requestCode",
                    "resultRequested=${requestCode >= 0}",
                    "activityResultVerdict=${if (requestCode >= 0) "UNSUPPORTED" else "NOT_REQUESTED"}",
                    "activityResultVerdictReason=${if (requestCode >= 0) "HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED" else ""}",
                    "reason=$reason",
                    "errorClass=${error.javaClass.name}",
                    "errorMessage=${(error.message ?: "").replace('\n', ' ')}"
                ).joinToString("\n")
            )
        }.onFailure { writeError ->
            Log.w(TAG, "Unable to write failed remap evidence for instanceId=$instanceId", writeError)
        }
    }

    private fun writeForegroundBootstrapBlockedEvidence(
        filesDir: File,
        instanceId: String,
        threadName: String
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.instrumentation(instanceId)).writeText(
                listOf(
                    "status=FAIL",
                    "stage=ACTIVITY_INSTRUMENTATION",
                    "detail=FOREGROUND_BOOTSTRAP_BLOCKED",
                    "runtimeCacheHit=false",
                    "processRuntimeReusable=false",
                    "foregroundBootstrapAllowed=false",
                    "threadName=$threadName",
                    "reason=RUNTIME_CACHE_MISS_ON_MAIN_THREAD"
                ).joinToString("\n")
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write foreground bootstrap blocked evidence for instanceId=$instanceId", error)
        }
    }

    private fun currentFilesDirOrNull(): File? = runCatching {
        resolveProcessHostContext().filesDir
    }.getOrNull()

    private fun writeLifecycleEvidence(activity: Activity, event: String) {
        val activityIdentity = activity.hostedActivityIdentity() ?: return
        val record = activityRecordManager.resolve(activityIdentity.token)
        val reason = when {
            activityIdentity.token.isBlank() -> "TOKEN_MISSING"
            record == null -> "ACTIVITY_RECORD_MISSING"
            else -> ""
        }
        writeEvidenceLines(
            instanceId = activityIdentity.instanceId,
            fileName = HostedActivityEvidenceFiles.lifecycle(activityIdentity.instanceId),
            lines = listOf(
                "status=${if (reason.isBlank()) "GUEST_ACTIVITY_LIFECYCLE" else "GUEST_ACTIVITY_LIFECYCLE_UNLINKED"}",
                "stage=ACTIVITY_LIFECYCLE",
                "event=$event",
                "instanceId=${activityIdentity.instanceId}",
                "guestActivityClassName=${activityIdentity.guestActivityClassName}",
                "token=${activityIdentity.token.redactTokenForEvidence()}",
                "activityRecordFound=${record != null}",
                "activityRecordState=${record?.state?.name.orEmpty()}",
                "isFinishing=${activity.isFinishing}",
                "taskId=${activity.taskId}",
                "reason=$reason"
            ),
            append = true
        )
    }

    private fun writeNewIntentEvidence(activity: Activity, intent: Intent) {
        val activityIdentity = activity.hostedActivityIdentity() ?: return
        val pending = activityOperations.consumePendingNewIntent(
            instanceId = activityIdentity.instanceId,
            token = activityIdentity.token
        )
        val reason = when {
            activityIdentity.token.isBlank() -> "TOKEN_MISSING"
            pending == null -> "NO_PENDING_NEW_INTENT_RECORD"
            else -> ""
        }
        writeEvidenceLines(
            instanceId = activityIdentity.instanceId,
            fileName = HostedActivityEvidenceFiles.newIntent(activityIdentity.instanceId),
            lines = listOf(
                "status=${if (reason.isBlank()) "GUEST_ACTIVITY_ON_NEW_INTENT" else "GUEST_ACTIVITY_ON_NEW_INTENT_UNLINKED"}",
                "stage=ACTIVITY_NEW_INTENT",
                "instanceId=${activityIdentity.instanceId}",
                "guestActivityClassName=${activityIdentity.guestActivityClassName}",
                "token=${activityIdentity.token.redactTokenForEvidence()}",
                "pendingNewIntentConsumed=${pending != null}",
                "pendingAction=${pending?.dataIntent?.action.orEmpty()}",
                "pendingDataUri=${pending?.dataIntent?.dataUri?.redactUriForEvidence().orEmpty()}",
                "pendingFlags=${pending?.intentFlags ?: 0}",
                "sourceToken=${pending?.sourceToken?.redactTokenForEvidence().orEmpty()}",
                "intentAction=${intent.action.orEmpty()}",
                "intentDataUri=${intent.dataString?.redactUriForEvidence().orEmpty()}",
                "reason=$reason"
            )
        )
    }

    private fun ensureActivityResultBaselineEvidence(activity: Activity) {
        writeActivityResultEvidence(
            activity = activity,
            requestCode = -1,
            resultCode = 0,
            data = null,
            consumedResult = null,
            unsupportedReason = ""
        )
    }

    private fun writeActivityResultEvidence(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        consumedResult: VirtualActivityResult?,
        unsupportedReason: String
    ) {
        val activityIdentity = activity.hostedActivityIdentity() ?: return
        val resultRequested = requestCode >= 0
        val resultDelivered = consumedResult != null && unsupportedReason.isBlank()
        val resolvedUnsupportedReason = when {
            !resultRequested || resultDelivered -> ""
            unsupportedReason.isNotBlank() -> unsupportedReason
            else -> "NO_VIRTUAL_RESULT_RECORD"
        }
        val status = when {
            !resultRequested -> "ACTIVITY_RESULT_NOT_REQUESTED"
            resultDelivered -> "ACTIVITY_RESULT_DELIVERED"
            else -> "ACTIVITY_RESULT_UNSUPPORTED"
        }
        writeEvidenceLines(
            instanceId = activityIdentity.instanceId,
            fileName = HostedActivityEvidenceFiles.result(activityIdentity.instanceId),
            lines = listOf(
                "status=$status",
                "stage=ACTIVITY_RESULT_BASELINE",
                "instanceId=${activityIdentity.instanceId}",
                "guestActivityClassName=${activityIdentity.guestActivityClassName}",
                "token=${activityIdentity.token.redactTokenForEvidence()}",
                "resultPipelineInstalled=true",
                "resultRequested=$resultRequested",
                "resultDelivered=$resultDelivered",
                "resultSupported=${!resultRequested || resultDelivered}",
                "requestCode=$requestCode",
                "resultCode=$resultCode",
                "virtualResultConsumed=${consumedResult != null}",
                "virtualResultCode=${consumedResult?.resultCode ?: 0}",
                "dataAction=${data?.action.orEmpty()}",
                "dataUri=${data?.dataString?.redactUriForEvidence().orEmpty()}",
                "unsupportedReason=$resolvedUnsupportedReason"
            )
        )
    }

    private fun writeDeliveredActivityResultEvidence(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        baseCallbackInvoked: Boolean
    ) {
        val activityIdentity = activity.hostedActivityIdentity() ?: return
        val consumedResult = activityOperations.consumeActivityResult(
            instanceId = activityIdentity.instanceId,
            token = activityIdentity.token
        )
        val resultCodeMatches = consumedResult?.resultCode == resultCode
        val delivered = consumedResult != null && resultCodeMatches && baseCallbackInvoked
        val reason = when {
            delivered -> ""
            consumedResult == null -> "NO_VIRTUAL_RESULT_RECORD"
            !baseCallbackInvoked -> "BASE_ACTIVITY_RESULT_CALLBACK_NOT_INVOKED"
            !resultCodeMatches -> "VIRTUAL_RESULT_CODE_MISMATCH"
            else -> "UNKNOWN_ACTIVITY_RESULT_DELIVERY_STATE"
        }
        writeEvidenceLines(
            instanceId = activityIdentity.instanceId,
            fileName = HostedActivityEvidenceFiles.result(activityIdentity.instanceId),
            lines = listOf(
                "status=${if (delivered) "ACTIVITY_RESULT_DELIVERED" else "ACTIVITY_RESULT_PARTIAL"}",
                "stage=ACTIVITY_RESULT_DELIVERY",
                "instanceId=${activityIdentity.instanceId}",
                "guestActivityClassName=${activityIdentity.guestActivityClassName}",
                "token=${activityIdentity.token.redactTokenForEvidence()}",
                "requestCode=$requestCode",
                "resultCode=$resultCode",
                "baseCallbackInvoked=$baseCallbackInvoked",
                "virtualResultConsumed=${consumedResult != null}",
                "virtualResultCode=${consumedResult?.resultCode ?: 0}",
                "resultCodeMatches=$resultCodeMatches",
                "dataAction=${data?.action.orEmpty()}",
                "dataUri=${data?.dataString?.redactUriForEvidence().orEmpty()}",
                "virtualDataAction=${consumedResult?.dataIntent?.action.orEmpty()}",
                "virtualDataUri=${consumedResult?.dataIntent?.dataUri.orEmpty()}",
                "reason=$reason"
            ),
            append = true
        )
    }

    private fun markActivityFinishedIfNeeded(activity: Activity) {
        if (!activity.isFinishing) return
        val identity = activity.hostedActivityIdentity() ?: return
        activityOperations.finishActivity(identity.instanceId, identity.token)
    }

    private fun rememberActivityThreadToken(activity: Activity) {
        val identity = activity.hostedActivityIdentity() ?: return
        val activityThreadToken = activity.readPrivateField<IBinder>("mToken") ?: return
        VirtualActivityResultFrameworkBridge.remember(
            activityThreadToken,
            HostedFrameworkActivityIdentity(
                instanceId = identity.instanceId,
                virtualActivityToken = identity.token,
                guestActivityClassName = identity.guestActivityClassName,
                hostFilesDir = resolveProcessHostContext().filesDir
            )
        )
    }

    private fun forgetActivityThreadToken(activity: Activity) {
        VirtualActivityResultFrameworkBridge.forget(activity.readPrivateField<IBinder>("mToken"))
    }

    private fun forgetInstrumentationFallbackCapability(activity: Activity) {
        val token = activity.intent?.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return
        instrumentationFallbackIdentities.remove(token)
    }

    private inline fun <reified T> Activity.readPrivateField(fieldName: String): T? =
        runCatching {
            findActivityField(javaClass, fieldName)
                ?.apply { isAccessible = true }
                ?.get(this) as? T
        }.getOrNull()

    private fun findActivityField(type: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(fieldName) }
            current = current.superclass
        }
        return runCatching { Activity::class.java.getDeclaredField(fieldName) }.getOrNull()
    }

    private fun Activity.hostedActivityIdentity(): HostedActivityIdentity? {
        val currentIntent = intent ?: return null
        val instanceId = currentIntent.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() } ?: return null
        val guestActivityClassName = currentIntent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val token = currentIntent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return HostedActivityIdentity(
            instanceId = instanceId,
            guestActivityClassName = guestActivityClassName,
            token = token
        )
    }

    private fun writeEvidenceLines(
        instanceId: String,
        fileName: String,
        lines: List<String>,
        append: Boolean = false
    ) {
        runCatching {
            val evidenceDir = File(resolveProcessHostContext().filesDir, EVIDENCE_DIR).apply { mkdirs() }.canonicalFile
            val file = File(evidenceDir, fileName).canonicalFile
            require(file.parentFile == evidenceDir) { "Activity evidence path escapes evidence dir" }
            val text = lines.joinToString("\n") { sanitizeEvidenceLine(it) }
            if (append && file.isFile) {
                file.appendText("\n---\n$text")
            } else {
                file.writeText(text)
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to write Activity lifecycle evidence for instanceId=$instanceId", error)
        }
    }


    private fun sanitizeEvidenceLine(value: String): String = EvidenceSanitizer.sanitizeEvidenceLine(value)

    private fun String.redactUriForEvidence(): String = EvidenceSanitizer.redactUriForEvidence(this)

    private fun Intent.safeFlags(): Int = runCatching { flags }.getOrDefault(0)

    private fun copyEngineLaunchIdentity(source: Intent, target: Intent) {
        if (source.hasExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH)) {
            target.putExtra(
                VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH,
                source.getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, 0L)
            )
        }
        listOf(
            VirtualActivityManager.EXTRA_ENGINE_SESSION_ID,
            VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT,
            VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME,
            VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY
        ).forEach { key ->
            source.getStringExtra(key)?.let { value -> target.putExtra(key, value) }
        }
    }

    private fun Intent.toVirtualIntentSnapshot(): VirtualIntentSnapshot {
        val sourceExtras = runCatching { extras }.getOrNull()
        val extrasSnapshot = sourceExtras
            ?.keySet()
            ?.associateWith { "<present>" }
            .orEmpty()
        return VirtualIntentSnapshot(
            flags = safeFlags(),
            action = runCatching { action }.getOrNull(),
            dataUri = runCatching { dataString?.redactUriForEvidence() }.getOrNull(),
            categories = runCatching { categories.orEmpty().toSet() }.getOrDefault(emptySet()),
            extras = extrasSnapshot
        )
    }

    private fun VirtualIntentSnapshot.toIntent(): Intent =
        Intent(action).also { intent ->
            dataUri?.takeIf { it.isNotBlank() }?.let { value ->
                runCatching { intent.data = Uri.parse(value) }
            }
            categories.forEach { category ->
                runCatching { intent.addCategory(category) }
            }
            intent.flags = flags
        }

    private fun String.redactTokenForEvidence(): String = EvidenceSanitizer.redactTokenForEvidence(this)

    private data class HostedActivityIdentity(
        val instanceId: String,
        val guestActivityClassName: String,
        val token: String
    )

    private fun writeActivityContextEvidence(
        filesDir: File,
        instanceId: String,
        guestActivityClassName: String,
        injection: HostedActivityContextInjector.InjectionResult,
        taskDescriptionLabel: String
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.context(instanceId)).writeText(
                HostedActivityContextEvidenceFormatter.format(
                    guestActivityClassName = guestActivityClassName,
                    injection = injection,
                    taskDescriptionLabel = taskDescriptionLabel
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write Activity context evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeSubstitutionFailureEvidence(instanceId: String, error: Throwable) {
        runCatching {
            val filesDir = resolveProcessHostContext().filesDir
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.instrumentation(instanceId)).writeText(
                listOf(
                    "status=FAIL",
                    "stage=ACTIVITY_INSTRUMENTATION",
                    "detail=${(error.message ?: error.javaClass.name).replace('\n', ' ')}",
                    "errorClass=${error.javaClass.name}",
                    "errorMessage=${(error.message ?: "").replace('\n', ' ')}"
                ).joinToString("\n")
            )
        }.onFailure { writeError ->
            Log.w(TAG, "Unable to write substitution failure evidence for instanceId=$instanceId", writeError)
        }
    }

    private data class HostedActivityRuntime(
        val hostApplication: Context,
        val result: HostedBootstrapResult
    )

    private class ForegroundBootstrapBlockedException(instanceId: String) : IllegalStateException(
        "RUNTIME_CACHE_MISS_ON_MAIN_THREAD: proxy Activity for $instanceId must be launched after ContainerActivity prewarm"
    )
}

private fun Context.stableApplicationContext(): Context {
    val applicationContext = runCatching { applicationContext }.getOrNull() ?: this
    val hostPackageName = runCatching { packageName }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return applicationContext
    return runCatching {
        applicationContext.createPackageContext(hostPackageName, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull() ?: applicationContext
}
