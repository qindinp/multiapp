package com.multiapp.core.engine

import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStorageDiagnosticStatus
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import com.multiapp.core.loader.VirtualStoragePathDiagnostics
import java.io.File

data class EngineStorageDiagnosticsPlan(
    val instanceId: String,
    val bootstrapUnsupportedEntry: EngineStorageEvidenceEntry? = null,
    val diagnostics: List<EngineStoragePathDiagnostic> = emptyList()
)

data class EngineStorageEvidenceEntry(
    val instanceId: String,
    val component: String,
    val operationName: String,
    val fields: Map<String, Any?>
)

enum class EngineStorageDiagnosticKind {
    JAVA_ABSOLUTE_PATH,
    NATIVE_IO;

    companion object {
        fun fromLoader(kind: VirtualStorageDiagnosticKind): EngineStorageDiagnosticKind =
            when (kind) {
                VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH -> JAVA_ABSOLUTE_PATH
                VirtualStorageDiagnosticKind.NATIVE_IO -> NATIVE_IO
            }
    }
}

enum class EngineStorageDiagnosticStatus {
    REDIRECTED,
    UNCHANGED,
    UNSUPPORTED;

    companion object {
        fun fromLoader(status: VirtualStorageDiagnosticStatus): EngineStorageDiagnosticStatus =
            when (status) {
                VirtualStorageDiagnosticStatus.REDIRECTED -> REDIRECTED
                VirtualStorageDiagnosticStatus.UNCHANGED -> UNCHANGED
                VirtualStorageDiagnosticStatus.UNSUPPORTED -> UNSUPPORTED
            }
    }
}

data class EngineStoragePathDiagnostic(
    val kind: EngineStorageDiagnosticKind,
    val status: EngineStorageDiagnosticStatus,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val dataRoot: String,
    val probeName: String?,
    val operation: String?,
    val originalPath: String,
    val redirectedPath: String,
    val candidateRedirectedPath: String?,
    val caller: String,
    val reason: String?,
    val withinDataRoot: Boolean,
    val candidateWithinDataRoot: Boolean?,
    val nativeProbeResultCode: Int? = null,
    val nativeProbeErrno: Int? = null,
    val nativeProbeCandidateExists: Boolean? = null,
    val nativeProbeResolvedPath: String? = null,
    val nativeRuntimeEvidence: EngineNativeRuntimeEvidence = EngineNativeRuntimeEvidence.unknown()
) {
    companion object {
        fun fromLoader(diagnostic: VirtualStoragePathDiagnostic): EngineStoragePathDiagnostic =
            EngineStoragePathDiagnostic(
                kind = EngineStorageDiagnosticKind.fromLoader(diagnostic.kind),
                status = EngineStorageDiagnosticStatus.fromLoader(diagnostic.status),
                instanceId = diagnostic.instanceId,
                originPackageName = diagnostic.originPackageName,
                virtualPackageName = diagnostic.virtualPackageName,
                dataRoot = diagnostic.dataRoot,
                probeName = diagnostic.probeName,
                operation = diagnostic.operation,
                originalPath = diagnostic.originalPath,
                redirectedPath = diagnostic.redirectedPath,
                candidateRedirectedPath = diagnostic.candidateRedirectedPath,
                caller = diagnostic.caller,
                reason = diagnostic.reason,
                withinDataRoot = diagnostic.withinDataRoot,
                candidateWithinDataRoot = diagnostic.candidateWithinDataRoot,
                nativeProbeResultCode = diagnostic.nativeProbeResultCode,
                nativeProbeErrno = diagnostic.nativeProbeErrno,
                nativeProbeCandidateExists = diagnostic.nativeProbeCandidateExists,
                nativeProbeResolvedPath = diagnostic.nativeProbeResolvedPath
            )
    }
}

