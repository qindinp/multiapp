package com.multiapp.core.loader

import com.multiapp.core.hook.HookEngine
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.hook.NativeHookCapability
import com.multiapp.core.hook.NativeHookPolicy
import com.multiapp.core.hook.NativeHookPolicyMode
import com.multiapp.core.hook.PackerRuntimeContext
import com.multiapp.core.hook.PackerRuntimeAdaptation
import com.multiapp.core.hook.PackerRuntimeDispatcher
import com.multiapp.core.hook.antidetection.PackerDetectionEvidence
import com.multiapp.core.hook.antidetection.PackerDetector
import java.io.File

/**
 * Hosted bootstrap stage that detects and adapts packed (加固) guest apps.
 *
 * Legacy LoaderFactory ran PackerRuntimeDispatcher inside its own class loader
 * swap path; hosted mode bypassed LoaderFactory entirely, so packed apps
 * (WeChat / Qidian / WPS / Weibo) reached Application creation without any
 * shell adaptation. This stage closes that gap: it runs after ClassLoader
 * creation and before Application creation, exactly where the shell's
 * StubApp/RegisterNatives bootstrap must be intercepted.
 *
 * The stage is intentionally non-terminal: a detection/load failure is
 * recorded as evidence so the downstream Application failure carries the
 * packer diagnostics, instead of hiding them behind a separate stage abort.
 */
