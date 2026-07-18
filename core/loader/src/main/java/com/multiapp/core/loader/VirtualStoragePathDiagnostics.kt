package com.multiapp.core.loader

import java.io.File

/** Deterministic diagnostics for storage paths that are not covered by Context APIs. */
object VirtualStoragePathDiagnostics {
    const val NATIVE_OPEN_FLAG_O_CREAT: Int = 0x40

    val DEFAULT_NATIVE_IO_OPERATIONS: List<String> = listOf(
        "open",
        "openat",
        "stat",
        "access",
        "fopen",
        "realpath"
    )

    fun nativeProcessSlot(instanceId: String): String =
        "process:${instanceId.ifBlank { "unknown" }}"

    fun javaAbsolutePathDiagnostics(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        caller: String
    ): List<VirtualStoragePathDiagnostic> {
        return listOf(
            "data-data" to "/data/data/$originPackageName/files/pr10-data-data.txt",
            "data-user" to "/data/user/0/$originPackageName/files/pr10-data-user.txt",
            "sdcard" to "/sdcard/Android/data/$originPackageName/files/pr10-sdcard.txt",
            "storage-emulated" to "/storage/emulated/0/Android/data/$originPackageName/files/pr10-storage-emulated.txt"
        ).map { (probeName, path) ->
            diagnoseJavaAbsolutePath(
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                dataRoot = dataRoot,
                originalPath = path,
                caller = caller,
                probeName = probeName
            )
        }
    }

    fun diagnoseJavaAbsolutePath(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        originalPath: String,
        caller: String,
        probeName: String? = null
    ): VirtualStoragePathDiagnostic {
        val dataRootFile = File(dataRoot)
        val normalizedOriginalPath = normalizeSeparators(originalPath)
        val unsafeReason = when {
            hasUnsafePathCharacter(normalizedOriginalPath) -> "PATH_CONTAINS_NUL"
            hasParentTraversalSegment(normalizedOriginalPath) -> "PATH_TRAVERSAL_REJECTED"
            else -> null
        }
        if (unsafeReason != null) {
            return VirtualStoragePathDiagnostic(
                kind = VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH,
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                dataRoot = dataRootFile.normalizedPath(),
                probeName = probeName,
                operation = null,
                originalPath = originalPath,
                redirectedPath = "",
                candidateRedirectedPath = null,
                caller = caller,
                reason = unsafeReason,
                withinDataRoot = false,
                candidateWithinDataRoot = null
            )
        }
        val candidate = rewriteJavaAbsolutePath(
            originalPath = originalPath,
            originPackageName = originPackageName,
            dataRoot = dataRoot
        )
        val redirected = candidate?.takeIf { it.isWithin(dataRootFile) }
        val status = when {
            candidate == null -> VirtualStorageDiagnosticStatus.UNCHANGED
            redirected == null -> VirtualStorageDiagnosticStatus.UNSUPPORTED
            else -> VirtualStorageDiagnosticStatus.REDIRECTED
        }
        val redirectedPath = when (status) {
            VirtualStorageDiagnosticStatus.REDIRECTED -> redirected?.normalizedPath().orEmpty()
            VirtualStorageDiagnosticStatus.UNCHANGED -> originalPath
            VirtualStorageDiagnosticStatus.UNSUPPORTED -> ""
        }
        val reason = when (status) {
            VirtualStorageDiagnosticStatus.REDIRECTED -> null
            VirtualStorageDiagnosticStatus.UNCHANGED -> "PATH_NOT_MATCHED"
            VirtualStorageDiagnosticStatus.UNSUPPORTED -> "REDIRECTED_PATH_ESCAPES_DATA_ROOT"
        }
        return VirtualStoragePathDiagnostic(
            kind = VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH,
            status = status,
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRootFile.normalizedPath(),
            probeName = probeName,
            operation = null,
            originalPath = originalPath,
            redirectedPath = redirectedPath,
            candidateRedirectedPath = candidate?.takeIf { it != redirected }?.normalizedPath(),
            caller = caller,
            reason = reason,
            withinDataRoot = redirected?.isWithin(dataRootFile) ?: false,
            candidateWithinDataRoot = candidate?.takeIf { it != redirected }?.isWithin(dataRootFile)
        )
    }

    fun nativeIoUnsupportedDiagnostics(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        caller: String,
        reason: String = "NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE",
        operations: List<String> = DEFAULT_NATIVE_IO_OPERATIONS
    ): List<VirtualStoragePathDiagnostic> {
        return operations.map { operation ->
            val originalPath = "/data/data/$originPackageName/files/pr10-native-$operation.txt"
            val decision = diagnoseNativePrivatePath(
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                dataRoot = dataRoot,
                originalPath = originalPath,
                operation = operation,
                caller = caller,
                processSlot = nativeProcessSlot(instanceId)
            )
            VirtualStoragePathDiagnostic(
                kind = VirtualStorageDiagnosticKind.NATIVE_IO,
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                dataRoot = File(dataRoot).normalizedPath(),
                probeName = null,
                operation = operation,
                originalPath = originalPath,
                redirectedPath = "",
                candidateRedirectedPath = decision.redirectedPath
                    .takeIf { decision.status == VirtualStorageDiagnosticStatus.REDIRECTED },
                caller = caller,
                reason = reason,
                withinDataRoot = false,
                candidateWithinDataRoot = decision.withinDataRoot,
                processSlot = decision.processSlot
            )
        }
    }

