package com.multiapp.core.loader

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class VirtualInstrumentation(
    private val base: Instrumentation
) : Instrumentation() {

    private val hostedRuntimeCache = ConcurrentHashMap<String, HostedActivityRuntime>()

    companion object {
        private const val TAG = "VirtualInstrumentation"
        private const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        private const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.guestActivityClassName"
        private const val EXTRA_HOST_PACKAGE_NAME = "multiapp.hostPackageName"
        private const val EXTRA_ORIGINAL_GUEST_INTENT = "multiapp.originalGuestIntent"
        private const val INSTANCES_DIR = "instances"
        private const val INSTALLS_DIR = "installs"
        private const val INSTANCE_DATA_DIR = "instance_data"
        private const val EVIDENCE_DIR = "hosted_launch_evidence"
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
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: android.os.PersistableBundle?
    ) {
        Log.d(TAG, "callActivityOnCreate persistent activity=${activity.javaClass.name}")
        injectHostedActivityContextIfNeeded(activity)
        base.callActivityOnCreate(activity, icicle, persistentState)
    }

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val remapped = remapStartActivityIntent(target, who, intent) ?: intent
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
        token: IBinder,
        target: Activity?,
        intent: Intent,
        requestCode: Int
    ): ActivityResult? = execStartActivity(who, contextThread, token, target, intent, requestCode, null)

    @Suppress("unused")
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
        target: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        return invokeBaseExecStartActivity(
            who = who,
            contextThread = contextThread,
            token = token,
            target = target,
            intent = intent,
            requestCode = requestCode,
            options = options
        )
    }

    internal fun remapStartActivityIntent(
        target: Activity?,
        who: Context,
        intent: Intent
    ): Intent? {
        if (intent.component?.className?.contains(".container.ProxyActivity") == true) return null
        val instanceId = target?.intent?.getStringExtra(EXTRA_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val runtime = runCatching { createHostedRuntime(instanceId) }
            .onFailure { error ->
                writeRemapFailureEvidence(
                    filesDir = currentFilesDirOrNull(),
                    instanceId = instanceId,
                    reason = "RUNTIME_BOOTSTRAP_FAILED",
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
                intent = intent
            )
            return null
        }

        return runCatching {
            val registry = ProxyActivityRegistry(
                proxyActivityClassNames(runtime.hostApplication.packageName),
                proxyLaunchModeByClassName(runtime.hostApplication.packageName)
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
                error = error
            )
        }.getOrNull()
    }

    private fun injectHostedActivityContextIfNeeded(activity: Activity) {
        val instanceId = activity.intent?.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }
            ?: return
        val guestActivityClassName = activity.intent
            ?.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return

        runCatching {
            val runtime = createHostedRuntime(instanceId)
            val config = buildVirtualContextConfig(runtime)
            val injection = HostedActivityContextInjector.inject(
                activity = activity,
                hostContext = runtime.hostApplication,
                config = config,
                guestApplication = runtime.result.guestApplication,
                guestClassLoader = runtime.result.guestClassLoader!!
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
        guestActivityClassName: String
    ): Intent {
        val guestIntent = proxyIntent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_GUEST_INTENT)
            ?.let { Intent(it) }
            ?: Intent(proxyIntent)
        return guestIntent.apply {
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME, guestActivityClassName)
            proxyIntent.getStringExtra(EXTRA_HOST_PACKAGE_NAME)?.let { hostPackageName ->
                putExtra(EXTRA_HOST_PACKAGE_NAME, hostPackageName)
            }
        }
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
            nativeLibraryDir = result.dataRoot?.let { File(it, "lib") }?.takeIf { it.isDirectory }?.absolutePath,
            classLoader = requireNotNull(result.guestClassLoader),
            applicationLabel = result.packageSnapshot?.applicationLabel ?: result.applicationLabel,
            packageSnapshot = result.packageSnapshot
        )
    }

    private fun proxyActivityClassNames(hostPackageName: String): List<String> = listOf(
        "$hostPackageName.container.ProxyActivity0",
        "$hostPackageName.container.ProxyActivity1",
        "$hostPackageName.container.ProxyActivitySingleTop0",
        "$hostPackageName.container.ProxyActivitySingleTop1",
        "$hostPackageName.container.ProxyActivitySingleTask0",
        "$hostPackageName.container.ProxyActivitySingleTask1"
    )

    private fun proxyLaunchModeByClassName(hostPackageName: String): Map<String, String?> = mapOf(
        "$hostPackageName.container.ProxyActivity0" to null,
        "$hostPackageName.container.ProxyActivity1" to null,
        "$hostPackageName.container.ProxyActivitySingleTop0" to "singleTop",
        "$hostPackageName.container.ProxyActivitySingleTop1" to "singleTop",
        "$hostPackageName.container.ProxyActivitySingleTask0" to "singleTask",
        "$hostPackageName.container.ProxyActivitySingleTask1" to "singleTask"
    )

    private fun invokeBaseExecStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder,
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
        token: IBinder,
        target: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val method = findExecStartActivityMethod(String::class.java, preferOptionsSignature = true)
        method.isAccessible = true
        return invokeExecStartActivityMethod(method, who, contextThread, token, target, intent, requestCode, options)
    }

    private fun invokeExecStartActivityMethod(
        method: java.lang.reflect.Method,
        who: Context,
        contextThread: IBinder,
        token: IBinder,
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
        reason: String,
        launchMode: String?
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP",
                    "stage=ACTIVITY_START_REMAP",
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
        intent: Intent
    ) {
        runCatching {
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP_SKIPPED",
                    "stage=ACTIVITY_START_REMAP",
                    "reason=$reason",
                    "intentAction=${intent.action.orEmpty()}",
                    "intentComponent=${intent.component?.flattenToShortString().orEmpty()}",
                    "intentData=${intent.dataString.orEmpty()}"
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
        error: Throwable
    ) {
        val dir = filesDir ?: return
        runCatching {
            val evidenceDir = File(dir, EVIDENCE_DIR).apply { mkdirs() }
            File(evidenceDir, HostedActivityEvidenceFiles.remap(instanceId)).writeText(
                listOf(
                    "status=GUEST_ACTIVITY_REMAP_FAILED",
                    "stage=ACTIVITY_START_REMAP",
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
