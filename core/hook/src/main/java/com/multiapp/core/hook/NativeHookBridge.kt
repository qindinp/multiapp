package com.multiapp.core.hook

import android.content.Context
import com.multiapp.core.common.AndroidCompat
import com.multiapp.core.common.findField
import com.multiapp.core.common.runSafe
import com.multiapp.core.model.VirtualConstants
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

data class NativePrivatePathProbeResult(
    val operation: String,
    val success: Boolean,
    val resultCode: Int,
    val errno: Int,
    val candidateExists: Boolean,
    val resolvedPath: String,
    val reason: String,
    val processSlot: String = "",
    val instanceId: String = ""
)

data class NativePathRedirectEvidence(
    val fromPrefix: String,
    val toPrefix: String,
    val processSlot: String,
    val instanceId: String,
    val dataRoot: String,
    val scoped: Boolean
)

/**
 * NativeHookBridge 鈥?Native 灞?hook 寮曟搸
 *
 * 涓嶄娇鐢?Hilt @Singleton/@Inject锛屽洜涓?LoaderFactory 鍦?AppComponentFactory 闃舵
 * 鐩存帴鏋勯€犲疄渚嬶紝姝ゆ椂 Hilt 灏氭湭鍒濆鍖栥€傜粺涓€鐢?getInstance() 鑾峰彇鍏ㄥ眬鍗曚緥銆?
 */
class NativeHookBridge {

    companion object {
        private const val TAG = "NativeHook"
        internal const val PROC_SELF_MAPS = "/proc/self/maps"
        private const val PROC_SELF_CMDLINE = "/proc/self/cmdline"
        private const val PROC_SELF_STATUS = "/proc/self/status"
        private const val PROC_SELF_COMM = "/proc/self/comm"

        private val ROOT_PATHS = setOf(
            "/system/app/Superuser.apk", "/system/xbin/su", "/system/bin/su",
            "/sbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )

        private val EMULATOR_PATHS = setOf(
            "/dev/socket/qemud", "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so", "/sys/qemu_trace", "/system/bin/qemu-props"
        )

        private val DEFAULT_NATIVE_LOAD_CALLERS = arrayOf(
            "com.stub.StubApp",
            "com.qihoo.util.StubApp",
            "com.stub.StubApplication",
            "com.secneo.apkwrapper.ApplicationWrapper"
        )

        @Volatile private var nativeLibLoaded = false

        /**
         * 鍏ㄥ眬鍗曚緥
         */
        @Volatile
        private var instance: NativeHookBridge? = null

        fun getInstance(): NativeHookBridge {
            return instance ?: synchronized(this) {
                instance ?: NativeHookBridge().also { instance = it }
            }
        }

        /**
         * 鎵嬪姩鏍囪 native 搴撳凡鍔犺浇銆?
         * 褰撳簱琚叾浠?ClassLoader 鍔犺浇鏃讹紙濡?stub ClassLoader锛夛紝
         * NativeHookBridge 鐨?init 鍧楁棤娉曟娴嬪埌锛岄渶瑕佹墜鍔ㄦ爣璁般€?
         */
        fun markNativeLibLoaded() {
            nativeLibLoaded = true
            Timber.tag(TAG).i("nativeLibLoaded manually set to true")
        }

        fun resetInstance() {
            instance?.cleanup()
            instance = null
        }

        init {
            nativeLibLoaded = try {
                System.loadLibrary("multiapp-native"); true
            } catch (e: UnsatisfiedLinkError) {
                Timber.tag(TAG).w("libmultiapp-native.so not available: ${e.message}"); false
            }
        }

        /**
         * Parse a key=value format RegisterNatives evidence report.
         *
         * Required fields: className, methodCount, result.
         * Boolean fields accept: 1/0/true/false (case insensitive).
         * Returns null if the report is null, empty, or missing required fields.
         */
        internal fun parseRegisterNativesEvidenceReport(report: String?): RegisterNativesEvidence? {
            if (report.isNullOrBlank()) return null

            val map = report.replace(";", "\n")
                .lines()
                .map { it.trim() }
                .filter { it.contains("=") }
                .associate {
                    val idx = it.indexOf('=')
                    it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                }
                .filter { it.key.isNotEmpty() }

            val className = map["className"] ?: return null
            val methodCountStr = map["methodCount"] ?: return null
            val resultStr = map["result"] ?: return null
            val methodCount = methodCountStr.toIntOrNull() ?: return null
            val result = resultStr.toIntOrNull() ?: return null

            fun parseBool(key: String): Boolean? = map[key]?.let { v ->
                when (v.lowercase()) {
                    "1", "true" -> true
                    "0", "false" -> false
                    else -> null
                }
            }

            return RegisterNativesEvidence(
                className = className,
                methodCount = methodCount,
                result = result,
                source = map["source"] ?: "",
                callerIsJiagu = parseBool("callerIsJiagu") ?: false,
                allMultiAppMethods = parseBool("allMultiAppMethods") ?: false,
                hasInterface11 = parseBool("hasInterface11") ?: false,
                hasInterface20 = parseBool("hasInterface20") ?: false,
                jiaguComplete = parseBool("jiaguComplete") ?: false,
                explicitOriginalShellPath = parseBool("originalShellPath")
            )
        }
    }

    private data class PathRedirectionRule(
        val fromPrefix: String,
        val toPrefix: String,
        val processSlot: String,
        val instanceId: String,
        val dataRoot: String,
        val scoped: Boolean
    ) {
        val key: String = listOf(fromPrefix, processSlot, instanceId).joinToString("\u0000")

        fun matchesScope(activeProcessSlot: String, activeInstanceId: String): Boolean {
            return !scoped || (processSlot == activeProcessSlot && instanceId == activeInstanceId)
        }
    }

    private val pathRedirections = ConcurrentHashMap<String, PathRedirectionRule>()
    private val pathTrie = PathTrie()
    /**
     * LRU 璺緞缂撳瓨锛屾渶澶?2048 鏉★紝瓒呭嚭鑷姩娣樻卑鏈€鏃ф潯鐩?
     */
    private val pathCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 2048
    }
    private val pathCacheLock = Object()
    private var spoofedPackageName: String? = null
    private var spoofedPid: Int = -1
    private var spoofedProcessName: String? = null
    private val propertyOverrides = ConcurrentHashMap<String, String>()
    private val hiddenPaths = ConcurrentHashMap.newKeySet<String>()
    private val fakeFileContent = ConcurrentHashMap<String, ByteArray>()
    private var initialized = false
    private var nativeBaseHooksInitialized = false
    private var nativePathRedirectHooksInitialized = false
    private var nativeHooksAvailable = false
    private var appContext: android.content.Context? = null
    @Volatile private var activeProcessSlot: String = ""
    @Volatile private var activeInstanceId: String = ""

    fun initialize() {
        Timber.tag(TAG).i("NativeHookBridge.initialize() called")
        hookRuntimeNativeLoad()
    }

    fun hookRuntimeNativeLoad(fallbackCallerClasses: Array<String> = DEFAULT_NATIVE_LOAD_CALLERS): Boolean {
        if (nativeLibLoaded) {
            try {
                val result = nativeInstallRuntimeLoadHook(fallbackCallerClasses)
                if (result) {
                    Timber.tag(TAG).i("Runtime.nativeLoad JNI hook installed")
                    return true
                }
            } catch (e: Exception) { Timber.tag(TAG).w("Native Runtime.nativeLoad hook failed: ${e.message}") }
        }
        Timber.tag(TAG).w("Runtime.nativeLoad hook not available")
        return false
    }

