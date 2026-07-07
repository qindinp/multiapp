package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import android.os.Looper
import com.multiapp.core.model.virtual.VirtualContextConfig

class ApplicationStage(
    private val hostContext: Context?,
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String?,
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

        val appClassName = applicationClassNameResolver(guestClassLoader, originApkPath)
        if (appClassName == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.APPLICATION,
                    message = "No Application class name resolved"
                ).copy(durationMs = clock() - startMs),
                terminalFailure = false
            )
        }

        val applicationThread = currentApplicationThreadEvidence()

        var runtimePublishedBeforeOnCreate = false
        var attachedContextPackageName: String? = null

        return runCatching {
            val appClass = guestClassLoader.loadClass(appClassName)
            val guestApplication = appClass.getDeclaredConstructor().newInstance() as Application
            val context = hostContext
                ?: throw IllegalStateException("hostContext is required for Application creation")
            val guestContext = VirtualContextWrappers.create(
                base = context,
                config = VirtualContextConfig(
                    instanceId = input.instanceId,
                    originPackageName = instance.originPackageName,
                    virtualPackageName = instance.virtualPackageName,
                    dataDir = instance.dataRoot,
                    sourceDir = originApkPath,
                    nativeLibraryDir = input.nativeLibraryDir,
                    classLoader = guestClassLoader,
                    applicationLabel = packageSnapshot.applicationLabel,
                    packageSnapshot = packageSnapshot
                ),
                guestClassLoader = guestClassLoader
            )
            attachedContextPackageName = guestContext.packageName

            val attachMethod = findAttachBaseContextMethod(guestApplication.javaClass)
            attachMethod.isAccessible = true
            attachMethod.invoke(guestApplication, guestContext)
            publishRuntimeBeforeOnCreate(input, guestApplication)
            runtimePublishedBeforeOnCreate = true
            guestApplication.onCreate()
            guestApplication
        }.fold(
            onSuccess = { guestApplication ->
                BootstrapStageOutput(
                    context = input.copy(guestApplication = guestApplication),
                    result = BootstrapResult.success(
                        stage = RuntimeStage.APPLICATION,
                        message = "Guest Application created: $appClassName",
                        evidence = listOf(
                            BootstrapEvidence("applicationClass", appClassName),
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
                            BootstrapEvidence("contextPackageName", attachedContextPackageName.orEmpty()),
                            BootstrapEvidence("originPackageName", instance.originPackageName),
                            BootstrapEvidence("virtualPackageName", instance.virtualPackageName)
                        ),
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
