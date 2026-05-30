package com.multiapp.core.hook

import android.content.pm.PackageManager
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App Genome Mapping — APK structure and feature fingerprinting.
 *
 * Extracts permissions, components, libraries, and signing info from APKs.
 * Generates a feature fingerprint for similarity detection.
 * Detects known malicious patterns (obfuscation indicators, suspicious APIs).
 *
 * The "genome" is a multi-dimensional feature vector that captures an app's
 * behavioral signature, useful for identifying similar apps, repackaged malware,
 * and cloned applications.
 */
@Singleton
class AppGenomeMapper @Inject constructor() {

    companion object {
        private const val TAG = "AppGenomeMapper"

        // Dangerous permissions grouped by risk category
        private val DANGEROUS_PERMISSIONS = setOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.CALL_PHONE",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION"
        )

        // Suspicious API patterns found in known malware families
        private val SUSPICIOUS_API_PATTERNS = listOf(
            "getDeviceId", "getSubscriberId", "getSimSerialNumber",
            "getMacAddress", "getSSID", "getBSSID",
            "getInstalledPackages", "getInstalledApplications",
            "Runtime.exec", "ProcessBuilder",
            "DexClassLoader", "PathClassLoader", "InMemoryDexClassLoader",
            "Cipher", "SecretKeySpec", "MessageDigest",
            "SecretKeyFactory", "KeyGenerator",
            "SmsManager.sendTextMessage",
            "ClipboardManager", "ContentObserver",
            "AccessibilityService", "NotificationListenerService",
            "DeviceAdminReceiver", "BIND_DEVICE_ADMIN"
        )

        // Known packer/protector signatures in DEX
        private val PACKER_SIGNATURES = mapOf(
            "com.qihoo.util" to "360 Jiagu",
            "com.stub.StubApp" to "iJiami",
            "com.SecShell" to "SecShell",
            "com.bangcle" to "Bangcle",
            "com.secneo" to "Bangcle SecNeo",
            "com.ijiami" to "iJiami",
            "com.tencent.StubShell" to "Tencent Jiagu",
            "com.nqshield" to "NQ Shield",
            "com.lbe.security" to "LBE"
        )

