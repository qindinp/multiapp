package com.multiapp.core.loader

import java.io.File

data class GuestClassLoaderSpec(
    val dexPath: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val nativeLibraryDir: String?,
    val librarySearchPath: String?,
    val libraryPermittedPath: String,
    val targetSdkVersion: Int
) {
    init {
        require(dexPath.isNotBlank()) { "dexPath must not be blank" }
        require(baseApkPath.isNotBlank()) { "baseApkPath must not be blank" }
        require(splitApkPaths.none { it.isBlank() }) { "splitApkPaths must not contain blanks" }
        require(nativeLibraryDir == null || nativeLibraryDir.isNotBlank()) {
            "nativeLibraryDir must not be blank"
        }
        require(librarySearchPath == null || librarySearchPath.isNotBlank()) {
            "librarySearchPath must not be blank"
        }
        require(libraryPermittedPath.isNotBlank()) { "libraryPermittedPath must not be blank" }
        require(targetSdkVersion > 0) { "targetSdkVersion must be positive" }
    }
}

enum class GuestClassLoaderNamespaceVerdict {
    PASS,
    NOT_APPLICABLE
}

data class GuestClassLoaderCreation(
    val classLoader: ClassLoader,
    val namespaceVerdict: GuestClassLoaderNamespaceVerdict,
    val creationMethod: String,
    val namespaceDetail: String = ""
) {
    init {
        require(creationMethod.isNotBlank()) { "creationMethod must not be blank" }
    }
}

fun interface GuestClassLoaderFactory {
    fun create(spec: GuestClassLoaderSpec): GuestClassLoaderCreation
}

