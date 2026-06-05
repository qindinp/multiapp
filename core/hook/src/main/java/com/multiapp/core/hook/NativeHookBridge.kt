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
    private external fun nativePreloadLibraries(libPaths: Array<String>): Int
    private external fun nativeLoadLibraryForGuest(libPath: String, classLoader: ClassLoader, callerClass: Class<*>): Int
    private external fun nativeSetupFindClassHook(classLoader: ClassLoader, targetClassNames: Array<String>): Boolean
    private external fun nativeInstallFindClassHook()
    private external fun nativeRegisterStubMethods(classLoader: ClassLoader, className: String): Boolean
    private external fun nativeSetIntegrityRedirect(fromPath: String, toPath: String)
    private external fun nativeClearIntegrityRedirect()
    private external fun nativeGotHookLibrary(libName: String)

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
