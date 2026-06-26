package com.multiapp.core.hook

data class Jiagu360DetectContext(
    val packageName: String? = null,
    val originApkPath: String? = null,
    val originLibDir: String? = null,
    val nativeLibPaths: List<String> = emptyList(),
    val classNames: Set<String> = emptySet()
)

data class Jiagu360VerifyContext(
    val loadedLibPaths: List<String> = emptyList(),
    val registerNativesEvents: List<RegisterNativesEvidence> = emptyList(),
    val errors: List<String> = emptyList(),
    val sourceDir: String? = null,
    val nativeLibraryDir: String? = null,
    val dataDir: String? = null
)

data class RegisterNativesEvidence(
    val className: String,
    val methodCount: Int,
    val result: Int,
    val source: String = "",
    val callerIsJiagu: Boolean = false,
    val allMultiAppMethods: Boolean = false,
    val hasInterface11: Boolean = false,
    val hasInterface20: Boolean = false,
    val jiaguComplete: Boolean = false,
    private val explicitOriginalShellPath: Boolean? = null
) {
    init {
        require(className.isNotBlank()) { "className must not be blank" }
        require(methodCount >= 0) { "methodCount must be non-negative" }
    }

    val originalShellPath: Boolean
        get() = explicitOriginalShellPath ?: (
            callerIsJiagu &&
                !allMultiAppMethods &&
                result == 0 &&
                methodCount >= 10 &&
                hasInterface11 &&
                hasInterface20
            )

    companion object {
        fun withExplicitOriginalShellPath(
            className: String,
            methodCount: Int,
            result: Int,
            source: String = "",
            originalShellPath: Boolean
        ): RegisterNativesEvidence = RegisterNativesEvidence(
            className = className,
            methodCount = methodCount,
            result = result,
            source = source,
            explicitOriginalShellPath = originalShellPath
        )
    }
}

enum class Jiagu360ProfileStatus {
    MATCH,
    NOT_MATCH,
    VERIFIED,
    INCOMPLETE
}

data class Jiagu360ProfileEvidence(
    val key: String,
    val value: String,
    val source: String = ""
) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
    }
}

data class Jiagu360ProfileResult(
    val status: Jiagu360ProfileStatus,
    val evidence: List<Jiagu360ProfileEvidence> = emptyList(),
    val missing: List<String> = emptyList()
) {
    val matched: Boolean
        get() = status == Jiagu360ProfileStatus.MATCH ||
            status == Jiagu360ProfileStatus.VERIFIED

    val verified: Boolean
        get() = status == Jiagu360ProfileStatus.VERIFIED
}

class Jiagu360Profile {

    val id: String = "jiagu360"

    fun detect(context: Jiagu360DetectContext): Jiagu360ProfileResult {
        val evidence = mutableListOf<Jiagu360ProfileEvidence>()

        val jiaguLibs = context.nativeLibPaths.filter { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            name == JIAGU_VIP_LIB || name.startsWith("libjiagu")
        }
        jiaguLibs.forEach { path ->
            evidence += Jiagu360ProfileEvidence("nativeLib", path, "apk")
        }

        val shellClasses = context.classNames.filter { it in SHELL_CLASSES }
        shellClasses.forEach { className ->
            evidence += Jiagu360ProfileEvidence("shellClass", className, "dex")
        }

        val matched = evidence.isNotEmpty()
        return Jiagu360ProfileResult(
            status = if (matched) Jiagu360ProfileStatus.MATCH else Jiagu360ProfileStatus.NOT_MATCH,
            evidence = evidence,
            missing = if (matched) emptyList() else listOf("libjiagu*.so", "com.stub.StubApp")
        )
    }

    fun verify(context: Jiagu360VerifyContext): Jiagu360ProfileResult {
        val evidence = mutableListOf<Jiagu360ProfileEvidence>()
        val missing = mutableListOf<String>()

        val jiaguLoaded = context.loadedLibPaths.any { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            name == JIAGU_VIP_LIB || name.startsWith("libjiagu")
        }
        if (jiaguLoaded) {
            evidence += Jiagu360ProfileEvidence(
                key = "loadedNativeLib",
                value = context.loadedLibPaths.first { it.contains("libjiagu") },
                source = "runtime"
            )
        } else {
            missing += "loaded libjiagu*.so"
        }

        val stubRegistration = context.registerNativesEvents.firstOrNull { event ->
            event.className in SHELL_CLASSES &&
                event.methodCount >= EXPECTED_STUB_NATIVE_COUNT &&
                event.result == 0 &&
                event.originalShellPath
        }
        if (stubRegistration != null) {
            evidence += Jiagu360ProfileEvidence(
                key = "registerNatives",
                value = "${stubRegistration.className}:${stubRegistration.methodCount}",
                source = stubRegistration.source
            )
        } else {
            missing += "original shell StubApp RegisterNatives from libjiagu_vip.so count >= $EXPECTED_STUB_NATIVE_COUNT with interface11/interface20"
        }

        val interface20Error = context.errors.any { error ->
            error.contains("UnsatisfiedLinkError") &&
                error.contains("com.stub.StubApp.interface20")
        }
        if (interface20Error) {
            missing += "no UnsatisfiedLinkError for com.stub.StubApp.interface20"
        }

        context.sourceDir?.let {
            evidence += Jiagu360ProfileEvidence("sourceDir", it, "runtime")
        }
        context.nativeLibraryDir?.let {
            evidence += Jiagu360ProfileEvidence("nativeLibraryDir", it, "runtime")
        }
        context.dataDir?.let {
            evidence += Jiagu360ProfileEvidence("dataDir", it, "runtime")
        }

        return Jiagu360ProfileResult(
            status = if (missing.isEmpty()) {
                Jiagu360ProfileStatus.VERIFIED
            } else {
                Jiagu360ProfileStatus.INCOMPLETE
            },
            evidence = evidence,
            missing = missing
        )
    }

    companion object {
        private const val JIAGU_VIP_LIB = "libjiagu_vip.so"
        private const val STUB_APP_CLASS = "com.stub.StubApp"
        private const val EXPECTED_STUB_NATIVE_COUNT = 10

        private val SHELL_CLASSES = setOf(
            STUB_APP_CLASS,
            "com.qihoo.util.StubApp"
        )
    }
}
