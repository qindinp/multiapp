package com.multiapp.core.hook

/**
 * Configuration flags controlling which evidence sources are collected
 * during native diagnostics. Root-requiring flags default to false.
 */
data class NativeDiagnosticsConfig(
    val recordNativeLoad: Boolean = true,
    val recordJniOnLoad: Boolean = true,
    val recordFindClass: Boolean = true,
    val recordRegisterNatives: Boolean = true,
    val recordLibraryPath: Boolean = true,
    val recordNativeNamespace: Boolean = false, // requires root
    val recordClassLoader: Boolean = true,
    val recordProcMaps: Boolean = false, // requires root
    val recordLinkerMessage: Boolean = false // requires root
)

/**
 * A single piece of evidence collected during native library loading diagnostics.
 *
 * @param key Identifier for the evidence type (e.g. "jni_onload_executed")
 * @param value The observed value (e.g. "true", "com.stub.StubApp")
 * @param source Where the evidence was collected from (e.g. "HookEngine", "procfs")
 * @param timestampMs Unix epoch millis when the evidence was captured
 */
data class NativeDiagnosticsEvidence(
    val key: String,
    val value: String,
    val source: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Root-cause verdict for interface-20 (register-natives) failures in
 * QQ Reader container scenarios.
 */
enum class Interface20Verdict {
    /** Shell native library did not load; JNI_OnLoad never ran. */
    JNI_ONLOAD_NOT_EXECUTED,

    /** JNI_OnLoad ran but RegisterNatives was never called for StubApp. */
    REGISTER_NATIVES_NOT_EXECUTED,

    /** RegisterNatives was called but targeted a class other than StubApp. */
    REGISTER_NATIVES_WRONG_CLASS,

    /** FindClass resolved StubApp from the wrong ClassLoader. */
    FIND_CLASS_WRONG_CLASSLOADER,

    /** Native namespace mismatch between shell and container. */
    NATIVE_NAMESPACE_MISMATCH,

    /** Shell detected the container environment and skipped registration. */
    SHELL_DETECTED_CONTAINER,

    /** Original shell path RegisterNatives completed successfully. */
    ORIGINAL_SHELL_REGISTERED,

    /** Only MultiApp fallback RegisterNatives observed, not original shell. */
    FALLBACK_REGISTERED,

    /** Unable to determine root cause. */
    UNKNOWN,

    /** Not enough evidence collected to reach a conclusion. */
    INSUFFICIENT_EVIDENCE
}

/**
 * Result of a native diagnostics analysis pass.
 */
data class NativeDiagnosticsResult(
    val config: NativeDiagnosticsConfig,
    val evidence: List<NativeDiagnosticsEvidence>,
    val verdict: Interface20Verdict,
    val verdictReason: String
)

/**
 * Analyzes collected evidence to produce a root-cause verdict for
 * interface-20 register-natives behavior in QQ Reader.
 *
 * This is a pure-function analysis engine -- it does not collect evidence,
 * install hooks, or touch any Android runtime. Callers gather evidence
 * from HookEngine, NativeHookBridge, or /proc parsing, then feed it here.
 */
object NativeDiagnosticsProfile {

    private const val KEY_JNI_ONLOAD_EXECUTED = "jni_onload_executed"
    private const val KEY_REGISTER_NATIVES_EXECUTED = "register_natives_executed"
    private const val KEY_REGISTER_NATIVES_CLASS = "register_natives_class"
    private const val KEY_ORIGINAL_SHELL_PATH = "original_shell_path"
    private const val KEY_FALLBACK_REGISTERED = "fallback_registered"

    private val VALID_SHELL_CLASSES = setOf(
        "com.stub.StubApp",
        "com.qihoo.util.StubApp"
    )

    /**
     * Analyze the given evidence against the provided config and return
     * a [NativeDiagnosticsResult] with the best-fit [Interface20Verdict].
     */
    fun analyze(
        config: NativeDiagnosticsConfig,
        evidence: List<NativeDiagnosticsEvidence>
    ): NativeDiagnosticsResult {
        val verdict = determineVerdict(evidence)
        val reason = buildVerdictReason(verdict, evidence)

        return NativeDiagnosticsResult(
            config = config,
            evidence = evidence,
            verdict = verdict,
            verdictReason = reason
        )
    }

    private fun determineVerdict(
        evidence: List<NativeDiagnosticsEvidence>
    ): Interface20Verdict {
        val hasJniOnLoad = evidence.any {
            it.key == KEY_JNI_ONLOAD_EXECUTED && it.value == "true"
        }
        val hasRegisterNatives = evidence.any {
            it.key == KEY_REGISTER_NATIVES_EXECUTED && it.value == "true"
        }
        val registerNativesClass = evidence.firstOrNull {
            it.key == KEY_REGISTER_NATIVES_CLASS
        }?.value
        val originalShellPath = evidence.any {
            it.key == KEY_ORIGINAL_SHELL_PATH && it.value == "true"
        }
        val fallbackRegistered = evidence.any {
            it.key == KEY_FALLBACK_REGISTERED && it.value == "true"
        }

        return when {
            !hasJniOnLoad ->
                Interface20Verdict.JNI_ONLOAD_NOT_EXECUTED

            !hasRegisterNatives ->
                Interface20Verdict.REGISTER_NATIVES_NOT_EXECUTED

            registerNativesClass !in VALID_SHELL_CLASSES ->
                Interface20Verdict.REGISTER_NATIVES_WRONG_CLASS

            originalShellPath ->
                Interface20Verdict.ORIGINAL_SHELL_REGISTERED

            fallbackRegistered ->
                Interface20Verdict.FALLBACK_REGISTERED

            else ->
                Interface20Verdict.INSUFFICIENT_EVIDENCE
        }
    }

    private fun buildVerdictReason(
        verdict: Interface20Verdict,
        evidence: List<NativeDiagnosticsEvidence>
    ): String {
        val registeredClass = evidence.firstOrNull {
            it.key == KEY_REGISTER_NATIVES_CLASS
        }?.value

        return when (verdict) {
            Interface20Verdict.JNI_ONLOAD_NOT_EXECUTED ->
                "JNI_OnLoad was not executed; shell native library may not have loaded"

            Interface20Verdict.REGISTER_NATIVES_NOT_EXECUTED ->
                "JNI_OnLoad executed but RegisterNatives was not called for StubApp"

            Interface20Verdict.REGISTER_NATIVES_WRONG_CLASS ->
                "RegisterNatives called for wrong class: $registeredClass"

            Interface20Verdict.ORIGINAL_SHELL_REGISTERED ->
                "Original shell path RegisterNatives completed successfully"

            Interface20Verdict.FALLBACK_REGISTERED ->
                "Only MultiApp fallback RegisterNatives observed, not original shell"

            Interface20Verdict.SHELL_DETECTED_CONTAINER ->
                "Shell detected container environment and skipped registration"

            Interface20Verdict.FIND_CLASS_WRONG_CLASSLOADER ->
                "FindClass resolved StubApp from wrong ClassLoader"

            Interface20Verdict.NATIVE_NAMESPACE_MISMATCH ->
                "Native namespace mismatch between shell and container"

            Interface20Verdict.UNKNOWN ->
                "Unable to determine root cause from available evidence"

            Interface20Verdict.INSUFFICIENT_EVIDENCE ->
                "Not enough evidence to determine root cause"
        }
    }
}
