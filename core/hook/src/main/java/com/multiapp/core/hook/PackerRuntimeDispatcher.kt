package com.multiapp.core.hook

import android.util.Log
import java.io.File

/**
 * PackerRuntimeDispatcher — 根据 APK 特征自动选择合适的 PackerRuntime
 *
 * 使用策略：
 * 1. 遍历所有已注册的 PackerRuntime
 * 2. 调用 detect() 检测 APK 特征
 * 3. 选择第一个匹配的 Runtime 执行加载流程
 *
 * 从 LoaderFactory.preloadPackerLibViaGuestClassLoader() 中提取调度逻辑，
 * 使 LoaderFactory 只需调用 dispatcher.execute(context) 即可完成壳加载。
 */
class PackerRuntimeDispatcher {

    companion object {
        private const val TAG = "PackerRuntimeDispatcher"

        @Volatile
        private var instance: PackerRuntimeDispatcher? = null

        fun getInstance(): PackerRuntimeDispatcher {
            return instance ?: synchronized(this) {
                instance ?: PackerRuntimeDispatcher().also { instance = it }
            }
        }
    }

    private val runtimes = mutableListOf<PackerRuntime>()

    init {
        // 注册所有已知的 PackerRuntime 实现
        register(JiaguRuntime())
    }

    /**
     * 注册一个新的 PackerRuntime 实现。
     * 先注册的优先匹配。
     */
    fun register(runtime: PackerRuntime) {
        runtimes.add(runtime)
        Log.d(TAG, "Registered runtime: ${runtime.name}")
    }

    /**
     * 根据 APK 特征检测并返回匹配的 PackerRuntime。
     *
     * @param originLibDir  原始 APK 解压后的 native lib 目录
     * @param originApkPath 原始 APK 路径
     * @return 匹配的 Runtime，或 null
     */
    fun detect(originLibDir: File?, originApkPath: String?): PackerRuntime? {
        for (runtime in runtimes) {
            try {
                if (runtime.detect(originLibDir, originApkPath)) {
                    Log.i(TAG, "Detected packer: ${runtime.name}")
                    return runtime
                }
            } catch (e: Throwable) {
                Log.w(TAG, "detect() failed for ${runtime.name}: ${e.message}")
            }
        }
        Log.d(TAG, "No packer detected")
        return null
    }

    /**
     * 执行完整的壳加载流程。
     *
     * 流程：detect → prepareFiles → loadPackerLibrary → verifyRegisterNatives → installPostLoadHooks → installStubFallback
     *
     * @param context 运行时上下文
     * @return 加载结果，如果未检测到加固壳则返回 null
     */
    fun execute(context: PackerRuntimeContext): PackerLoadResult? {
        val originLibDir = context.originLibDir?.let { File(it) }
        val runtime = detect(originLibDir, context.originApkPath)
            ?: return null

        Log.i(TAG, "Executing packer runtime: ${runtime.name}")

        // Step 1: 准备文件和环境
        try {
            val prepared = runtime.prepareFiles(context)
            if (!prepared) {
                Log.w(TAG, "prepareFiles() returned false for ${runtime.name}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "prepareFiles() failed for ${runtime.name}", e)
        }

        // Step 2: 加载壳库
        val loadResult = try {
            runtime.loadPackerLibrary(context)
        } catch (e: Throwable) {
            Log.e(TAG, "loadPackerLibrary() failed for ${runtime.name}", e)
            PackerLoadResult(false, false, diagnostics = listOf("Exception: ${e.message}"))
        }

        // 打印诊断信息
        loadResult.diagnostics.forEach { Log.d(TAG, "  [${runtime.name}] $it") }

        // Step 3: 验证 RegisterNatives
        val verified = try {
            runtime.verifyRegisterNatives(context.guestClassLoader)
        } catch (e: Throwable) {
            Log.w(TAG, "verifyRegisterNatives() failed: ${e.message}")
            false
        }
        Log.i(TAG, "RegisterNatives verified: $verified")

        // Step 4: 安装加载后 hook
        try {
            runtime.installPostLoadHooks(context, loadResult)
        } catch (e: Throwable) {
            Log.w(TAG, "installPostLoadHooks() failed: ${e.message}")
        }

        // Step 5: 安装 stub fallback
        try {
            runtime.installStubFallback(context, loadResult)
        } catch (e: Throwable) {
            Log.w(TAG, "installStubFallback() failed: ${e.message}")
        }

        Log.i(TAG, "Packer runtime ${runtime.name} complete: jiaguLoaded=${loadResult.jiaguLoaded}, stubLoad=${loadResult.stubAppLoadSucceeded}, verified=$verified")

        return loadResult
    }

    /**
     * 获取所有已注册的 Runtime 名称（用于诊断）。
     */
    fun getRegisteredRuntimeNames(): List<String> = runtimes.map { it.name }
}
