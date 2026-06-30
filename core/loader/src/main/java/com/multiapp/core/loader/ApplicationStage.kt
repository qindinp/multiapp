package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import com.multiapp.core.model.virtual.VirtualContextConfig

class ApplicationStage(
    private val hostContext: Context?,
    private val applicationClassNameResolver: (classLoader: ClassLoader, apkPath: String?) -> String?,
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

        return runCatching {
            val appClass = guestClassLoader.loadClass(appClassName)
            val guestApplication = appClass.getDeclaredConstructor().newInstance() as Application
            val context = hostContext
                ?: throw IllegalStateException("hostContext is required for Application creation")
            val guestContext = VirtualContextWrapper(
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

            val attachMethod = findAttachBaseContextMethod(guestApplication.javaClass)
            attachMethod.isAccessible = true
            attachMethod.invoke(guestApplication, guestContext)
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
                            BootstrapEvidence("attached", "true"),
                            BootstrapEvidence("onCreate", "true")
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
}