    fun diagnoseNativePrivatePath(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        originalPath: String,
        operation: String,
        caller: String,
        processSlot: String = nativeProcessSlot(instanceId),
        openFlags: Int = 0
    ): VirtualStoragePathDiagnostic {
        return diagnoseNativePrivatePath(
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            originalPath = originalPath,
            operation = operation,
            caller = caller,
            processSlot = processSlot,
            openFlags = openFlags,
            fileSystem = JvmNativePathFileSystem
        )
    }

    internal fun diagnoseNativePrivatePath(
        instanceId: String,
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        originalPath: String,
        operation: String,
        caller: String,
        processSlot: String,
        openFlags: Int,
        fileSystem: NativePathFileSystem
    ): VirtualStoragePathDiagnostic {
        val normalizedProcessSlot = processSlot.ifBlank { nativeProcessSlot(instanceId) }
        val root = File(dataRoot)

        fun diagnostic(
            status: VirtualStorageDiagnosticStatus,
            redirectedPath: String,
            candidate: File?,
            reason: String?,
            withinDataRoot: Boolean,
            candidateWithinDataRoot: Boolean?
        ): VirtualStoragePathDiagnostic {
            return VirtualStoragePathDiagnostic(
                kind = VirtualStorageDiagnosticKind.NATIVE_IO,
                status = status,
                instanceId = instanceId,
                originPackageName = originPackageName,
                virtualPackageName = virtualPackageName,
                dataRoot = root.normalizedPath(fileSystem),
                probeName = null,
                operation = operation,
                originalPath = originalPath,
                redirectedPath = redirectedPath,
                candidateRedirectedPath = candidate?.normalizedPath(fileSystem),
                caller = caller,
                reason = reason,
                withinDataRoot = withinDataRoot,
                candidateWithinDataRoot = candidateWithinDataRoot,
                openFlags = openFlags,
                processSlot = normalizedProcessSlot
            )
        }

        if (originalPath.isBlank()) {
            return diagnostic(
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                redirectedPath = "",
                candidate = null,
                reason = "EMPTY_PATH",
                withinDataRoot = false,
                candidateWithinDataRoot = null
            )
        }
        if (instanceId.isBlank() || originPackageName.isBlank() || dataRoot.isBlank()) {
            return diagnostic(
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                redirectedPath = "",
                candidate = null,
                reason = "PRIVATE_PATH_REDIRECT_INPUT_INCOMPLETE",
                withinDataRoot = false,
                candidateWithinDataRoot = null
            )
        }
        if (hasUnsafePathCharacter(originalPath) ||
            hasUnsafePathCharacter(originPackageName) ||
            hasUnsafePathCharacter(instanceId) ||
            hasUnsafePathCharacter(dataRoot)
        ) {
            return diagnostic(
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                redirectedPath = "",
                candidate = null,
                reason = "PATH_CONTAINS_NUL",
                withinDataRoot = false,
                candidateWithinDataRoot = null
            )
        }

        val normalizedPath = normalizeSeparators(originalPath)
        if (listOf(normalizedPath, instanceId, originPackageName, dataRoot, normalizedProcessSlot).any {
                hasParentTraversalSegment(it)
            }
        ) {
            return diagnostic(
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                redirectedPath = "",
                candidate = null,
                reason = "PATH_TRAVERSAL_REJECTED",
                withinDataRoot = false,
                candidateWithinDataRoot = null
            )
        }

        val candidate = guestAppScopedPathCandidate(normalizedPath, originPackageName, root)
            ?: return diagnostic(
                status = VirtualStorageDiagnosticStatus.UNCHANGED,
                redirectedPath = originalPath,
                candidate = null,
                reason = "PATH_NOT_MATCHED",
                withinDataRoot = false,
                candidateWithinDataRoot = null
            )
        if (openFlags and NATIVE_OPEN_FLAG_O_CREAT != 0) {
            val parent = candidate.parentFile ?: root
            if (!parent.isWithin(root, fileSystem)) {
                return diagnostic(
                    status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                    redirectedPath = "",
                    candidate = candidate,
                    reason = "CREATE_PARENT_ESCAPES_DATA_ROOT",
                    withinDataRoot = false,
                    candidateWithinDataRoot = false
                )
            }
        }

        if (!candidate.isWithin(root, fileSystem)) {
            return diagnostic(
                status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
                redirectedPath = "",
                candidate = candidate,
                reason = "REDIRECTED_PATH_ESCAPES_DATA_ROOT",
                withinDataRoot = false,
                candidateWithinDataRoot = false
            )
        }

        return diagnostic(
            status = VirtualStorageDiagnosticStatus.REDIRECTED,
            redirectedPath = candidate.normalizedPath(fileSystem),
            candidate = null,
            reason = null,
            withinDataRoot = true,
            candidateWithinDataRoot = true
        )
    }

