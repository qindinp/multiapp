package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import android.os.Looper
import com.multiapp.core.model.virtual.VirtualContextConfig

class ApplicationStage(
    private val hostContext: Context?,
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String?,
    private val guestApplicationCreator: GuestApplicationCreator = ReflectiveGuestApplicationCreator(),
    private val runtimePublisher: (String, HostedBootstrapResult) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val guestClassLoader = input.guestClassLoader ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Guest ClassLoader is required before Application creation"
        )
        val originApkPath = input.originApkPath ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before Application creation"
        )
        val instance = input.instance ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Instance is required before Application creation"
        )
        val packageSnapshot = input.packageSnapshot ?: return nonTerminalFailure(
            input = input,
            startMs = startMs,
            message = "Package snapshot is required before Application creation"
        )

        val resolvedAppClassName = applicationClassNameResolver(guestClassLoader, originApkPath)
        val appClassName = resolvedAppClassName ?: Application::class.java.name
        val appClassSource = if (resolvedAppClassName == null) "DEFAULT_APPLICATION" else "MANIFEST"
        if (hostContext == null && resolvedAppClassName == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.APPLICATION,
                    message = "Default Application creation skipped because hostContext is missing",
                    evidence = listOf(
                        BootstrapEvidence("applicationClass", appClassName),
                        BootstrapEvidence("applicationClassSource", appClassSource),
                        BootstrapEvidence("applicationCreator", "SKIPPED"),
                        BootstrapEvidence("applicationCreatorSkippedReason", "HOST_CONTEXT_MISSING")
                    )
                ).copy(durationMs = clock() - startMs),
                terminalFailure = false
            )
        }

        val applicationThread = currentApplicationThreadEvidence()

        var runtimePublishedBeforeOnCreate = false
        fun progress(status: String, detail: String, extra: Map<String, String> = emptyMap()) {
            HostedRuntimeProgressEvidenceWriter.write(
                context = hostContext,
                instanceId = input.instanceId,
                component = "application-progress",
                fields = linkedMapOf(
                    "status" to status,
                    "stage" to "APPLICATION",
                    "detail" to detail,
                    "applicationClass" to appClassName,
                    "applicationClassSource" to appClassSource,
                    "originPackageName" to instance.originPackageName,
                    "virtualPackageName" to instance.virtualPackageName,
                    "threadName" to Thread.currentThread().name,
                    "elapsedMs" to (System.currentTimeMillis() - startMs).toString()
                ) + extra
            )
        }

        return runCatching {
            val context = hostContext
                ?: throw IllegalStateException("hostContext is required for Application creation")
            progress("STARTED", "guest Application creation started")
            val virtualContextConfig = VirtualContextConfig(
                instanceId = input.instanceId,
                originPackageName = instance.originPackageName,
                virtualPackageName = instance.virtualPackageName,
                dataDir = instance.dataRoot,
                sourceDir = originApkPath,
                nativeLibraryDir = input.nativeLibraryDir,
                classLoader = guestClassLoader,
                applicationLabel = packageSnapshot.applicationLabel,
                packageSnapshot = packageSnapshot,
                splitSourceDirs = packageSnapshot.splitSourceDirs,
                splitPublicSourceDirs = packageSnapshot.splitPublicSourceDirs,
                splitNames = packageSnapshot.splitNames,
                isolatedSplits = packageSnapshot.isolatedSplits
            )
            val creation = guestApplicationCreator.create(
                GuestApplicationCreateRequest(
                    applicationClassName = appClassName,
                    applicationClassSource = appClassSource,
                    hostContext = context,
                    virtualContextConfig = virtualContextConfig,
                    guestClassLoader = guestClassLoader,
                    progress = { status, detail, extra -> progress(status, detail, extra) }
                )
            )
            progress(
                "APPLICATION_ATTACHED",
                "guest Application attachBaseContext returned",
                mapOf("attachedContextPackageName" to creation.attachedContextPackageName.orEmpty())
            )
            publishRuntimeBeforeOnCreate(input, creation.application)
            runtimePublishedBeforeOnCreate = true
            progress("RUNTIME_PUBLISHED", "runtime published before Application.onCreate")
            progress("ON_CREATE_STARTED", "guest Application.onCreate started")
            creation.application.onCreate()
            progress("ON_CREATE_FINISHED", "guest Application.onCreate returned")
            creation
        }.fold(
            onSuccess = { creation ->
                BootstrapStageOutput(
                    context = input.copy(guestApplication = creation.application),
                    result = BootstrapResult.success(
                        stage = RuntimeStage.APPLICATION,
                        message = "Guest Application created: $appClassName",
                        evidence = listOf(
                            BootstrapEvidence("applicationClass", appClassName),
                            BootstrapEvidence("applicationClassSource", appClassSource),
                            BootstrapEvidence("applicationThreadName", applicationThread.name),
                            BootstrapEvidence("applicationThreadHasLooper", applicationThread.hasLooper.toString()),
                            BootstrapEvidence("applicationThreadIsMain", applicationThread.isMain.toString()),
                            BootstrapEvidence(
                                "applicationThreadLooperProbeSkippedReason",
                                applicationThread.looperProbeSkippedReason.orEmpty()
                            ),
                            BootstrapEvidence("attached", "true"),
                            BootstrapEvidence("onCreate", "true"),
                            BootstrapEvidence(
                                "runtimePublishedBeforeOnCreate",
                                runtimePublishedBeforeOnCreate.toString()
                            ),
                            BootstrapEvidence("contextPackageName", creation.attachedContextPackageName.orEmpty()),
                            BootstrapEvidence("originPackageName", instance.originPackageName),
                            BootstrapEvidence("virtualPackageName", instance.virtualPackageName)
                        ) + creation.evidence,
                        durationMs = clock() - startMs
                    ),
                    terminalFailure = false
                )
            },
            onFailure = { error ->
                nonTerminalFailure(
                    input = input,
                    startMs = startMs,
                    message = "Guest Application creation failed: ${error.message}",
                    error = error
                )
            }
        )
    }

    private fun currentApplicationThreadEvidence(): ApplicationThreadEvidence {
        val threadName = Thread.currentThread().name
        return runCatching {
            val currentLooper = Looper.myLooper()
            val mainLooper = Looper.getMainLooper()
            ApplicationThreadEvidence(
                name = threadName,
                hasLooper = currentLooper != null,
                isMain = currentLooper != null && currentLooper == mainLooper,
                looperProbeSkippedReason = null
            )
        }.getOrElse { error ->
            ApplicationThreadEvidence(
                name = threadName,
                hasLooper = false,
                isMain = false,
                looperProbeSkippedReason = error.message ?: error.javaClass.name
            )
        }
    }

    private fun nonTerminalFailure(
        input: BootstrapStageInput,
        startMs: Long,
        message: String,
        error: Throwable? = null
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.APPLICATION,
            message = message,
            error = error,
            durationMs = clock() - startMs
        ),
        terminalFailure = false
    )

    private fun publishRuntimeBeforeOnCreate(input: BootstrapStageInput, guestApplication: Application) {
        val snapshot = requireNotNull(input.packageSnapshot) {
            "Package snapshot is required before publishing Application runtime"
        }
        val instance = requireNotNull(input.instance) {
            "Instance is required before publishing Application runtime"
        }
        val guestClassLoader = requireNotNull(input.guestClassLoader) {
            "Guest ClassLoader is required before publishing Application runtime"
        }
        runtimePublisher(
            input.instanceId,
            HostedBootstrapResult(
                instanceId = input.instanceId,
                installId = input.installRecord?.packageName,
                originPackageName = instance.originPackageName,
                virtualPackageName = instance.virtualPackageName,
                applicationLabel = snapshot.applicationLabel,
                originApkPath = input.originApkPath,
                dataRoot = instance.dataRoot,
                guestClassLoader = guestClassLoader,
                guestApplication = guestApplication,
                installRecord = input.installRecord,
                packageSnapshot = snapshot,
                launcherActivityClassName = input.launcherActivityClassName,
                stageResults = emptyList(),
                summary = emptyList<BootstrapResult>().toSummary(),
                success = true,
                diagnostics = null
            )
        )
    }

    private data class ApplicationThreadEvidence(
        val name: String,
        val hasLooper: Boolean,
        val isMain: Boolean,
        val looperProbeSkippedReason: String?
    )
}

