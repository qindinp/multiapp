package com.multiapp.core.stub

import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.ManifestGenerator
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.StubConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Stub APK 构建器
 * 组装完整的 Stub APK: manifest + DEX + 原始 APK (assets) + 配置 + 签名
 */
class StubBuilder {

    companion object {
        private const val LOADER_DEX_ASSET = "loader.dex"
        private const val ORIGIN_APK_ASSET = "assets/origin.apk"
        private const val CONFIG_JSON_ASSET = "assets/multiapp_config.json"
        private const val MANIFEST_ENTRY = "AndroidManifest.xml"
        private const val DEX_ENTRY = "classes.dex"

        // APK Signing Block magic: "APK Sig Block 42"
        private val APK_SIGNING_BLOCK_MAGIC = byteArrayOf(
            0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
            0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32
        )
    }

    private val parser = ManifestParser()
    private val generator = ManifestGenerator()
    private val extractor = ComponentExtractor()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 构建 Stub APK
     *
     * 流程:
     * 1. 解析原始 APK manifest
     * 2. 生成 Stub manifest (二进制 XML)
     * 3. 提取 launcher icon
     * 4. 生成配置 JSON
     * 5. 组装 APK (manifest + DEX + assets)
     * 6. zipalign 对齐
     * 7. 复制原始 APK 签名块
     *
     * @param config Stub 配置
     * @return 生成的 Stub APK 文件
     */
    fun build(config: StubConfig): File {
        Timber.d("StubBuilder: building stub for ${config.originalPackageName}")

        val workDir = createWorkDir(config.instanceId)
        try {
            // 1. 解析原始 APK manifest
            val originApk = File(config.originalSignatures.firstOrNull()
                ?: error("originalSignatures must contain the origin APK path"))
            require(originApk.exists()) { "Origin APK not found: ${originApk.absolutePath}" }

            val manifest = parser.parse(originApk)
            Timber.d("StubBuilder: parsed manifest, ${manifest.activities.size} activities")

            // 2. 生成 Stub manifest (二进制 XML)
            val launcherActivity = extractor.extractLauncherActivity(manifest)
                ?: error("No launcher activity found in origin APK")
            val manifestBytes = generator.generateBytes(
                stubPackageName = config.stubPackageName,
                manifest = manifest,
                launcherActivity = launcherActivity,
                config = config
            )

            // 3. 提取 launcher icon
            val iconFile = extractLauncherIcon(originApk, workDir)

            // 4. 生成配置 JSON
            val configJson = createConfigJson(config)
            val configFile = File(workDir, "multiapp_config.json")
            configFile.writeText(configJson)

            // 5. 获取 loader DEX
            val loaderDex = getLoaderDex()

            // 6. 组装 APK (含 patched DEX)
            val patchedDexFiles = config.patchedDexPaths.map { File(it) }.filter { it.exists() }
            val unsignedApk = File(workDir, "stub-unsigned.apk")
            assembleApk(
                outputFile = unsignedApk,
                manifestBytes = manifestBytes,
                loaderDex = loaderDex,
                originApk = originApk,
                configFile = configFile,
                iconFile = iconFile,
                patchedDexFiles = patchedDexFiles
            )

            // 7. zipalign 对齐
            val alignedApk = File(workDir, "stub-aligned.apk")
            zipalign(unsignedApk, alignedApk)

            // 8. 复制原始 APK 签名块
            val signedApk = File(workDir, "stub-signed.apk")
            alignedApk.copyTo(signedApk, overwrite = true)
            copySigningBlock(originApk, signedApk)

            // 9. 移动到输出目录
            val outputDir = File(workDir.parentFile, "output")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "stub-${config.instanceId}.apk")
            signedApk.copyTo(outputFile, overwrite = true)

            Timber.d("StubBuilder: stub APK built at ${outputFile.absolutePath}")
            return outputFile
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * 从原始 APK 提取 launcher icon PNG
     *
     * 使用 apk-parser 解析 manifest 中的 icon 资源引用，
     * 然后从 APK 的 res/ 目录中提取对应的 PNG 文件。
     * 支持 mipmap 和 drawable 资源目录。
     *
     * @param originApk 原始 APK 文件
     * @param outputDir 输出目录
     * @return 提取的 icon 文件，如果未找到则返回 null
     */
    internal fun extractLauncherIcon(originApk: File, outputDir: File): File? {
        Timber.d("StubBuilder: extracting launcher icon from ${originApk.name}")

        ZipFile(originApk).use { zip ->
            // 查找 icon 资源: 优先 mipmap-anydpi-v26, 再 mipmap-xxhdpi, 再 mipmap, 再 drawable
            val iconPriorities = listOf(
                "res/mipmap-anydpi-v26/",
                "res/mipmap-xxhdpi-v4/",
                "res/mipmap-xxhdpi/",
                "res/mipmap-xhdpi-v4/",
                "res/mipmap-xhdpi/",
                "res/mipmap-hdpi-v4/",
                "res/mipmap-hdpi/",
                "res/mipmap-mdpi-v4/",
                "res/mipmap-mdpi/",
                "res/mipmap/",
                "res/drawable-xxhdpi-v4/",
                "res/drawable-xxhdpi/",
                "res/drawable-xhdpi-v4/",
                "res/drawable-xhdpi/",
                "res/drawable-hdpi-v4/",
                "res/drawable-hdpi/",
                "res/drawable-mdpi-v4/",
                "res/drawable-mdpi/",
                "res/drawable/"
            )

            val iconEntry = iconPriorities.flatMap { prefix ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith(prefix) && it.name.contains("ic_launcher") }
                    .filter { it.name.endsWith(".png") || it.name.endsWith(".webp") }
                    .toList()
            }.firstOrNull()

            if (iconEntry == null) {
                Timber.w("StubBuilder: no launcher icon found in APK")
                return null
            }

            val ext = iconEntry.name.substringAfterLast('.')
            val iconFile = File(outputDir, "ic_launcher.$ext")
            zip.getInputStream(iconEntry).use { input ->
                iconFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Timber.d("StubBuilder: extracted icon from ${iconEntry.name}")
            return iconFile
        }
    }

    /**
     * 生成 multiapp_config.json 配置
     *
     * 包含:
     * - instanceId: 实例标识
     * - stubPackageName: Stub 包名
     * - originalPackageName: 原始包名
     * - launchActivity: 启动 Activity
     * - authorityMap: ContentProvider 权限映射
     * - deviceIdentity: 设备标识信息
     *
     * @param config Stub 配置
     * @return JSON 字符串
     */
    internal fun createConfigJson(config: StubConfig): String {
        val configMap = mapOf(
            "instanceId" to config.instanceId,
            "stubPackageName" to config.stubPackageName,
            "originalPackageName" to config.originalPackageName,
            "launchActivity" to config.launchActivity,
            "authorityMap" to config.authorityMap,
            "deviceIdentity" to mapOf(
                "imei" to config.deviceIdentity.imei,
                "androidId" to config.deviceIdentity.androidId,
                "macAddress" to config.deviceIdentity.macAddress,
                "serial" to config.deviceIdentity.serial,
                "buildModel" to config.deviceIdentity.buildModel,
                "buildManufacturer" to config.deviceIdentity.buildManufacturer,
                "buildFingerprint" to config.deviceIdentity.buildFingerprint,
                "buildBrand" to config.deviceIdentity.buildBrand,
                "buildDevice" to config.deviceIdentity.buildDevice,
                "buildProduct" to config.deviceIdentity.buildProduct,
                "versionRelease" to config.deviceIdentity.versionRelease,
                "sdkInt" to config.deviceIdentity.sdkInt
            )
        )
        return gson.toJson(configMap)
    }

    /**
     * 组装 APK 文件
     *
     * APK 结构: AndroidManifest.xml, classes.dex, assets/origin.apk, assets/multiapp_config.json
     */
    internal fun assembleApk(
        outputFile: File,
        manifestBytes: ByteArray,
        loaderDex: ByteArray,
        originApk: File,
        configFile: File,
        iconFile: File?,
        patchedDexFiles: List<File> = emptyList()
    ) {
        Timber.d("StubBuilder: assembling APK -> ${outputFile.name}")

        ZipOutputStream(outputFile.outputStream().buffered()).use { zos ->
            // AndroidManifest.xml (二进制 XML)
            zos.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zos.write(manifestBytes)
            zos.closeEntry()

            // classes.dex (LoaderFactory 预编译 DEX)
            zos.putNextEntry(ZipEntry(DEX_ENTRY))
            zos.write(loaderDex)
            zos.closeEntry()

            // assets/origin.apk (原始 APK 完整副本)
            zos.putNextEntry(ZipEntry(ORIGIN_APK_ASSET))
            originApk.inputStream().buffered().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()

            // assets/multiapp_config.json
            zos.putNextEntry(ZipEntry(CONFIG_JSON_ASSET))
            configFile.inputStream().buffered().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()

            // res/mipmap-*/ic_launcher.png (可选)
            if (iconFile != null && iconFile.exists()) {
                val ext = iconFile.name.substringAfterLast('.')
                val iconEntryName = "res/mipmap-xxhdpi/ic_launcher.$ext"
                zos.putNextEntry(ZipEntry(iconEntryName))
                iconFile.inputStream().buffered().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }

            // assets/patched/*.dex (加固壳检测代码已删除的 DEX 文件)
            for ((index, dexFile) in patchedDexFiles.withIndex()) {
                val entryName = "assets/patched/classes${index + 2}.dex"
                zos.putNextEntry(ZipEntry(entryName))
                dexFile.inputStream().buffered().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
                Timber.d("StubBuilder: embedded patched DEX: $entryName (${dexFile.length()} bytes)")
            }
        }

        Timber.d("StubBuilder: APK assembled, size=${outputFile.length()} bytes")
    }

    /**
     * zipalign 对齐 APK
     *
     * 确保 APK 中的未压缩数据按照 4 字节边界对齐，
     * 这是 Android 系统内存映射加载 APK 的要求。
     *
     * @param input 输入 APK
     * @param output 对齐后的输出 APK
     */
    internal fun zipalign(input: File, output: File) {
        Timber.d("StubBuilder: zipalign ${input.name}")

        ZipFile(input).use { srcZip ->
            FileOutputStream(output).buffered().use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val buffer = ByteArray(8192)

                    srcZip.entries().asSequence().forEach { entry ->
                        zos.putNextEntry(ZipEntry(entry.name).apply {
                            method = entry.method
                            if (entry.method == ZipEntry.STORED) {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                            }
                        })

                        srcZip.getInputStream(entry).use { entryInput ->
                            var bytesRead: Int
                            while (entryInput.read(buffer).also { bytesRead = it } != -1) {
                                zos.write(buffer, 0, bytesRead)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }

        Timber.d("StubBuilder: zipalign complete, size=${output.length()} bytes")
    }

    /**
     * 从原始 APK 复制签名块到目标 APK
     *
     * APK 签名块位于 ZIP 中央目录之前，格式:
     *   - size (uint64): 签名块大小 (不含自身)
     *   - pairs: (id: uint32, value: bytes) 序列
     *   - magic: "APK Sig Block 42" (16 bytes)
     *   - size (uint64): 签名块大小 (与开头相同)
     *
     * 使用 apksig 库解析源 APK 的签名块，
     * 然后写入目标 APK 并更新中央目录偏移。
     *
     * @param source 源 APK (包含签名)
     * @param target 目标 APK (将被签名)
     * @throws IllegalStateException 如果源 APK 没有签名块
     */
    internal fun copySigningBlock(source: File, target: File) {
        Timber.d("StubBuilder: copying signing block from ${source.name} to ${target.name}")

        // 1. 读取源 APK 签名块
        val signingBlockBytes = readApkSigningBlock(source)

        // 2. 读取目标 APK 的中央目录和 End of Central Directory
        val targetData = readZipStructure(target)

        // 3. 重写目标 APK: 数据区 + 签名块 + 中央目录 + EOCD
        RandomAccessFile(target, "rw").use { raf ->
            // 截断到数据区末尾 (中央目录之前)
            raf.setLength(targetData.centralDirOffset)

            // 写入签名块
            raf.seek(targetData.centralDirOffset)
            raf.write(signingBlockBytes)

            // 计算新的中央目录偏移
            val newCentralDirOffset = targetData.centralDirOffset + signingBlockBytes.size

            // 写入中央目录
            raf.write(targetData.centralDirBytes)

            // 写入 End of Central Directory，更新偏移
            val eocd = updateEocdCentralDirOffset(
                targetData.eocdBytes,
                newCentralDirOffset.toInt()
            )
            raf.write(eocd)
        }

        Timber.d("StubBuilder: signing block copied, size=${signingBlockBytes.size} bytes")
    }

    /**
     * 读取 APK 签名块
     *
     * 在 APK 文件末尾搜索 "APK Sig Block 42" 魔术标记，
     * 然后读取整个签名块 (包括 size 头和 magic 尾)。
     *
     * @param apkFile APK 文件
     * @return 签名块字节数组
     * @throws IllegalStateException 如果未找到签名块
     */
    private fun readApkSigningBlock(apkFile: File): ByteArray {
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLength = raf.length()

            // 搜索 APK Signing Block magic
            val searchSize = minOf(fileLength, 1024 * 1024).toInt() // 搜索最后 1MB
            raf.seek(fileLength - searchSize)
            val searchData = ByteArray(searchSize)
            raf.readFully(searchData)

            // 搜索 magic
            val magicIndex = findPattern(searchData, APK_SIGNING_BLOCK_MAGIC)
            if (magicIndex < 0) {
                error("APK Signing Block magic not found in ${apkFile.name}")
            }

            // magic 之后是 8 字节的 size (little-endian)
            val magicEnd = magicIndex + APK_SIGNING_BLOCK_MAGIC.size
            val sizeOffset = fileLength - searchSize + magicEnd
            raf.seek(sizeOffset)
            val sizeBytes = ByteArray(8)
            raf.readFully(sizeBytes)
            val blockSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).long

            // 签名块总大小 = 8 (size头) + blockSize + 16 (magic) + 8 (size尾)
            val totalSize = 8 + blockSize + 16 + 8
            val blockStart = sizeOffset - 8 - blockSize.toInt() - 16

            // 读取整个签名块
            raf.seek(blockStart)
            val blockBytes = ByteArray(totalSize.toInt())
            raf.readFully(blockBytes)
            return blockBytes
        }
    }

    /**
     * 读取 ZIP 文件结构
     *
     * @param file ZIP/APK 文件
     * @return ZIP 结构数据
     */
    private fun readZipStructure(file: File): ZipStructure {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()

            // 搜索 End of Central Directory (EOCD) 签名: 0x06054b50
            val eocdSignature = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
            val searchSize = minOf(fileLength, 65536 + 22).toInt() // EOCD 最大搜索范围
            raf.seek(fileLength - searchSize)
            val searchData = ByteArray(searchSize)
            raf.readFully(searchData)

            var eocdOffset = -1
            for (i in searchData.size - 22 downTo 0) {
                if (searchData[i] == eocdSignature[0] &&
                    searchData[i + 1] == eocdSignature[1] &&
                    searchData[i + 2] == eocdSignature[2] &&
                    searchData[i + 3] == eocdSignature[3]
                ) {
                    eocdOffset = i
                    break
                }
            }
            require(eocdOffset >= 0) { "EOCD not found in ${file.name}" }

            val absoluteEocdOffset = fileLength - searchSize + eocdOffset

            // 读取 EOCD
            raf.seek(absoluteEocdOffset)
            val eocdSize = (fileLength - absoluteEocdOffset).toInt()
            val eocdBytes = ByteArray(eocdSize)
            raf.readFully(eocdBytes)

            // 解析中央目录偏移 (offset 16, uint32 LE)
            val centralDirOffset = ByteBuffer.wrap(eocdBytes, 16, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            // 读取中央目录
            raf.seek(centralDirOffset)
            val centralDirSize = (absoluteEocdOffset - centralDirOffset).toInt()
            val centralDirBytes = ByteArray(centralDirSize)
            raf.readFully(centralDirBytes)

            return ZipStructure(
                centralDirOffset = centralDirOffset,
                centralDirBytes = centralDirBytes,
                eocdBytes = eocdBytes
            )
        }
    }

    /**
     * 更新 EOCD 中的中央目录偏移
     */
    private fun updateEocdCentralDirOffset(eocdBytes: ByteArray, newOffset: Int): ByteArray {
        val result = eocdBytes.copyOf()
        ByteBuffer.wrap(result, 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(newOffset)
        return result
    }

    /**
     * 在字节数组中搜索模式
     */
    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * 计算 CRC32
     */
    private fun calculateCrc(data: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value
    }

    /**
     * 获取 LoaderFactory DEX
     *
     * 从 stub 模块的 assets 目录加载预编译的 loader.dex。
     * 该 DEX 由 Gradle 构建系统在编译期生成，
     * 包含 LoaderFactory 及其依赖类。
     *
     * @return DEX 字节数组
     * @throws IllegalStateException 如果 loader.dex 不存在
     */
    private fun getLoaderDex(): ByteArray {
        // 从 stub 模块的 assets 加载预编译的 loader DEX
        // 此文件由 Gradle 构建任务在编译期生成
        val resource = javaClass.classLoader?.getResourceAsStream("assets/$LOADER_DEX_ASSET")
        if (resource != null) {
            return resource.use { it.readBytes() }
        }

        // 回退: 检查文件系统路径 (用于开发/测试)
        val dexFile = File("src/main/assets/$LOADER_DEX_ASSET")
        if (dexFile.exists()) {
            return dexFile.readBytes()
        }

        error(
            "loader.dex not found. Ensure the loader module DEX is bundled " +
                "as an asset in the stub module. Expected: assets/$LOADER_DEX_ASSET"
        )
    }

    /**
     * 创建临时工作目录
     */
    private fun createWorkDir(instanceId: String): File {
        val workDir = File(System.getProperty("java.io.tmpdir"), "multiapp_stub_$instanceId")
        workDir.mkdirs()
        return workDir
    }

    /**
     * ZIP 文件结构数据
     */
    private data class ZipStructure(
        val centralDirOffset: Long,
        val centralDirBytes: ByteArray,
        val eocdBytes: ByteArray
    )
}
