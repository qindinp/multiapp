package com.multiapp.core.hook.antidetection

import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * 自动检测 APK 使用的加固壳类型。
 *
 * 检测策略（按优先级）：
 * 1. 检查 APK 内 native 库名称
 * 2. 检查 APK 内 DEX 中的壳特征类名
 * 3. 检查 AndroidManifest 中的特殊组件
 */
object PackerDetector {

    private const val TAG = "PackerDetector"

    private val detectionCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Shell 特征类名位于 DEX 头部的字符串池中；只读前缀避免全量解压大 APK。 */
    private const val MAX_DEX_SCAN_BYTES = 2 * 1024 * 1024

    /**
     * 自动检测 APK 使用的加固壳类型。
     *
     * @param apkPath APK 文件路径
     * @return 壳类型标识，未检测到返回 "unknown"
     */
    fun detect(apkPath: String): String {
        Timber.tag(TAG).i("Detecting packer type for: $apkPath")
        val apkFile = File(apkPath)
        val cacheKey = "$apkPath|" + apkFile.length() + "|" + apkFile.lastModified()
        detectionCache[cacheKey]?.let { cached ->
            Timber.tag(TAG).i("Packer type cache hit for: $apkPath -> $cached")
            return cached
        }
        if (!apkFile.exists()) {
            Timber.tag(TAG).w("APK file does not exist: $apkPath")
            return "unknown"
        }

        val result = try {
            ZipFile(apkFile).use { zip ->
                detectByNativeLibs(zip)
                    ?: detectByClasses(zip)
                    ?: detectByManifest(zip)
                    ?: "unknown"
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to detect packer type")
            "unknown"
        }
        detectionCache[cacheKey] = result
        return result
    }

    // ---------------------------------------------------------------------------
    // Native library detection
    // ---------------------------------------------------------------------------

    /**
     * 检测 native 库中的壳特征。
     * 各壳通常包含特定的 .so 文件。
     */
    private fun detectByNativeLibs(zip: ZipFile): String? {
        val nativeLibs = zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .map { File(it.name).name }
            .toList()

        // 按优先级检查 — 有些 .so 名称可能重叠（如 libsecexe.so）
        // Bangcle 的 libsecexe.so 与 360 不同，需要结合其他特征区分
        for (lib in nativeLibs) {
            when {
                // 360 Jiagu 特有
                lib.startsWith("libjiagu") -> return "360 Jiagu".also {
                    Timber.tag(TAG).i("Detected 360 Jiagu by native lib: $lib")
                }

                // Tencent Jiagu (Legu)
                lib.startsWith("libshella-") || lib.startsWith("libshellx-") -> return "Tencent Jiagu".also {
                    Timber.tag(TAG).i("Detected Tencent Jiagu by native lib: $lib")
                }

                // iJiami
                lib == "libexec.so" || lib.startsWith("libDexHelper") -> {
                    // libDexHelper.so 也可能被 Bangcle 使用，需结合类名
                    // libexec.so 是 iJiami 更强的特征
                    if (lib == "libexec.so") return "iJiami".also {
                        Timber.tag(TAG).i("Detected iJiami by native lib: $lib")
                    }
                }

                // Alibaba 加固
                lib == "libsgmain.so" || lib == "libsgsecuritybody.so" -> return "Alibaba".also {
                    Timber.tag(TAG).i("Detected Alibaba by native lib: $lib")
                }
            }
        }

        // 组合判断：有 libsecexe.so 时看是否有 libjiagu 已判断过
        // 此处 360 和 Bangcle 共享 libsecexe.so，留到类名检测再区分
        return null
    }

    // ---------------------------------------------------------------------------
    // Class name detection (from DEX)
    // ---------------------------------------------------------------------------

    /**
     * 检测 DEX 中的壳特征类。
     * 通过读取 DEX 文件二进制内容搜索特征类名路径字符串。
     *
     * DEX 字符串以 modified UTF-8 存储，ASCII 部分可直接匹配。
     */
    private fun detectByClasses(zip: ZipFile): String? {
        val dexEntries = zip.entries().asSequence()
            .filter { it.name.endsWith(".dex") }
            .toList()

        for (entry in dexEntries) {
            val content = try {
                zip.getInputStream(entry).use { readDexPrefix(it) }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to read ${entry.name}: ${e.message}")
                continue
            }

            val detected = searchClassPatterns(content)
            if (detected != null) return detected
        }
        return null
    }

    /**
     * 在 DEX 二进制内容中搜索壳特征类名模式。
     */
    /**
     * 只读输入流前 MAX_DEX_SCAN_BYTES 字节，避免对大 DEX 全量解压。
     */
    private fun readDexPrefix(input: java.io.InputStream): ByteArray {
        val buffer = ByteArray(MAX_DEX_SCAN_BYTES)
        var offset = 0
        while (offset < MAX_DEX_SCAN_BYTES) {
            val read = input.read(buffer, offset, MAX_DEX_SCAN_BYTES - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == MAX_DEX_SCAN_BYTES) buffer else buffer.copyOf(offset)
    }

    private fun searchClassPatterns(dexBytes: ByteArray): String? {
        // 将字节转为 ISO-8859-1 兼容字符串用于模式匹配
        val content = String(dexBytes, Charsets.ISO_8859_1)

        // 按优先级从高到低搜索 — 越具体的特征越优先
        val patterns = listOf(
            // 360 Jiagu
            "com/qihoo/util/" to "360 Jiagu",
            "com/stub/StubApp" to "360 Jiagu",
            // Tencent Jiagu
            "com/tencent/StubShell/" to "Tencent Jiagu",
            // iJiami
            "com/shell/SuperApplication" to "iJiami",
            "com/ijiami/armc/" to "iJiami",
            // Bangcle
            "com/secnium/" to "Bangcle",
            "com/secshell/" to "Bangcle",
            // Alibaba 加固
            "com/alibaba/fix/" to "Alibaba",
        )

        for ((pattern, packerType) in patterns) {
            if (content.contains(pattern)) {
                Timber.tag(TAG).i("Detected $packerType by class pattern: $pattern")
                return packerType
            }
        }

        return null
    }

    // ---------------------------------------------------------------------------
    // Manifest-based detection
    // ---------------------------------------------------------------------------

    /**
     * 检测 AndroidManifest 中的壳特征组件。
     * 各壳可能在 manifest 中注册特定的 Application 或 Activity。
     */
    private fun detectByManifest(zip: ZipFile): String? {
        val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
        val manifestBytes = try {
            zip.getInputStream(manifestEntry).readBytes()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to read AndroidManifest.xml: ${e.message}")
            return null
        }

        val content = String(manifestBytes, Charsets.ISO_8859_1)

        val manifestPatterns = listOf(
            "com.stub.StubApp" to "360 Jiagu",
            "com.qihoo" to "360 Jiagu",
            "com.tencent.StubShell" to "Tencent Jiagu",
            "com.ijiami" to "iJiami",
            "com.shell.SuperApplication" to "iJiami",
            "com.secnium" to "Bangcle",
            "com.alibaba.fix" to "Alibaba",
        )

        for ((pattern, packerType) in manifestPatterns) {
            if (content.contains(pattern)) {
                Timber.tag(TAG).i("Detected $packerType by manifest pattern: $pattern")
                return packerType
            }
        }

        return null
    }
}