fun interface GuestApplicationCreator {
    fun create(request: GuestApplicationCreateRequest): GuestApplicationCreateResult
}

data class GuestApplicationCreateRequest(
    val applicationClassName: String,
    val applicationClassSource: String,
    val hostContext: Context,
    val virtualContextConfig: VirtualContextConfig,
    val guestClassLoader: ClassLoader,
    val progress: (status: String, detail: String, extra: Map<String, String>) -> Unit = { _, _, _ -> }
)

data class GuestApplicationCreateResult(
    val application: Application,
    val attachedContextPackageName: String?,
    val evidence: List<BootstrapEvidence> = emptyList()
)

class ReflectiveGuestApplicationCreator : GuestApplicationCreator {
    override fun create(request: GuestApplicationCreateRequest): GuestApplicationCreateResult {
        request.progress("LOAD_CLASS_STARTED", "loading guest Application class", emptyMap())
        val appClass = request.guestClassLoader.loadClass(request.applicationClassName)
        request.progress("LOAD_CLASS_FINISHED", "guest Application class loaded", mapOf("loadedClass" to appClass.name))
        request.progress("CONSTRUCTOR_STARTED", "constructing guest Application", emptyMap())
        val guestApplication = appClass.getDeclaredConstructor().newInstance() as Application
        request.progress("CONSTRUCTOR_FINISHED", "guest Application constructed", mapOf("actualClass" to guestApplication.javaClass.name))
        request.progress("CONTEXT_CREATE_STARTED", "creating virtual Application context", emptyMap())
        val guestContext = VirtualContextWrappers.create(
            base = request.hostContext,
            config = request.virtualContextConfig,
            guestClassLoader = request.guestClassLoader
        )
        request.progress("CONTEXT_CREATE_FINISHED", "virtual Application context created", mapOf("contextPackageName" to guestContext.packageName))
        request.progress("ATTACH_STARTED", "calling Application.attachBaseContext", mapOf("contextPackageName" to guestContext.packageName))
        val attachMethod = findAttachBaseContextMethod(guestApplication.javaClass)
        attachMethod.isAccessible = true
        attachMethod.invoke(guestApplication, guestContext)
        return GuestApplicationCreateResult(
            application = guestApplication,
            attachedContextPackageName = guestContext.packageName,
            evidence = listOf(
                BootstrapEvidence("applicationCreator", "REFLECTIVE_ATTACH"),
                BootstrapEvidence("applicationRequestedClassSource", request.applicationClassSource)
            )
        )
    }

    private fun findAttachBaseContextMethod(startClass: Class<*>): java.lang.reflect.Method {
        var clazz: Class<*>? = startClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(
                    "attachBaseContext",
                    Context::class.java
                )
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException("attachBaseContext(Context) not found in Application hierarchy")
    }
}