    fun rewriteJavaAbsolutePath(
        originalPath: String,
        originPackageName: String,
        dataRoot: String
    ): File? {
        if (originPackageName.isBlank() || dataRoot.isBlank()) return null
        val normalized = originalPath.replace('\\', '/')
        if (hasUnsafePathCharacter(normalized) || hasParentTraversalSegment(normalized)) return null
        return guestAppScopedPathCandidate(normalized, originPackageName, File(dataRoot))
    }

    private fun stripPackagePrefix(path: String, prefix: String): String? {
        return when {
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.substring(prefix.length + 1)
            else -> null
        }
    }

    private fun guestPrivatePathRemainder(path: String, originPackageName: String): String? {
        return listOf(
            "/data/data/$originPackageName",
            "/data/user/0/$originPackageName"
        ).firstNotNullOfOrNull { prefix -> stripPackagePrefix(path, prefix) }
    }

    private fun guestAppScopedPathCandidate(
        path: String,
        originPackageName: String,
        dataRoot: File
    ): File? {
        guestPrivatePathRemainder(path, originPackageName)?.let { remainder ->
            return childPath(dataRoot, remainder)
        }
        listOf(
            "/storage/emulated/0/Android/data/$originPackageName",
            "/sdcard/Android/data/$originPackageName",
            "/mnt/sdcard/Android/data/$originPackageName",
            "/storage/self/primary/Android/data/$originPackageName"
        ).firstNotNullOfOrNull { prefix -> stripPackagePrefix(path, prefix) }?.let { remainder ->
            return redirectExternalPath(dataRoot, remainder)
        }
        listOf(
            "/storage/emulated/0/Android/obb/$originPackageName",
            "/sdcard/Android/obb/$originPackageName",
            "/mnt/sdcard/Android/obb/$originPackageName",
            "/storage/self/primary/Android/obb/$originPackageName"
        ).firstNotNullOfOrNull { prefix -> stripPackagePrefix(path, prefix) }?.let { remainder ->
            return childPath(File(dataRoot, "obb"), remainder)
        }
        return null
    }

    private fun redirectExternalPath(dataRoot: File, remainder: String): File {
        val clean = remainder.trim('/')
        val externalFiles = File(dataRoot, "external_files")
        val externalCache = File(dataRoot, "external_cache")
        return when {
            clean.isBlank() -> externalFiles
            clean == "files" -> externalFiles
            clean.startsWith("files/") -> childPath(externalFiles, clean.removePrefix("files/"))
            clean == "cache" -> externalCache
            clean.startsWith("cache/") -> childPath(externalCache, clean.removePrefix("cache/"))
            else -> childPath(externalFiles, clean)
        }
    }

    private fun childPath(parent: File, remainder: String): File {
        val clean = remainder.trim('/')
        return if (clean.isBlank()) parent else File(parent, clean)
    }

    internal fun hasParentTraversalSegment(path: String): Boolean {
        val normalized = normalizeSeparators(path)
        return normalized.split('/').any { it == ".." }
    }

    internal fun hasUnsafePathCharacter(path: String): Boolean =
        path.indexOf('\u0000') >= 0

    private fun normalizeSeparators(path: String): String =
        path.replace('\\', '/')

    private fun File.isWithin(root: File): Boolean {
        val filePath = normalizedPath()
        val rootPath = root.normalizedPath()
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private fun File.isWithin(root: File, fileSystem: NativePathFileSystem): Boolean {
        val filePath = normalizedPath(fileSystem)
        val rootPath = root.normalizedPath(fileSystem).trimEnd(File.separatorChar)
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private fun File.normalizedPath(): String =
        runCatching { canonicalFile.absolutePath }.getOrElse { absoluteFile.absolutePath }

    private fun File.normalizedPath(fileSystem: NativePathFileSystem): String =
        runCatching { fileSystem.canonicalFile(this).absolutePath }.getOrElse { absoluteFile.absolutePath }
}

internal interface NativePathFileSystem {
    fun canonicalFile(file: File): File
}

private object JvmNativePathFileSystem : NativePathFileSystem {
    override fun canonicalFile(file: File): File = file.canonicalFile
}

enum class VirtualStorageDiagnosticKind {
    JAVA_ABSOLUTE_PATH,
    NATIVE_IO
}

enum class VirtualStorageDiagnosticStatus {
    REDIRECTED,
    UNSUPPORTED,
    UNCHANGED
}

data class VirtualStoragePathDiagnostic(
    val kind: VirtualStorageDiagnosticKind,
    val status: VirtualStorageDiagnosticStatus,
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
    val openFlags: Int? = null,
    val processSlot: String? = null
)
