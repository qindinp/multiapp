package com.multiapp.core.hook

import android.content.Context
import com.multiapp.core.common.AndroidCompat
import com.multiapp.core.common.findField
import com.multiapp.core.common.runSafe
import com.multiapp.core.model.VirtualConstants
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeHookBridge @Inject constructor() {

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

        @Volatile private var nativeLibLoaded = false

        init {
            nativeLibLoaded = try {
                System.loadLibrary("multiapp-native"); true
            } catch (e: UnsatisfiedLinkError) {
                Timber.tag(TAG).w("libmultiapp-native.so not available: ${e.message}"); false
            }
        }

        /**
         * LoaderFactory 专用: 一次性完成 shadowhook 初始化 + /proc/self 伪装 + 属性伪装
         * 不需要 NativeHookBridge 实例，全部通过 static JNI 完成
         *
         * @param packageName 原始 app 包名 (用于 /proc/self/cmdline 伪装)
         * @param properties 系统属性伪装 (ro.product.model 等)
         * @return true 如果 native hooks 初始化成功
         */
        fun setupForLoader(packageName: String, properties: Map<String, String>): Boolean {
            if (!nativeLibLoaded) {
                Timber.tag(TAG).w("setupForLoader: native lib not loaded")
                return false
            }
            return try {
                val propKeys = properties.keys.toTypedArray()
                val propValues = properties.values.toTypedArray()
                nativeSetupForLoader(packageName, propKeys, propValues)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "setupForLoader failed")
                false
            }
        }

        // Static JNI — 一次性完成 shadowhook 初始化 + /proc/self + 属性伪装
        @JvmStatic private external fun nativeSetupForLoader(
            packageName: String, propKeys: Array<String>, propValues: Array<String>
        ): Boolean
    }

    private val pathRedirections = ConcurrentHashMap<String, String>()
    private val pathTrie = PathTrie()
    private val pathCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }
    private var spoofedPackageName: String? = null
    private var spoofedPid: Int = -1
    private var spoofedProcessName: String? = null
    private val propertyOverrides = ConcurrentHashMap<String, String>()
    private val hiddenPaths = ConcurrentHashMap.newKeySet<String>()
    private val fakeFileContent = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var initialized = false
    @Volatile private var nativeHooksAvailable = false
    private var appContext: android.content.Context? = null

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            Timber.tag(TAG).i("NativeHookBridge initialized")
            hookRuntimeNativeLoad()
            initialized = true
        }
    }

    fun hookRuntimeNativeLoad() {
        if (nativeLibLoaded) {
            try {
                val result = nativeInstallRuntimeLoadHook()
                if (result) { Timber.tag(TAG).i("Runtime.nativeLoad JNI hook installed"); return }
            } catch (e: Exception) { Timber.tag(TAG).w("Native Runtime.nativeLoad hook failed: ${e.message}") }
        }
        Timber.tag(TAG).w("Runtime.nativeLoad hook not available")
    }

    private fun rebuildPrefixIndex() {
        pathTrie.clear()
        for ((from, to) in pathRedirections) pathTrie.insert(from, to)
        synchronized(pathCache) { pathCache.clear() }
    }

    fun addPathRedirection(fromPrefix: String, toPrefix: String) {
        pathRedirections[fromPrefix] = toPrefix; rebuildPrefixIndex()
        Timber.tag(TAG).d("Path redirect: $fromPrefix -> $toPrefix")
        if (nativeHooksAvailable) nativeAddPathRedirection(fromPrefix, toPrefix)
    }

    /**
     * 批量添加路径重定向，只重建一次前缀索引。
     * 适用于 setupAppRedirections / setupExternalStorageRedirections 等批量场景。
     * 单条规则仍使用 [addPathRedirection]。
     */
    fun addPathRedirections(redirections: Map<String, String>) {
        redirections.forEach { (from, to) ->
            pathRedirections[from] = to
        }
        rebuildPrefixIndex()
        redirections.forEach { (from, to) ->
            Timber.tag(TAG).d("Path redirect: $from -> $to")
            if (nativeHooksAvailable) nativeAddPathRedirection(from, to)
        }
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

    fun setupAppRedirections(guestPackageName: String, instanceId: String, sandboxDataDir: String) {
        addPathRedirections(mapOf(
            "/data/data/$guestPackageName/" to "$sandboxDataDir/",
            "/data/user/0/$guestPackageName/" to "$sandboxDataDir/",
            "/storage/emulated/0/Android/data/$guestPackageName/" to "$sandboxDataDir/external_data/",
            "/sdcard/Android/data/$guestPackageName/" to "$sandboxDataDir/external_data/",
            "/storage/emulated/0/Android/obb/$guestPackageName/" to "$sandboxDataDir/obb/"
        ))
        Timber.tag(TAG).d("App redirections set for $guestPackageName ($instanceId)")
    }

    fun setupExternalStorageRedirections(instanceId: String, virtualSdcardDir: String) {
        addPathRedirections(mapOf(
            "/sdcard/" to "$virtualSdcardDir/",
            "/storage/emulated/0/" to "$virtualSdcardDir/",
            "/mnt/sdcard/" to "$virtualSdcardDir/",
            "/storage/self/primary/" to "$virtualSdcardDir/",
            "/storage/emulated/0" to virtualSdcardDir,
            "/sdcard" to virtualSdcardDir
        ))
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
        synchronized(pathCache) {
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
        fakeFileContent.clear(); propertyOverrides.clear()
        spoofedPackageName = null; spoofedPid = -1
        if (nativeHooksAvailable) nativeCleanup()
        initialized = false
        Timber.tag(TAG).i("Native hook bridge cleaned up")
    }

    fun initNativeHooks(context: android.content.Context? = null): Boolean {
        if (initialized) return true
        appContext = context?.applicationContext
        nativeHooksAvailable = tryLoadNativeLibrary()
        if (nativeHooksAvailable) {
            try {
                nativeInit()
                appContext?.let { ctx ->
                    try {
                        nativeSetHostDataPrefix(ctx.dataDir.absolutePath)
                        nativeSetVirtualDataRoot(ctx.getDir("virtual", android.content.Context.MODE_PRIVATE).absolutePath)
                    } catch (e: Exception) { Timber.tag(TAG).w("Failed to set native data paths: ${e.message}") }
                }
            } catch (e: Exception) { Timber.tag(TAG).e(e, "Native hook init failed"); nativeHooksAvailable = false }
        }
        ROOT_PATHS.forEach { hiddenPaths.add(it) }
        EMULATOR_PATHS.forEach { hiddenPaths.add(it) }
        initialized = true
        return true
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
        val lowerLinesToHide = linesToHide.map { it.lowercase() }
        return originalMaps.lines().filter { line ->
            val lower = line.lowercase()
            lowerLinesToHide.none { lower.contains(it) }
        }.joinToString("\n")
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
    private external fun nativeInstallRuntimeLoadHook(): Boolean

    private fun tryLoadNativeLibrary(): Boolean = nativeLibLoaded

    private class PathTrie {
        private class Node {
            val children = mutableMapOf<Char, Node>()
            var replacement: String? = null
            var prefixLength: Int = 0
        }
        private val root = Node()
        private val lock = ReentrantReadWriteLock()

        fun insert(prefix: String, replacement: String) {
            val writeLock = lock.writeLock()
            writeLock.lock()
            try {
                var node = root
                for (ch in prefix) node = node.children.getOrPut(ch) { Node() }
                node.replacement = replacement; node.prefixLength = prefix.length
            } finally {
                writeLock.unlock()
            }
        }

        fun translate(path: String): String? {
            val readLock = lock.readLock()
            readLock.lock()
            try {
                var node = root
                var bestReplacement: String? = null
                var bestPrefixLength = 0
                for (ch in path) {
                    val child = node.children[ch] ?: break
                    node = child
                    if (node.replacement != null && node.prefixLength > bestPrefixLength) {
                        bestReplacement = node.replacement
                        bestPrefixLength = node.prefixLength
                    }
                }
                return bestReplacement?.let { it + path.substring(bestPrefixLength) }
            } finally {
                readLock.unlock()
            }
        }

        fun clear() {
            val writeLock = lock.writeLock()
            writeLock.lock()
            try {
                root.children.clear()
            } finally {
                writeLock.unlock()
            }
        }
    }
}

class FileAccessInterceptor(private val bridge: NativeHookBridge) {
    companion object { private const val TAG = "FileIntercept" }

    fun interceptFile(originalPath: String): File = File(bridge.translatePath(originalPath))

    fun interceptFileInputStream(originalPath: String): InputStream {
        val fakeContent = bridge.getFakeContent(originalPath)
        if (fakeContent != null) {
            return ByteArrayInputStream(fakeContent)
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