class ClassLoaderStage(
    private val classLoaderFactory: ((apkPath: String, nativeLibDir: String?) -> ClassLoader)? = null,
    private val structuredClassLoaderFactory: GuestClassLoaderFactory? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require((classLoaderFactory == null) != (structuredClassLoaderFactory == null)) {
            "Exactly one guest ClassLoader factory must be configured"
        }
    }

    fun execute(
        input: BootstrapStageInput,
        additionalEvidence: List<BootstrapEvidence> = emptyList()
    ): BootstrapStageOutput {
        val startMs = clock()
        val originApkPath = input.originApkPath ?: return failed(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before ClassLoader creation"
        )
        if (input.installRecord?.isolatedSplits == true) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.CLASS_LOADER,
                    message = "Isolated split loading is unsupported by hosted ClassLoader baseline",
                    evidence = listOf(
                        BootstrapEvidence("isolatedSplits", "true"),
                        BootstrapEvidence("classLoaderSplitSupport", "UNSUPPORTED")
                    ),
                    durationMs = clock() - startMs
                )
            )
        }
        val apkPaths = listOf(originApkPath) + input.installRecord?.splitApkPaths.orEmpty()
        val dexPath = apkPaths.distinct().joinToString(File.pathSeparator)
        val librarySearchPath = NativeLibraryPaths.buildClassLoaderSearchPath(
            apkPath = originApkPath,
            splitApkPaths = input.installRecord?.splitApkPaths.orEmpty(),
            nativeLibraryDir = input.nativeLibraryDir
        )
        val spec = GuestClassLoaderSpec(
            dexPath = dexPath,
            baseApkPath = originApkPath,
            splitApkPaths = input.installRecord?.splitApkPaths.orEmpty(),
            nativeLibraryDir = input.nativeLibraryDir,
            librarySearchPath = librarySearchPath,
            libraryPermittedPath = buildLibraryPermittedPath(input, apkPaths),
            targetSdkVersion = input.packageSnapshot?.targetSdk
                ?: input.installRecord?.targetSdk
                ?: if (structuredClassLoaderFactory == null) {
                    1
                } else {
                    return failed(
                        input = input,
                        startMs = startMs,
                        message = "Guest targetSdk is required before ClassLoader creation"
                    )
                }
        )

        return runCatching {
            structuredClassLoaderFactory?.create(spec)
                ?: GuestClassLoaderCreation(
                    classLoader = requireNotNull(classLoaderFactory)(dexPath, input.nativeLibraryDir),
                    namespaceVerdict = GuestClassLoaderNamespaceVerdict.NOT_APPLICABLE,
                    creationMethod = "LEGACY_FACTORY",
                    namespaceDetail = "custom factory did not report namespace state"
                )
        }.fold(
            onSuccess = { creation ->
                val guestClassLoader = creation.classLoader
                BootstrapStageOutput(
                    context = input.copy(guestClassLoader = guestClassLoader),
                    result = BootstrapResult.success(
                        stage = RuntimeStage.CLASS_LOADER,
                        message = "Guest ClassLoader created",
                        evidence = listOf(
                            BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name),
                            BootstrapEvidence("classLoaderDexPath", dexPath),
                            BootstrapEvidence("classLoaderApkPathCount", apkPaths.distinct().size.toString()),
                            BootstrapEvidence(
                                "classLoaderSplitSourceDirs",
                                input.installRecord?.splitApkPaths.orEmpty().joinToString(",")
                            ),
                            BootstrapEvidence("nativeLibraryDir", input.nativeLibraryDir.orEmpty()),
                            BootstrapEvidence("nativeLibrarySearchPath", librarySearchPath.orEmpty()),
                            BootstrapEvidence("namespaceVerdict", creation.namespaceVerdict.name),
                            BootstrapEvidence("namespaceCreationMethod", creation.creationMethod),
                            BootstrapEvidence("namespaceDetail", creation.namespaceDetail),
                            BootstrapEvidence("namespaceTargetSdk", spec.targetSdkVersion.toString()),
                            BootstrapEvidence("namespaceDexPath", spec.dexPath),
                            BootstrapEvidence("namespaceLibrarySearchPath", spec.librarySearchPath.orEmpty()),
                            BootstrapEvidence("namespaceLibraryPermittedPath", spec.libraryPermittedPath),
                            BootstrapEvidence(
                                "classLoaderIdentity",
                                System.identityHashCode(guestClassLoader).toString()
                            ),
                            BootstrapEvidence(
                                "classLoaderParentClass",
                                guestClassLoader.parent?.javaClass?.name.orEmpty()
                            ),
                            BootstrapEvidence(
                                "classLoaderParentIdentity",
                                guestClassLoader.parent?.let(System::identityHashCode)?.toString().orEmpty()
                            ),
                            BootstrapEvidence(
                                "threadContextClassLoaderIdentity",
                                System.identityHashCode(Thread.currentThread().contextClassLoader).toString()
                            )
                        ) + additionalEvidence,
                        durationMs = clock() - startMs
                    )
                )
            },
            onFailure = { error ->
                BootstrapStageOutput(
                    context = input,
                    result = BootstrapResult.failed(
                        stage = RuntimeStage.CLASS_LOADER,
                        message = "ClassLoader creation failed: ${error.message}",
                        error = error,
                        evidence = listOf(
                            BootstrapEvidence("namespaceVerdict", "FAIL"),
                            BootstrapEvidence("namespaceTargetSdk", spec.targetSdkVersion.toString()),
                            BootstrapEvidence("namespaceDexPath", spec.dexPath),
                            BootstrapEvidence(
                                "namespaceLibrarySearchPath",
                                spec.librarySearchPath.orEmpty()
                            ),
                            BootstrapEvidence(
                                "namespaceLibraryPermittedPath",
                                spec.libraryPermittedPath
                            ),
                            BootstrapEvidence("namespaceFailureClass", error.javaClass.name),
                            BootstrapEvidence("namespaceFailureMessage", error.message.orEmpty())
                        ),
                        durationMs = clock() - startMs
                    )
                )
            }
        )
    }

    private fun buildLibraryPermittedPath(
        input: BootstrapStageInput,
        apkPaths: List<String>
    ): String = buildList {
        add(input.instance?.dataRoot)
        add(input.packageSnapshot?.dataDir)
        add(input.nativeLibraryDir)
        apkPaths.mapNotNullTo(this) { path -> File(path).parent }
    }
        .filterNotNull()
        .filter { it.isNotBlank() }
        .map { path -> runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath) }
        .distinct()
        .joinToString(File.pathSeparator)
        .ifBlank { File(input.originApkPath.orEmpty()).parent.orEmpty() }

    private fun failed(
        input: BootstrapStageInput,
        startMs: Long,
        message: String
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.CLASS_LOADER,
            message = message,
            durationMs = clock() - startMs
        )
    )
}