data class EngineNativeRuntimeEvidence(
    val namespaceVerdict: String,
    val namespaceVerdictReason: String,
    val findLibraryVerdict: String,
    val findLibraryVerdictReason: String,
    val nativeLoadVerdict: String,
    val nativeLoadVerdictReason: String,
    val nativeLibraryDir: String? = null,
    val nativeLibrarySearchPath: String? = null,
    val nativeLibraryCount: Int? = null,
    val findLibraryName: String? = null,
    val findLibraryResolvedPath: String? = null
) {
    companion object {
        fun unknown(): EngineNativeRuntimeEvidence = EngineNativeRuntimeEvidence(
            namespaceVerdict = "UNKNOWN",
            namespaceVerdictReason = "NAMESPACE_COLLECTOR_NOT_IMPLEMENTED",
            findLibraryVerdict = "UNKNOWN",
            findLibraryVerdictReason = "FIND_LIBRARY_COLLECTOR_NOT_IMPLEMENTED",
            nativeLoadVerdict = "UNKNOWN",
            nativeLoadVerdictReason = "NATIVE_LOAD_COLLECTOR_NOT_IMPLEMENTED"
        )

        fun identityIncomplete(): EngineNativeRuntimeEvidence = EngineNativeRuntimeEvidence(
            namespaceVerdict = "UNKNOWN",
            namespaceVerdictReason = "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            findLibraryVerdict = "UNKNOWN",
            findLibraryVerdictReason = "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            nativeLoadVerdict = "UNKNOWN",
            nativeLoadVerdictReason = "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE"
        )
    }
}

object EngineStorageDiagnosticsFacade {
    private const val CALLER = "ContainerActivity.PR10_STORAGE_DIAGNOSTICS"
    private const val STAGE = "STORAGE_PATH_DIAGNOSTIC"

    private val javaProbeComponents = mapOf(
        "data-data" to "storage-java-data-data",
        "data-user" to "storage-java-data-user",
        "sdcard" to "storage-java-sdcard",
        "storage-emulated" to "storage-java-storage-emulated"
    )

    fun diagnosticsFromBootstrapResult(result: Any): EngineStorageDiagnosticsPlan {
        val loaderResult = EngineHostedBootstrapResult.unwrap(result) ?: throw IllegalArgumentException(
            "Expected HostedBootstrapResult, got ${result::class.java.name}"
        )
        return diagnosticsFromBootstrapResult(loaderResult)
    }

    fun diagnosticsFromBootstrapResult(result: HostedBootstrapResult): EngineStorageDiagnosticsPlan {
        val originPackageName = result.originPackageName.orEmpty()
        val virtualPackageName = result.virtualPackageName.orEmpty()
        val dataRoot = result.dataRoot.orEmpty()
        if (originPackageName.isBlank() || virtualPackageName.isBlank() || dataRoot.isBlank()) {
            return EngineStorageDiagnosticsPlan(
                instanceId = result.instanceId,
                bootstrapUnsupportedEntry = bootstrapUnsupportedEntry(
                    result = result,
                    originPackageName = originPackageName,
                    virtualPackageName = virtualPackageName,
                    dataRoot = dataRoot
                )
            )
        }

        val javaDiagnostics = VirtualStoragePathDiagnostics.javaAbsolutePathDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER
        ).map(EngineStoragePathDiagnostic::fromLoader)

        val nativeDiagnostics = nativeIoDiagnostics(
            instanceId = result.instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = CALLER,
            result = result
        )

