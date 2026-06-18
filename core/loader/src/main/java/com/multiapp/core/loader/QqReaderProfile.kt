package com.multiapp.core.loader

import android.util.Log

/**
 * QQ Reader 兼容配置 — 集中管理 QQ 阅读器应用的 hook 和兼容逻辑。
 *
 * 从 LoaderFactory.installAppSpecificPostLoadHooks 提取：
 * - ShortcutManager.cihai() 跳过快捷方式创建（icon 资源找不到会崩溃）
 * - ReaderApplication.initPushSDK() 跳过推送初始化（YWPushSDK Bundle NPE）
 * - Pangle 广告 SDK (ZeusPlatformUtils.initZeus) 跳过初始化
 * - QQReader 文件/Provider/协议诊断 hook
 * - QQReader eqct plaintext 兼容
 *
 * 所有 hook 使用 LSPlant（通过 HookEngine），需要 lsplantOk=true。
 */
object QqReaderProfile {

    private const val TAG = "QqReaderProfile"

    /**
     * QQReader hook 安装结果。
     */
    data class HookResult(
        val shortcutHooked: Boolean = false,
        val pushHooked: Boolean = false,
        val pangleHooked: Boolean = false,
        val fileDiagInstalled: Boolean = false,
        val providerDiagInstalled: Boolean = false,
        val protocolDiagInstalled: Boolean = false,
        val eqctCompatInstalled: Boolean = false,
        val loginDiagInstalled: Boolean = false
    ) {
        val anyInstalled: Boolean
            get() = shortcutHooked || pushHooked || pangleHooked ||
                fileDiagInstalled || providerDiagInstalled || protocolDiagInstalled ||
                eqctCompatInstalled || loginDiagInstalled
    }

    /**
     * 安装所有 QQReader 兼容 hook。
     *
     * @param guestCl guest ClassLoader（加载原始 APK 类）
     * @param hookEngine HookEngine 实例
     * @param lsplantOk LSPlant 是否初始化成功
     * @return hook 安装结果
     */
    fun installAll(
        guestCl: ClassLoader,
        hookEngine: com.multiapp.core.hook.HookEngine,
        lsplantOk: Boolean
    ): HookResult {
        var result = HookResult()

        result = result.copy(shortcutHooked = installShortcutHook(guestCl, hookEngine, lsplantOk))
        result = result.copy(pushHooked = installPushHook(guestCl, hookEngine, lsplantOk))
        result = result.copy(pangleHooked = installPangleHook(guestCl, hookEngine, lsplantOk))

        if (lsplantOk) {
            val diag = installDiagnosticHooks(hookEngine, guestCl)
            result = result.copy(
                fileDiagInstalled = diag.fileDiag,
                providerDiagInstalled = diag.providerDiag,
                protocolDiagInstalled = diag.protocolDiag,
                eqctCompatInstalled = diag.eqctCompat,
                loginDiagInstalled = diag.loginDiag
            )
        }

        Log.d(TAG, "Hook result: $result")
        return result
    }

