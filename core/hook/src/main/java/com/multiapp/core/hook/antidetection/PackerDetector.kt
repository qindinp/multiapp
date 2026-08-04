package com.multiapp.core.hook.antidetection

import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * 自动检测 APK 使用的加固壳类型。
 *
 * 检测策略（按优先级）：
 * 1. 检查 APK 内 native 库名称（L1_SO）
 * 2. 检查 APK 内 DEX 中的壳特征类名（L2_DEX）
 * 3. 检查 AndroidManifest 中的特殊组件（L3_MANIFEST）
 *
 * Phase1 类型化升级：
 * - [detect] 保留旧版 String 兼容入口（返回家族 legacyLabel，miss 返回 "unknown"）。
 * - [detectEvidence] 返回类型化 [PackerDetectionEvidence]，内部三级扫描输出
 *   [List]<[PackerSignal]> 并聚合家族 + 置信度 + 策略。
 * - 360 家族聚合扩展：{libjiagu_*, libslib, libdexvmp, libpatchtools,
 *   com/stub/StubApp, com/qihoo/util/} → [PackerFamily.QIHOO_360]。
 */
object PackerDetector {

    private const val TAG = "PackerDetector"

    private val detectionCache = java.util.concurrent.ConcurrentHashMap<String, PackerDetectionEvidence>()

    /** Shell 特征类名位于 DEX 头部的字符串池中；只读前缀避免全量解压大 APK。 */
    private const val MAX_DEX_SCAN_BYTES = 2 * 1024 * 1024

    /**
     * 自动检测 APK 使用的加固壳类型（旧版兼容入口）。
     *
     * @param apkPath APK 文件路径
     * @return 壳类型标识，未检测到返回 "unknown"
     */
    fun detect(apkPath: String): String = detectEvidence(apkPath).family.legacyLabel