        return EngineStorageDiagnosticsPlan(
            instanceId = result.instanceId,
            diagnostics = javaDiagnostics + nativeDiagnostics
        )
    }

    fun fieldsForDiagnostic(
        diagnostic: EngineStoragePathDiagnostic,
        isolationMarkerPath: String? = null,
        isolationMarkerContent: String? = null
    ): Map<String, Any?> = buildMap {
        put("stage", STAGE)
        put("instanceId", diagnostic.instanceId)
        put("originPackageName", diagnostic.originPackageName)
        put("virtualPackageName", diagnostic.virtualPackageName)
        put("dataRoot", diagnostic.dataRoot)
        put("storageDiagnosticKind", diagnostic.kind.name)
        put("storageDiagnosticStatus", diagnostic.status.name)
        put("originalPath", diagnostic.originalPath)
        put("redirectedPath", diagnostic.redirectedPath)
        put("caller", diagnostic.caller)
        put("withinDataRoot", diagnostic.withinDataRoot)
        diagnostic.probeName?.let { put("probeName", it) }
        diagnostic.operation?.let { put("nativeIoOperation", it) }
        diagnostic.reason?.let { put("reason", it) }
        diagnostic.candidateRedirectedPath?.let { put("candidateRedirectedPath", it) }
        diagnostic.candidateWithinDataRoot?.let { put("candidateWithinDataRoot", it) }
        if (diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO) {
            put("nativeIoDiagnosticStatus", diagnostic.status.name)
            diagnostic.nativeProbeResultCode?.let { put("nativeProbeResultCode", it) }
            diagnostic.nativeProbeErrno?.let { put("nativeProbeErrno", it) }
            diagnostic.nativeProbeCandidateExists?.let { put("nativeProbeCandidateExists", it) }
            diagnostic.nativeProbeResolvedPath?.let { put("nativeProbeResolvedPath", it) }
            putAll(nativeRuntimeVerdictFields(diagnostic))
        }
        if (!isolationMarkerPath.isNullOrBlank()) {
            put("isolationMarkerPath", isolationMarkerPath)
            put("isolationMarkerContent", isolationMarkerContent.orEmpty())
        }
    }

    fun shouldWriteIsolationMarker(diagnostic: EngineStoragePathDiagnostic): Boolean =
        diagnostic.kind == EngineStorageDiagnosticKind.JAVA_ABSOLUTE_PATH &&
            diagnostic.status == EngineStorageDiagnosticStatus.REDIRECTED &&
            diagnostic.withinDataRoot

    fun isolationMarkerContent(diagnostic: EngineStoragePathDiagnostic): String =
        "instanceId=${diagnostic.instanceId}\n" +
            "probeName=${diagnostic.probeName.orEmpty()}\n" +
            "originalPath=${diagnostic.originalPath}\n"

    fun componentName(diagnostic: EngineStoragePathDiagnostic): String {
        if (diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO) {
            return "storage-native-${diagnostic.operation.orEmpty()}"
        }
        return javaProbeComponents[diagnostic.probeName] ?: "storage-java-absolute-path"
    }

    private fun bootstrapUnsupportedEntry(
        result: HostedBootstrapResult,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String
    ): EngineStorageEvidenceEntry {
        val fields = linkedMapOf<String, Any?>(
            "stage" to STAGE,
            "instanceId" to result.instanceId,
            "originPackageName" to originPackageName,
            "virtualPackageName" to virtualPackageName,
            "dataRoot" to dataRoot,
            "storageDiagnosticStatus" to EngineStorageDiagnosticStatus.UNSUPPORTED.name,
            "nativeIoRedirectVerdict" to "UNSUPPORTED",
            "nativeIoRedirectVerdictReason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "procMapsSpoofEnabled" to false,
            "procStatusSpoofEnabled" to false,
            "reason" to "BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE",
            "caller" to CALLER
        ).apply {
            putAll(EngineNativeRuntimeEvidence.identityIncomplete().toFields())
        }
        return EngineStorageEvidenceEntry(
            instanceId = result.instanceId,
            component = "storage-bootstrap",
            operationName = "storage-bootstrap",
            fields = fields
        )
    }

    private fun nativeIoDiagnostics(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        caller: String,
        result: HostedBootstrapResult
    ): List<EngineStoragePathDiagnostic> {
        val bootstrapEvidence = result.stageResults
            .flatMap { it.evidence }
            .associate { it.key to it.value }
        val redirectVerdict = bootstrapEvidence["nativePrivatePathRedirectVerdict"]
        val unsupportedReason = nativeIoUnsupportedReason(result)
        val runtimeEvidence = nativeRuntimeEvidence(result, bootstrapEvidence)
        val baseDiagnostics = VirtualStoragePathDiagnostics.nativeIoUnsupportedDiagnostics(
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            caller = caller,
            reason = unsupportedReason
        ).map { diagnostic ->
            EngineStoragePathDiagnostic.fromLoader(diagnostic).copy(
                nativeRuntimeEvidence = runtimeEvidence
            )
        }
        if (redirectVerdict != "PARTIAL") return baseDiagnostics

        val bridge = NativeHookBridge.getInstance()
        return baseDiagnostics.map { diagnostic ->
            val candidate = diagnostic.candidateRedirectedPath
            if (candidate.isNullOrBlank() || diagnostic.candidateWithinDataRoot != true) {
                diagnostic.copy(
                    status = EngineStorageDiagnosticStatus.UNSUPPORTED,
                    reason = "NATIVE_IO_CANDIDATE_OUTSIDE_DATA_ROOT"
                )
            } else {
                probeNativeIoDiagnostic(bridge, diagnostic, candidate)
            }
        }
    }

    private fun probeNativeIoDiagnostic(
        bridge: NativeHookBridge,
        diagnostic: EngineStoragePathDiagnostic,
        candidatePath: String
    ): EngineStoragePathDiagnostic {
        val operation = diagnostic.operation.orEmpty()
        val candidateFile = File(candidatePath)
        candidateFile.parentFile?.mkdirs()
        if (operation in setOf("stat", "access", "realpath")) {
            candidateFile.writeText("multiapp-native-probe-$operation")
        } else {
            candidateFile.delete()
        }
        val probe = bridge.probePrivatePathRedirect(
            operation = operation,
            originalPath = diagnostic.originalPath,
            expectedRedirectedPath = candidatePath
        )
        val status = if (probe.success) {
            EngineStorageDiagnosticStatus.REDIRECTED
        } else {
            EngineStorageDiagnosticStatus.UNCHANGED
        }
        val redirectedPath = if (probe.success) candidateFile.absolutePath else ""
        return diagnostic.copy(
            status = status,
            redirectedPath = redirectedPath,
            withinDataRoot = probe.success,
            reason = if (probe.success) null else probe.reason.ifBlank { "NATIVE_IO_PATH_NOT_REDIRECTED" },
            nativeProbeResultCode = probe.resultCode,
            nativeProbeErrno = probe.errno,
            nativeProbeCandidateExists = probe.candidateExists,
            nativeProbeResolvedPath = probe.resolvedPath
        )
    }

    private fun nativeRuntimeVerdictFields(diagnostic: EngineStoragePathDiagnostic): Map<String, Any?> {
        val nativeIoRedirectVerdict = when (diagnostic.status) {
            EngineStorageDiagnosticStatus.REDIRECTED -> "PASS"
            EngineStorageDiagnosticStatus.UNSUPPORTED -> "UNSUPPORTED"
            EngineStorageDiagnosticStatus.UNCHANGED -> "FAIL"
        }
        val nativeIoRedirectReason = when (diagnostic.status) {
            EngineStorageDiagnosticStatus.REDIRECTED -> ""
            EngineStorageDiagnosticStatus.UNSUPPORTED -> diagnostic.reason.orEmpty()
            EngineStorageDiagnosticStatus.UNCHANGED -> diagnostic.reason ?: "NATIVE_IO_PATH_NOT_REDIRECTED"
        }
        return linkedMapOf<String, Any?>(
            "nativeIoRedirectVerdict" to nativeIoRedirectVerdict,
            "nativeIoRedirectVerdictReason" to nativeIoRedirectReason,
            "nativeRedirectScope" to "GUEST_PRIVATE_PATHS_ONLY",
            "nativeIoRedirectEnabled" to (diagnostic.status == EngineStorageDiagnosticStatus.REDIRECTED),
            "nativeIoCandidateWithinDataRoot" to (diagnostic.candidateWithinDataRoot ?: false),
            "procMapsSpoofEnabled" to false,
            "procStatusSpoofEnabled" to false
        ).apply {
            putAll(diagnostic.nativeRuntimeEvidence.toFields())
        }
    }

    private fun EngineNativeRuntimeEvidence.toFields(): Map<String, Any?> = buildMap {
        put("namespaceVerdict", namespaceVerdict)
        put("namespaceVerdictReason", namespaceVerdictReason)
        put("findLibraryVerdict", findLibraryVerdict)
        put("findLibraryVerdictReason", findLibraryVerdictReason)
        put("nativeLoadVerdict", nativeLoadVerdict)
        put("nativeLoadVerdictReason", nativeLoadVerdictReason)
        nativeLibraryDir?.let { put("nativeLibraryDir", it) }
        nativeLibrarySearchPath?.let { put("nativeLibrarySearchPath", it) }
        nativeLibraryCount?.let { put("nativeLibraryCount", it) }
        findLibraryName?.let { put("findLibraryName", it) }
        findLibraryResolvedPath?.let { put("findLibraryResolvedPath", it) }
    }

    private fun nativeRuntimeEvidence(
        result: HostedBootstrapResult,
        bootstrapEvidence: Map<String, String>
    ): EngineNativeRuntimeEvidence {
        val nativeLibraryDir = bootstrapEvidence["nativeLibraryDir"].orEmpty()
        val nativeLibrarySearchPath = bootstrapEvidence["nativeLibrarySearchPath"].orEmpty()
        val libraries = nativeLibraryCandidates(bootstrapEvidence, nativeLibraryDir)
        val nativeLibraryCount = bootstrapEvidence["nativeLibraryCount"]?.toIntOrNull() ?: libraries.size
        if (nativeLibraryCount == 0 || libraries.isEmpty()) {
            return EngineNativeRuntimeEvidence(
                namespaceVerdict = "UNSUPPORTED",
                namespaceVerdictReason = "NO_GUEST_NATIVE_LIBRARIES",
                findLibraryVerdict = "UNSUPPORTED",
                findLibraryVerdictReason = "NO_GUEST_NATIVE_LIBRARIES",
                nativeLoadVerdict = "UNSUPPORTED",
                nativeLoadVerdictReason = "NO_GUEST_NATIVE_LIBRARIES",
                nativeLibraryDir = nativeLibraryDir.ifBlank { null },
                nativeLibrarySearchPath = nativeLibrarySearchPath.ifBlank { null },
                nativeLibraryCount = nativeLibraryCount
            )
        }

        val classLoader = result.guestClassLoader
        if (classLoader == null) {
            return EngineNativeRuntimeEvidence(
                namespaceVerdict = "FAIL",
                namespaceVerdictReason = "GUEST_CLASSLOADER_MISSING",
                findLibraryVerdict = "FAIL",
                findLibraryVerdictReason = "GUEST_CLASSLOADER_MISSING",
                nativeLoadVerdict = "FAIL",
                nativeLoadVerdictReason = "GUEST_CLASSLOADER_MISSING",
                nativeLibraryDir = nativeLibraryDir.ifBlank { null },
                nativeLibrarySearchPath = nativeLibrarySearchPath.ifBlank { null },
                nativeLibraryCount = nativeLibraryCount
            )
        }

        val findLibrary = resolveFirstNativeLibrary(classLoader, libraries, nativeLibraryDir, result.dataRoot)
        val namespaceVerdict = when {
            nativeLibrarySearchPath.isBlank() -> "FAIL"
            findLibrary.verdict == "PASS" || findLibrary.verdict == "PARTIAL" -> "PARTIAL"
            else -> "FAIL"
        }
        val namespaceReason = when {
            nativeLibrarySearchPath.isBlank() -> "NATIVE_LIBRARY_SEARCH_PATH_MISSING"
            findLibrary.verdict == "PASS" ->
                "CLASSLOADER_FIND_LIBRARY_PASS_LINKER_NAMESPACE_DEVICE_LOAD_NOT_PROBED"
            findLibrary.verdict == "PARTIAL" ->
                "CLASSLOADER_FIND_LIBRARY_PARTIAL_LINKER_NAMESPACE_DEVICE_LOAD_NOT_PROBED"
            else -> "CLASSLOADER_FIND_LIBRARY_FAILED"
        }
        val nativeLoadVerdict = when (findLibrary.verdict) {
            "PASS", "PARTIAL" -> "PARTIAL"
            else -> "FAIL"
        }
        val nativeLoadReason = when (findLibrary.verdict) {
            "PASS", "PARTIAL" -> "NATIVE_LOAD_NOT_EXECUTED_BY_STORAGE_DIAGNOSTIC"
            else -> "FIND_LIBRARY_UNRESOLVED"
        }
        return EngineNativeRuntimeEvidence(
            namespaceVerdict = namespaceVerdict,
            namespaceVerdictReason = namespaceReason,
            findLibraryVerdict = findLibrary.verdict,
            findLibraryVerdictReason = findLibrary.reason,
            nativeLoadVerdict = nativeLoadVerdict,
            nativeLoadVerdictReason = nativeLoadReason,
            nativeLibraryDir = nativeLibraryDir.ifBlank { null },
            nativeLibrarySearchPath = nativeLibrarySearchPath.ifBlank { null },
            nativeLibraryCount = nativeLibraryCount,
            findLibraryName = findLibrary.libraryName,
            findLibraryResolvedPath = findLibrary.resolvedPath
        )
    }

    private fun nativeLibraryCandidates(
        bootstrapEvidence: Map<String, String>,
        nativeLibraryDir: String
    ): List<String> {
        val fromEvidence = bootstrapEvidence["nativeLibraries"]
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val fromDirectory = nativeLibraryDir
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.listFiles { file -> file.isFile && file.name.endsWith(".so") }
            ?.map { it.name }
            .orEmpty()
        return (fromEvidence + fromDirectory).distinct().sorted()
    }

    private data class FindLibraryEvidence(
        val libraryName: String?,
        val resolvedPath: String?,
        val verdict: String,
        val reason: String
    )

    private fun resolveFirstNativeLibrary(
        classLoader: ClassLoader,
        libraries: List<String>,
        nativeLibraryDir: String,
        dataRoot: String?
    ): FindLibraryEvidence {
        val libraryName = libraries.firstOrNull()?.toLoadLibraryName()
            ?: return FindLibraryEvidence(
                libraryName = null,
                resolvedPath = null,
                verdict = "UNSUPPORTED",
                reason = "NO_GUEST_NATIVE_LIBRARIES"
            )
        val resolved = invokeFindLibrary(classLoader, libraryName)
        val resolvedPath = resolved.getOrNull().orEmpty()
        if (resolved.isFailure) {
            val error = resolved.exceptionOrNull()
            return FindLibraryEvidence(
                libraryName = libraryName,
                resolvedPath = null,
                verdict = "FAIL",
                reason = "FIND_LIBRARY_EXCEPTION:${error?.javaClass?.simpleName.orEmpty()}"
            )
        }
        if (resolvedPath.isBlank()) {
            return FindLibraryEvidence(
                libraryName = libraryName,
                resolvedPath = null,
                verdict = "FAIL",
                reason = "FIND_LIBRARY_RETURNED_EMPTY"
            )
        }
        val verdict = if (isResolvedLibraryPathVerified(resolvedPath, nativeLibraryDir, dataRoot)) {
            "PASS"
        } else {
            "PARTIAL"
        }
        return FindLibraryEvidence(
            libraryName = libraryName,
            resolvedPath = resolvedPath,
            verdict = verdict,
            reason = if (verdict == "PASS") "" else "FIND_LIBRARY_RETURNED_UNVERIFIED_PATH"
        )
    }

    private fun String.toLoadLibraryName(): String =
        removePrefix("lib").removeSuffix(".so")

    private fun invokeFindLibrary(classLoader: ClassLoader, libraryName: String): Result<String?> =
        runCatching {
            var current: Class<*>? = classLoader.javaClass
            while (current != null) {
                val method = runCatching { current.getDeclaredMethod("findLibrary", String::class.java) }.getOrNull()
                if (method != null) {
                    method.isAccessible = true
                    return@runCatching method.invoke(classLoader, libraryName) as? String
                }
                current = current.superclass
            }
            null
        }

    private fun isResolvedLibraryPathVerified(
        resolvedPath: String,
        nativeLibraryDir: String,
        dataRoot: String?
    ): Boolean {
        if (resolvedPath.contains("!/lib/") && resolvedPath.endsWith(".so")) return true
        val resolvedFile = File(resolvedPath)
        if (resolvedFile.isFile) return true
        return listOf(nativeLibraryDir, dataRoot.orEmpty())
            .filter { it.isNotBlank() }
            .any { root -> isCanonicalContained(resolvedFile, File(root)) }
    }

    private fun isCanonicalContained(candidate: File, root: File): Boolean =
        runCatching {
            val rootPath = root.canonicalFile.path.trimEnd(File.separatorChar)
            val candidatePath = candidate.canonicalFile.path
            candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        }.getOrDefault(false)

    private fun nativeIoUnsupportedReason(result: HostedBootstrapResult): String {
        val evidence = result.stageResults
            .flatMap { it.evidence }
            .associate { it.key to it.value }
        return when (evidence["nativePrivatePathRedirectVerdict"]) {
            "PARTIAL" -> "NATIVE_IO_DEVICE_PROBE_NOT_IMPLEMENTED"
            "FAIL" -> evidence["nativePrivatePathRedirectReason"] ?: "PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE"
            else -> "NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE"
        }
    }
}
