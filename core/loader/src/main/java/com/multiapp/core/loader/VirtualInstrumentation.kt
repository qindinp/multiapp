@file:Suppress("DEPRECATION")

package com.multiapp.core.loader

import android.app.Activity
import android.app.Fragment
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.hook.NativeHookPolicyResolver
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class VirtualInstrumentation(
    protected val base: Instrumentation
) : Instrumentation() {

    private val hostedRuntimeCache = ConcurrentHashMap<String, HostedActivityRuntime>()

    companion object {
        private const val TAG = "VirtualInstrumentation"
        private const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        private const val EXTRA_VIRTUAL_ACTIVITY_TOKEN = "multiapp.virtualActivityToken"
        private const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.guestActivityClassName"
        private const val EXTRA_HOST_PACKAGE_NAME = "multiapp.hostPackageName"
        private const val EXTRA_ORIGINAL_GUEST_INTENT = "multiapp.originalGuestIntent"
        private const val INSTANCES_DIR = "instances"
        private const val INSTALLS_DIR = "installs"
        private const val INSTANCE_DATA_DIR = "instance_data"
        private const val EVIDENCE_DIR = "hosted_launch_evidence"
        private const val TOKEN_EVIDENCE_PREFIX_LENGTH = 8
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
        base.callActivityOnCreate(activity, icicle)
        writeLifecycleEvidence(activity, "onCreate")
        ensureActivityResultBaselineEvidence(activity)
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) {
        Log.d(TAG, "callActivityOnCreate persistent activity=${activity.javaClass.name}")
        injectHostedActivityContextIfNeeded(activity)
        base.callActivityOnCreate(activity, icicle, persistentState)
        writeLifecycleEvidence(activity, "onCreate")
        ensureActivityResultBaselineEvidence(activity)
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
        base.callActivityOnResume(activity)
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
    }

    override fun callActivityOnNewIntent(activity: Activity, intent: Intent) {
        dispatchHostedNewIntent(activity, intent) { guestIntent ->
            base.callActivityOnNewIntent(activity, guestIntent)
        }
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
        val remapped = remapStartActivityIntent(
            target = target,
            who = who,
            intent = intent,
            api = "execStartActivity:activity-options",
            requestCode = requestCode
        ) ?: intent
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = remapped,
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
        val remapped = remapStartActivityIntent(
            target = target,
            who = who,
            intent = intent,
            api = "execStartActivity:activity",
            requestCode = requestCode
        ) ?: intent
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = remapped,
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
        val remapped = remapStartActivityIntent(
            target = hostedStartActivitySource(who = who),
            who = who,
            intent = intent,
            api = "execStartActivity:string-options",
            requestCode = requestCode
        ) ?: intent
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = remapped,
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
        val remapped = remapStartActivityIntent(
            target = sourceActivity,
            who = who,
            intent = intent,
            api = "execStartActivity:fragment-options",
            requestCode = requestCode
        ) ?: intent
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = remapped,
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
        val remapped = remapStartActivityIntent(
            target = target,
            who = who,
            intent = intent,
            api = "execStartActivity:activity-user",
            requestCode = requestCode
        ) ?: intent
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = remapped,
            requestCode = requestCode,
            options = options,
            user = user
        )
    }

    internal fun remapStartActivityIntent(
        target: Activity?,
        who: Context,
        intent: Intent,
        api: String = "execStartActivity:activity",
        requestCode: Int = -1
    ): Intent? {
        if (intent.component?.className?.contains(".container.ProxyActivity") == true) return null
        val sourceIntent = hostedStartActivitySource(target = target, who = who)?.intent
        val instanceId = sourceIntent?.getStringExtra(EXTRA_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
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

        return runCatching {
            val registry = ProxyActivityRegistry(
                ProxyActivitySlots.classNames(runtime.hostApplication.packageName),
                ProxyActivitySlots.launchModeByClassName(runtime.hostApplication.packageName)
            )
            val manager = VirtualActivityManager(
                context = who,
                proxyActivityRegistry = registry,
                hostPackageName = runtime.hostApplication.packageName
            )
            val record = manager.allocateGuestActivity(request)
            manager.createProxyIntent(record, request.sourceIntent).apply {
                flags = flags or (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
                writeRemapEvidence(
                    filesDir = runtime.hostApplication.filesDir,
                    instanceId = instanceId,
                    guestActivityClassName = request.guestActivityClassName,
                    proxyActivityClassName = record.proxyActivityClassName,
                    api = api,
                    requestCode = requestCode,
                    reason = request.reason,
                    launchMode = record.launchMode
                )
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

    internal fun hostedStartActivitySource(
        target: Activity? = null,
        who: Context
    ): Activity? = target ?: who as? Activity

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
            val injection = HostedActivityContextInjector.inject(
                activity = activity,
                hostContext = runtime.hostApplication,
                hostPackageName = hostPackageName,
                config = config,
                guestApplication = runtime.result.guestApplication,
                guestClassLoader = runtime.result.guestClassLoader!!,
                injectionPhase = injectionPhase,
                allowHostAppCompatFallback = allowHostAppCompatFallback
            )
            writeActivityContextEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                guestActivityClassName = guestActivityClassName,
                injection = injection
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

    private fun createHostedGuestActivity(
        proxyClassName: String,
        intent: Intent
    ): Activity? {
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }
            ?: return null
        val guestActivityClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (!proxyClassName.contains(".container.ProxyActivity")) {
            return null
        }

        return runCatching {
            val runtime = createHostedRuntime(instanceId)
            val result = runtime.result
            require(result.success) {
                "Hosted bootstrap failed before guest Activity substitution: " +
                    (result.summary.failureReason ?: "unknown")
            }
            val guestClassLoader = requireNotNull(result.guestClassLoader) {
                "Hosted bootstrap returned null guestClassLoader"
            }
            val activity = base.newActivity(
                guestClassLoader,
                guestActivityClassName,
                buildGuestActivityIntent(intent, instanceId, guestActivityClassName)
            )
            writeSubstitutionEvidence(
                filesDir = runtime.hostApplication.filesDir,
                instanceId = instanceId,
                proxyClassName = proxyClassName,
                guestActivityClassName = guestActivityClassName
            )
            Log.i(
                TAG,
                "Substituted proxy Activity: proxy=$proxyClassName, guest=$guestActivityClassName, " +
                    "instanceId=$instanceId"
            )
            activity
        }.onFailure { error ->
            Log.e(
                TAG,
                "Unable to substitute hosted guest Activity: proxy=$proxyClassName, " +
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
        val guestIntent = proxyIntent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_GUEST_INTENT)
            ?.let { Intent(it) }
            ?: Intent(proxyIntent)
        return guestIntent.apply {
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME, guestActivityClassName)
            virtualActivityToken?.takeIf { it.isNotBlank() }?.let { token ->
                putExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN, token)
            }
            hostPackageName?.takeIf { it.isNotBlank() }?.let { packageName ->
                putExtra(EXTRA_HOST_PACKAGE_NAME, packageName)
            }
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

    private fun createHostedRuntime(instanceId: String): HostedActivityRuntime {
        hostedRuntimeCache[instanceId]?.let { return it }

        val hostApplication = ActivityThreadCompat.currentApplication()
        val filesDir = hostApplication.filesDir
        val installRecordStore = JsonInstallRecordStore(File(filesDir, INSTALLS_DIR))
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(File(filesDir, INSTANCES_DIR)),
            dataRootBase = File(filesDir, INSTANCE_DATA_DIR),
            installRecordStore = installRecordStore
        )
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = installRecordStore,
            hostContext = hostApplication
        )
        val result = VirtualProcessRuntime.global.bindApplication(instanceId) {
            bootstrap.run(instanceId)
        }
        writeProtectedDiagnosticsEvidence(hostApplication.filesDir, result)
        require(result.success) {
            "Hosted bootstrap failed: " + (result.summary.failureReason ?: "unknown")
        }
        requireNotNull(result.guestClassLoader) { "Hosted bootstrap returned null guestClassLoader" }
        return HostedActivityRuntime(hostApplication, result).also {
            hostedRuntimeCache[instanceId] = it
        }
    }

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
            packageSnapshot = result.packageSnapshot
        )
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

    private fun writeSubstitutionEvidence(
        filesDir: File,
        instanceId: String,
        proxyClassName: String,
        guestActivityClassName: String
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.instrumentation(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_SUBSTITUTED",
                    "stage=ACTIVITY_INSTRUMENTATION",
                    "proxyActivityClassName=$proxyClassName",
                    "guestActivityClassName=$guestActivityClassName"
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
        launchMode: String?
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP",
                    "stage=ACTIVITY_START_REMAP",
                    "api=$api",
                    "hostFallback=false",
                    "requestCode=$requestCode",
                    "resultRequested=${requestCode >= 0}",
                    "activityResultVerdict=${if (requestCode >= 0) "UNSUPPORTED" else "NOT_REQUESTED"}",
                    "activityResultVerdictReason=${if (requestCode >= 0) "HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED" else ""}",
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

    private fun currentFilesDirOrNull(): File? = runCatching {
        ActivityThreadCompat.currentApplication().filesDir
    }.getOrNull()

    private fun writeLifecycleEvidence(activity: Activity, event: String) {
        val activityIdentity = activity.hostedActivityIdentity() ?: return
        val record = VirtualActivityRecordManager.global.resolve(activityIdentity.token)
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
        val pending = VirtualActivityRecordManager.global.consumePendingNewIntent(activityIdentity.token)
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
        val activityIdentity = activity.hostedActivityIdentity() ?: return
        writeActivityResultEvidence(
            activity = activity,
            requestCode = -1,
            resultCode = 0,
            data = null,
            consumedResult = null,
            unsupportedReason = "HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED"
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
        val isSupported = unsupportedReason.isBlank() && consumedResult != null
        val resolvedUnsupportedReason = when {
            isSupported -> ""
            unsupportedReason.isNotBlank() -> unsupportedReason
            else -> "NO_VIRTUAL_RESULT_RECORD"
        }
        writeEvidenceLines(
            instanceId = activityIdentity.instanceId,
            fileName = HostedActivityEvidenceFiles.result(activityIdentity.instanceId),
            lines = listOf(
                "status=${if (isSupported) "ACTIVITY_RESULT_DELIVERED" else "ACTIVITY_RESULT_UNSUPPORTED"}",
                "stage=ACTIVITY_RESULT_BASELINE",
                "instanceId=${activityIdentity.instanceId}",
                "guestActivityClassName=${activityIdentity.guestActivityClassName}",
                "token=${activityIdentity.token.redactTokenForEvidence()}",
                "resultSupported=$isSupported",
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


    private fun markActivityFinishedIfNeeded(activity: Activity) {
        if (!activity.isFinishing) return
        val token = activity.hostedActivityIdentity()?.token ?: return
        VirtualActivityRecordManager.global.finish(token)
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
            val evidenceDir = File(ActivityThreadCompat.currentApplication().filesDir, EVIDENCE_DIR).apply { mkdirs() }.canonicalFile
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

    private fun String.redactTokenForEvidence(): String {
        if (isBlank()) return ""
        if (length <= TOKEN_EVIDENCE_PREFIX_LENGTH) return "<redacted>"
        return take(TOKEN_EVIDENCE_PREFIX_LENGTH) + "...<redacted>"
    }

    private data class HostedActivityIdentity(
        val instanceId: String,
        val guestActivityClassName: String,
        val token: String
    )

    private fun writeActivityContextEvidence(
        filesDir: File,
        instanceId: String,
        guestActivityClassName: String,
        injection: HostedActivityContextInjector.InjectionResult
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.context(instanceId)).writeText(
                HostedActivityContextEvidenceFormatter.format(
                    guestActivityClassName = guestActivityClassName,
                    injection = injection
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write Activity context evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeSubstitutionFailureEvidence(instanceId: String, error: Throwable) {
        runCatching {
            val filesDir = ActivityThreadCompat.currentApplication().filesDir
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.instrumentation(instanceId)).writeText(
                listOf(
                    "status=FAIL",
                    "stage=ACTIVITY_INSTRUMENTATION",
                    "detail=${(error.message ?: error.javaClass.name).replace('\n', ' ')}"
                ).joinToString("\n")
            )
        }.onFailure { writeError ->
            Log.w(TAG, "Unable to write substitution failure evidence for instanceId=$instanceId", writeError)
        }
    }

    private data class HostedActivityRuntime(
        val hostApplication: android.app.Application,
        val result: HostedBootstrapResult
    )
}