    /**
     * 跳过 ShortcutManager.cihai() — icon 资源找不到会崩溃。
     */
    fun installShortcutHook(
        guestCl: ClassLoader,
        hookEngine: com.multiapp.core.hook.HookEngine,
        lsplantOk: Boolean
    ): Boolean {
        return try {
            val shortcutClass = Class.forName("com.qq.reader.shortcut.ShortcutManager", false, guestCl)
            val cihaiMethod = shortcutClass.declaredMethods.firstOrNull { it.name == "cihai" }
            if (cihaiMethod != null && lsplantOk) {
                hookEngine.hookMethod(
                    cihaiMethod,
                    beforeCallback = { _, _ ->
                        Log.d(TAG, "Hooked ShortcutManager.cihai — skipping shortcut creation")
                        null
                    }
                )
                Log.d(TAG, "ShortcutManager.cihai hooked")
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.d(TAG, "ShortcutManager hook skipped: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 跳过 ReaderApplication.initPushSDK() — YWPushSDK Bundle NPE。
     */
    fun installPushHook(
        guestCl: ClassLoader,
        hookEngine: com.multiapp.core.hook.HookEngine,
        lsplantOk: Boolean
    ): Boolean {
        return try {
            val readerAppClass = Class.forName("com.qq.reader.ReaderApplication", false, guestCl)
            val initPushMethod = readerAppClass.declaredMethods.firstOrNull { it.name == "initPushSDK" }
            if (initPushMethod != null && lsplantOk) {
                hookEngine.hookMethod(
                    initPushMethod,
                    beforeCallback = { _, _ ->
                        Log.d(TAG, "Hooked ReaderApplication.initPushSDK — skipping push init")
                        null
                    }
                )
                Log.d(TAG, "initPushSDK hooked")
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.d(TAG, "initPushSDK hook skipped: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 跳过 Pangle 广告 SDK (ZeusPlatformUtils.initZeus) 初始化。
     */
    fun installPangleHook(
        guestCl: ClassLoader,
        hookEngine: com.multiapp.core.hook.HookEngine,
        lsplantOk: Boolean
    ): Boolean {
        return try {
            val zeusUtilsClass = Class.forName(
                "com.bytedance.android.dy.sdk.pangle.ZeusPlatformUtils", false, guestCl
            )
            val initZeusMethod = zeusUtilsClass.declaredMethods.firstOrNull { it.name == "initZeus" }
            if (initZeusMethod != null && lsplantOk) {
                hookEngine.hookMethod(
                    initZeusMethod,
                    beforeCallback = { _, _ ->
                        Log.d(TAG, "Hooked ZeusPlatformUtils.initZeus — skipping Pangle init")
                        null
                    }
                )
                Log.d(TAG, "Pangle initZeus hooked")
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Pangle hook skipped: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 诊断 hook 安装结果。
     */
    data class DiagResult(
        val fileDiag: Boolean = false,
        val providerDiag: Boolean = false,
        val protocolDiag: Boolean = false,
        val eqctCompat: Boolean = false,
        val loginDiag: Boolean = false
    )

    /**
     * 安装 QQReader 文件/Provider/协议诊断 hook 和 eqct plaintext 兼容。
     */
    fun installDiagnosticHooks(
        hookEngine: com.multiapp.core.hook.HookEngine,
        guestCl: ClassLoader
    ): DiagResult {
        var result = DiagResult()
        try {
            val fileDiagOk = com.multiapp.core.hook.QqReaderFileJavaDiag.install(hookEngine)
            Log.d(TAG, "QQReader java file diag installed: $fileDiagOk")
            result = result.copy(fileDiag = fileDiagOk)

            val providerDiagOk = com.multiapp.core.hook.QqReaderProviderDiag.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader provider diag installed: $providerDiagOk")
            result = result.copy(providerDiag = providerDiagOk)

            val protocolDiagOk = com.multiapp.core.hook.QqReaderProtocolDiag.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader protocol diag installed: $protocolDiagOk")
            result = result.copy(protocolDiag = protocolDiagOk)

            val eqctCompatOk = com.multiapp.core.hook.QqReaderEqctPlaintextCompat.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader eqct plaintext compat installed: $eqctCompatOk")
            result = result.copy(eqctCompat = eqctCompatOk)

            val loginDiagOk = com.multiapp.core.hook.QqReaderYwLoginJavaDiag.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader YWLogin java diag installed: $loginDiagOk")
            result = result.copy(loginDiag = loginDiagOk)
        } catch (e: Throwable) {
            Log.d(TAG, "QQReader diag hooks skipped: ${e.javaClass.simpleName}: ${e.message}")
        }
        return result
    }

    /**
     * 判断给定包名是否为 QQ Reader。
     */
    fun isQqReaderPackage(packageName: String?): Boolean {
        return packageName == "com.qq.reader" ||
            packageName?.startsWith("com.qq.reader.") == true
    }
}
