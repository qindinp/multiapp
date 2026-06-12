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

/**
 * NativeHookBridge — Native 层 hook 引擎
 *
 * 不使用 Hilt @Singleton/@Inject，因为 LoaderFactory 在 AppComponentFactory 阶段
 * 直接构造实例，此时 Hilt 尚未初始化。统一用 getInstance() 获取全局单例。
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
         * 全局单例
         */
        @Volatile
        private var instance: NativeHookBridge? = null

        fun getInstance(): NativeHookBridge {
            return instance ?: synchronized(this) {
                instance ?: NativeHookBridge().also { instance = it }
            }
        }

        /**
         * 手动标记 native 库已加载。
         * 当库被其他 ClassLoader 加载时（如 stub ClassLoader），
         * NativeHookBridge 的 init 块无法检测到，需要手动标记。
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
    }

    private val pathRedirections = ConcurrentHashMap<String, String>()
    private val pathTrie = PathTrie()
    /**
     * LRU 路径缓存，最大 2048 条，超出自动淘汰最旧条目
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
    private var nativeHooksAvailable = false
    private var appContext: android.content.Context? = null

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
        if (!nativeLibLoaded) {
            android.util.Log.w(TAG, "RegisterNatives logger not available: native lib not loaded")
            return false
        }
        return try {
            val result = nativeInstallRegisterNativesLogger()
            android.util.Log.i(TAG, "RegisterNatives logger installed=$result")
            result
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "RegisterNatives logger install failed: ${e.message}", e)
            false
        }
    }

    /**
     * 通过 dlopen 直接加载 native 库（绕过 Java 层 hidden API 限制）
     * 用于加载加固壳的 libjiagu_vip.so 等库
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
     * 只做 dlopen + GOT hook，不调 JNI_OnLoad。
     * 用于混合方案：先 dlopen 加载并 hook GOT，再通过 loadLibraryForGuest 让 ART 做 ClassLoader 绑定 + JNI_OnLoad。
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
     * 通过 JNI 调用 Runtime.nativeLoad，将库加载到 guest ClassLoader 命名空间
     * JNI 层面调用绕过 Java hidden API 限制
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
     * P0: 从 guest ClassLoader 中 dump 所有已加载的 DEX 文件。
     * 遍历 DexPathList.dexElements，通过 mCookie 提取 DexFile 字节。
     *
     * @param classLoader guest ClassLoader (PathClassLoader)
     * @param dumpDir 输出目录
     * @return 成功 dump 的 DEX 数量
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
     * P0: dump 已加载的 native libraries。
     * 通过 dl_iterate_phdr 遍历所有已加载的 .so，按 PT_LOAD 段重建 ELF。
     *
     * @param dumpDir 输出目录
     * @param targetLib 特定库名（null = dump 所有 app .so）
     * @return 成功 dump 的 .so 数量
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

    /**
     * 设置 FindClass hook 的 guest ClassLoader 和候选目标类名列表。
     * 当 JNI_OnLoad 中 FindClass 查找任一候选类时，通过 guest ClassLoader 加载。
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
     * 安装 FindClass hook（修改 JNI 函数表）。
     * 必须在 setupFindClassHook 之后、System.loadLibrary 之前调用。
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
     * 手动注册壳的 native 方法 stub 实现。
     * 当 FindClass hook 不生效时，用 RegisterNatives 直接注册。
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
     * 注册最小业务 native 兜底（当前仅 YWLoginManager）。
     * 内容签名/加密链路必须保留原始实现，否则书城会出现空数据。
     */
    fun registerBusinessStubs(classLoader: ClassLoader): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterBusinessStubs(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerBusinessStubs failed")
            false
        }
    }

    fun registerOnlineChapterDownloadFallbackStubs(classLoader: ClassLoader): Boolean {
        if (!nativeLibLoaded) return false
        return try {
            nativeRegisterOnlineChapterDownloadFallbackStubs(classLoader)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "registerOnlineChapterDownloadFallbackStubs failed")
            false
        }
    }

    /**
     * 扫描所有已知 native 类，批量注册缺失的 native 方法
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

    private fun rebuildPrefixIndex() {
        pathTrie.clear()
        for ((from, to) in pathRedirections) pathTrie.insert(from, to)
        pathCache.clear()
    }

    fun addPathRedirection(fromPrefix: String, toPrefix: String) {
        pathRedirections[fromPrefix] = toPrefix; rebuildPrefixIndex()
        Timber.tag(TAG).d("Path redirect: $fromPrefix -> $toPrefix")
        if (nativeHooksAvailable) nativeAddPathRedirection(fromPrefix, toPrefix)
    }

    fun removePathRedirection(fromPrefix: String) {
        pathRedirections.remove(fromPrefix); rebuildPrefixIndex()
        if (nativeHooksAvailable) nativeRemovePathRedirection(fromPrefix)
    }

    fun clearPathRedirections() {
        pathRedirections.clear(); rebuildPrefixIndex()
        if (nativeHooksAvailable) nativeClearPathRedirections()
        Timber.tag(TAG).d("All path redirections cleared")
    }

    /**
     * 设置完整性校验重定向：壳的 JNI_OnLoad 读 APK 校验 DEX 时，重定向到原始 APK。
     * 必须在调用 System.loadLibrary() 之前设置，之后调用 clearIntegrityRedirect()。
     */
    fun setIntegrityRedirect(fromPath: String, toPath: String) {
        if (nativeHooksAvailable) nativeSetIntegrityRedirect(fromPath, toPath)
    }

    fun clearIntegrityRedirect() {
        if (nativeHooksAvailable) nativeClearIntegrityRedirect()
    }

    /**
     * GOT hook：修改目标库的 GOT 表，拦截 open/openat/fopen 调用。
     * 用于过滤 /proc/self/maps 读取，绕过壳的反调试检测。
     * 不需要 trampoline 内存，Android 16 上可行。
     */
    fun gotHookLibrary(libName: String) {
        if (nativeHooksAvailable) nativeGotHookLibrary(libName)
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
            // 找到 host APK 的 nativeLibraryDir（liblsplant.so 所在目录）
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
     * 找到 host APK 的 native library 目录
     * liblsplant.so 在 host APK 的 lib 目录中，不在 guest/stub APK 中
     */
    private fun findHostNativeLibDir(): String? {
        // 方式0: 从 PackageManager 获取 host APK 的 nativeLibraryDir
        // liblsplant.so 在 host APK（com.multiapp.app）的 lib 目录中，不在 stub 中
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

        // 方式1: 从 /proc/self/maps 找 libmultiapp-native.so 的路径
        // liblsplant.so 在同一目录（host APK 的 lib 目录）
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

        // 方式2: 从 HookEngine 的 ClassLoader 获取（可能是 stub 的，作 fallback）
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
        if (!nativeHooksAvailable) {
            android.util.Log.w(TAG, "hookMethod: native lib not loaded")
            return false
        }
        return try {
            val result = nativeHookMethod(targetMethod, hookerObject)
            android.util.Log.i(TAG, "hookMethod: ${targetMethod.declaringClass.name}.${targetMethod.name} result=$result")
            result
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "hookMethod failed: ${e.message}", e)
            false
        }
    }

    fun setupAppRedirections(guestPackageName: String, instanceId: String, sandboxDataDir: String) {
        addPathRedirection("/data/data/$guestPackageName/", "$sandboxDataDir/")
        addPathRedirection("/data/user/0/$guestPackageName/", "$sandboxDataDir/")
        addPathRedirection("/storage/emulated/0/Android/data/$guestPackageName/", "$sandboxDataDir/external_data/")
        addPathRedirection("/sdcard/Android/data/$guestPackageName/", "$sandboxDataDir/external_data/")
        addPathRedirection("/storage/emulated/0/Android/obb/$guestPackageName/", "$sandboxDataDir/obb/")
        Timber.tag(TAG).d("App redirections set for $guestPackageName ($instanceId)")
    }

    fun setupExternalStorageRedirections(instanceId: String, virtualSdcardDir: String) {
        addPathRedirection("/sdcard/", "$virtualSdcardDir/")
        addPathRedirection("/storage/emulated/0/", "$virtualSdcardDir/")
        addPathRedirection("/mnt/sdcard/", "$virtualSdcardDir/")
        addPathRedirection("/storage/self/primary/", "$virtualSdcardDir/")
        addPathRedirection("/storage/emulated/0", virtualSdcardDir)
        addPathRedirection("/sdcard", virtualSdcardDir)
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
        synchronized(pathCacheLock) {
            pathCache[originalPath]?.let { return it }
            val result = pathTrie.translate(originalPath) ?: originalPath
            pathCache[originalPath] = result
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
        if (nativeHooksAvailable) nativeCleanup()
        initialized = false
        Timber.tag(TAG).i("Native hook bridge cleaned up")
    }

    fun initNativeHooks(context: android.content.Context? = null, hostDataDir: String? = null): Boolean {
        if (initialized) return nativeHooksAvailable
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
                    Timber.tag(TAG).w("No dataDir available — native path redirection may not work")
                }
            } catch (e: Exception) { Timber.tag(TAG).e(e, "Native hook init failed"); nativeHooksAvailable = false }
        } else {
            Timber.tag(TAG).e("Native library not loaded — native hooks DISABLED")
        }
        ROOT_PATHS.forEach { hiddenPaths.add(it) }
        EMULATOR_PATHS.forEach { hiddenPaths.add(it) }
        initialized = true
        return nativeHooksAvailable
    }

    private fun buildFakeProcStatus(pid: Int, packageName: String): ByteArray {
        val name = packageName.take(15)
        return "Name:\t$name\nUmask:\t0077\nState:\tS (sleeping)\nTgid:\t$pid\nPid:\t$pid\nPPid:\t${pid - 1}\nTracerPid:\t0\n".toByteArray()
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
    private external fun nativeAddPathRedirection(fromPrefix: String, toPrefix: String)
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
    private external fun nativeGetPropertySpoofCount(): Int
    private external fun nativeIsInitialized(): Boolean
    private external fun nativeInstallRuntimeLoadHook(fallbackCallerClasses: Array<String>): Boolean
    private external fun nativeInstallRegisterNativesLogger(): Boolean
    private external fun nativePreloadLibraries(libPaths: Array<String>): Int
    private external fun nativeLoadLibraryForGuest(libPath: String, classLoader: ClassLoader, callerClass: Class<*>): Int
    private external fun nativeDlopenOnly(libPath: String): Int
    private external fun nativeSetupFindClassHook(classLoader: ClassLoader, targetClassNames: Array<String>): Boolean
    private external fun nativeInstallFindClassHook()
    private external fun nativeRegisterStubMethods(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeRegisterBusinessStubs(classLoader: ClassLoader): Boolean
    private external fun nativeRegisterOnlineChapterDownloadFallbackStubs(classLoader: ClassLoader): Boolean
    private external fun nativeRegisterAllMissingNativeMethods(classLoader: ClassLoader): Int
    private external fun nativeSetIntegrityRedirect(fromPath: String, toPath: String)
    private external fun nativeClearIntegrityRedirect()
    private external fun nativeGotHookLibrary(libName: String)

    // LSPlant integration
    private external fun nativeInitLsplant(libDir: String?): Boolean
    private external fun nativeIsLsplantInitialized(): Boolean
    private external fun nativeHookMethod(targetMethod: java.lang.reflect.Executable, hookerObject: Any): Boolean

    // P0: DEX + SO dump
    private external fun nativeDumpDexFromClassLoader(classLoader: ClassLoader, dumpDir: String): Int
    private external fun nativeDumpLoadedLibraries(dumpDir: String, targetLib: String?): Int

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
