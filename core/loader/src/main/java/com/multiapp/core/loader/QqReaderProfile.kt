package com.multiapp.core.loader

import android.util.Log

/**
 * Legacy QQ Reader diagnostic/compatibility profile.
 *
 * Protected baseline must not enable these hooks by default. QQ Reader should
 * normally run through CloneProfile.NORMAL with protected baseline policy; these
 * LSPlant-backed hooks are only for explicit legacy/diagnostic comparison.
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

    data class DiagnosticsGate(
        val allowed: Boolean,
        val reason: String,
        val protectedPackage: Boolean,
        val explicitDiagnosticsEnabled: Boolean,
        val lsplantReady: Boolean
    )

    /**
     * Installs explicit legacy/diagnostic QQReader hooks.
     *
     * This must remain gated by profile/policy and must not be treated as the
     * default protected-app startup path.
     */
    fun installAll(
        guestCl: ClassLoader,
        hookEngine: com.multiapp.core.hook.HookEngine,
        lsplantOk: Boolean,
        diagnosticsGate: DiagnosticsGate = diagnosticsGate(
            packageName = null,
            cloneProfile = null,
            lsplantOk = lsplantOk,
            explicitDiagnosticsEnabled = false
        )
    ): HookResult {
        var result = HookResult()

        result = result.copy(shortcutHooked = installShortcutHook(guestCl, hookEngine, lsplantOk))
        result = result.copy(pushHooked = installPushHook(guestCl, hookEngine, lsplantOk))
        result = result.copy(pangleHooked = installPangleHook(guestCl, hookEngine, lsplantOk))

        if (diagnosticsGate.allowed) {
            val diag = installDiagnosticHooks(hookEngine, guestCl)
            result = result.copy(
                fileDiagInstalled = diag.fileDiag,
                providerDiagInstalled = diag.providerDiag,
                protocolDiagInstalled = diag.protocolDiag,
                eqctCompatInstalled = diag.eqctCompat,
                loginDiagInstalled = diag.loginDiag
            )
        } else {
            Log.d(TAG, "QQReader diagnostics skipped by gate: ${diagnosticsGate.reason}")
        }

        Log.d(TAG, "Hook result: $result")
        return result
    }

    /**
     * Legacy diagnostic hook for ShortcutManager.cihai; not baseline behavior.
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
     * Legacy diagnostic hook for ReaderApplication.initPushSDK; not baseline behavior.
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
     * Legacy diagnostic hook for Pangle init; not baseline behavior.
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
     * Installs explicit QQReader diagnostics; protected baseline keeps this
     * disabled unless explicitly requested.
     */
    fun installDiagnosticHooks(
        hookEngine: com.multiapp.core.hook.HookEngine,
        guestCl: ClassLoader
    ): DiagResult {
        var result = DiagResult()
        try {
            val fileDiagOk = com.multiapp.core.hook.compat.qqreader.QqReaderFileJavaDiag.install(hookEngine)
            Log.d(TAG, "QQReader java file diag installed: $fileDiagOk")
            result = result.copy(fileDiag = fileDiagOk)

            val providerDiagOk = com.multiapp.core.hook.compat.qqreader.QqReaderProviderDiag.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader provider diag installed: $providerDiagOk")
            result = result.copy(providerDiag = providerDiagOk)

            val protocolDiagOk = com.multiapp.core.hook.compat.qqreader.QqReaderProtocolDiag.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader protocol diag installed: $protocolDiagOk")
            result = result.copy(protocolDiag = protocolDiagOk)

            val eqctCompatOk = com.multiapp.core.hook.compat.qqreader.QqReaderEqctPlaintextCompat.install(hookEngine, guestCl)
            Log.d(TAG, "QQReader eqct plaintext compat installed: $eqctCompatOk")
            result = result.copy(eqctCompat = eqctCompatOk)

            val loginDiagOk = com.multiapp.core.hook.compat.qqreader.QqReaderYwLoginJavaDiag.install(hookEngine, guestCl)
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
            packageName?.startsWith("com.qq.reader.") == true ||
            packageName == "com.qidian.QDReader" ||
            packageName?.startsWith("com.qidian.QDReader.") == true
    }

    fun diagnosticsGate(
        packageName: String?,
        cloneProfile: String?,
        lsplantOk: Boolean,
        explicitDiagnosticsEnabled: Boolean
    ): DiagnosticsGate {
        val protectedPackage = isQqReaderPackage(packageName) || cloneProfile == "QQ_READER_SPECIAL"
        return when {
            !protectedPackage -> DiagnosticsGate(
                allowed = false,
                reason = "NOT_QQ_READER_PROFILE",
                protectedPackage = false,
                explicitDiagnosticsEnabled = explicitDiagnosticsEnabled,
                lsplantReady = lsplantOk
            )
            !explicitDiagnosticsEnabled -> DiagnosticsGate(
                allowed = false,
                reason = "EXPLICIT_DIAGNOSTICS_DISABLED",
                protectedPackage = true,
                explicitDiagnosticsEnabled = false,
                lsplantReady = lsplantOk
            )
            !lsplantOk -> DiagnosticsGate(
                allowed = false,
                reason = "LSPLANT_NOT_READY",
                protectedPackage = true,
                explicitDiagnosticsEnabled = true,
                lsplantReady = false
            )
            else -> DiagnosticsGate(
                allowed = true,
                reason = "ALLOWED",
                protectedPackage = true,
                explicitDiagnosticsEnabled = true,
                lsplantReady = true
            )
        }
    }
}
