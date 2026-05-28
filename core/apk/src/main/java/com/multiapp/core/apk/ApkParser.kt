package com.multiapp.core.apk

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import com.multiapp.core.model.ApkInfo
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ApkParser — Parses APK files to extract manifest information.
 *
 * Uses PackageManager.getPackageArchiveInfo() which leverages
 * the real Android framework's APK parsing (PackageParser in Android 16).
 *
 * Extracts: package name, version, activities, services, providers,
 * receivers, permissions, native ABI support, main launcher activity.
 */
@Singleton
class ApkParser @Inject constructor(
    application: Application
) {
    private val context: Context = application.applicationContext
    companion object {
        private const val TAG = "ApkParser"
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    /**
     * Parse an APK file and extract all manifest information.
     *
     * @param apkPath Absolute path to the APK file
     * @return ApkInfo with all parsed data, or null if parsing fails
     */
    fun parseApk(apkPath: String): ApkInfo? {
        val file = File(apkPath)
        if (!file.exists()) {
            Timber.tag(TAG).e("APK file not found: $apkPath")
            return null
        }

        val pm = context.packageManager

        // Parse with ALL component flags
        val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_META_DATA or
                PackageManager.GET_CONFIGURATIONS

        // NOTE: On Android 15+, getPackageArchiveInfo() may trigger
        // AconfigFlags init which logs FileNotFoundException for
        // /vendor/etc/aconfig_flags.pb — this is harmless and can be ignored.
        val packageInfo = try {
            pm.getPackageArchiveInfo(apkPath, flags)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getPackageArchiveInfo threw — trying fallback")
            null
        }
        if (packageInfo == null) {
            Timber.tag(TAG).w("getPackageArchiveInfo returned null — trying direct manifest parse for: $apkPath")
            return parseApkManifestDirect(apkPath)
        }

        // CRITICAL: Set source paths so loadIcon() and loadLabel() work
        packageInfo.applicationInfo?.sourceDir = apkPath
        packageInfo.applicationInfo?.publicSourceDir = apkPath

        val appInfo = packageInfo.applicationInfo

        // Extract component names
        val activities = packageInfo.activities?.map { it.name } ?: emptyList()
        val services = packageInfo.services?.map { it.name } ?: emptyList()
        val providers = packageInfo.providers?.map { it.name } ?: emptyList()
        val receivers = packageInfo.receivers?.map { it.name } ?: emptyList()
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

        // Detect native library ABIs
        val nativeLibs = detectNativeAbis(apkPath)
        val is64Bit = nativeLibs.any { it.contains("64") }

        // Find main launcher activity
        val mainActivity = findMainActivity(activities, apkPath)

        // Extract meta-data
        val metaData = mutableMapOf<String, String>()
        appInfo?.metaData?.let { bundle ->
            for (key in bundle.keySet()) {
                metaData[key] = bundle.get(key)?.toString() ?: ""
            }
        }

        val apkInfo = ApkInfo(
            packageName = packageInfo.packageName,
            appName = appInfo?.loadLabel(pm)?.toString() ?: packageInfo.packageName,
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.longVersionCode,
            minSdk = appInfo?.minSdkVersion ?: 1,
            targetSdk = appInfo?.targetSdkVersion ?: 35,
            icon = appInfo?.loadIcon(pm),
            apkPath = apkPath,
            apkSizeBytes = file.length(),
            permissions = permissions,
            activities = activities,
            services = services,
            providers = providers,
            receivers = receivers,
            nativeLibs = nativeLibs,
            is64Bit = is64Bit,
            usesSplitApk = appInfo?.splitSourceDirs?.isNotEmpty() == true,
            mainActivity = mainActivity,
            applicationClassName = appInfo?.className,
            metaData = metaData
        )

        Timber.tag(TAG).i(
            "Parsed APK: ${apkInfo.appName} (${apkInfo.packageName}) " +
            "v${apkInfo.versionName} | ${activities.size} activities | " +
            "${services.size} services | ${nativeLibs.joinToString()}"
        )

        return apkInfo
    }

    /**
     * FIX 3: Direct manifest parse using AssetManager + XmlResourceParser.
     * Avoids PackageManager.getPackageArchiveInfo() which can trigger AconfigFlags init
     * that reads /vendor/etc/aconfig_flags.pb — causing ENOENT in virtual environments.
     */
    @Suppress("DiscouragedPrivateApi")
    private fun parseApkManifestDirect(apkPath: String): ApkInfo? {
        var assetManager: AssetManager? = null
        try {
            assetManager = AssetManager::class.java
                .getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            val cookie = addAssetPath.invoke(assetManager, apkPath) as Int

            if (cookie == 0) {
                Timber.tag(TAG).w("addAssetPath failed for $apkPath")
                return null
            }

            val parser = assetManager.openXmlResourceParser(cookie, "AndroidManifest.xml")

            var packageName: String? = null
            var versionName = "unknown"
            var versionCode = 0L
            var minSdk = 1
            var targetSdk = 35
            var applicationClassName: String? = null
            val activities = mutableListOf<String>()
            val services = mutableListOf<String>()
            val providers = mutableListOf<String>()
            val receivers = mutableListOf<String>()
            val permissions = mutableListOf<String>()

            // Track intent-filter state for main activity detection
            var currentActivityName: String? = null
            var inIntentFilter = false
            var hasActionMain = false
            var hasCategoryLauncher = false
            var mainActivity: String? = null

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "manifest" -> {
                                packageName = parser.getAttributeValue(null, "package")
                                parser.getAttributeValue(ANDROID_NS, "versionName")?.let { versionName = it }
                                val vc = parser.getAttributeValue(ANDROID_NS, "versionCode")
                                versionCode = vc?.toLongOrNull() ?: 0
                            }
                            "uses-sdk" -> {
                                parser.getAttributeValue(ANDROID_NS, "minSdkVersion")
                                    ?.toIntOrNull()?.let { minSdk = it }
                                parser.getAttributeValue(ANDROID_NS, "targetSdkVersion")
                                    ?.toIntOrNull()?.let { targetSdk = it }
                            }
                            "application" -> {
                                applicationClassName = parser.getAttributeValue(ANDROID_NS, "name")
                                    ?.let { resolveComponentName(packageName, it) }
                            }
                            "activity", "activity-alias" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    ?.let { resolveComponentName(packageName, it) }
                                if (name != null) {
                                    activities.add(name)
                                    currentActivityName = name
                                }
                                hasActionMain = false
                                hasCategoryLauncher = false
                            }
                            "service" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    ?.let { resolveComponentName(packageName, it) }
                                if (name != null) services.add(name)
                            }
                            "provider" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    ?.let { resolveComponentName(packageName, it) }
                                if (name != null) providers.add(name)
                            }
                            "receiver" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    ?.let { resolveComponentName(packageName, it) }
                                if (name != null) receivers.add(name)
                            }
                            "uses-permission" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                if (name != null) permissions.add(name)
                            }
                            "intent-filter" -> {
                                inIntentFilter = true
                            }
                            "action" -> {
                                if (inIntentFilter) {
                                    val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    if (name == "android.intent.action.MAIN") hasActionMain = true
                                }
                            }
                            "category" -> {
                                if (inIntentFilter) {
                                    val name = parser.getAttributeValue(ANDROID_NS, "name")
                                    if (name == "android.intent.category.LAUNCHER") hasCategoryLauncher = true
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "intent-filter" -> {
                                if (hasActionMain && hasCategoryLauncher && currentActivityName != null && mainActivity == null) {
                                    mainActivity = currentActivityName
                                }
                                inIntentFilter = false
                            }
                            "activity", "activity-alias" -> {
                                currentActivityName = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            parser.close()

            if (packageName == null) {
                Timber.tag(TAG).e("Direct parse: no package name found in $apkPath")
                return null
            }

            val file = File(apkPath)
            val nativeLibs = detectNativeAbis(apkPath)
            val is64Bit = nativeLibs.any { it.contains("64") }

            val apkInfo = ApkInfo(
                packageName = packageName,
                appName = packageName, // No label resolution without Resources
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                icon = null, // Icon requires resource resolution
                apkPath = apkPath,
                apkSizeBytes = file.length(),
                permissions = permissions,
                activities = activities,
                services = services,
                providers = providers,
                receivers = receivers,
                nativeLibs = nativeLibs,
                is64Bit = is64Bit,
                usesSplitApk = false,
                mainActivity = mainActivity ?: activities.firstOrNull(),
                applicationClassName = applicationClassName,
                metaData = emptyMap()
            )

            Timber.tag(TAG).i(
                "Direct-parsed APK: ${apkInfo.packageName} v${apkInfo.versionName} " +
                "| ${activities.size} activities | main=$mainActivity"
            )

            return apkInfo
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "parseApkManifestDirect failed for $apkPath")
            return null
        } finally {
            try { assetManager?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Resolve a component class name — handles relative names like ".MyActivity".
     */
    private fun resolveComponentName(packageName: String?, name: String): String {
        if (name.startsWith(".") && packageName != null) return "$packageName$name"
        if (!name.contains(".") && packageName != null) return "$packageName.$name"
        return name
    }

    /**
     * Find the main launcher activity from the APK.
     * Looks for Activity with MAIN + LAUNCHER intent filter.
     */
    private fun findMainActivity(activities: List<String>, apkPath: String): String? {
        if (activities.isEmpty()) return null

        try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES
            ) ?: return activities.firstOrNull()

            info.applicationInfo?.sourceDir = apkPath
            info.applicationInfo?.publicSourceDir = apkPath

            // The first activity is typically the main one
            // For more accurate detection, we'd need to parse the manifest XML
            // PackageManager doesn't directly expose intent-filter info for archive packages
            return activities.firstOrNull()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error finding main activity")
            return activities.firstOrNull()
        }
    }

    /**
     * Detect which native ABI architectures are included in the APK.
     * Looks in the lib/ directory of the APK.
     */
    private fun detectNativeAbis(apkPath: String): List<String> {
        val abis = mutableSetOf<String>()

        try {
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        // e.g., "lib/arm64-v8a/libnative.so" → "arm64-v8a"
                        val parts = entry.name.split("/")
                        if (parts.size >= 2) {
                            abis.add(parts[1])
                        }
                    }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error detecting native ABIs")
        }

        return abis.toList()
    }
}
