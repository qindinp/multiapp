package com.multiapp.core.hook

import com.multiapp.core.hook.antidetection.PackerFamily
import java.io.File

/**
 * PackerRuntime — 加固壳运行时策略接口
 *
 * 将 LoaderFactory 中与特定加固壳（如 360 jiagu）耦合的逻辑抽象为通用接口，
 * 使不同加固壳可以独立实现、按 APK 特征自动选择。
 *
 * 生命周期：
 *   1. detect()           — 检测 APK 是否由该加固壳保护
 *   2. prepareFiles()     — 准备壳运行所需的文件（native lib、DEX 等）
 *   3. loadPackerLibrary() — 通过 guest ClassLoader 加载壳的 native 库
 *   4. verifyRegisterNatives() — 验证壳的 JNI RegisterNatives 是否成功
 *   5. installPostLoadHooks()  — 加载后安装业务兼容 hook（可选）
 */
interface PackerRuntime {

    /** 运行时名称，用于日志和诊断 */
    val name: String

    /**
     * 该运行时是否支持指定的加固家族（Phase1 按家族路由）。
     *
     * [PackerRuntimeDispatcher] 在 detect() 阶段优先查询此方法进行家族路由：
     * 先匹配 [PackerFamily] 专用适配器，再回退到传统顺序遍历 detect()。
     *
     * 默认返回 false（通用运行时不做家族声明，走 detect() 兜底）；
     * 专用运行时按需覆写，如 [JiaguRuntime] 覆写 QIHOO_360 = true。
     */
    fun supportsFamily(family: PackerFamily): Boolean = false

    /**
     * 检测 APK 是否由此加固壳保护。
     *
     * @param originLibDir  原始 APK 解压后的 native lib 目录
     * @param originApkPath 原始 APK 路径
     * @return true 如果检测到此加固壳的特征
     */
    fun detect(originLibDir: File?, originApkPath: String?): Boolean

    /**
     * 准备壳运行所需的文件和环境。
     * 包括：完整性校验重定向、GOT hook 预装、FindClass hook 设置等。
     *
     * @param context 运行时上下文
     * @return true 如果准备成功
     */
    fun prepareFiles(context: PackerRuntimeContext): Boolean

    /**
     * 通过 guest ClassLoader 加载壳的 native 库。
     * 实现应负责：ShadowHook 初始化、FindClass hook、dlopen/nativeLoad、
     * StubApp.load() 调用等完整加载流程。
     *
     * @param context 运行时上下文
     * @return 加载结果
     */
    fun loadPackerLibrary(context: PackerRuntimeContext): PackerLoadResult

    /**
     * 验证壳的 RegisterNatives 是否成功完成。
     * 例如检查 StubApp.interface20 等关键 native 方法是否已注册。
     *
     * @param context runtime context
     * @return true 如果关键 native 方法已注册
     */
    fun verifyRegisterNatives(guestCl: ClassLoader): Boolean

    /**
     * 加载后安装的业务兼容 hook（可选）。
     * 例如：LSPlant 初始化、AntiDetectionEngine、特定 SDK 的 hook 等。
     *
     * @param context 运行时上下文
     * @param loadResult 加载结果
     */
    fun installPostLoadHooks(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 安装壳 native 方法的 stub fallback（可选）。
     * 当壳自身 RegisterNatives 失败时，提供最小 stub 实现。
     *
     * @param guestCl guest ClassLoader
     * @param loadResult 加载结果
     */
    fun installStubFallback(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        // 默认空实现
    }
}

/**
 * PackerRuntime 执行上下文，封装 LoaderFactory 传递的运行时状态。
 */
data class PackerRuntimeContext(
    val guestClassLoader: ClassLoader,
    val originLibDir: String?,
    val originApkPath: String?,
    val originalApkPath: String?,
    val originalPackageName: String?,
    val stubPackageName: String? = null,
    val cloneProfile: String?,
    val dataDir: String?,
    val stubApkPath: String,
    val bridge: NativeHookBridge,
    val hookEngine: HookEngine,
    val nativeHookPolicy: NativeHookPolicy = NativeHookPolicy.baseline(),
)

/**
 * 壳库加载结果。
 */
data class PackerLoadResult(
    /** native 库是否通过 guest ClassLoader 成功加载 */
    val jiaguLoaded: Boolean,
    /** StubApp.load() 是否成功调用 */
    val stubAppLoadSucceeded: Boolean,
    /** 已加载的 native 库路径列表 */
    val loadedLibPaths: List<String> = emptyList(),
    /** 加载过程中的诊断信息 */
    val diagnostics: List<String> = emptyList(),
    /** verifyRegisterNatives 确认壳已注册 StubApp natives（即使我们的 hook 未捕获） */
    val stubNativesVerified: Boolean = false,
    /** 结构化 RegisterNatives evidence，用于 profile verify 闭环 */
    val registerNativesEvidence: List<RegisterNativesEvidence> = emptyList()
)