        // Pre-compiled regex patterns for manifest parsing
        private val REGEX_USES_PERMISSION = Regex("uses-permission.*?name=\"([^\"]+)\"")
        private val REGEX_ACTIVITY = Regex("<activity\\b")
        private val REGEX_SERVICE = Regex("<service\\b")
        private val REGEX_RECEIVER = Regex("<receiver\\b")
        private val REGEX_PROVIDER = Regex("<provider\\b")
    }

    // Cached genome results: fileHash -> genome
    private val genomeCache = ConcurrentHashMap<String, AppGenome>()

    /**
     * Analyze an APK and produce a full genome.
     *
     * @param apkPath Absolute path to the APK file
     * @param packageManager PackageManager for metadata queries
     * @return AppGenome with all extracted features, or null on failure
     */
    fun analyze(apkPath: String, packageManager: PackageManager): AppGenome? {
        val file = File(apkPath)
        if (!file.exists()) {
            Timber.tag(TAG).e("APK not found: $apkPath")
            return null
        }

        // Check cache by file hash
        val fileHash = computeSha256(apkPath)
        genomeCache[fileHash]?.let { return it }

        return try {
            val zipFile = ZipFile(file)

            // Extract features
            val permissions = extractPermissions(zipFile)
            val components = extractComponents(zipFile)
            val nativeLibs = extractNativeLibs(zipFile)
            val dexFiles = extractDexFiles(zipFile)
            val resources = extractResourceStats(zipFile)
            val signatures = extractSignatures(apkPath, packageManager)
            val suspiciousApis = scanForSuspiciousPatterns(zipFile)
            val packer = detectPacker(zipFile)
            val permissionsRiskScore = calculatePermissionRisk(permissions)
            val fingerprint = generateFingerprint(
                permissions, components, nativeLibs, dexFiles, suspiciousApis
            )

            zipFile.close()

            val genome = AppGenome(
                apkPath = apkPath,
                fileHash = fileHash,
                fileSizeBytes = file.length(),
                permissions = permissions,
                dangerousPermissions = permissions.filter { it in DANGEROUS_PERMISSIONS },
                componentCounts = components,
                nativeLibraries = nativeLibs,
                dexFileCount = dexFiles.size,
                totalDexSizeBytes = dexFiles.sumOf { it.second },
                resourceStats = resources,
                signatureHash = signatures,
                suspiciousApis = suspiciousApis,
                detectedPacker = packer,
                permissionRiskScore = permissionsRiskScore,
                fingerprint = fingerprint,
                analyzedAt = System.currentTimeMillis()
            )

            genomeCache[fileHash] = genome
            Timber.tag(TAG).i(
                "Genome mapped: ${file.name} | perms=${permissions.size} | " +
                    "risk=$permissionsRiskScore | suspicious=${suspiciousApis.size} | " +
                    "packer=${packer ?: "none"}"
            )
            genome
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to analyze APK: $apkPath")
            null
        }
    }

    /**
     * Compare two genomes and return a similarity score (0.0 - 1.0).
     *
     * Uses Jaccard similarity on permission sets, component counts, and
     * native library sets. A score > 0.85 suggests the apps are repackaged
     * variants of each other.
     */
    fun compareSimilarity(genomeA: AppGenome, genomeB: AppGenome): Double {
        val permSimilarity = jaccardSimilarity(
            genomeA.permissions.toSet(),
            genomeB.permissions.toSet()
        )
        val libSimilarity = jaccardSimilarity(
            genomeA.nativeLibraries.toSet(),
            genomeB.nativeLibraries.toSet()
        )
        val componentSim = 1.0 - normalizedDistance(
            genomeA.componentCounts.totalComponents,
            genomeB.componentCounts.totalComponents
        )
        val suspiciousSim = jaccardSimilarity(
            genomeA.suspiciousApis.toSet(),
            genomeB.suspiciousApis.toSet()
        )

        // Weighted average: permissions 30%, libraries 30%, components 20%, suspicious 20%
        return permSimilarity * 0.3 + libSimilarity * 0.3 +
            componentSim * 0.2 + suspiciousSim * 0.2
    }

    /**
     * Get a cached genome by file hash.
     */
    fun getCached(fingerprint: String): AppGenome? = genomeCache[fingerprint]

    /**
     * Get all cached genomes.
     */
    fun getAllCached(): List<AppGenome> = genomeCache.values.toList()

    /**
     * Clear the genome cache.
     */
    fun clearCache() {
        genomeCache.clear()
        Timber.tag(TAG).d("Genome cache cleared")
    }

    // ─── Extraction helpers ─────────────────────────────────────────

    private fun extractPermissions(zip: ZipFile): List<String> {
        val manifest = readManifestText(zip) ?: return emptyList()
        return REGEX_USES_PERMISSION
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun extractComponents(zip: ZipFile): ComponentCounts {
        val manifest = readManifestText(zip) ?: return ComponentCounts()
        val activities = REGEX_ACTIVITY.findAll(manifest).count()
        val services = REGEX_SERVICE.findAll(manifest).count()
        val receivers = REGEX_RECEIVER.findAll(manifest).count()
        val providers = REGEX_PROVIDER.findAll(manifest).count()
        return ComponentCounts(activities, services, receivers, providers)
    }

    private fun extractNativeLibs(zip: ZipFile): List<String> {
        return zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .map { it.name }
            .toList()
    }

    private fun extractDexFiles(zip: ZipFile): List<Pair<String, Long>> {
        return zip.entries().asSequence()
            .filter { it.name.endsWith(".dex") }
            .map { it.name to it.size }
            .toList()
    }

    private fun extractResourceStats(zip: ZipFile): ResourceStats {
        var drawableCount = 0
        var layoutCount = 0
        var xmlCount = 0
        var totalResourceSize = 0L

        zip.entries().asSequence().forEach { entry ->
            when {
                entry.name.startsWith("res/drawable") -> drawableCount++
                entry.name.startsWith("res/layout") -> layoutCount++
                entry.name.endsWith(".xml") -> xmlCount++
            }
            if (entry.name.startsWith("res/")) {
                totalResourceSize += entry.size
            }
        }
        return ResourceStats(drawableCount, layoutCount, xmlCount, totalResourceSize)
    }

    private fun extractSignatures(apkPath: String, pm: PackageManager): String {
        return try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val packageInfo = pm.getPackageArchiveInfo(apkPath, flags)
            packageInfo?.applicationInfo?.apply {
                sourceDir = apkPath
                publicSourceDir = apkPath
            }
            val signingInfo = packageInfo?.signingInfo
            val digests = signingInfo?.apkContentsSigners?.map { sig ->
                sha256Hex(sig.toByteArray())
            }?.sorted() ?: emptyList()
            if (digests.isEmpty()) "unsigned" else digests.joinToString(";")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Signature extraction failed: ${e.message}")
            "unknown"
        }
    }

    private fun scanForSuspiciousPatterns(zip: ZipFile): List<String> {
        val found = mutableSetOf<String>()
        val dexEntries = zip.entries().asSequence().filter { it.name.endsWith(".dex") }

        for (entry in dexEntries) {
            try {
                // 流式扫描：每次读 8KB，避免大 DEX (50MB+) 导致 OOM
                val stream = zip.getInputStream(entry).buffered(8192)
                val buffer = ByteArray(8192)
                var prevTail = "" // 上一次读取的尾部，用于跨 buffer 匹配
                var bytesRead: Int

                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = prevTail + String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    for (pattern in SUSPICIOUS_API_PATTERNS) {
                        if (chunk.contains(pattern, ignoreCase = true)) {
                            found.add(pattern)
                        }
                    }
                    // 保留尾部用于跨 buffer 匹配
                    prevTail = if (bytesRead >= 256) {
                        String(buffer, bytesRead - 256, 256, Charsets.ISO_8859_1)
                    } else {
                        chunk.takeLast(256)
                    }
                    // 如果所有特征都找到了，提前退出
                    if (found.size == SUSPICIOUS_API_PATTERNS.size) break
                }
                stream.close()
            } catch (_: Exception) { /* skip unreadable entries */ }
        }
        return found.sorted()
    }

    private fun detectPacker(zip: ZipFile): String? {
        val dexEntries = zip.entries().asSequence().filter { it.name.endsWith(".dex") }
        for (entry in dexEntries) {
            try {
                // 流式扫描：避免把整个 DEX 读到内存
                val stream = zip.getInputStream(entry).buffered(8192)
                val buffer = ByteArray(8192)
                var prevTail = ""
                var bytesRead: Int

                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = prevTail + String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    for ((signature, name) in PACKER_SIGNATURES) {
                        if (chunk.contains(signature)) {
                            Timber.tag(TAG).w("Packer detected: $name (signature: $signature)")
                            stream.close()
                            return name
                        }
                    }
                    prevTail = if (bytesRead >= 256) {
                        String(buffer, bytesRead - 256, 256, Charsets.ISO_8859_1)
                    } else {
                        chunk.takeLast(256)
                    }
                }
                stream.close()
            } catch (_: Exception) { /* skip */ }
        }
        return null
    }

    private fun readManifestText(zip: ZipFile): String? {
        val entry = zip.getEntry("AndroidManifest.xml") ?: return null
        // AndroidManifest.xml in APK is binary XML, so we do byte-pattern matching
        return try {
            val bytes = zip.getInputStream(entry).readBytes()
            extractAsciiStrings(bytes)
        } catch (_: Exception) { null }
    }

    /**
     * Extract readable ASCII strings from binary AndroidManifest.xml.
     * Binary XML stores strings as length-prefixed UTF-16. We do a simpler
     * approach: scan for printable ASCII runs.
     */
    private fun extractAsciiStrings(data: ByteArray): String {
        val sb = StringBuilder()
        var current = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                if (current.length >= 4) {
                    sb.appendLine(current.toString())
                }
                current = StringBuilder()
            }
        }
        if (current.length >= 4) sb.appendLine(current.toString())
        return sb.toString()
    }

    // ─── Scoring helpers ────────────────────────────────────────────

    private fun calculatePermissionRisk(permissions: List<String>): Int {
        val dangerousCount = permissions.count { it in DANGEROUS_PERMISSIONS }
        return when {
            dangerousCount == 0 -> 0
            dangerousCount <= 3 -> 1
            dangerousCount <= 6 -> 2
            dangerousCount <= 10 -> 3
            else -> 4
        }
    }

    private fun generateFingerprint(
        permissions: List<String>,
        components: ComponentCounts,
        nativeLibs: List<String>,
        dexFiles: List<Pair<String, Long>>,
        suspiciousApis: List<String>
    ): String {
        val raw = buildString {
            append(permissions.sorted().joinToString(","))
            append("|${components.activities},${components.services},${components.receivers},${components.providers}")
            append("|${nativeLibs.sorted().joinToString(",")}")
            append("|${dexFiles.size}")
            append("|${suspiciousApis.sorted().joinToString(",")}")
        }
        return sha256Hex(raw).take(32)
    }

    private fun jaccardSimilarity(setA: Set<String>, setB: Set<String>): Double {
        if (setA.isEmpty() && setB.isEmpty()) return 1.0
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun normalizedDistance(a: Int, b: Int): Double {
        val max = maxOf(a, b).toDouble()
        if (max == 0.0) return 0.0
        return kotlin.math.abs(a - b) / max
    }

    private fun computeSha256(filePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(filePath).inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }
}

// ─── Data classes ───────────────────────────────────────────────────

data class AppGenome(
    val apkPath: String,
    val fileHash: String,
    val fileSizeBytes: Long,
    val permissions: List<String>,
    val dangerousPermissions: List<String>,
    val componentCounts: ComponentCounts,
    val nativeLibraries: List<String>,
    val dexFileCount: Int,
    val totalDexSizeBytes: Long,
    val resourceStats: ResourceStats,
    val signatureHash: String,
    val suspiciousApis: List<String>,
    val detectedPacker: String?,
    val permissionRiskScore: Int,
    val fingerprint: String,
    val analyzedAt: Long
) {
    val isSuspicious: Boolean
        get() = permissionRiskScore >= 3 || suspiciousApis.size >= 5 || detectedPacker != null
}

data class ComponentCounts(
    val activities: Int = 0,
    val services: Int = 0,
    val receivers: Int = 0,
    val providers: Int = 0
) {
    val totalComponents: Int get() = activities + services + receivers + providers
}

data class ResourceStats(
    val drawableCount: Int = 0,
    val layoutCount: Int = 0,
    val xmlCount: Int = 0,
    val totalResourceSizeBytes: Long = 0L
)
