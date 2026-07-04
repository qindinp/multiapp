package com.multiapp.core.loader

import java.io.File

/** Deterministic diagnostics for storage paths that are not covered by Context APIs. */
object VirtualStoragePathDiagnostics {
    val DEFAULT_NATIVE_IO_OPERATIONS: List<String> = listOf(
        "open",
        "openat",
        "stat",
        "access",
        "fopen",
        "realpath"
    )

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
        operations: List<String> = DEFAULT_NATIVE_IO_OPERATIONS
    ): List<VirtualStoragePathDiagnostic> {
        return operations.map { operation ->
            val originalPath = "/data/data/$originPackageName/files/pr10-native-$operation.txt"
            val candidate = rewriteJavaAbsolutePath(
                originalPath = originalPath,
                originPackageName = originPackageName,
                dataRoot = dataRoot
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
                candidateRedirectedPath = candidate?.normalizedPath(),
                caller = caller,
                reason = "NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE",
                withinDataRoot = false,
                candidateWithinDataRoot = candidate?.isWithin(File(dataRoot))
            )
        }
    }

    fun rewriteJavaAbsolutePath(
        originalPath: String,
        originPackageName: String,
        dataRoot: String
    ): File? {
        if (originPackageName.isBlank() || dataRoot.isBlank()) return null
        val normalized = originalPath.replace('\\', '/')
        val internalRemainder = listOf(
            "/data/data/$originPackageName",
            "/data/user/0/$originPackageName"
        ).firstNotNullOfOrNull { prefix -> stripPackagePrefix(normalized, prefix) }
        if (internalRemainder != null) {
            return childPath(File(dataRoot), internalRemainder)
        }

        val externalRemainder = listOf(
            "/sdcard/Android/data/$originPackageName",
            "/storage/emulated/0/Android/data/$originPackageName"
        ).firstNotNullOfOrNull { prefix -> stripPackagePrefix(normalized, prefix) }
        return externalRemainder?.let { redirectExternalPath(File(dataRoot), it) }
    }

    private fun stripPackagePrefix(path: String, prefix: String): String? {
        return when {
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.substring(prefix.length + 1)
            else -> null
        }
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

    private fun File.isWithin(root: File): Boolean {
        val filePath = normalizedPath()
        val rootPath = root.normalizedPath()
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private fun File.normalizedPath(): String =
        runCatching { canonicalFile.absolutePath }.getOrElse { absoluteFile.absolutePath }
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
    val candidateWithinDataRoot: Boolean?
)