class PackerRuntimeStage(
    private val packerEnabled: Boolean,
    private val dispatcherProvider: () -> PackerRuntimeAdaptation = {
        PackerRuntimeDispatcher.getInstance()
    },
    private val nativeHookPolicyProvider: (Boolean) -> NativeHookPolicy =
        { enabled -> if (enabled) compatibilityHookPolicy() else NativeHookPolicy.baseline() },
    private val hostContextProvider: (() -> android.content.Context?)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val guestClassLoader = input.guestClassLoader
            ?: return skipped(
                input = input,
                startMs = startMs,
                reason = "GUEST_CLASS_LOADER_MISSING",
                detail = "Packer adaptation requires a guest ClassLoader"
            )
        if (!packerEnabled) {
            return skipped(
                input = input,
                startMs = startMs,
                reason = "PACKER_ADAPTATION_DISABLED",
                detail = "packerEnabled=false; packed-app shell adaptation skipped"
            )
        }

        val originApkPath = input.originApkPath
            ?: return skipped(
                input = input,
                startMs = startMs,
                reason = "ORIGIN_APK_MISSING",
                detail = "Packer adaptation requires the origin APK path"
            )
        val instance = input.instance
            ?: return skipped(
                input = input,
                startMs = startMs,
                reason = "INSTANCE_MISSING",
                detail = "Packer adaptation requires the virtual instance"
            )

        // Spoof /proc/self/cmdline to the guest identity before detection so apps
        // whose shell is NOT recognized (No packer detected) still see the original
        // package name instead of the virtual :vN suffix in native reads. This also
        // covers shells that reach JiaguRuntime.prepareFiles after a successful detect.
        // 进程名还原目标：组件进程用 effectiveGuestProcessName（如 com.tencent.mm:sandbox /
        // cn.wps.moffice_eng），主进程退回 origin 包名。native cmdline spoof 与
        // Java 通用 getter hook 都使用这个名字，保证微信看门狗与 WPS RePlugin 看到「自己人」。
        val guestProcessName = input.effectiveGuestProcessName?.takeIf(String::isNotBlank)
            ?: instance.originPackageName
        runCatching {
            NativeHookBridge.getInstance().spoofProcSelf(
                android.os.Process.myPid(),
                guestProcessName
            )
        }

        // 通用 Java 进程名还原（对齐 VirtualApp 运行期进程名还原）：hook
        // Application.getProcessName() / ActivityThread.currentProcessName() /
        // Process.myProcessName()，组件进程返回 guest 原名，主进程返回包名。
        val genericProcessNameHook = GuestProcessNameCompat.install(
            guestProcessName = guestProcessName,
            hookEngine = HookEngine.getInstance()
        )
        runCatching {
            android.util.Log.d("PackerRuntimeStage", "Guest process-name hooks: " + genericProcessNameHook)
        }

        // WeChat 特判：混淆类 in5.f1.a() 绕过通用 getter 直接读进程名，仍需专属 hook。
        if (MicroMsgProcessNameCompat.isMicroMsgPackage(instance.originPackageName)) {
            val hookResult = MicroMsgProcessNameCompat.installProcessNameHook(
                guestClassLoader,
                HookEngine.getInstance()
            )
            android.util.Log.d("PackerRuntimeStage", "MicroMsg process-name hook: " + hookResult)
            // WeChat's embedded Flutter init calls Context.getDir() on contexts
            // whose LoadedApk can have a null dataDir ("No data directory found
            // for package com.tencent.mm"). Install the instance-data-dir
            // fallback so getDir() has a writable root for the guest.
            val dataDirHookResult = MicroMsgProcessNameCompat.installDataDirFallbackHook(
                guestDataDir = instance.dataRoot,
                originPackageName = instance.originPackageName,
                virtualPackageName = instance.virtualPackageName,
                hookEngine = HookEngine.getInstance()
            )
            android.util.Log.d("PackerRuntimeStage", "MicroMsg getDataDir fallback hook: " + dataDirHookResult)
        }

        // P0-1: 微信/WPS 在 guest Application.onCreate 期间 System.exit(1) 自保护。
        // native _exit 抑制时机太晚（ART 先提交 VM 关停，libc 拦截时 VM 已僵尸），
        // 必须在 Java 层、ART 提交 VM 关停之前吞掉。bootstrap 窗口 = 安装后 ->
        // guest Application.onCreate 完成（ApplicationStage 关闭窗口）。
        val javaExitHookResult = JavaExitSuppressionHook.install(
            hookEngine = HookEngine.getInstance(),
            guestClassLoader = guestClassLoader
        )
        runCatching {
            android.util.Log.d("PackerRuntimeStage", "Java exit suppression hook: " + javaExitHookResult)
        }
        // P0-1 诊断：微信/WPS 加固在窗口外也会主动 System.exit，打开全量退出栈日志
        // 定位看门狗自杀点（该开关不影响 exit 放行语义，只多打日志）。
        if (javaExitHookResult.anyHooked) {
            JavaExitSuppressionHook.enableAlwaysLogExitStacks()
        }

        val originLibDir = input.nativeLibraryDir?.takeIf(String::isNotBlank)
        val nativeHookPolicy = nativeHookPolicyProvider(packerEnabled)

        // Pre-detect full native hook install: install the FULL hook profile
        // (exit/_exit suppression, dlopen, ptrace, property spoofing) before
        // Application creation even when no packer shell is detected. Verified
        // 2026-08-04 on-device: QQReader only survived its System.exit(1) fatal
        // path because Jiagu360 detection installed the FULL profile; WeChat
        // (no packer detected) had only path-redirect hooks and its main process
        // died at srvinit with 'System.exit called, status: 1'. This pre-detect
        // install closes that gap for generic (unpacked) guests.
        val preDetectNativeHooks = runCatching {
            NativeHookBridge.getInstance().initNativeHooks(
                policy = nativeHookPolicy,
                component = "PackerRuntimeStage.preDetect.initNativeHooks"
            )
        }.getOrDefault(false)
        runCatching {
            android.util.Log.d(
                "PackerRuntimeStage",
                "pre-detect native hooks (exit/_exit suppression): $preDetectNativeHooks"
            )
        }

        // Typed packer detection (family/confidence/signals/strategy) recorded into
        // stage evidence so the 兼容率报表 can aggregate per-family outcomes. The
        // dispatcher re-detects internally; PackerDetector caches by apk key so the
        // second scan is O(1). Detection failure degrades to UNKNOWN evidence.
        val packerEvidence: PackerDetectionEvidence = runCatching {
            PackerDetector.detectEvidence(originApkPath)
        }.getOrElse {
            PackerDetectionEvidence.UNKNOWN
        }

        val dispatcher = runCatching { dispatcherProvider() }.getOrElse { error ->
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.degraded(
                    stage = RuntimeStage.PACKER_RUNTIME,
                    message = "Packer dispatcher unavailable: " + error.javaClass.simpleName + ": " + error.message,
                    error = error,
                    evidence = listOf(
                        BootstrapEvidence("packerStage", "DISPATCHER_UNAVAILABLE"),
                        BootstrapEvidence("originPackageName", instance.originPackageName)
                    ),
                    durationMs = clock() - startMs
                )
            )
        }

        val hostContext = hostContextProvider?.invoke()
        val stubApkPath = hostContext?.applicationInfo?.sourceDir.orEmpty()

        val packerContext = PackerRuntimeContext(
            guestClassLoader = guestClassLoader,
            originLibDir = originLibDir,
            originApkPath = originApkPath,
            originalApkPath = originApkPath,
            originalPackageName = instance.originPackageName,
            stubPackageName = instance.virtualPackageName,
            cloneProfile = if (QqReaderProfile.isQqReaderPackage(instance.originPackageName)) "QQ_READER_SPECIAL" else null,
            dataDir = instance.dataRoot,
            stubApkPath = stubApkPath,
            bridge = NativeHookBridge.getInstance(),
            hookEngine = HookEngine.getInstance(),
            nativeHookPolicy = nativeHookPolicy
        )

        // detect() gives us the matched runtime name; execute() performs the
        // full lifecycle. Both use the same origin lib/apk facts.
        val detectedRuntime = runCatching {
            dispatcher.detect(
                originLibDir?.let(::File),
                originApkPath
            )
        }.getOrNull()
        val packerName = detectedRuntime?.name
        val result = runCatching { dispatcher.execute(packerContext) }
        val output = result.getOrElse { error ->
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.degraded(
                    stage = RuntimeStage.PACKER_RUNTIME,
                    message = "Packer adaptation threw " + error.javaClass.simpleName + ": " + error.message,
                    error = error,
                    evidence = listOf(
                        BootstrapEvidence("packerStage", "EXCEPTION"),
                        BootstrapEvidence("packerEnabled", packerEnabled.toString()),
                        BootstrapEvidence("originPackageName", instance.originPackageName),
                        BootstrapEvidence("originApkPath", originApkPath),
                        BootstrapEvidence("nativeLibraryDir", originLibDir.orEmpty()),
                        BootstrapEvidence("policyMode", nativeHookPolicy.mode.name)
                    ),
                    durationMs = clock() - startMs
                )
            )
        }

        if (output == null) {
            return skipped(
                input = input,
                startMs = startMs,
                reason = "NO_PACKER_DETECTED",
                detail = "No packed shell detected for " + instance.originPackageName,
                originPackageName = instance.originPackageName,
                extraEvidence = listOf(
                    BootstrapEvidence("preDetectNativeHooks", preDetectNativeHooks.toString()),
                    BootstrapEvidence("javaExitHook", javaExitHookResult.toString()),
                    BootstrapEvidence("packerFamily", packerEvidence.family.name)
                )
            )
        }

        val evidence = mutableListOf(
            BootstrapEvidence("packerStage", "COMPLETE"),
            BootstrapEvidence("packerName", packerName.orEmpty()),
            BootstrapEvidence("packerFamily", packerEvidence.family.name),
            BootstrapEvidence("packerConfidence", packerEvidence.confidence.name),
            BootstrapEvidence("packerStrategy", packerEvidence.strategy.name),
            BootstrapEvidence("packerSignals", packerEvidence.signals.joinToString("|") { "${it.level.name}:${it.pattern}" }),
            BootstrapEvidence("packerEnabled", packerEnabled.toString()),
            BootstrapEvidence("originPackageName", instance.originPackageName),
            BootstrapEvidence("originLibDir", originLibDir.orEmpty()),
            BootstrapEvidence("originApkPath", originApkPath),
            BootstrapEvidence("policyMode", nativeHookPolicy.mode.name),
            BootstrapEvidence("preDetectNativeHooks", preDetectNativeHooks.toString()),
            BootstrapEvidence("javaExitHook", javaExitHookResult.toString()),
            BootstrapEvidence("jiaguLoaded", output.jiaguLoaded.toString()),
            BootstrapEvidence("stubAppLoadSucceeded", output.stubAppLoadSucceeded.toString()),
            BootstrapEvidence("stubNativesVerified", output.stubNativesVerified.toString()),
            BootstrapEvidence("loadedLibPaths", output.loadedLibPaths.joinToString(",")),
            BootstrapEvidence("diagnostics", output.diagnostics.joinToString(" | "))
        )
        output.registerNativesEvidence.forEachIndexed { index, evidenceItem ->
            evidence += BootstrapEvidence(
                "registerNatives[" + index + "]",
                evidenceItem.className + " count=" + evidenceItem.methodCount +
                    " result=" + evidenceItem.result + " originalShellPath=" + evidenceItem.originalShellPath
            )
        }

        val status = if (output.jiaguLoaded || output.stubNativesVerified) {
            BootstrapStatus.SUCCESS
        } else {
            BootstrapStatus.DEGRADED
        }
        return BootstrapStageOutput(
            context = input,
            result = BootstrapResult(
                stage = RuntimeStage.PACKER_RUNTIME,
                status = status,
                message = "Packer " + packerName.orEmpty() + " adaptation: " +
                    "jiaguLoaded=" + output.jiaguLoaded + " stubLoad=" + output.stubAppLoadSucceeded + " " +
                    "verified=" + output.stubNativesVerified,
                evidence = evidence,
                durationMs = clock() - startMs
            )
        )
    }

    private fun skipped(
        input: BootstrapStageInput,
        startMs: Long,
        reason: String,
        detail: String,
        originPackageName: String? = null,
        extraEvidence: List<BootstrapEvidence> = emptyList()
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.skipped(
            stage = RuntimeStage.PACKER_RUNTIME,
            message = detail,
            evidence = listOfNotNull(
                BootstrapEvidence("packerStage", "SKIPPED"),
                BootstrapEvidence("packerSkipReason", reason),
                originPackageName?.let { BootstrapEvidence("originPackageName", it) }
            ) + extraEvidence
        ).copy(durationMs = clock() - startMs)
    )

    companion object {
        /**
         * Full compatibility hook policy for packed apps. Unlike
         * NativeHookPolicy.compatibility() (which keeps native base hooks off),
         * this enables the shadow-hook / LSPlant / stub machinery that packed
         * shells require. It is only ever selected when [PackerRuntimeStage] is
         * explicitly enabled (providerHookEnabled=true).
         */
        fun compatibilityHookPolicy(): NativeHookPolicy =
            NativeHookPolicy.fromCapabilities(
                mode = NativeHookPolicyMode.COMPATIBILITY,
                enabledCapabilities = setOf(
                    NativeHookCapability.CONTAINER_IDENTITY_VIRTUALIZATION,
                    NativeHookCapability.PACKAGE_MANAGER_VIRTUALIZATION,
                    NativeHookCapability.PATH_VIRTUALIZATION,
                    NativeHookCapability.NATIVE_BASE_HOOKS,
                    NativeHookCapability.LSPLANT_METHOD_HOOKS,
                    NativeHookCapability.XPOSED_MODULES,
                    NativeHookCapability.BUSINESS_NATIVE_STUBS,
                    NativeHookCapability.METHOD_REPLACEMENT,
                    NativeHookCapability.NO_OP_PATCHES
                )
            ).copy(cmdlineSpoof = true)
    }
}