    /**
     * 自动检测 APK 使用的加固壳类型（类型化入口）。
     *
     * 三级扫描（native libs → dex classes → manifest）收集全部 [PackerSignal]，
     * 按层级优先级（L1 > L2 > L3）与扫描顺序聚合家族。
     *
     * @param apkPath APK 文件路径
     * @return 类型化检测结果；文件缺失/损坏/未识别返回 [PackerDetectionEvidence.UNKNOWN]
     */
    fun detectEvidence(apkPath: String): PackerDetectionEvidence {
        Timber.tag(TAG).i("Detecting packer type for: $apkPath")
        val apkFile = File(apkPath)
        val cacheKey = "$apkPath|" + apkFile.length() + "|" + apkFile.lastModified()
        detectionCache[cacheKey]?.let { cached ->
            Timber.tag(TAG).i("Packer type cache hit for: $apkPath -> ${cached.family.legacyLabel}")
            return cached
        }
        if (!apkFile.exists()) {
            Timber.tag(TAG).w("APK file does not exist: $apkPath")
            return PackerDetectionEvidence.UNKNOWN
        }

        val result = try {
            ZipFile(apkFile).use { zip ->
                buildEvidence(
                    scanNativeLibs(zip) +
                        scanClasses(zip) +
                        scanManifest(zip)
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to detect packer type")
            PackerDetectionEvidence.UNKNOWN
        }
        detectionCache[cacheKey] = result
        return result
    }

    // ---------------------------------------------------------------------------
    // Evidence 聚合
    // ---------------------------------------------------------------------------

    private fun buildEvidence(signals: List<PackerSignal>): PackerDetectionEvidence {
        val family = aggregateFamily(signals)
        if (family == PackerFamily.UNKNOWN) {
            return PackerDetectionEvidence.UNKNOWN
        }
        val confidence = computeConfidence(family, signals)
        val strategy = when (family) {
            PackerFamily.OTHER -> PackerDetectionStrategy.ROUTE_GENERIC
            else -> PackerDetectionStrategy.ROUTE_SPECIFIC
        }
        return PackerDetectionEvidence(
            family = family,
            confidence = confidence,
            signals = signals,
            strategy = strategy
        )
    }

    /**
     * 聚合家族：优先取第一个非空层级的首条信号（扫描顺序）。
     * 与旧版"native libs > DEX classes > manifest、先匹配先赢"语义完全一致。
     */
    private fun aggregateFamily(signals: List<PackerSignal>): PackerFamily {
        for (level in LEVEL_PRIORITY) {
            val first = signals.firstOrNull { it.level == level }
            if (first != null) return first.family
        }
        return PackerFamily.UNKNOWN
    }

    /**
     * 置信度：L1 so 名命中 → HIGH；L2/L3 → MEDIUM；同一家族多信号可提升为 HIGH。
     */
    private fun computeConfidence(family: PackerFamily, signals: List<PackerSignal>): PackerConfidence {
        val familySignals = signals.filter { it.family == family }
        if (familySignals.isEmpty()) return PackerConfidence.LOW
        val base = familySignals.maxOf { it.confidence }
        return if (base == PackerConfidence.HIGH || familySignals.size >= 2) {
            PackerConfidence.HIGH
        } else {
            base
        }
    }

    private fun signalConfidence(level: PackerSignalLevel): PackerConfidence = when (level) {
        PackerSignalLevel.L1_SO -> PackerConfidence.HIGH
        PackerSignalLevel.L2_DEX -> PackerConfidence.MEDIUM
        PackerSignalLevel.L3_MANIFEST -> PackerConfidence.MEDIUM
        PackerSignalLevel.L4_RUNTIME -> PackerConfidence.HIGH
    }

    // ---------------------------------------------------------------------------
    // Native library detection (L1)
    // ---------------------------------------------------------------------------

    /**
     * 扫描 native 库中的壳特征，收集全部 L1 信号。
     * 各壳通常包含特定的 .so 文件；一个 .so 至多命中一个家族。
     */
    private fun scanNativeLibs(zip: ZipFile): List<PackerSignal> {
        val nativeLibs = zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .map { File(it.name).name }
            .toList()

        val signals = mutableListOf<PackerSignal>()
        // 按优先级检查 — 有些 .so 名称可能重叠（如 libsecexe.so）
        // Bangcle 的 libsecexe.so 与 360 不同，需要结合其他特征区分
        for (lib in nativeLibs) {
            val family = when {
                // 360 Jiagu 特有（含 libslib/libdexvmp/libpatchtools 变体）
                lib.startsWith("libjiagu") ||
                    lib.startsWith("libslib") ||
                    lib.startsWith("libdexvmp") ||
                    lib.startsWith("libpatchtools") -> PackerFamily.QIHOO_360

                // Tencent Jiagu (Legu)
                lib.startsWith("libshella-") || lib.startsWith("libshellx-") -> PackerFamily.TENCENT_JIAGU

                // iJiami（libDexHelper 也可能被 Bangcle 使用，需结合类名，不在此判定）
                lib == "libexec.so" -> PackerFamily.IJIMAI

                // Alibaba 加固
                lib == "libsgmain.so" || lib == "libsgsecuritybody.so" -> PackerFamily.ALIBABA

                else -> null
            }
            if (family != null) {
                Timber.tag(TAG).i("Detected ${family.legacyLabel} by native lib: $lib")
                signals += PackerSignal(
                    family = family,
                    level = PackerSignalLevel.L1_SO,
                    pattern = lib,
                    confidence = signalConfidence(PackerSignalLevel.L1_SO)
                )
            }
        }
        return signals
    }

    // ---------------------------------------------------------------------------
    // Class name detection (L2, from DEX)
    // ---------------------------------------------------------------------------

    /**
     * 检测 DEX 中的壳特征类，收集全部 L2 信号。
     * 通过读取 DEX 文件二进制内容搜索特征类名路径字符串。
     *
     * DEX 字符串以 modified UTF-8 存储，ASCII 部分可直接匹配。
     */
    private fun scanClasses(zip: ZipFile): List<PackerSignal> {
        val dexEntries = zip.entries().asSequence()
            .filter { it.name.endsWith(".dex") }
            .toList()

        val signals = mutableListOf<PackerSignal>()
        for (entry in dexEntries) {
            val content = try {
                zip.getInputStream(entry).use { readDexPrefix(it) }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to read ${entry.name}: ${e.message}")
                continue
            }
            signals += searchClassPatterns(content)
        }
        return signals
    }

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

    private fun searchClassPatterns(dexBytes: ByteArray): List<PackerSignal> {
        // 将字节转为 ISO-8859-1 兼容字符串用于模式匹配
        val content = String(dexBytes, Charsets.ISO_8859_1)

        // 按优先级从高到低搜索 — 越具体的特征越优先
        val patterns = listOf(
            // 360 Jiagu
            "com/qihoo/util/" to PackerFamily.QIHOO_360,
            "com/stub/StubApp" to PackerFamily.QIHOO_360,
            // Tencent Jiagu
            "com/tencent/StubShell/" to PackerFamily.TENCENT_JIAGU,
            // iJiami
            "com/shell/SuperApplication" to PackerFamily.IJIMAI,
            "com/ijiami/armc/" to PackerFamily.IJIMAI,
            // Bangcle
            "com/secnium/" to PackerFamily.BANGCLE,
            "com/secshell/" to PackerFamily.BANGCLE,
            // Alibaba 加固
            "com/alibaba/fix/" to PackerFamily.ALIBABA,
        )

        val signals = mutableListOf<PackerSignal>()
        for ((pattern, family) in patterns) {
            if (content.contains(pattern)) {
                Timber.tag(TAG).i("Detected ${family.legacyLabel} by class pattern: $pattern")
                signals += PackerSignal(
                    family = family,
                    level = PackerSignalLevel.L2_DEX,
                    pattern = pattern,
                    confidence = signalConfidence(PackerSignalLevel.L2_DEX)
                )
            }
        }
        return signals
    }

    // ---------------------------------------------------------------------------
    // Manifest-based detection (L3)
    // ---------------------------------------------------------------------------

    /**
     * 检测 AndroidManifest 中的壳特征组件，收集全部 L3 信号。
     * 各壳可能在 manifest 中注册特定的 Application 或 Activity。
     */
    private fun scanManifest(zip: ZipFile): List<PackerSignal> {
        val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
        val manifestBytes = try {
            zip.getInputStream(manifestEntry).readBytes()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to read AndroidManifest.xml: ${e.message}")
            return emptyList()
        }

        val content = String(manifestBytes, Charsets.ISO_8859_1)

        val manifestPatterns = listOf(
            "com.stub.StubApp" to PackerFamily.QIHOO_360,
            "com.qihoo" to PackerFamily.QIHOO_360,
            "com.tencent.StubShell" to PackerFamily.TENCENT_JIAGU,
            "com.ijiami" to PackerFamily.IJIMAI,
            "com.shell.SuperApplication" to PackerFamily.IJIMAI,
            "com.secnium" to PackerFamily.BANGCLE,
            "com.alibaba.fix" to PackerFamily.ALIBABA,
        )

        val signals = mutableListOf<PackerSignal>()
        for ((pattern, family) in manifestPatterns) {
            if (content.contains(pattern)) {
                Timber.tag(TAG).i("Detected ${family.legacyLabel} by manifest pattern: $pattern")
                signals += PackerSignal(
                    family = family,
                    level = PackerSignalLevel.L3_MANIFEST,
                    pattern = pattern,
                    confidence = signalConfidence(PackerSignalLevel.L3_MANIFEST)
                )
            }
        }
        return signals
    }

    private val LEVEL_PRIORITY = arrayOf(
        PackerSignalLevel.L1_SO,
        PackerSignalLevel.L2_DEX,
        PackerSignalLevel.L3_MANIFEST
    )
}
