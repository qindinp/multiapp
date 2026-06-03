package com.multiapp.core.stub

import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.ManifestGenerator
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.StubConfig
import com.android.apksig.ApkSigner
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.multiapp.core.common.ConfigEncryptor
import timber.log.Timber
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Stub APK 构建器
 * 组装完整的 Stub APK: manifest + DEX + 原始 APK (assets) + 配置 + 签名
 */
class StubBuilder(
    private val context: android.content.Context? = null,
    private val parser: ManifestParser = context?.let { ManifestParser(it) } ?: ManifestParser(),
    private val generator: ManifestGenerator = ManifestGenerator(),
    private val extractor: ComponentExtractor = ComponentExtractor()
) {

    companion object {
        private const val LOADER_DEX_ASSET = "loader.dex"
        private const val ORIGIN_APK_ENTRY = "assets/origin.apk"
        private const val CONFIG_JSON_ENTRY = "assets/multiapp_config.json"
        private const val MANIFEST_ENTRY = "AndroidManifest.xml"
        private const val DEX_ENTRY = "classes.dex"
        private val HOOK_NATIVE_LIBS = listOf(
            "libmultiapp-native.so",
            "libshadowhook.so",
            "libc++_shared.so"
        )
    }

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
     * 7. ApkSigner 自签名
     *
     * @param config Stub 配置
     * @return 生成的 Stub APK 文件
     */
    fun build(config: StubConfig): File {
        Log.w("StubBuilder", "build() called, instanceId=${config.instanceId}, pkg=${config.stubPackageName}")

        val workDir = createWorkDir(config.instanceId)
        try {
            // 1. 解析原始 APK manifest
            val originApk = File(config.originalSignatures.firstOrNull()
                ?: error("originalSignatures must contain the origin APK path"))
            require(originApk.exists()) { "Origin APK not found: ${originApk.absolutePath}" }

            val parsedManifest = parser.parse(originApk)
            Log.w("StubBuilder", "parsed manifest, ${parsedManifest.activities.size} activities")

            // 2. 提取原 app 的 theme 资源 ID（int），用于以 TYPE_REFERENCE 写进 stub manifest。
            // 运行时 addAssetPath(origin.apk) 已挂载原 app 资源，该 ID 正确解析到原 app 自己的 theme。
            val manifest = enrichWithThemeIds(parsedManifest, originApk)

            // 3. 生成 Stub manifest (二进制 XML)
            val launcherActivity = extractor.extractLauncherActivity(manifest)
                ?: error("No launcher activity found in origin APK")
            val manifestBytes = try {
                generator.generateBytes(
                    stubPackageName = config.stubPackageName,
                    manifest = manifest,
                    launcherActivity = launcherActivity,
                    config = config
                )
            } catch (e: Throwable) {
                Log.e("StubBuilder", "generateBytes failed", e)
                throw e
            }
            Log.w("StubBuilder", "manifest binary XML: ${manifestBytes.size} bytes")

            // 3. 提取 launcher icon
            val iconFile = extractLauncherIcon(originApk, workDir)

            // 4. 生成配置 JSON
            val configJson = createConfigJson(config)
            val configFile = File(workDir, "multiapp_config.json")
            configFile.writeText(configJson)

            // 5. 获取 loader DEX
            val loaderDex = getLoaderDex()
            Log.w("StubBuilder", "loader.dex: ${loaderDex.size} bytes")

            // 5.5 注入 System.loadLibrary("jiagu_vip") 到加固壳 StubApp.load()
            // originApk 在 /data/app/... 是只读的，先复制到 workDir 再注入
            val injectableApk = File(workDir, "origin_inject.apk")
            originApk.copyTo(injectableApk, overwrite = true)
            injectPackerLibLoad(injectableApk)
            Log.w("StubBuilder", "injectableApk: ${injectableApk.length()} bytes")

            // 6. 组装 APK (含 patched DEX)
            val patchedDexFiles = config.patchedDexPaths.map { File(it) }.filter { it.exists() }
            val unsignedApk = File(workDir, "stub-unsigned.apk")
            assembleApk(
                outputFile = unsignedApk,
                manifestBytes = manifestBytes,
                loaderDex = loaderDex,
                originApk = injectableApk,
                configFile = configFile,
                iconFile = iconFile,
                patchedDexFiles = patchedDexFiles
            )

            // 7. zipalign 对齐（必须在签名前）
            val alignedApk = File(workDir, "stub-aligned.apk")
            zipalign(unsignedApk, alignedApk)

            // 8. ApkSigner 自签名（V1+V2+V3，正确的签名流程）
            val signedApk = File(workDir, "stub-signed.apk")
            signApk(alignedApk, signedApk)

            // 9. 移动到输出目录
            val outputDir = File(workDir.parentFile, "output")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "stub-${config.instanceId}.apk")
            signedApk.copyTo(outputFile, overwrite = true)

            Log.w("StubBuilder", "stub APK built at ${outputFile.absolutePath}, size=${outputFile.length()}")
            return outputFile
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * 用原 app 的 theme 资源 ID 补全 ParsedManifest。
     *
     * 通过 PackageManager.getPackageArchiveInfo 直接拿到 int 资源 ID（可靠，
     * 无需解析 binary XML）：
     * - applicationInfo.theme → application theme ID
     * - 每个 activityInfo.theme → 对应 activity 的 theme ID（0 = 未声明）
     *
     * 无 context（测试场景）时原样返回，theme ID 保持 0，回退到旧行为。
     */
    internal fun enrichWithThemeIds(
        manifest: ManifestParser.ParsedManifest,
        originApk: File
    ): ManifestParser.ParsedManifest {
        val pm = context?.packageManager ?: run {
            Log.w("StubBuilder", "no context, skip theme ID extraction")
            return manifest
        }
        @Suppress("DEPRECATION")
        val pkgInfo = pm.getPackageArchiveInfo(
            originApk.absolutePath,
            android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS
        )
        if (pkgInfo == null) {
            Log.w("StubBuilder", "getPackageArchiveInfo returned null, theme IDs unavailable")
            return manifest
        }

        val appThemeId = pkgInfo.applicationInfo?.theme ?: 0
        val activityByName = (pkgInfo.activities ?: emptyArray())
            .associateBy { it.name }
        val activityThemeById = activityByName.mapValues { it.value.theme }

        val enrichedActivities = manifest.activities.map { act ->
            val activityThemeId = activityThemeById[act.name] ?: 0
            val actInfo = activityByName[act.name]
            act.copy(
                themeId = if (activityThemeId != 0) activityThemeId else act.themeId,
                // 仅当 XML 解析未填充时，从 PackageManager 补充
                launchMode = act.launchMode ?: actInfo?.let {
                    ManifestParser.convertLaunchMode(it.launchMode)
                },
                configChanges = act.configChanges ?: actInfo?.let {
                    ManifestParser.convertConfigChanges(it.configChanges)
                },
                screenOrientation = act.screenOrientation ?: actInfo?.let {
                    ManifestParser.convertScreenOrientation(it.screenOrientation)
                },
                windowSoftInputMode = act.windowSoftInputMode ?: actInfo?.let {
                    ManifestParser.convertSoftInputMode(it.softInputMode)
                },
                taskAffinity = act.taskAffinity ?: actInfo?.taskAffinity?.takeIf { it.isNotEmpty() },
                permission = act.permission ?: actInfo?.permission?.takeIf { it.isNotEmpty() },
                stateNotNeeded = act.stateNotNeeded || (actInfo != null && (actInfo.flags and 0x0040) != 0),
                noHistory = act.noHistory || (actInfo != null && (actInfo.flags and 0x8000) != 0),
                allowTaskReparenting = act.allowTaskReparenting || (actInfo != null && (actInfo.flags and 0x0020) != 0),
                clearTaskOnLaunch = act.clearTaskOnLaunch || (actInfo != null && (actInfo.flags and 0x0004) != 0),
                finishOnTaskLaunch = act.finishOnTaskLaunch || (actInfo != null && (actInfo.flags and 0x0002) != 0),
                enabled = act.enabled && (actInfo?.enabled ?: true)
            )
        }

        // 为 service/receiver 补充 permission 和 enabled
        val serviceByName = (pkgInfo.services ?: emptyArray()).associateBy { it.name }
        val enrichedServices = manifest.services.map { svc ->
            val svcInfo = serviceByName[svc.name]
            svc.copy(
                permission = svc.permission ?: svcInfo?.permission?.takeIf { it.isNotEmpty() },
                enabled = svc.enabled && (svcInfo?.enabled ?: true)
            )
        }
        val receiverByName = (pkgInfo.receivers ?: emptyArray()).associateBy { it.name }
        val enrichedReceivers = manifest.receivers.map { rcv ->
            val rcvInfo = receiverByName[rcv.name]
            rcv.copy(
                permission = rcv.permission ?: rcvInfo?.permission?.takeIf { it.isNotEmpty() },
                enabled = rcv.enabled && (rcvInfo?.enabled ?: true)
            )
        }

        Log.w(
            "StubBuilder",
            "Extracted theme IDs: app=0x${Integer.toHexString(appThemeId)}, " +
                "activities=${activityThemeById.filterValues { it != 0 }
                    .mapValues { "0x${Integer.toHexString(it.value)}" }}"
        )

        return manifest.copy(
            applicationThemeId = appThemeId,
            activities = enrichedActivities,
            services = enrichedServices,
            receivers = enrichedReceivers
        )
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
        // 加密敏感字段 (IMEI, MAC, AndroidId, Serial) — 失败时使用明文
        return try {
            val encryptedMap = ConfigEncryptor.encryptSensitiveFields(
                configMap, config.stubPackageName, config.instanceId
            )
            gson.toJson(encryptedMap)
        } catch (e: Throwable) {
            Log.e("StubBuilder", "ConfigEncryptor failed, using plain JSON", e)
            gson.toJson(configMap)
        }
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
            // AndroidManifest.xml (二进制 XML) — 必须 STORED，否则 Android 无法直接读取
            zos.putNextEntry(createStoredEntry(MANIFEST_ENTRY, manifestBytes))
            zos.write(manifestBytes)
            zos.closeEntry()

            // classes.dex (LoaderFactory 预编译 DEX) — 必须 STORED
            zos.putNextEntry(createStoredEntry(DEX_ENTRY, loaderDex))
            zos.write(loaderDex)
            zos.closeEntry()

            // assets/origin.apk (原始 APK 完整副本)
            zos.putNextEntry(ZipEntry(ORIGIN_APK_ENTRY))
            originApk.inputStream().buffered().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()

            // assets/multiapp_config.json
            zos.putNextEntry(ZipEntry(CONFIG_JSON_ENTRY))
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

            // lib/ native libraries — 从原始 APK 中提取并打包
            // loader.dex 依赖 libmultiapp-native.so（shadowhook PLT hook）
            // 必须打进 Stub APK 的 lib/ 目录，否则 native hook 全部失效
            packageNativeLibs(originApk, zos)
        }

        Timber.d("StubBuilder: APK assembled, size=${outputFile.length()} bytes")
    }

    /**
     * 从原始 APK 和应用自身提取 native libraries 打包到 Stub APK
     *
     * loader.dex 依赖 libmultiapp-native.so（shadowhook PLT hook），
     * 必须打进 Stub APK 的 lib/ 目录。同时打包原始 APK 的 native libs。
     */
    private fun packageNativeLibs(originApk: File, zos: ZipOutputStream) {
        // 打包所有可用 ABI 的 native libs, 避免目标设备 ABI 不匹配
        val writtenEntries = mutableSetOf<String>()
        var count = packageHookNativeLibs(zos, writtenEntries)

        // 1. 从原始 APK 提取 native libs (所有 ABI)
        try {
            ZipFile(originApk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") && !it.isDirectory }
                    .forEach { entry ->
                        if (!writtenEntries.add(entry.name)) {
                            Timber.w("StubBuilder: skipping duplicate native lib ${entry.name}")
                            return@forEach
                        }
                        zos.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                        count++
                    }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract native libs from origin APK")
        }

        Timber.d("StubBuilder: packaged $count native libraries")
    }

    private fun packageHookNativeLibs(
        zos: ZipOutputStream,
        writtenEntries: MutableSet<String>
    ): Int {
        return try {
            val primaryAbi = currentProcessSupportedAbis().firstOrNull() ?: "arm64-v8a"
            val appNativeDir = findAppNativeLibDir(primaryAbi)
            if (appNativeDir != null) {
                copyHookNativeLibsFromDirectory(appNativeDir, primaryAbi, zos, writtenEntries)
            } else {
                val fromHostApk = copyHookNativeLibsFromHostApk(primaryAbi, zos, writtenEntries)
                if (fromHostApk != HOOK_NATIVE_LIBS.size) {
                    Timber.w("StubBuilder: hook native libs unavailable for ABI $primaryAbi")
                }
                fromHostApk
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to package app native libs")
            0
        }
    }

    private fun copyHookNativeLibsFromDirectory(
        sourceDir: File,
        abi: String,
        zos: ZipOutputStream,
        writtenEntries: MutableSet<String>
    ): Int {
        var count = 0
        for (libName in HOOK_NATIVE_LIBS) {
            val entryName = "lib/$abi/$libName"
            if (!writtenEntries.add(entryName)) continue
            val libFile = File(sourceDir, libName)
            zos.putNextEntry(ZipEntry(entryName))
            libFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
            count++
            Timber.d("StubBuilder: packaged $libName from ${sourceDir.absolutePath}")
        }
        return count
    }

    private fun copyHookNativeLibsFromHostApk(
        abi: String,
        zos: ZipOutputStream,
        writtenEntries: MutableSet<String>
    ): Int {
        val sourceDirs = listOfNotNull(
            context?.applicationInfo?.sourceDir,
            context?.applicationInfo?.publicSourceDir
        ).distinct()

        for (sourceDir in sourceDirs) {
            val copied = copyHookNativeLibsFromApk(File(sourceDir), abi, zos, writtenEntries)
            if (copied == HOOK_NATIVE_LIBS.size) {
                Timber.d("StubBuilder: packaged hook native libs from host APK $sourceDir")
                return copied
            }
        }
        return 0
    }

    internal fun copyHookNativeLibsFromApk(
        hostApk: File,
        abi: String,
        zos: ZipOutputStream,
        writtenEntries: MutableSet<String>
    ): Int {
        if (!hostApk.isFile) return 0

        ZipFile(hostApk).use { zip ->
            val entries = HOOK_NATIVE_LIBS.map { libName ->
                val entryName = "lib/$abi/$libName"
                entryName to zip.getEntry(entryName)
            }
            if (entries.any { it.second == null }) return 0

            var count = 0
            for ((entryName, entry) in entries) {
                if (!writtenEntries.add(entryName)) continue
                zos.putNextEntry(ZipEntry(entryName))
                zip.getInputStream(entry!!).use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
            return count
        }
    }

    /**
     * 查找当前应用的 native library 目录
     */
    private fun findAppNativeLibDir(abi: String): File? {
        val contextNativeDir = context?.applicationInfo?.nativeLibraryDir
            ?.let { File(it) }
        if (contextNativeDir?.isDirectory == true && hasHookNativeLibs(contextNativeDir)) {
            return contextNativeDir
        }

        // 动态查找：扫描 /data/app/ 下所有 com.multiapp.app* 目录
        val dataApp = File("/data/app")
        val nativeDirNames = nativeDirNamesForAbi(abi)
        if (dataApp.isDirectory) {
            dataApp.listFiles()?.filter { dir ->
                dir.isDirectory && dir.name.startsWith("com.multiapp.app")
            }?.forEach { appDir ->
                // 尝试直接子目录 lib/$abi
                for (dirName in nativeDirNames) {
                    val libDir = File(appDir, "lib/$dirName")
                    if (libDir.isDirectory && hasHookNativeLibs(libDir)) return libDir
                }
                // 尝试 OAT/ODEX 结构下的 lib 目录
                appDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    for (dirName in nativeDirNames) {
                        val subLibDir = File(subDir, "lib/$dirName")
                        if (subLibDir.isDirectory && hasHookNativeLibs(subLibDir)) return subLibDir
                    }
                }
            }
        }
        // 回退：从当前进程的 maps 中查找
        return findNativeLibDirFromMaps()?.takeIf { hasHookNativeLibs(it) }
    }

    private fun currentProcessSupportedAbis(): Array<String> {
        val processAbis = if (android.os.Process.is64Bit()) {
            android.os.Build.SUPPORTED_64_BIT_ABIS
        } else {
            android.os.Build.SUPPORTED_32_BIT_ABIS
        }
        return if (processAbis.isNotEmpty()) processAbis else android.os.Build.SUPPORTED_ABIS
    }

    private fun nativeDirNamesForAbi(abi: String): List<String> {
        val installDirName = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            else -> abi
        }
        return listOf(abi, installDirName).distinct()
    }

    private fun hasHookNativeLibs(dir: File): Boolean {
        return HOOK_NATIVE_LIBS.all { File(dir, it).isFile }
    }

    /**
     * 从 /proc/self/maps 中查找 libmultiapp-native.so 所在目录
     */
    private fun findNativeLibDirFromMaps(): File? {
        return try {
            java.io.BufferedReader(java.io.FileReader("/proc/self/maps")).use { reader ->
                reader.lineSequence()
                    .firstOrNull { it.contains("libmultiapp-native.so") }
                    ?.trim()
                    ?.split("\\s+".toRegex())
                    ?.lastOrNull()
                    ?.let { path -> File(path).parentFile }
            }
        } catch (_: Exception) { null }
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

        // For each STORED entry, its data must start at a 4-byte aligned offset.
        // ZipOutputStream writes: LOCAL_FILE_HEADER(30 + nameLen) + data + optional DataDescriptor.
        // We track the cumulative offset and insert padding via the extra field.
        //
        // The approach: write entries with an 'extra' field that pads to 4-byte alignment.
        // ZipOutputStream includes 'extra' between the header and data.

        ZipFile(input).use { srcZip ->
            FileOutputStream(output).buffered().use { fos ->
                // We need raw byte tracking, so wrap in a counting stream
                val counter = ByteCountingOutputStream(fos)
                ZipOutputStream(counter).use { zos ->
                    val entries = srcZip.entries().toList()
                    for (entry in entries) {
                        val data = srcZip.getInputStream(entry).readBytes()
                        val nameBytes = entry.name.toByteArray()

                        // Calculate where data would start without padding:
                        // LOCAL_FILE_HEADER = 30 bytes + nameLen + extraLen
                        // Current file offset + 30 + nameLen = data start (if extra is empty)
                        val currentOffset = counter.count
                        val dataStartNoPadding = currentOffset + 30 + nameBytes.size

                        val padding = if (entry.method == ZipEntry.STORED) {
                            ((4 - (dataStartNoPadding % 4)) % 4).toInt()
                        } else 0

                        zos.putNextEntry(ZipEntry(entry.name).apply {
                            method = entry.method
                            if (entry.method == ZipEntry.STORED) {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                                // Use extra field for padding alignment
                                if (padding > 0) extra = ByteArray(padding)
                            }
                        })
                        zos.write(data)
                        zos.closeEntry()
                    }
                }
            }
        }

        Timber.d("StubBuilder: zipalign complete, size=${output.length()} bytes")
    }

    /**
     * OutputStream wrapper that counts bytes written
     */
    private class ByteCountingOutputStream(
        private val out: java.io.OutputStream
    ) : java.io.OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /**
     * 使用 ApkSigner 对 APK 进行 V1+V2+V3 签名
     *
     * 从 AndroidKeyStore 获取签名密钥和证书，
     * 使用 apksig 库进行 APK 签名。
     *
     * @param input 未签名的 APK
     * @param output 签名后的 APK
     */
    internal fun signApk(input: File, output: File) {
        Timber.d("StubBuilder: signing APK with ApkSigner")

        val (privateKey, cert) = ApkSigningHelper.getOrCreateSigningKey()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "MultiApp",
            privateKey,
            listOf(cert)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setInputApk(input)
            .setOutputApk(output)
            .build()
            .sign()

        Timber.d("StubBuilder: APK signed, size=${output.length()} bytes")
    }

    /**
     * 在 origin APK 的 DEX 中注入 System.loadLibrary("jiagu_vip") 到 StubApp.load()
     *
     * 加固壳的 StubApp.load() 直接调用 interface20() 等 native 方法，
     * 但不先加载 libjiagu_vip.so。正常 app 中，System.loadLibrary 由 app 代码调用，
     * ClassLoader 是 app 的 → linker namespace 正确。
     *
     * 构建时注入确保 origin APK 提取后 DEX 已包含加载调用。
     */
    private fun injectPackerLibLoad(originApk: File) {
        try {
            val workDir = File(originApk.parentFile, "dex_inject")
            workDir.mkdirs()

            // 提取 DEX 文件
            val dexFiles = mutableListOf<File>()
            ZipFile(originApk).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.name.endsWith(".dex")) {
                        val outFile = File(workDir, entry.name)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        dexFiles.add(outFile)
                    }
                }
            }

            if (dexFiles.isEmpty()) {
                Log.w("StubBuilder", "injectPackerLibLoad: no DEX files found")
                workDir.deleteRecursively()
                return
            }

            Log.w("StubBuilder", "injectPackerLibLoad: extracted ${dexFiles.size} DEX files")

            // 注入到多个可能的壳类
            val packerClasses = listOf(
                "com.stub.StubApp",
                "com.qihoo.util.StubApp",
                "com.stub.StubApplication",
                "com.secneo.apkwrapper.ApplicationWrapper"
            )

            val patcher = com.multiapp.core.hook.dexpatch.DexPatcher()
            var injected = false
            for (className in packerClasses) {
                if (patcher.injectLoadLibrary(dexFiles, className, "load", "jiagu_vip")) {
                    Log.w("StubBuilder", "injectPackerLibLoad: injected into $className.load()")
                    injected = true
                    break
                }
            }

            if (!injected) {
                Log.w("StubBuilder", "injectPackerLibLoad: no packer class found")
                workDir.deleteRecursively()
                return
            }

            // 用 patched DEX 替换 origin APK 中的 DEX
            val tmpApk = File(originApk.parentFile, "origin_patched.apk")
            ZipFile(originApk).use { zip ->
                ZipOutputStream(tmpApk.outputStream().buffered()).use { zos ->
                    var dexIndex = 0
                    for (entry in zip.entries()) {
                        val newEntry = ZipEntry(entry.name)
                        newEntry.method = ZipEntry.DEFLATED
                        zos.putNextEntry(newEntry)
                        if (entry.name.endsWith(".dex") && dexIndex < dexFiles.size) {
                            dexFiles[dexIndex].inputStream().use { it.copyTo(zos) }
                            dexIndex++
                        } else {
                            zip.getInputStream(entry).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                }
            }

            originApk.delete()
            tmpApk.renameTo(originApk)
            Log.w("StubBuilder", "injectPackerLibLoad: origin APK patched, size=${originApk.length()}")

            workDir.deleteRecursively()
        } catch (e: Exception) {
            Log.w("StubBuilder", "injectPackerLibLoad failed: ${e.message}")
        }
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
     * 使用 aapt 编译文本 XML manifest 为二进制 XML
     *
     * 在设备上运行时，使用系统自带的 aapt（/system/bin/aapt 或通过 Context 获取）。
     * 开发环境下使用 Android SDK 的 aapt。
     *
     * @param textManifest 文本格式的 AndroidManifest.xml
     * @param workDir 临时工作目录
     * @param config Stub 配置
     * @return 编译后的二进制 XML 字节数组
     */
    private fun compileManifestWithAapt(textManifest: String, workDir: File, config: StubConfig): ByteArray {
        // 写入文本 manifest
        val manifestFile = File(workDir, "AndroidManifest.xml")
        manifestFile.writeText(textManifest)

        // 创建临时 APK（只含 manifest）
        val tempApk = File(workDir, "manifest-only.apk")

        // 查找 aapt 可执行文件
        val aaptPath = findAapt()

        // 执行 aapt package
        val cmd = listOf(
            aaptPath, "package", "-f",
            "-M", manifestFile.absolutePath,
            "-F", tempApk.absolutePath
        )

        // 添加 -I 参数指向 android.jar（如果能找到）
        val androidJar = findAndroidJar()
        val fullCmd = if (androidJar != null) {
            cmd + listOf("-I", androidJar.absolutePath)
        } else {
            cmd
        }

        val process = ProcessBuilder(fullCmd)
            .redirectErrorStream(true)
            .directory(workDir)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Timber.e("aapt failed (exit=$exitCode): $output")
            error("aapt package failed: $output")
        }

        // 从临时 APK 中提取 AndroidManifest.xml
        ZipFile(tempApk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: error("AndroidManifest.xml not found in aapt output")
            return zip.getInputStream(entry).readBytes()
        }
    }

    /**
     * 查找 aapt 可执行文件
     */
    private fun findAapt(): String {
        val candidates = listOf(
            // Android SDK (开发环境)
            "${System.getenv("ANDROID_HOME") ?: ""}/build-tools/36.0.0/aapt",
            "${System.getenv("ANDROID_HOME") ?: ""}/build-tools/35.0.0/aapt",
            "${System.getenv("ANDROID_HOME") ?: ""}/build-tools/34.0.0/aapt",
            // 系统 PATH
            "/system/bin/aapt",
            "aapt"
        )

        for (candidate in candidates) {
            val file = File(candidate)
            if (file.canExecute()) return candidate
        }

        error("aapt not found. Set ANDROID_HOME or ensure aapt is in PATH")
    }

    /**
     * 查找 android.jar
     */
    private fun findAndroidJar(): File? {
        val sdkHome = System.getenv("ANDROID_HOME") ?: return null
        val candidates = listOf(
            "$sdkHome/platforms/android-36/android.jar",
            "$sdkHome/platforms/android-35/android.jar",
            "$sdkHome/platforms/android-34/android.jar"
        )

        for (candidate in candidates) {
            val file = File(candidate)
            if (file.exists()) return file
        }
        return null
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
     * 创建 STORED 模式的 ZipEntry
     *
     * AndroidManifest.xml 和 classes.dex 在 APK 中必须使用 STORED (无压缩) 模式。
     * Java ZipOutputStream 要求 STORED 条目必须预设 size、compressedSize 和 crc32。
     */
    private fun createStoredEntry(name: String, data: ByteArray): ZipEntry {
        val crcCalculator = CRC32()
        crcCalculator.update(data)
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = crcCalculator.value
        }
    }
}