    fun installRegisterNativesLogger(): Boolean {
        return installRegisterNativesLoggerInternal(enableBusinessWrappers = false)
    }

    private fun installRegisterNativesLoggerInternal(enableBusinessWrappers: Boolean): Boolean {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "RegisterNatives logger not available: native lib not loaded")
            return false
        }
        return try {
            nativeSetRegisterNativesBusinessWrappersEnabled(enableBusinessWrappers)
            val result = nativeInstallRegisterNativesLogger()
            android.util.Log.i(
                TAG,
                "RegisterNatives logger installed=$result businessWrappers=$enableBusinessWrappers"
            )
            result
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "RegisterNatives logger install failed: ${e.message}", e)
            false
        }
    }

    fun installRegisterNativesObserveOnly(policy: NativeHookPolicy): Boolean {
        val observeDecision = NativeHookPolicyGate.evaluate(
            policy = policy,
            capability = NativeHookCapability.REGISTER_NATIVES_OBSERVE_ONLY,
            component = "NativeHookBridge.installRegisterNativesObserveOnly"
        )
        val wrappersDecision = NativeHookPolicyGate.evaluate(
            policy = policy,
            capability = NativeHookCapability.BUSINESS_NATIVE_WRAPPERS,
            component = "NativeHookBridge.installRegisterNativesObserveOnly.businessNativeWrappers"
        )
        if (!observeDecision.allowed) {
            Timber.tag(TAG).i("RegisterNatives observe-only skipped by policy: %s", observeDecision.evidence)
            return false
        }
        return try {
            if (nativeLibLoaded) {
                nativeSetRegisterNativesBusinessWrappersEnabled(wrappersDecision.allowed)
            }
            if (wrappersDecision.allowed) {
                Timber.tag(TAG).w("RegisterNatives business wrappers enabled by policy: %s", wrappersDecision.evidence)
            } else {
                Timber.tag(TAG).i("RegisterNatives business wrappers disabled by policy: %s", wrappersDecision.evidence)
            }
            installRegisterNativesLoggerInternal(enableBusinessWrappers = wrappersDecision.allowed)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "installRegisterNativesObserveOnly failed")
            false
        }
    }

    fun installJiaguJniDiagHooks(): Boolean {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "Jiagu JNI diag hooks not available: native lib not loaded")
            return false
        }
        return try {
            val result = nativeInstallJiaguJniDiagHooks()
            android.util.Log.i(TAG, "Jiagu JNI diag hooks installed=$result")
            result
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Jiagu JNI diag hooks install failed: ${e.message}", e)
            false
        }
    }

    fun getYwLoginBindingReport(): String {
        if (!nativeLibLoaded) return "native-lib-not-loaded"
        return try {
            nativeGetYwLoginBindingReport()
        } catch (e: Throwable) {
            "error=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun getStubAppBindingReport(): String {
        if (!nativeLibLoaded) return "native-lib-not-loaded"
        return try {
            nativeGetStubAppBindingReport()
        } catch (e: Throwable) {
            "error=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Get structured RegisterNatives evidence from the native layer.
     * Returns null if native lib not loaded, JNI call fails, or report cannot be parsed.
     */
    fun getStubAppRegisterNativesEvidence(): RegisterNativesEvidence? {
        if (!nativeLibLoaded) return null
        return try {
            val report = nativeGetStubAppRegisterNativesEvidenceReport()
            parseRegisterNativesEvidenceReport(report)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "getStubAppRegisterNativesEvidence failed")
            null
        }
    }

    /**
     * 閫氳繃 dlopen 鐩存帴鍔犺浇 native 搴擄紙缁曡繃 Java 灞?hidden API 闄愬埗锛?
     * 鐢ㄤ簬鍔犺浇鍔犲浐澹崇殑 libjiagu_vip.so 绛夊簱
     */
    fun preloadNativeLibraries(libPaths: List<String>): Int {
        if (libPaths.isEmpty()) return 0
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "preloadNativeLibraries: native lib not loaded")
            return 0
        }
        return try {
            android.util.Log.i(TAG, "preloadNativeLibraries: calling native with ${libPaths.size} paths: $libPaths")
            val count = nativePreloadLibraries(libPaths.toTypedArray())
            android.util.Log.i(TAG, "preloadNativeLibraries: returned $count/${libPaths.size}")
            count
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "preloadNativeLibraries: exception: ${e.javaClass.simpleName}: ${e.message}", e)
            0
        }
    }

    /**
     * 鍙仛 dlopen + GOT hook锛屼笉璋?JNI_OnLoad銆?
     * 鐢ㄤ簬娣峰悎鏂规锛氬厛 dlopen 鍔犺浇骞?hook GOT锛屽啀閫氳繃 loadLibraryForGuest 璁?ART 鍋?ClassLoader 缁戝畾 + JNI_OnLoad銆?
     */
    fun dlopenOnly(libPath: String): Boolean {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "dlopenOnly: native lib not loaded")
            return false
        }
        return try {
            val result = nativeDlopenOnly(libPath)
            android.util.Log.i(TAG, "dlopenOnly: $libPath result=$result")
            result != 0
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "dlopenOnly exception: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    /**
     * 閫氳繃 JNI 璋冪敤 Runtime.nativeLoad锛屽皢搴撳姞杞藉埌 guest ClassLoader 鍛藉悕绌洪棿
     * JNI 灞傞潰璋冪敤缁曡繃 Java hidden API 闄愬埗
     */
    fun loadLibraryForGuest(libPath: String, classLoader: ClassLoader, callerClass: Class<*>): Boolean {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "Cannot load for guest: native lib not loaded")
            return false
        }
        return try {
            val result = nativeLoadLibraryForGuest(libPath, classLoader, callerClass)
            if (result == 0) {
                android.util.Log.i(TAG, "loadLibraryForGuest OK: $libPath")
                true
            } else {
                android.util.Log.w(TAG, "loadLibraryForGuest failed (code=$result): $libPath")
                false
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "loadLibraryForGuest exception: ${e.message}", e)
            false
        }
    }

    /**
     * P0: 浠?guest ClassLoader 涓?dump 鎵€鏈夊凡鍔犺浇鐨?DEX 鏂囦欢銆?
     * 閬嶅巻 DexPathList.dexElements锛岄€氳繃 mCookie 鎻愬彇 DexFile 瀛楄妭銆?
     *
     * @param classLoader guest ClassLoader (PathClassLoader)
     * @param dumpDir 杈撳嚭鐩綍
     * @return 鎴愬姛 dump 鐨?DEX 鏁伴噺
     */
    fun dumpDexFromClassLoader(classLoader: ClassLoader, dumpDir: java.io.File): Int {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "dumpDexFromClassLoader: native lib not loaded")
            return 0
        }
        return try {
            dumpDir.mkdirs()
            val count = nativeDumpDexFromClassLoader(classLoader, dumpDir.absolutePath)
            android.util.Log.i(TAG, "dumpDexFromClassLoader: dumped $count DEX to ${dumpDir.absolutePath}")
            count
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "dumpDexFromClassLoader exception: ${e.message}", e)
            0
        }
    }

    /**
     * P0: dump 宸插姞杞界殑 native libraries銆?
     * 閫氳繃 dl_iterate_phdr 閬嶅巻鎵€鏈夊凡鍔犺浇鐨?.so锛屾寜 PT_LOAD 娈甸噸寤?ELF銆?
     *
     * @param dumpDir 杈撳嚭鐩綍
     * @param targetLib 鐗瑰畾搴撳悕锛坣ull = dump 鎵€鏈?app .so锛?
     * @return 鎴愬姛 dump 鐨?.so 鏁伴噺
     */
    fun dumpLoadedLibraries(dumpDir: java.io.File, targetLib: String? = null): Int {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "dumpLoadedLibraries: native lib not loaded")
            return 0
        }
        return try {
            dumpDir.mkdirs()
            val count = nativeDumpLoadedLibraries(dumpDir.absolutePath, targetLib)
            android.util.Log.i(TAG, "dumpLoadedLibraries: dumped $count SO to ${dumpDir.absolutePath}")
            count
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "dumpLoadedLibraries exception: ${e.message}", e)
            0
        }
    }

    fun dumpJiaguRuntimeRanges(dumpDir: java.io.File): Int {
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "dumpJiaguRuntimeRanges: native lib not loaded")
            return 0
        }
        return try {
            dumpDir.mkdirs()
            val count = nativeDumpJiaguRuntimeRanges(dumpDir.absolutePath)
            android.util.Log.i(TAG, "dumpJiaguRuntimeRanges: dumped $count ranges to ${dumpDir.absolutePath}")
            count
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "dumpJiaguRuntimeRanges exception: ${e.message}", e)
            0
        }
    }

    /**
     * 璁剧疆 FindClass hook 鐨?guest ClassLoader 鍜屽€欓€夌洰鏍囩被鍚嶅垪琛ㄣ€?
     * 褰?JNI_OnLoad 涓?FindClass 鏌ユ壘浠讳竴鍊欓€夌被鏃讹紝閫氳繃 guest ClassLoader 鍔犺浇銆?
     */
    fun setupFindClassHook(classLoader: ClassLoader, targetClassNames: Array<String>): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeSetupFindClassHook(classLoader, targetClassNames)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "setupFindClassHook failed")
            false
        }
    }

    /**
     * 瀹夎 FindClass hook锛堜慨鏀?JNI 鍑芥暟琛級銆?
     * 蹇呴』鍦?setupFindClassHook 涔嬪悗銆丼ystem.loadLibrary 涔嬪墠璋冪敤銆?
     */
    fun installFindClassHook() {
        if (!nativeLibLoaded) return
        try {
            nativeInstallFindClassHook()
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "installFindClassHook failed")
        }
    }

    /**
     * 鎵嬪姩娉ㄥ唽澹崇殑 native 鏂规硶 stub 瀹炵幇銆?
     * 褰?FindClass hook 涓嶇敓鏁堟椂锛岀敤 RegisterNatives 鐩存帴娉ㄥ唽銆?
     */
    fun registerStubMethods(classLoader: ClassLoader, className: String): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterStubMethods(classLoader, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerStubMethods failed")
            false
        }
    }

    /**
     * 娉ㄥ唽鏈€灏忎笟鍔?native 鍏滃簳锛堝綋鍓嶄粎 YWLoginManager锛夈€?     * 鍐呭绛惧悕/鍔犲瘑閾捐矾蹇呴』淇濈暀鍘熷瀹炵幇锛屽惁鍒欎功鍩庝細鍑虹幇绌烘暟鎹€?     */
    fun registerStubInterface20Only(classLoader: ClassLoader, className: String): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterStubInterface20Only(classLoader, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerStubInterface20Only failed")
            false
        }
    }

    fun registerStubCoreBootstrapMethods(classLoader: ClassLoader, className: String): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterStubCoreBootstrapMethods(classLoader, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerStubCoreBootstrapMethods failed")
            false
        }
    }

    fun callOriginalStubInterface11(classLoader: ClassLoader, className: String, value: Int): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeCallOriginalStubInterface11(classLoader, className, value)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "callOriginalStubInterface11 failed")
            false
        }
    }

    fun callOriginalStubInterface5(classLoader: ClassLoader, className: String, application: android.app.Application): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeCallOriginalStubInterface5(classLoader, className, application)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "callOriginalStubInterface5 failed")
            false
        }
    }

    fun callOriginalStubInterface20(classLoader: ClassLoader, className: String): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeCallOriginalStubInterface20(classLoader, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "callOriginalStubInterface20 failed")
            false
        }
    }

    fun getJiaguTokenDiag(value: Int): String {
        if (!nativeLibLoaded) return "nativeLibLoaded=false"
        return try {
            nativeGetJiaguTokenDiag(value)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "getJiaguTokenDiag failed")
            "error=${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun registerStubBootstrapMethods(classLoader: ClassLoader, className: String): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterStubBootstrapMethods(classLoader, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerStubBootstrapMethods failed")
            false
        }
    }

    fun registerBusinessStubs(classLoader: ClassLoader): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterBusinessStubs(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerBusinessStubs failed")
            false
        }
    }

    fun registerQrencryptStubs(classLoader: ClassLoader): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterQrencryptStubs(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerQrencryptStubs failed")
            false
        }
    }

    fun registerOnlineChapterStateStubs(classLoader: ClassLoader): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterOnlineChapterStateStubs(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerOnlineChapterStateStubs failed")
            false
        }
    }

    fun registerOnlineChapterDownloadFallbackStubs(classLoader: ClassLoader): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            rememberHookClassLoader()
            nativeRegisterOnlineChapterDownloadFallbackStubs(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerOnlineChapterDownloadFallbackStubs failed")
            false
        }
    }

    private fun rememberHookClassLoader() {
        val hookClassLoader = NativeHookBridge::class.java.classLoader ?: return
        try {
            nativeRememberHookClassLoader(hookClassLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "rememberHookClassLoader failed")
        }
    }

    /**
     * 鎵弿鎵€鏈夊凡鐭?native 绫伙紝鎵归噺娉ㄥ唽缂哄け鐨?native 鏂规硶
     */
    fun registerAllMissingNativeMethods(classLoader: ClassLoader): Int {
        if (!nativeLibLoaded) return 0
        return try {
            nativeRegisterAllMissingNativeMethods(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerAllMissingNativeMethods failed")
            0
        }
    }

    private fun defaultProcessSlot(instanceId: String): String {
        return System.getProperty("multiapp.processSlot")
            ?.takeIf { it.isNotBlank() }
            ?: "process:${instanceId.ifBlank { "unknown" }}"
    }

    fun setNativeRedirectScope(processSlot: String, instanceId: String) {
        if (hasParentTraversal(processSlot) || hasParentTraversal(instanceId)) {
            Timber.tag(TAG).w("Native redirect scope rejected: processSlot=$processSlot instanceId=$instanceId")
            return
        }
        activeProcessSlot = processSlot
        activeInstanceId = instanceId
        synchronized(pathCacheLock) { pathCache.clear() }
        if (nativeHooksAvailable) nativeSetRedirectScope(processSlot, instanceId)
    }

    fun getPathRedirectionEvidence(): List<NativePathRedirectEvidence> {
        return pathRedirections.values
            .sortedWith(compareBy<PathRedirectionRule> { it.fromPrefix }.thenBy { it.instanceId }.thenBy { it.processSlot })
            .map { rule ->
                NativePathRedirectEvidence(
                    fromPrefix = rule.fromPrefix,
                    toPrefix = rule.toPrefix,
                    processSlot = rule.processSlot,
                    instanceId = rule.instanceId,
                    dataRoot = rule.dataRoot,
                    scoped = rule.scoped
                )
            }
    }

    private fun addScopedPathRedirection(
        fromPrefix: String,
        toPrefix: String,
        processSlot: String,
        instanceId: String,
        dataRoot: String
    ): Boolean {
        val normalizedProcessSlot = processSlot.ifBlank { defaultProcessSlot(instanceId) }
        setNativeRedirectScope(normalizedProcessSlot, instanceId)
        return addPathRedirectionRule(
            fromPrefix = fromPrefix,
            toPrefix = toPrefix,
            processSlot = normalizedProcessSlot,
            instanceId = instanceId,
            dataRoot = dataRoot.trimEnd('/', '\\'),
            scoped = true
        )
    }

    private fun addPathRedirectionRule(
        fromPrefix: String,
        toPrefix: String,
        processSlot: String,
        instanceId: String,
        dataRoot: String,
        scoped: Boolean
    ): Boolean {
        if (hasParentTraversal(fromPrefix) ||
            hasParentTraversal(toPrefix) ||
            hasParentTraversal(processSlot) ||
            hasParentTraversal(instanceId) ||
            hasParentTraversal(dataRoot)
        ) {
            return false
        }

        val rule = PathRedirectionRule(
            fromPrefix = fromPrefix,
            toPrefix = toPrefix,
            processSlot = processSlot,
            instanceId = instanceId,
            dataRoot = dataRoot,
            scoped = scoped
        )
        pathRedirections[rule.key] = rule
        rebuildPrefixIndex()
        Timber.tag(TAG).d(
            "Path redirect: $fromPrefix -> $toPrefix processSlot=$processSlot instanceId=$instanceId scoped=$scoped"
        )
        if (nativeHooksAvailable) {
            if (scoped) {
                nativeAddScopedPathRedirection(fromPrefix, toPrefix, processSlot, instanceId, dataRoot)
            } else {
                nativeAddPathRedirection(fromPrefix, toPrefix)
            }
        }
        return true
    }

    private fun hasParentTraversal(path: String): Boolean {
        if (path.isEmpty()) return false
        var segmentStart = 0
        for (index in 0..path.length) {
            val atEnd = index == path.length
            val separator = !atEnd && (path[index] == '/' || path[index] == '\\')
            if (atEnd || separator) {
                if (index - segmentStart == 2 && path[segmentStart] == '.' && path[segmentStart + 1] == '.') {
                    return true
                }
                segmentStart = index + 1
            }
        }
        return false
    }

    private fun secureScopedTranslation(rule: PathRedirectionRule, originalPath: String): String? {
        if (hasParentTraversal(originalPath)) return null
        val candidate = rule.toPrefix + originalPath.substring(rule.fromPrefix.length)
        if (rule.dataRoot.isBlank()) return candidate
        val candidateFile = File(candidate)
        if (!candidateFile.exists()) return candidate
        return if (isCanonicalContained(candidateFile, File(rule.dataRoot))) candidate else null
    }

    private fun isCanonicalContained(candidate: File, root: File): Boolean {
        return try {
            val rootPath = root.canonicalFile.path.trimEnd(File.separatorChar)
            val candidatePath = candidate.canonicalFile.path
            candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun translateScopedPath(originalPath: String): String? {
        val processSlot = activeProcessSlot
        val instanceId = activeInstanceId
        if (processSlot.isBlank() || instanceId.isBlank()) return null

        var bestRule: PathRedirectionRule? = null
        for (rule in pathRedirections.values) {
            if (!rule.scoped || !rule.matchesScope(processSlot, instanceId)) continue
            if (originalPath.startsWith(rule.fromPrefix) &&
                rule.fromPrefix.length > (bestRule?.fromPrefix?.length ?: -1)
            ) {
                bestRule = rule
            }
        }
        return bestRule?.let { secureScopedTranslation(it, originalPath) }
    }

    private fun rebuildPrefixIndex() {
        pathTrie.clear()
        for (rule in pathRedirections.values) {
            if (!rule.scoped) pathTrie.insert(rule.fromPrefix, rule.toPrefix)
        }
        synchronized(pathCacheLock) { pathCache.clear() }
    }

    fun addPathRedirection(fromPrefix: String, toPrefix: String) {
        if (!addPathRedirectionRule(
                fromPrefix = fromPrefix,
                toPrefix = toPrefix,
                processSlot = "",
                instanceId = "",
                dataRoot = "",
                scoped = false
            )
        ) {
            Timber.tag(TAG).w("Path redirect rejected: $fromPrefix -> $toPrefix")
        }
    }

    fun probePrivatePathRedirect(
        operation: String,
        originalPath: String,
        expectedRedirectedPath: String
    ): NativePrivatePathProbeResult {
        if (!nativeLibLoaded) {
            return NativePrivatePathProbeResult(
                operation = operation,
                success = false,
                resultCode = -1,
                errno = 0,
                candidateExists = false,
                resolvedPath = "",
                reason = "NATIVE_LIBRARY_NOT_AVAILABLE"
            )
        }
        return try {
            parsePrivatePathProbeReport(
                nativeProbePrivatePathRedirect(operation, originalPath, expectedRedirectedPath),
                operation
            )
        } catch (error: Throwable) {
            NativePrivatePathProbeResult(
                operation = operation,
                success = false,
                resultCode = -1,
                errno = 0,
                candidateExists = false,
                resolvedPath = "",
                reason = error.javaClass.simpleName + ":" + error.message.orEmpty()
            )
        }
    }

    fun removePathRedirection(fromPrefix: String) {
        pathRedirections.entries
            .filter { it.value.fromPrefix == fromPrefix }
            .forEach { pathRedirections.remove(it.key) }
        rebuildPrefixIndex()
        if (nativeHooksAvailable) nativeRemovePathRedirection(fromPrefix)
    }

    fun clearPathRedirections() {
        pathRedirections.clear(); rebuildPrefixIndex()
        if (nativeHooksAvailable) nativeClearPathRedirections()
        Timber.tag(TAG).d("All path redirections cleared")
    }

    /**
     * 璁剧疆瀹屾暣鎬ф牎楠岄噸瀹氬悜锛氬３鐨?JNI_OnLoad 璇?APK 鏍￠獙 DEX 鏃讹紝閲嶅畾鍚戝埌鍘熷 APK銆?
     * 蹇呴』鍦ㄨ皟鐢?System.loadLibrary() 涔嬪墠璁剧疆锛屼箣鍚庤皟鐢?clearIntegrityRedirect()銆?
     */
    fun setIntegrityRedirect(fromPath: String, toPath: String) {
        if (nativeHooksAvailable) nativeSetIntegrityRedirect(fromPath, toPath)
    }

    fun setJiaguPackageSpoof(stubPackageName: String, originalPackageName: String) {
        if (nativeHooksAvailable) nativeSetJiaguPackageSpoof(stubPackageName, originalPackageName)
    }

    fun clearIntegrityRedirect() {
        if (nativeHooksAvailable) nativeClearIntegrityRedirect()
    }

    fun setSuppressSelfSigkill(enabled: Boolean) {
        if (nativeHooksAvailable) nativeSetSuppressSelfSigkill(enabled)
    }

    /**
     * GOT hook锛氫慨鏀圭洰鏍囧簱鐨?GOT 琛紝鎷︽埅 open/openat/fopen 璋冪敤銆?
     * 鐢ㄤ簬杩囨护 /proc/self/maps 璇诲彇锛岀粫杩囧３鐨勫弽璋冭瘯妫€娴嬨€?
     * 涓嶉渶瑕?trampoline 鍐呭瓨锛孉ndroid 16 涓婂彲琛屻€?
     */
    fun gotHookLibrary(libName: String) {
        if (nativeHooksAvailable) nativeGotHookLibrary(libName)
    }

    /**
     * 预解析 ELF 并记录 GOT 偏移量，但不调用 dlopen。
     * 用于在 StubApp.load() 之前预解析壳库，为后续 GOT hook 做准备。
     * 实际的 GOT patch 在 dlopen 后由 got_hook_immediate 完成。
     *
     * @param libPath .so 文件绝对路径
     * @return 预解析的 GOT 条目数量
     */
    fun preParseAndInstallGotHooks(libPath: String): Int {
        return if (nativeHooksAvailable) nativePreParseAndInstallGotHooks(libPath) else 0
    }

    /**
     * Initialize LSPlant for ART method hooking.
     * Must be called before hookMethod.
     * Uses ShadowHook as the inline hooker backend.
     */
    fun initLsplant(): Boolean {
        if (!nativeHooksAvailable) {
            android.util.Log.w(TAG, "initLsplant: native lib not loaded")
            return false
        }
        return try {
            // 鎵惧埌 host APK 鐨?nativeLibraryDir锛坙iblsplant.so 鎵€鍦ㄧ洰褰曪級
            val hiddenApiOk = AndroidCompat.bypassHiddenApis()
            android.util.Log.i(TAG, "initLsplant: hiddenApiBypass=$hiddenApiOk")
            val hostLibDir = findHostNativeLibDir()
            android.util.Log.i(TAG, "initLsplant: hostLibDir=$hostLibDir")
            val result = nativeInitLsplant(hostLibDir)
            android.util.Log.i(TAG, "initLsplant: result=$result")
            result
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "initLsplant failed: ${e.message}", e)
            false
        }
    }

    /**
     * 鎵惧埌 host APK 鐨?native library 鐩綍
     * liblsplant.so 鍦?host APK 鐨?lib 鐩綍涓紝涓嶅湪 guest/stub APK 涓?
     */
    private fun findHostNativeLibDir(): String? {
        // 鏂瑰紡0: 浠?PackageManager 鑾峰彇 host APK 鐨?nativeLibraryDir
        // liblsplant.so 鍦?host APK锛坈om.multiapp.app锛夌殑 lib 鐩綍涓紝涓嶅湪 stub 涓?
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val ctx = atClass.getMethod("currentApplication").invoke(null) as? android.content.Context
            if (ctx != null) {
                val hostInfo = ctx.packageManager.getApplicationInfo("com.multiapp.app", 0)
                val hostLibDir = hostInfo.nativeLibraryDir
                android.util.Log.i(TAG, "findHostNativeLibDir: host nativeLibDir=$hostLibDir")
                if (hostLibDir != null && java.io.File(hostLibDir, "liblsplant.so").exists()) {
                    return hostLibDir
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "findHostNativeLibDir via PackageManager failed: ${e.message}")
        }

        // 鏂瑰紡1: 浠?/proc/self/maps 鎵?libmultiapp-native.so 鐨勮矾寰?
        // liblsplant.so 鍦ㄥ悓涓€鐩綍锛坔ost APK 鐨?lib 鐩綍锛?
        try {
            var lineCount = 0
            var foundMultiapp = false
            var foundLsplant = false
            java.io.File("/proc/self/maps").readLines().forEach { line ->
                lineCount++
                if (line.contains("libmultiapp-native.so")) {
                    foundMultiapp = true
                    val path = line.trim().substringAfterLast(" ")
                    val dir = path.substringBeforeLast("/")
                    android.util.Log.i(TAG, "findHostNativeLibDir: found libmultiapp-native.so at: $dir")
                    val lsplantPath = "$dir/liblsplant.so"
                    if (java.io.File(lsplantPath).exists()) {
                        android.util.Log.i(TAG, "findHostNativeLibDir: liblsplant.so exists at: $lsplantPath")
                        return dir
                    } else {
                        android.util.Log.w(TAG, "findHostNativeLibDir: liblsplant.so NOT found at: $lsplantPath")
                    }
                }
                if (line.contains("liblsplant.so")) {
                    foundLsplant = true
                    android.util.Log.i(TAG, "findHostNativeLibDir: found liblsplant.so in maps: $line")
                }
            }
            android.util.Log.w(TAG, "findHostNativeLibDir: maps scan done. lines=$lineCount, multiapp=$foundMultiapp, lsplant=$foundLsplant")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "findHostNativeLibDir via maps failed: ${e.message}")
        }

        // 鏂瑰紡2: 浠?HookEngine 鐨?ClassLoader 鑾峰彇锛堝彲鑳芥槸 stub 鐨勶紝浣?fallback锛?
        try {
            val cl = HookEngine::class.java.classLoader
            if (cl is dalvik.system.BaseDexClassLoader) {
                val pathList = dalvik.system.BaseDexClassLoader::class.java
                    .getDeclaredField("pathList")
                    .apply { isAccessible = true }
                    .get(cl)
                if (pathList != null) {
                    val rawDirs = pathList.javaClass
                        .getDeclaredField("nativeLibraryDirectories")
                        .apply { isAccessible = true }
                        .get(pathList)
                    val nativeLibDirs: List<java.io.File> = when (rawDirs) {
                        is Array<*> -> rawDirs.filterIsInstance<java.io.File>()
                        is List<*> -> rawDirs.filterIsInstance<java.io.File>()
                        else -> emptyList()
                    }
                    android.util.Log.i(TAG, "findHostNativeLibDir: nativeLibDirs=$nativeLibDirs")
                    val result = nativeLibDirs.firstOrNull { dir ->
                        java.io.File(dir, "liblsplant.so").exists()
                    }
                    if (result != null) return result.absolutePath
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "findHostNativeLibDir via ClassLoader failed: ${e.message}")
        }

        return null
    }

    /**
     * Check if LSPlant is initialized.
     */
    fun isLsplantInitialized(): Boolean {
        return nativeHooksAvailable && nativeIsLsplantInitialized()
    }

    /**
     * Hook a Java method using LSPlant.
     * The hookerObject must have a method: public Object callback(Object[] args)
     *
     * @param targetMethod The method to hook
     * @param hookerObject The object containing the callback method
     * @return true if hook was successful
     */
    fun hookMethod(targetMethod: java.lang.reflect.Executable, hookerObject: Any): Boolean {
        return hookMethodWithBackup(targetMethod, hookerObject) != null
    }

    fun hookMethodWithBackup(targetMethod: java.lang.reflect.Executable, hookerObject: Any): java.lang.reflect.Executable? {
        if (!nativeHooksAvailable) {
            android.util.Log.w(TAG, "hookMethodWithBackup: native lib not loaded")
            return null
        }
        return try {
            val backup = nativeHookMethodWithBackup(targetMethod, hookerObject)
            android.util.Log.i(TAG, "hookMethodWithBackup: ${targetMethod.declaringClass.name}.${targetMethod.name} backup=$backup")
            backup
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "hookMethodWithBackup failed: ${e.message}", e)
            null
        }
    }

    fun unhookMethod(targetMethod: java.lang.reflect.Executable): Boolean {
        if (!nativeHooksAvailable) return false
        return try {
            nativeUnhookMethod(targetMethod)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "unhookMethod failed: ${e.message}", e)
            false
        }
    }

    fun isMethodHooked(method: java.lang.reflect.Executable): Boolean {
        if (!nativeHooksAvailable) return false
        return try {
            nativeIsMethodHooked(method)
        } catch (e: Throwable) {
            false
        }
    }

    fun deoptimizeMethod(method: java.lang.reflect.Executable): Boolean {
        if (!nativeHooksAvailable) return false
        return try {
            nativeDeoptimizeMethod(method)
        } catch (e: Throwable) {
            false
        }
    }

    fun setupAppRedirections(guestPackageName: String, instanceId: String, sandboxDataDir: String) {
        val processSlot = defaultProcessSlot(instanceId)
        addScopedPathRedirection("/data/data/$guestPackageName/", "$sandboxDataDir/", processSlot, instanceId, sandboxDataDir)
        addScopedPathRedirection("/data/user/0/$guestPackageName/", "$sandboxDataDir/", processSlot, instanceId, sandboxDataDir)
        addScopedPathRedirection(
            "/storage/emulated/0/Android/data/$guestPackageName/",
            "$sandboxDataDir/external_data/",
            processSlot,
            instanceId,
            sandboxDataDir
        )
        addScopedPathRedirection(
            "/sdcard/Android/data/$guestPackageName/",
            "$sandboxDataDir/external_data/",
            processSlot,
            instanceId,
            sandboxDataDir
        )
        addScopedPathRedirection(
            "/storage/emulated/0/Android/obb/$guestPackageName/",
            "$sandboxDataDir/obb/",
            processSlot,
            instanceId,
            sandboxDataDir
        )
        Timber.tag(TAG).d("App redirections set for $guestPackageName ($instanceId)")
    }

    fun setupGuestPrivatePathRedirections(guestPackageName: String, instanceId: String, dataRoot: String): Int {
        return setupGuestPrivatePathRedirections(
            guestPackageName = guestPackageName,
            processSlot = defaultProcessSlot(instanceId),
            instanceId = instanceId,
            dataRoot = dataRoot
        )
    }

    fun setupGuestPrivatePathRedirections(
        guestPackageName: String,
        processSlot: String,
        instanceId: String,
        dataRoot: String
    ): Int {
        if (guestPackageName.isBlank() || processSlot.isBlank() || instanceId.isBlank() || dataRoot.isBlank()) {
            Timber.tag(TAG).w(
                "Guest private path redirections skipped for instanceId=$instanceId processSlot=$processSlot: incomplete input"
            )
            return 0
        }
        if (hasParentTraversal(guestPackageName) ||
            hasParentTraversal(processSlot) ||
            hasParentTraversal(instanceId) ||
            hasParentTraversal(dataRoot)
        ) {
            Timber.tag(TAG).w(
                "Guest private path redirections rejected for instanceId=$instanceId processSlot=$processSlot: unsafe input"
            )
            return 0
        }
        val targetRoot = dataRoot.trimEnd('/') + "/"
        var ruleCount = 0
        if (addScopedPathRedirection("/data/data/$guestPackageName/", targetRoot, processSlot, instanceId, dataRoot)) {
            ruleCount++
        }
        if (addScopedPathRedirection("/data/user/0/$guestPackageName/", targetRoot, processSlot, instanceId, dataRoot)) {
            ruleCount++
        }
        Timber.tag(TAG).d("Guest private path redirections set for $guestPackageName ($instanceId/$processSlot)")
        return ruleCount
    }

    fun setupExternalStorageRedirections(instanceId: String, virtualSdcardDir: String) {
        val processSlot = defaultProcessSlot(instanceId)
        addScopedPathRedirection("/sdcard/", "$virtualSdcardDir/", processSlot, instanceId, virtualSdcardDir)
        addScopedPathRedirection("/storage/emulated/0/", "$virtualSdcardDir/", processSlot, instanceId, virtualSdcardDir)
        addScopedPathRedirection("/mnt/sdcard/", "$virtualSdcardDir/", processSlot, instanceId, virtualSdcardDir)
        addScopedPathRedirection("/storage/self/primary/", "$virtualSdcardDir/", processSlot, instanceId, virtualSdcardDir)
        addScopedPathRedirection("/storage/emulated/0", virtualSdcardDir, processSlot, instanceId, virtualSdcardDir)
        addScopedPathRedirection("/sdcard", virtualSdcardDir, processSlot, instanceId, virtualSdcardDir)
        Timber.tag(TAG).d("External storage redirections set for $instanceId -> $virtualSdcardDir")
    }

    fun removeExternalStorageRedirections() {
        listOf("/sdcard/", "/sdcard", "/storage/emulated/0/", "/storage/emulated/0", "/mnt/sdcard/", "/storage/self/primary/").forEach { removePathRedirection(it) }
        Timber.tag(TAG).d("External storage redirections removed")
    }

    fun removeAppRedirections(guestPackageName: String) {
        listOf("/data/data/$guestPackageName/", "/data/user/0/$guestPackageName/",
            "/storage/emulated/0/Android/data/$guestPackageName/", "/sdcard/Android/data/$guestPackageName/",
            "/storage/emulated/0/Android/obb/$guestPackageName/").forEach { removePathRedirection(it) }
        Timber.tag(TAG).d("App redirections removed for $guestPackageName")
    }

    fun spoofProcSelf(pid: Int, packageName: String) {
        spoofedPid = pid; spoofedPackageName = packageName; spoofedProcessName = packageName
        fakeFileContent[PROC_SELF_CMDLINE] = packageName.toByteArray() + byteArrayOf(0)
        fakeFileContent[PROC_SELF_COMM] = "${packageName.take(15)}\n".toByteArray()
        fakeFileContent[PROC_SELF_STATUS] = buildFakeProcStatus(pid, packageName)
        if (nativeHooksAvailable) nativeSpoofProcSelf(pid, packageName)
        Timber.tag(TAG).d("/proc/self spoofed: pid=$pid, pkg=$packageName")
    }

    fun spoofSystemProperty(key: String, value: String) {
        propertyOverrides[key] = value
        if (nativeHooksAvailable) nativeSpoofSystemProperty(key, value)
        Timber.tag(TAG).d("System property override: $key=$value")
    }

    fun spoofSystemProperties(properties: Map<String, String>) { properties.forEach { (k, v) -> spoofSystemProperty(k, v) } }

    fun hidePath(path: String) { hiddenPaths.add(path); if (nativeHooksAvailable) nativeHidePath(path) }
    fun unhidePath(path: String) { hiddenPaths.remove(path); if (nativeHooksAvailable) nativeUnhidePath(path) }
    fun setFakeFileContent(path: String, content: ByteArray) { fakeFileContent[path] = content }
    fun setFakeFileContent(path: String, content: String) { fakeFileContent[path] = content.toByteArray() }

    fun translatePath(originalPath: String): String {
        if (hiddenPaths.contains(originalPath)) return "/dev/null"
        val scopedCacheKey = "$activeProcessSlot\u0000$activeInstanceId\u0000$originalPath"
        synchronized(pathCacheLock) {
            pathCache[scopedCacheKey]?.let { return it }
            val result = translateScopedPath(originalPath)
                ?: pathTrie.translate(originalPath)
                ?: originalPath
            pathCache[scopedCacheKey] = result
            return result
        }
    }

    fun isPathHidden(path: String): Boolean = hiddenPaths.contains(path)
    fun hasFakeContent(path: String): Boolean = fakeFileContent.containsKey(path)
    fun getFakeContent(path: String): ByteArray? = fakeFileContent[path]
    fun getPropertyOverride(key: String): String? = propertyOverrides[key]
    fun getRedirectionCount(): Int = pathRedirections.size
    fun getHiddenPathCount(): Int = hiddenPaths.size
    fun isNativeAvailable(): Boolean = nativeHooksAvailable

    fun cleanup() {
        pathRedirections.clear(); pathTrie.clear(); hiddenPaths.clear()
        synchronized(pathCacheLock) { pathCache.clear() }
        fakeFileContent.clear(); propertyOverrides.clear()
        spoofedPackageName = null; spoofedPid = -1; appContext = null
        activeProcessSlot = ""; activeInstanceId = ""
        if (nativeHooksAvailable) nativeCleanup()
        initialized = false
        nativeBaseHooksInitialized = false
        nativePathRedirectHooksInitialized = false
        Timber.tag(TAG).i("Native hook bridge cleaned up")
    }

    fun initNativeHooks(context: android.content.Context? = null, hostDataDir: String? = null): Boolean {
        if (nativeBaseHooksInitialized) return nativeHooksAvailable
        appContext = context?.applicationContext
        nativeHooksAvailable = tryLoadNativeLibrary()
        if (nativeHooksAvailable) {
            try {
                nativeInit()
                val effectiveDataDir = hostDataDir
                    ?: appContext?.let { ctx ->
                        try {
                            nativeSetVirtualDataRoot(ctx.getDir("virtual", android.content.Context.MODE_PRIVATE).absolutePath)
                            ctx.dataDir.absolutePath
                        } catch (e: Exception) { Timber.tag(TAG).w("Failed to get dataDir from context: ${e.message}"); null }
                    }
                if (effectiveDataDir != null) {
                    nativeSetHostDataPrefix(effectiveDataDir)
                } else {
                    Timber.tag(TAG).w("No dataDir available 鈥?native path redirection may not work")
                }
            } catch (e: Exception) { Timber.tag(TAG).e(e, "Native hook init failed"); nativeHooksAvailable = false }
        } else {
            Timber.tag(TAG).e("Native library not loaded 鈥?native hooks DISABLED")
        }
        ROOT_PATHS.forEach { hiddenPaths.add(it) }
        EMULATOR_PATHS.forEach { hiddenPaths.add(it) }
        nativeBaseHooksInitialized = true
        initialized = true
        return nativeHooksAvailable
    }

    fun initNativePathRedirectHooks(
        context: android.content.Context? = null,
        hostDataDir: String? = null
    ): Boolean {
        if (nativePathRedirectHooksInitialized) return nativeHooksAvailable
        appContext = context?.applicationContext
        nativeHooksAvailable = tryLoadNativeLibrary()
        if (nativeHooksAvailable) {
            try {
                val hooksOk = nativeInitPathRedirectHooks()
                val effectiveDataDir = hostDataDir
                    ?: appContext?.let { ctx ->
                        try {
                            ctx.dataDir.absolutePath
                        } catch (e: Exception) {
                            Timber.tag(TAG).w("Failed to get dataDir from context: ${e.message}")
                            null
                        }
                    }
                if (effectiveDataDir != null) {
                    nativeSetHostDataPrefix(effectiveDataDir)
                }
                nativeHooksAvailable = hooksOk
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Native path redirect hook init failed")
                nativeHooksAvailable = false
            }
        } else {
            Timber.tag(TAG).e("Native library not loaded - native path redirect hooks DISABLED")
        }
        nativePathRedirectHooksInitialized = true
        initialized = nativeBaseHooksInitialized || nativePathRedirectHooksInitialized
        return nativeHooksAvailable
    }

    fun initNativeHooks(
        policy: NativeHookPolicy,
        context: android.content.Context? = null,
        hostDataDir: String? = null,
        component: String = "NativeHookBridge.initNativeHooks"
    ): Boolean {
        val decision = NativeHookPolicyGate.evaluate(
            policy = policy,
            capability = NativeHookCapability.NATIVE_BASE_HOOKS,
            component = component
        )
        if (!decision.allowed) {
            Timber.tag(TAG).i("Native base hooks skipped by policy: %s", decision.evidence)
            return false
        }
        return initNativeHooks(context, hostDataDir)
    }

    fun initNativePathRedirectHooks(
        policy: NativeHookPolicy,
        context: android.content.Context? = null,
        hostDataDir: String? = null,
        component: String = "NativeHookBridge.initNativePathRedirectHooks"
    ): Boolean {
        val decision = NativeHookPolicyGate.evaluate(
            policy = policy,
            capability = NativeHookCapability.PATH_VIRTUALIZATION,
            component = component
        )
        if (!decision.allowed) {
            Timber.tag(TAG).i("Native path redirect hooks skipped by policy: %s", decision.evidence)
            return false
        }
        return initNativePathRedirectHooks(context, hostDataDir)
    }

    private fun buildFakeProcStatus(pid: Int, packageName: String): ByteArray {
        val name = packageName.take(15)
        return "Name:\t$name\nUmask:\t0077\nState:\tS (sleeping)\nTgid:\t$pid\nPid:\t$pid\nPPid:\t${pid - 1}\nTracerPid:\t0\n".toByteArray()
    }

    private fun parsePrivatePathProbeReport(report: String, fallbackOperation: String): NativePrivatePathProbeResult {
        val fields = report.split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 0) null else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()
        return NativePrivatePathProbeResult(
            operation = fields["operation"] ?: fallbackOperation,
            success = fields["success"] == "true",
            resultCode = fields["resultCode"]?.toIntOrNull() ?: -1,
            errno = fields["errno"]?.toIntOrNull() ?: 0,
            candidateExists = fields["candidateExists"] == "true",
            resolvedPath = fields["resolvedPath"].orEmpty(),
            reason = fields["reason"].orEmpty(),
            processSlot = fields["processSlot"].orEmpty(),
            instanceId = fields["instanceId"].orEmpty()
        )
    }

    fun filterProcMaps(originalMaps: String): String {
        val linesToHide = listOf(
            VirtualConstants.HOST_PACKAGE, "multiapp", "libmultiapp", "shadowhook", "lsplant",
            "dobby", "bhook", "xhook", "substrate", "xposed",
            "libnextvm", "LSPosed", "edxposed", "riru", "zygisk", "magisk", "/data/adb"
        )
        return originalMaps.lines().filter { line -> linesToHide.none { line.contains(it, ignoreCase = true) } }.joinToString("\n")
    }

    private external fun nativeInit(): Boolean
    private external fun nativeInitPathRedirectHooks(): Boolean
    private external fun nativeAddPathRedirection(fromPrefix: String, toPrefix: String)
    private external fun nativeAddScopedPathRedirection(
        fromPrefix: String,
        toPrefix: String,
        processSlot: String,
        instanceId: String,
        dataRoot: String
    )
    private external fun nativeSetRedirectScope(processSlot: String, instanceId: String)
    private external fun nativeRemovePathRedirection(fromPrefix: String)
    private external fun nativeClearPathRedirections()
    private external fun nativeSpoofProcSelf(pid: Int, packageName: String)
    private external fun nativeSpoofTracerPid(enable: Boolean)
    private external fun nativeSpoofSystemProperty(key: String, value: String)
    private external fun nativeHidePath(path: String)
    private external fun nativeUnhidePath(path: String)
    private external fun nativeCleanup()
    private external fun nativeSetHostDataPrefix(prefix: String)
    private external fun nativeSetVirtualDataRoot(root: String)
    private external fun nativeGetRedirectCount(): Int
    private external fun nativeProbePrivatePathRedirect(
        operation: String,
        originalPath: String,
        expectedRedirectedPath: String
    ): String
    private external fun nativeGetPropertySpoofCount(): Int
    private external fun nativeIsInitialized(): Boolean
    private external fun nativeInstallRuntimeLoadHook(fallbackCallerClasses: Array<String>): Boolean
    private external fun nativeInstallRegisterNativesLogger(): Boolean
    private external fun nativeSetRegisterNativesBusinessWrappersEnabled(enabled: Boolean)
    private external fun nativeInstallJiaguJniDiagHooks(): Boolean
    private external fun nativeGetYwLoginBindingReport(): String
    private external fun nativeGetStubAppBindingReport(): String
    private external fun nativeGetStubAppRegisterNativesEvidenceReport(): String
    private external fun nativePreloadLibraries(libPaths: Array<String>): Int
    private external fun nativeLoadLibraryForGuest(libPath: String, classLoader: ClassLoader, callerClass: Class<*>): Int
    private external fun nativeDlopenOnly(libPath: String): Int
    private external fun nativeSetupFindClassHook(classLoader: ClassLoader, targetClassNames: Array<String>): Boolean
    private external fun nativeInstallFindClassHook()
    private external fun nativeRegisterStubMethods(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeRegisterStubInterface20Only(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeRegisterStubCoreBootstrapMethods(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeCallOriginalStubInterface11(classLoader: ClassLoader, className: String, value: Int): Boolean
    private external fun nativeCallOriginalStubInterface5(classLoader: ClassLoader, className: String, application: android.app.Application): Boolean
    private external fun nativeCallOriginalStubInterface20(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeGetJiaguTokenDiag(value: Int): String
    private external fun nativeRegisterStubBootstrapMethods(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeRegisterBusinessStubs(classLoader: ClassLoader): Boolean
    private external fun nativeRegisterQrencryptStubs(classLoader: ClassLoader): Boolean
    private external fun nativeRegisterOnlineChapterStateStubs(classLoader: ClassLoader): Boolean
    private external fun nativeRememberHookClassLoader(classLoader: ClassLoader): Boolean
    private external fun nativeRegisterOnlineChapterDownloadFallbackStubs(classLoader: ClassLoader): Boolean
    private external fun nativeRegisterAllMissingNativeMethods(classLoader: ClassLoader): Int
    private external fun nativeSetIntegrityRedirect(fromPath: String, toPath: String)
    private external fun nativeSetJiaguPackageSpoof(stubPackageName: String, originalPackageName: String)
    private external fun nativeClearIntegrityRedirect()
    private external fun nativeSetSuppressSelfSigkill(enabled: Boolean)
    private external fun nativeGotHookLibrary(libName: String)
    private external fun nativePreParseAndInstallGotHooks(libPath: String): Int

    // LSPlant integration
    private external fun nativeInitLsplant(libDir: String?): Boolean
    private external fun nativeIsLsplantInitialized(): Boolean
    private external fun nativeHookMethod(targetMethod: java.lang.reflect.Executable, hookerObject: Any): Boolean
    private external fun nativeHookMethodWithBackup(targetMethod: java.lang.reflect.Executable, hookerObject: Any): java.lang.reflect.Executable?
    private external fun nativeUnhookMethod(targetMethod: java.lang.reflect.Executable): Boolean
    private external fun nativeIsMethodHooked(method: java.lang.reflect.Executable): Boolean
    private external fun nativeDeoptimizeMethod(method: java.lang.reflect.Executable): Boolean

    // P0: DEX + SO dump
    private external fun nativeDumpDexFromClassLoader(classLoader: ClassLoader, dumpDir: String): Int
    private external fun nativeDumpLoadedLibraries(dumpDir: String, targetLib: String?): Int
    private external fun nativeDumpJiaguRuntimeRanges(dumpDir: String): Int

    private fun tryLoadNativeLibrary(): Boolean = nativeLibLoaded

    fun isNativeLibLoaded(): Boolean = nativeLibLoaded

    private class PathTrie {
        private class Node {
            val children = mutableMapOf<Char, Node>()
            var replacement: String? = null
            var prefixLength: Int = 0
        }
        private val root = Node()
        fun insert(prefix: String, replacement: String) {
            var node = root
            for (ch in prefix) node = node.children.getOrPut(ch) { Node() }
            node.replacement = replacement; node.prefixLength = prefix.length
        }
        fun translate(path: String): String? {
            var node = root; var bestReplacement: String? = null; var bestPrefixLength = 0
            for (ch in path) {
                val child = node.children[ch] ?: break
                node = child
                if (node.replacement != null && node.prefixLength > bestPrefixLength) {
                    bestReplacement = node.replacement; bestPrefixLength = node.prefixLength
                }
            }
            return bestReplacement?.let { it + path.substring(bestPrefixLength) }
        }
        fun clear() { root.children.clear() }
    }
}

class FileAccessInterceptor(private val bridge: NativeHookBridge) {
    companion object { private const val TAG = "FileIntercept" }

    fun interceptFile(originalPath: String): File = File(bridge.translatePath(originalPath))

    fun interceptFileInputStream(originalPath: String): FileInputStream {
        val fakeContent = bridge.getFakeContent(originalPath)
        if (fakeContent != null) {
            val tempFile = File.createTempFile("multiapp_fake_", ".tmp")
            tempFile.deleteOnExit(); tempFile.writeBytes(fakeContent)
            return FileInputStream(tempFile)
        }
        return FileInputStream(bridge.translatePath(originalPath))
    }

    fun interceptFileOutputStream(originalPath: String, append: Boolean = false): FileOutputStream {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) File(translatedPath).parentFile?.mkdirs()
        return FileOutputStream(translatedPath, append)
    }

    fun interceptExists(path: String): Boolean {
        if (bridge.isPathHidden(path)) return false
        return File(bridge.translatePath(path)).exists()
    }

    fun interceptCanRead(path: String): Boolean {
        if (bridge.isPathHidden(path)) return false
        return File(bridge.translatePath(path)).canRead()
    }

    fun interceptListFiles(path: String): Array<File>? {
        return File(bridge.translatePath(path)).listFiles()?.filter { !bridge.isPathHidden(it.absolutePath) }?.toTypedArray()
    }

    fun readFilteredProcMaps(): String {
        return try { bridge.filterProcMaps(File(NativeHookBridge.PROC_SELF_MAPS).readText()) }
        catch (e: Exception) { Timber.tag(TAG).e(e, "Failed to read /proc/self/maps"); "" }
    }
}
