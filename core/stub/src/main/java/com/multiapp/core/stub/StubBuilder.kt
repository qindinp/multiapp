package com.multiapp.core.stub

import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.ManifestGenerator
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.ManifestRewriter
import com.multiapp.core.manifest.AuthorityRewriter
import com.multiapp.core.manifest.StubConfig
import com.android.apksig.ApkSigner
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.multiapp.core.common.ConfigEncryptor
import com.multiapp.core.model.CloneProfile
import timber.log.Timber
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Legacy Stub APK builder.
 *
 * Builds the transitional Stub APK used by existing legacy instances and
 * evidence comparison. v2 hosted-container instances should not use this as
 * their final runtime path.
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
        private const val XPOSED_MODULES_DIR = "assets/xposed_modules/"
        private const val XPOSED_INIT_ENTRY = "assets/xposed_init"
        private val HOOK_NATIVE_LIBS = listOf(
            "libmultiapp-native.so",
            "libshadowhook.so",
            "liblsplant.so",
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

            // 2. 提取原 app 的 theme 资源 ID
            val manifest = enrichWithThemeIds(parsedManifest, originApk).let { m ->
                val maxTargetSdk = 34
                if (m.targetSdkVersion > maxTargetSdk) {
                    Log.w("StubBuilder", "capping targetSdkVersion ${m.targetSdkVersion} -> $maxTargetSdk")
                    m.copy(targetSdkVersion = maxTargetSdk)
                } else m
            }

            // 3. 生成 Stub manifest (二进制 XML)
            //    使用 ManifestRewriter 增量修改原 APK 的 manifest，保留所有结构
            val launcherActivity = extractor.extractLauncherActivity(manifest)
                ?: error("No launcher activity found in origin APK")
            val manifestBytes = try {
                rewriteManifest(originApk, config, manifest)
            } catch (e: Throwable) {
                Log.e("StubBuilder", "rewriteManifest failed, falling back to generator", e)
                // 回退到从零生成（兼容不支持增量修改的情况）
                // MIUI 上 provider meta-data 编码会导致安装失败，检测到 MIUI 时跳过
                val isMiui = isMiuiDevice()
                if (isMiui) Log.w("StubBuilder", "MIUI detected, skipping provider meta-data encoding")
                generator.generateBytes(
                    stubPackageName = config.stubPackageName,
                    manifest = manifest,
                    launcherActivity = launcherActivity,
                    config = config,
                    encodeProviderMetaData = !isMiui
                )
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

            // 5.5 Legacy protected-app helper injection for non-NORMAL profiles only.
            // Kept for diagnostic/compatibility comparison; NORMAL protected baseline skips
            // DEX/native patching and should rely on the guest runtime environment.
            val injectableApk = File(workDir, "origin_inject.apk")
            originApk.copyTo(injectableApk, overwrite = true)
            if (config.cloneProfile != CloneProfile.NORMAL) {
                injectPackerLibLoad(injectableApk, config.cloneProfile)
            } else {
                Log.w("StubBuilder", "profile=NORMAL: skip protected-app DEX/native patching")
            }
            Log.w("StubBuilder", "injectableApk: ${injectableApk.length()} bytes")

            // 6. Assemble legacy Stub APK. patchedDexFiles are only for explicit legacy profiles.
            val patchedDexFiles = config.patchedDexPaths.map { File(it) }.filter { it.exists() }
            val xposedModuleFiles = config.xposedModules.map { File(it) }.filter { it.exists() }
            val xposedInitEntries = collectXposedInitEntries(xposedModuleFiles)
            Log.w("StubBuilder", "xposed modules: ${xposedModuleFiles.size}, init entries: $xposedInitEntries")
            val unsignedApk = File(workDir, "stub-unsigned.apk")
            assembleApk(
                outputFile = unsignedApk,
                manifestBytes = manifestBytes,
                loaderDex = loaderDex,
                originApk = injectableApk,
                originalApk = originApk,  // Unmodified origin APK retained for legacy evidence/path comparison.
                configFile = configFile,
                config = config,
                iconFile = iconFile,
                patchedDexFiles = patchedDexFiles,
                xposedModuleFiles = xposedModuleFiles,
                xposedInitEntries = xposedInitEntries
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
            "cloneProfile" to config.cloneProfile.name,
            "appLabel" to config.appLabel,
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
     * 从 Xposed 模块 APK 中读取 xposed_init 入口类名
     *
     * @param moduleFiles 模块 APK 文件列表
     * @return 入口类名列表
     */
    private fun collectXposedInitEntries(moduleFiles: List<File>): List<String> {
        val entries = mutableListOf<String>()
        for (moduleApk in moduleFiles) {
            try {
                ZipFile(moduleApk).use { zip ->
                    val entry = zip.getEntry("assets/xposed_init") ?: return@use
                    zip.getInputStream(entry).bufferedReader().readLines()
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { entries.add(it.trim()) }
                }
            } catch (e: Throwable) {
                Log.w("StubBuilder", "Failed to read xposed_init from ${moduleApk.name}: ${e.message}")
            }
        }
        return entries
    }

    /**
     * 使用 ManifestRewriter 增量修改原 APK 的二进制 manifest
     */
    private fun rewriteManifest(originApk: File, config: StubConfig, manifest: ManifestParser.ParsedManifest): ByteArray {
        // ManifestRewriter 保留原 manifest 结构（含 meta-data），但会保留原 app 的 <permission> 声明。
        // stub 包名不同 → 系统报 INSTALL_FAILED_DUPLICATE_PERMISSION。
        // 因此始终走 BinaryXmlEncoder 从零生成（不含原 app 权限声明）。
        error("Using fallback generator to avoid duplicate permission conflicts")
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
        originalApk: File? = null,
        configFile: File,
        config: StubConfig,
        iconFile: File?,
        patchedDexFiles: List<File> = emptyList(),
        xposedModuleFiles: List<File> = emptyList(),
        xposedInitEntries: List<String> = emptyList()
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

            // assets/origin.apk (修改后的 APK，含 JiaguLoader)
            zos.putNextEntry(ZipEntry(ORIGIN_APK_ENTRY))
            originApk.inputStream().buffered().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()

            // assets/origin_original.apk (未修改的原始 APK，用于完整性校验重定向)
            if (originalApk != null && originalApk.exists()) {
                zos.putNextEntry(ZipEntry("assets/origin_original.apk"))
                originalApk.inputStream().buffered().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
                Timber.d("StubBuilder: embedded original APK: ${originalApk.length()} bytes")
            }

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

            // assets/xposed_modules/*.apk (嵌入的 Xposed 模块)
            for (moduleApk in xposedModuleFiles) {
                val entryName = "$XPOSED_MODULES_DIR${moduleApk.name}"
                zos.putNextEntry(ZipEntry(entryName))
                moduleApk.inputStream().buffered().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
                Timber.d("StubBuilder: embedded Xposed module: $entryName (${moduleApk.length()} bytes)")
            }

            // assets/xposed_init (模块入口类名列表)
            if (xposedInitEntries.isNotEmpty()) {
                val initContent = xposedInitEntries.joinToString("\n")
                zos.putNextEntry(ZipEntry(XPOSED_INIT_ENTRY))
                zos.write(initContent.toByteArray())
                zos.closeEntry()
                Timber.d("StubBuilder: wrote xposed_init with ${xposedInitEntries.size} entries")
            }

            // ★ 复制 origin APK 的所有 assets 到 stub APK
            // 解决：native 代码通过 NDK AAssetManager 读取 assets 时，
            //       指向 stub APK（没有这些文件）而不是 origin APK。
            //       直接把 origin 的 assets 复制到 stub 中，确保 native 代码能找到。
            try {
                ZipFile(originApk).use { originZip ->
                    val assetEntries = originZip.entries().toList()
                        .filter { it.name.startsWith("assets/") && !it.isDirectory }
                        .filter { !it.name.startsWith("assets/origin") && !it.name.startsWith("assets/multiapp_config") }
                    for (entry in assetEntries) {
                        // 跳过已经添加的 patched DEX
                        if (entry.name.startsWith("assets/patched/")) continue
                        zos.putNextEntry(ZipEntry(entry.name))
                        originZip.getInputStream(entry).use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                    Timber.d("StubBuilder: copied ${assetEntries.size} assets from origin APK")
                }
            } catch (e: Throwable) {
                Timber.w(e, "StubBuilder: failed to copy origin assets")
            }

            // lib/ native libraries — 从原始 APK 中提取并打包
            // loader.dex 依赖 libmultiapp-native.so（shadowhook PLT hook）
            // 必须打进 Stub APK 的 lib/ 目录，否则 native hook 全部失效
            packageNativeLibs(originApk, zos, config.cloneProfile)
        }

        Timber.d("StubBuilder: APK assembled, size=${outputFile.length()} bytes")
    }

    /**
     * 从原始 APK 和应用自身提取 native libraries 打包到 Stub APK
     *
     * loader.dex 依赖 libmultiapp-native.so（shadowhook PLT hook），
     * 必须打进 Stub APK 的 lib/ 目录。同时打包原始 APK 的 native libs。
     */
    private fun packageNativeLibs(originApk: File, zos: ZipOutputStream, cloneProfile: CloneProfile) {
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
                        // Native libs 必须 STORED（不压缩），Android 需要直接 mmap
                        var data = zip.getInputStream(entry).readBytes()

                        // Patch libjiagu_vip.so: JNI_OnLoad 的 return -1 改为 return 0
                        if (cloneProfile == CloneProfile.QQ_READER_SPECIAL &&
                            entry.name.contains("libjiagu_vip.so") && !entry.name.contains("_x86")) {
                            data = patchJiaguLoad(data, entry.name)
                        }

                        val storedEntry = ZipEntry(entry.name).apply {
                            method = ZipEntry.STORED
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            crc = java.util.zip.CRC32().also { it.update(data) }.value
                        }
                        zos.putNextEntry(storedEntry)
                        zos.write(data)
                        zos.closeEntry()
                        count++
                    }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract native libs from origin APK")
        }

        Timber.d("StubBuilder: packaged $count native libraries")
    }

    /**
     * Patch libjiagu_vip.so: JNI_OnLoad 的 return -1 改为 return 0
     *
     * 360 壳的 JNI_OnLoad 内部有环境检测，检测失败返回 JNI_ERR (-1)。
     * 通过二进制 patch 把 MOV W0, #-1 (0x12800000) 改成 MOV W0, #0 (0x52800000)，
     * 让 JNI_OnLoad 返回成功，使 RegisterNatives 能执行。
     *
     * @param data .so 文件内容
     * @param name 文件名（用于日志）
     * @return patch 后的 .so 内容
     */
    private fun patchJiaguLoad(data: ByteArray, name: String): ByteArray {
        val patched = data.copyOf()

        // 找 JNI_OnLoad 符号：通过 ELF 动态段找 .dynsym 和 .dynstr
        val elfClass = patched[4].toInt() and 0xFF
        if (elfClass != 2) { // 不是 ELF64
            Log.w("StubBuilder", "patchJiaguLoad: not ELF64, skip")
            return data
        }

        // 解析 ELF64 header
        val ePhoff = readLongLE(patched, 32)
        val ePhentsize = readShortLE(patched, 54)
        val ePhnum = readShortLE(patched, 56)

        // 找 PT_DYNAMIC 段
        var dynOffset = -1L
        var dynVaddr = -1L
        for (i in 0 until ePhnum) {
            val phOff = (ePhoff + i * ePhentsize).toInt()
            val pType = readIntLE(patched, phOff)
            if (pType == 2) { // PT_DYNAMIC
                dynOffset = readLongLE(patched, phOff + 8)
                dynVaddr = readLongLE(patched, phOff + 16)
                break
            }
        }
        if (dynOffset < 0) {
            Log.w("StubBuilder", "patchJiaguLoad: PT_DYNAMIC not found")
            return data
        }

        // 找 PT_LOAD 段用于 vaddr → file offset 映射
        data class LoadSegment(val vaddr: Long, val offset: Long, val filesz: Long)
        val loads = mutableListOf<LoadSegment>()
        for (i in 0 until ePhnum) {
            val phOff = (ePhoff + i * ePhentsize).toInt()
            val pType = readIntLE(patched, phOff)
            if (pType == 1) { // PT_LOAD
                loads.add(LoadSegment(
                    readLongLE(patched, phOff + 16),
                    readLongLE(patched, phOff + 8),
                    readLongLE(patched, phOff + 32)
                ))
            }
        }

        fun vaddrToFile(vaddr: Long): Long {
            for (seg in loads) {
                if (vaddr >= seg.vaddr && vaddr < seg.vaddr + seg.filesz) {
                    return seg.offset + (vaddr - seg.vaddr)
                }
            }
            return -1
        }

        // 解析 dynamic entries 找 DT_SYMTAB, DT_STRTAB
        var symtabVaddr = -1L
        var strtabVaddr = -1L
        var dynI = dynOffset.toInt()
        while (dynI + 16 <= patched.size) {
            val dTag = readLongLE(patched, dynI)
            val dVal = readLongLE(patched, dynI + 8)
            if (dTag == 0L) break // DT_NULL
            when (dTag) {
                6L -> symtabVaddr = dVal   // DT_SYMTAB
                5L -> strtabVaddr = dVal   // DT_STRTAB
            }
            dynI += 16
        }

        if (symtabVaddr < 0 || strtabVaddr < 0) {
            Log.w("StubBuilder", "patchJiaguLoad: symtab/strtab not found")
            return data
        }

        val symtabFile = vaddrToFile(symtabVaddr).toInt()
        val strtabFile = vaddrToFile(strtabVaddr).toInt()
        if (symtabFile < 0 || strtabFile < 0) {
            Log.w("StubBuilder", "patchJiaguLoad: can't convert vaddr to file offset")
            return data
        }

        // 在 .dynstr 中找 "JNI_OnLoad" 字符串
        val jniOnLoadStr = "JNI_OnLoad"
        val jniStrPos = findBytes(patched, jniOnLoadStr.toByteArray(), strtabFile)
        if (jniStrPos < 0) {
            Log.w("StubBuilder", "patchJiaguLoad: JNI_OnLoad string not found in .dynstr")
            return data
        }
        val jniNameIdx = jniStrPos - strtabFile

        // 在 .dynsym 中找 JNI_OnLoad 符号（每个 Elf64_Sym = 24 字节）
        var jniVaddr = -1L
        var jniSize = -1L
        val maxSym = 2000
        for (i in 0 until maxSym) {
            val entryOff = symtabFile + i * 24
            if (entryOff + 24 > patched.size) break
            val stName = readIntLE(patched, entryOff)
            if (stName == jniNameIdx) {
                jniVaddr = readLongLE(patched, entryOff + 8)
                jniSize = readLongLE(patched, entryOff + 16)
                break
            }
        }

        if (jniVaddr < 0 || jniSize <= 0) {
            Log.w("StubBuilder", "patchJiaguLoad: JNI_OnLoad symbol not found")
            return data
        }

        val jniFileOff = vaddrToFile(jniVaddr).toInt()
        if (jniFileOff < 0) {
            Log.w("StubBuilder", "patchJiaguLoad: JNI_OnLoad file offset not found")
            return data
        }

        Log.w("StubBuilder", "patchJiaguLoad: JNI_OnLoad at vaddr=0x${Integer.toHexString(jniVaddr.toInt())}, file=0x${Integer.toHexString(jniFileOff)}, size=$jniSize")

        // Patch 策略：
        // 1. NOP 掉 JNI_OnLoad 前 64 字节内的 CBZ/CBNZ 条件跳转（环境检测守卫）
        // 2. 把 MOV W0, #-1 改成 MOV W0, #0（强制返回成功）
        var patchCount = 0
        val endOff = jniFileOff + jniSize.toInt() - 4

        // Patch 1: NOP 掉前 64 字节内的 CBZ/CBNZ（环境检测跳转）
        // CBZ 编码: 0x34000000 | (imm19 << 5) | Rt
        // CBNZ 编码: 0x35000000 | (imm19 << 5) | Rt
        val scanEnd = minOf(jniFileOff + jniSize.toInt(), endOff)
        var off = jniFileOff
        while (off < scanEnd) {
            val insn = readIntLE(patched, off)
            val isCBZ = (insn and 0xFF000000.toInt()) == 0x34000000
            val isCBNZ = (insn and 0xFF000000.toInt()) == 0x35000000
            if (isCBZ || isCBNZ) {
                // NOP = 0xD503201F
                patched[off] = 0x1F.toByte()
                patched[off + 1] = 0x20.toByte()
                patched[off + 2] = 0x03.toByte()
                patched[off + 3] = 0xD5.toByte()
                patchCount++
                val op = if (isCBZ) "CBZ" else "CBNZ"
                Log.w("StubBuilder", "patchJiaguLoad: NOP'd $op at offset 0x${Integer.toHexString(off)}")
            }
            off += 4
        }

        // Patch 2: MOV W0, #-1 → MOV W0, #0
        off = jniFileOff
        while (off <= endOff) {
            val insn = readIntLE(patched, off)
            if (insn == 0x12800000) { // MOV W0, #-1
                val nextInsn = readIntLE(patched, off + 4)
                val isBranch = (nextInsn and 0xFF000000.toInt()) == 0x14000000 ||
                               (nextInsn and 0xFF000000.toInt()) == 0x17000000.toInt()
                if (isBranch || (nextInsn and 0xFC000000.toInt()) == 0x14000000) {
                    patched[off] = 0x00
                    patched[off + 1] = 0x00
                    patched[off + 2] = 0x80.toByte()
                    patched[off + 3] = 0x52
                    patchCount++
                    Log.w("StubBuilder", "patchJiaguLoad: patched MOV W0,#-1 at offset 0x${Integer.toHexString(off)}")
                }
            }
            off += 4
        }

        if (patchCount == 0) {
            Log.w("StubBuilder", "patchJiaguLoad: no MOV W0,#-1 pattern found in JNI_OnLoad")
            return data
        }

        Log.w("StubBuilder", "patchJiaguLoad: patched $patchCount instruction(s) in $name")
        return patched
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLongLE(data: ByteArray, offset: Int): Long {
        return (readIntLE(data, offset).toLong() and 0xFFFFFFFFL) or
            ((readIntLE(data, offset + 4).toLong() and 0xFFFFFFFFL) shl 32)
    }

    private fun readShortLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray, startOffset: Int = 0): Int {
        for (i in startOffset..(haystack.size - needle.size)) {
            if (haystack[i] == needle[0]) {
                var match = true
                for (j in 1 until needle.size) {
                    if (haystack[i + j] != needle[j]) { match = false; break }
                }
                if (match) return i
            }
        }
        return -1
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
            val data = libFile.readBytes()
            val storedEntry = ZipEntry(entryName).apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = java.util.zip.CRC32().also { it.update(data) }.value
            }
            zos.putNextEntry(storedEntry)
            zos.write(data)
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
                val data = zip.getInputStream(entry!!).readBytes()
                val storedEntry = ZipEntry(entryName).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = java.util.zip.CRC32().also { it.update(data) }.value
                }
                zos.putNextEntry(storedEntry)
                zos.write(data)
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
    private fun injectPackerLibLoad(originApk: File, cloneProfile: CloneProfile) {
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

            // 注入 helper 类到 guest DEX（从 guest ClassLoader 上下文加载 native 库）
            try {
                val helperInjected = patcher.injectHelperClass(dexFiles, "jiagu_vip")
                Log.w("StubBuilder", "injectPackerLibLoad: helper class injection result=$helperInjected")
            } catch (e: Throwable) {
                Log.e("StubBuilder", "injectPackerLibLoad: helper class injection failed", e)
            }

            var injected = false
            if (cloneProfile == CloneProfile.QQ_READER_SPECIAL) {
                for (className in packerClasses) {
                    if (patcher.injectLoadLibrary(dexFiles, className, "load", "jiagu_vip")) {
                        Log.w("StubBuilder", "injectPackerLibLoad: injected into $className.load()")
                        injected = true
                        break
                    }
                }
            }

            if (!injected) {
                Log.w("StubBuilder", "injectPackerLibLoad: no packer class found, continue with DEX neutralize")
            }

            // 中和不需要的初始化方法
            try {
                if (cloneProfile != CloneProfile.QQ_READER_SPECIAL) {
                    Log.w("StubBuilder", "injectPackerLibLoad: skip QQ Reader neutralizers for $cloneProfile")
                    throw SkipNeutralizeException()
                }
                val targets = listOf(
                    "com.bytedance.android.dy.sdk.pangle.ZeusPlatformUtils->initZeus",
                    "com.qq.reader.ReaderApplication->initPushSDK",
                    "com.qq.reader.shortcut.ShortcutManager->cihai",
                    "com.qq.reader.abtest_sdk.qdab->cihai",
                    "com.qq.reader.common.utils.qdbd->search",
                    "com.qq.reader.common.utils.qdcg->search",
                    "com.qq.reader.common.utils.qdeb->search",
                    "com.qq.reader.common.utils.ae->search",
                    "com.qq.reader.plugin.qdbh->search",
                    "com.qq.reader.qrlightdark.LightDarkStatusManager->search",
                    "com.yuewen.fock.Fock->sign"
                )
                Log.w("StubBuilder", "injectPackerLibLoad: neutralize targets: $targets")
                val neutralized = patcher.neutralizeMethods(dexFiles, targets)
                Log.w("StubBuilder", "injectPackerLibLoad: neutralized $neutralized methods")
            } catch (e: SkipNeutralizeException) {
                // Expected for non-QQ Reader protected profiles.
            } catch (e: Throwable) {
                Log.w("StubBuilder", "injectPackerLibLoad: neutralize failed: ${e.message}")
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

    private class SkipNeutralizeException : RuntimeException()

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

    private fun isMiuiDevice(): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java)
            val prop = method.invoke(null, "ro.miui.ui.version.name") as String
            prop.isNotEmpty()
        } catch (_: Exception) {
            android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        }
    }
}
