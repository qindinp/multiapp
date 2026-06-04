package com.multiapp.core.manifest

import net.dongliu.apk.parser.ApkFile
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import javax.inject.Inject

/**
 * 解析原始 APK 的二进制 AndroidManifest.xml
 * 使用 net.dongliu:apk-parser 库
 */
class ManifestParser @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {

    /**
     * 无参构造：用于测试或无 Context 场景。
     * 通过反射获取 Application Context，失败则 parseViaPackageManager 不可用。
     */
    @Suppress("unused")
    constructor() : this(getDefaultContext())

    data class ParsedManifest(
        val packageName: String,
        val applicationClass: String?,
        val activities: List<ComponentInfo>,
        val services: List<ComponentInfo>,
        val receivers: List<ComponentInfo>,
        val providers: List<ProviderInfo>,
        val permissions: List<String>,
        val minSdkVersion: Int = 28,
        val targetSdkVersion: Int = 36,
        val applicationTheme: String? = null,
        // 原 app 的 application theme 资源 ID（int，如 0x7f0f00xx）。
        // 0 表示未声明。由 StubBuilder 在构建期通过 getPackageArchiveInfo 填充。
        val applicationThemeId: Int = 0,
        // ContentProvider 的 <meta-data> 子元素，key = provider name
        val providerMetaData: Map<String, List<MetaDataInfo>> = emptyMap()
    )

    data class ComponentInfo(
        val name: String,
        val exported: Boolean = false,
        val process: String? = null,
        val intentFilters: List<IntentFilterInfo> = emptyList(),
        // 该 activity 声明的 theme 资源 ID（int）。0 表示未声明，运行时回退到 app theme。
        val themeId: Int = 0,
        // ── 以下字段用于保留原始 APK 的关键组件属性 ──
        val launchMode: String? = null,           // "singleTop", "singleTask", "singleInstance", "singleInstancePerTask"
        val configChanges: String? = null,        // e.g. "orientation|screenSize|keyboardHidden"
        val screenOrientation: String? = null,    // "portrait", "landscape", "unspecified", etc.
        val windowSoftInputMode: String? = null,  // "adjustResize", "adjustPan", "stateHidden", etc.
        val taskAffinity: String? = null,         // null = default (package name), "" = no affinity
        val permission: String? = null,           // component-level permission
        val stateNotNeeded: Boolean = false,
        val noHistory: Boolean = false,
        val allowTaskReparenting: Boolean = false,
        val clearTaskOnLaunch: Boolean = false,
        val finishOnTaskLaunch: Boolean = false,
        val enabled: Boolean = true
    )

    data class ProviderInfo(
        val name: String,
        val authorities: String,
        val exported: Boolean = false,
        val grantUriPermissions: Boolean = false
    )

    data class MetaDataInfo(
        val name: String,
        val resource: String? = null,
        val value: String? = null,
        val resourceId: Int = 0
    )

    data class IntentFilterInfo(
        val actions: List<String>,
        val categories: List<String> = emptyList()
    )

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /**
         * ActivityInfo.launchMode → manifest 字符串
         */
        fun convertLaunchMode(value: Int): String? = when (value) {
            0 -> null   // standard (default)
            1 -> "singleTop"
            2 -> "singleTask"
            3 -> "singleInstance"
            4 -> "singleInstancePerTask"
            else -> null
        }

        /**
         * ActivityInfo.screenOrientation → manifest 字符串
         */
        fun convertScreenOrientation(value: Int): String? = when (value) {
            -1 -> "unspecified"     // SCREEN_ORIENTATION_UNSPECIFIED
            0 -> "landscape"        // SCREEN_ORIENTATION_LANDSCAPE
            1 -> "portrait"         // SCREEN_ORIENTATION_PORTRAIT
            2 -> "user"             // SCREEN_ORIENTATION_USER
            3 -> "behind"           // SCREEN_ORIENTATION_BEHIND
            4 -> "sensor"           // SCREEN_ORIENTATION_SENSOR
            5 -> "nosensor"         // SCREEN_ORIENTATION_NOSENSOR
            6 -> "sensorLandscape"  // SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            7 -> "sensorPortrait"   // SCREEN_ORIENTATION_SENSOR_PORTRAIT
            8 -> "reverseLandscape" // SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            9 -> "reversePortrait"  // SCREEN_ORIENTATION_REVERSE_PORTRAIT
            10 -> "fullSensor"      // SCREEN_ORIENTATION_FULL_SENSOR
            11 -> "userLandscape"   // SCREEN_ORIENTATION_USER_LANDSCAPE
            12 -> "userPortrait"    // SCREEN_ORIENTATION_USER_PORTRAIT
            13 -> "fullUser"        // SCREEN_ORIENTATION_FULL_USER
            14 -> "locked"          // SCREEN_ORIENTATION_LOCKED
            else -> null
        }

        /**
         * ActivityInfo.configChanges bitmask → manifest 管道分隔字符串
         */
        fun convertConfigChanges(value: Int): String? {
            if (value == 0) return null
            val flags = mutableListOf<String>()
            if (value and 0x0001 != 0) flags.add("mcc")
            if (value and 0x0002 != 0) flags.add("mnc")
            if (value and 0x0004 != 0) flags.add("locale")
            if (value and 0x0008 != 0) flags.add("touchscreen")
            if (value and 0x0010 != 0) flags.add("keyboard")
            if (value and 0x0020 != 0) flags.add("keyboardHidden")
            if (value and 0x0040 != 0) flags.add("navigation")
            if (value and 0x0080 != 0) flags.add("orientation")
            if (value and 0x0100 != 0) flags.add("screenLayout")
            if (value and 0x0200 != 0) flags.add("uiMode")
            if (value and 0x0400 != 0) flags.add("screenSize")
            if (value and 0x0800 != 0) flags.add("smallestScreenSize")
            if (value and 0x1000 != 0) flags.add("density")
            if (value and 0x2000 != 0) flags.add("layoutDirection")
            if (value and 0x4000 != 0) flags.add("colorMode")
            if (value and 0x8000.toInt() != 0) flags.add("fontScale")
            if (value and 0x10000 != 0) flags.add("fontWeightAdjustment")
            if (value and 0x10000000 != 0) flags.add("grammaticalGender")
            val knownMask = 0x1001FFFF
            val unknown = value and knownMask.inv()
            if (unknown != 0) flags.add("0x${Integer.toHexString(unknown)}")
            return flags.joinToString("|")
        }

        /**
         * ActivityInfo.softInputMode → manifest 字符串
         */
        fun convertSoftInputMode(value: Int): String? {
            if (value == 0) return null
            val statePart = value and 0x0F
            val adjustPart = value and 0xF0
            val flags = mutableListOf<String>()
            when (statePart) {
                0x00 -> {} // stateUnspecified (default)
                0x01 -> flags.add("stateUnchanged")
                0x02 -> flags.add("stateHidden")
                0x03 -> flags.add("stateAlwaysHidden")
                0x04 -> flags.add("stateVisible")
                0x05 -> flags.add("stateAlwaysVisible")
            }
            when (adjustPart) {
                0x00 -> {} // adjustUnspecified (default)
                0x10 -> flags.add("adjustResize")
                0x20 -> flags.add("adjustPan")
                0x30 -> flags.add("adjustNothing")
            }
            if (value and 0x100 != 0) flags.add("isForwardNavigation")
            return flags.joinToString("|").takeIf { it.isNotEmpty() }
        }

        private fun getDefaultContext(): android.content.Context {
            // 尝试通过 ActivityThread 获取 Application Context
            return try {
                val at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread")
                    .apply { isAccessible = true }
                    .invoke(null)
                val app = at.javaClass
                    .getDeclaredMethod("getApplication")
                    .apply { isAccessible = true }
                    .invoke(at) as? android.content.Context
                app ?: throw IllegalStateException("Application is null")
            } catch (e: Exception) {
                throw IllegalStateException("Cannot get Context outside Android runtime. Use ManifestParser(context) instead.", e)
            }
        }
    }

    fun parse(apkFile: File): ParsedManifest {
        val manifestXml = try {
            ApkFile(apkFile).use { it.manifestXml }
        } catch (e: Exception) {
            // apk-parser 解析失败的常见原因：
            // - resources.arsc 为空或损坏（BufferUnderflowException）
            // - 360 加固等壳加密 manifest（二进制 XML 格式异常）
            // - APK 格式损坏
            // 回退到 Android PackageManager（系统原生 parser 能处理以上所有情况）
            android.util.Log.w("ManifestParser", "apk-parser failed, falling back to PackageManager", e)
            return parseViaPackageManager(apkFile)
        }
        return parseFromXml(manifestXml)
    }

    /**
     * 使用 Android PackageManager.getPackageArchiveInfo() 解析 APK
     * 系统原生 parser 能处理极小/损坏的 resources.arsc
     */
    private fun parseViaPackageManager(apkFile: File): ParsedManifest {
        val pm = context.packageManager

        val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
            android.content.pm.PackageManager.GET_SERVICES or
            android.content.pm.PackageManager.GET_RECEIVERS or
            android.content.pm.PackageManager.GET_PROVIDERS or
            android.content.pm.PackageManager.GET_PERMISSIONS or
            android.content.pm.PackageManager.GET_META_DATA or
            android.content.pm.PackageManager.GET_RESOLVED_FILTER

        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw IllegalArgumentException("PackageManager cannot parse APK: ${apkFile.name}")

        android.util.Log.d("ManifestParser", "PackageManager parsed: pkg=${info.packageName}, " +
            "activities=${info.activities?.size ?: 0}, services=${info.services?.size ?: 0}")

        val rawActivities = (info.activities ?: emptyArray<android.content.pm.ActivityInfo>())

        // 启发式推断 launcher activity：
        // PackageManager.getPackageArchiveInfo() 对非安装 APK 通常不返回 intent-filter 数据，
        // 所以即使有 GET_RESOLVED_FILTER 也可能为空。通过以下规则推断：
        //  1. 如果有 activity 带 MAIN+LAUNCHER filter → 使用它
        //  2. 如果只有一个 activity → 它就是 launcher
        //  3. 如果有名为 *MainActivity 的 → 使用它
        val mainActivityName = findLauncherFromFilters(rawActivities)
            ?: if (rawActivities.size == 1) rawActivities[0].name else null
            ?: rawActivities.firstOrNull { it.name.contains("MainActivity") }?.name

        val activities = rawActivities.map { act ->
            val filters = extractFiltersFromActivity(act)
            val resolvedFilters = if (filters.isEmpty() && act.name == mainActivityName) {
                // 为推断出的 launcher activity 注入 MAIN+LAUNCHER filter
                listOf(IntentFilterInfo(
                    actions = listOf("android.intent.action.MAIN"),
                    categories = listOf("android.intent.category.LAUNCHER")
                ))
            } else {
                filters
            }
            ComponentInfo(
                name = act.name,
                exported = act.exported,
                process = act.processName?.takeIf { it.isNotEmpty() },
                intentFilters = resolvedFilters,
                launchMode = convertLaunchMode(act.launchMode),
                configChanges = convertConfigChanges(act.configChanges),
                screenOrientation = convertScreenOrientation(act.screenOrientation),
                windowSoftInputMode = convertSoftInputMode(act.softInputMode),
                taskAffinity = act.taskAffinity?.takeIf { it.isNotEmpty() },
                permission = act.permission?.takeIf { it.isNotEmpty() },
                stateNotNeeded = (act.flags and 0x0040) != 0,
                noHistory = (act.flags and 0x8000) != 0,
                allowTaskReparenting = (act.flags and 0x0020) != 0,
                clearTaskOnLaunch = (act.flags and 0x0004) != 0,
                finishOnTaskLaunch = (act.flags and 0x0002) != 0,
                enabled = act.enabled
            )
        }

        val services = (info.services ?: emptyArray<android.content.pm.ServiceInfo>()).map { svc ->
            ComponentInfo(
                name = svc.name,
                exported = svc.exported,
                process = svc.processName?.takeIf { it.isNotEmpty() },
                intentFilters = emptyList(),
                permission = svc.permission?.takeIf { it.isNotEmpty() },
                enabled = svc.enabled
            )
        }

        val receivers = (info.receivers ?: emptyArray<android.content.pm.ActivityInfo>()).map { rcv ->
            ComponentInfo(
                name = rcv.name,
                exported = rcv.exported,
                process = rcv.processName?.takeIf { it.isNotEmpty() },
                intentFilters = emptyList(),
                permission = rcv.permission?.takeIf { it.isNotEmpty() },
                enabled = rcv.enabled
            )
        }

        val providerMetaDataMap = mutableMapOf<String, List<MetaDataInfo>>()
        val providers = (info.providers ?: emptyArray<android.content.pm.ProviderInfo>()).map { prv: android.content.pm.ProviderInfo ->
            val metaData = prv.metaData?.let { bundle ->
                bundle.keySet().mapNotNull { key ->
                    val value = bundle.get(key)
                    when (value) {
                        is Int -> MetaDataInfo(name = key, resource = "@0x${Integer.toHexString(value)}", resourceId = value)
                        is String -> MetaDataInfo(name = key, value = value)
                        else -> MetaDataInfo(name = key, value = value?.toString())
                    }
                }
            } ?: emptyList()
            if (metaData.isNotEmpty()) {
                providerMetaDataMap[prv.name ?: ""] = metaData
            }
            ProviderInfo(
                name = prv.name ?: "",
                authorities = prv.authority ?: "",
                exported = prv.exported,
                grantUriPermissions = prv.grantUriPermissions || prv.name?.contains("FileProvider") == true
            )
        }

        val permissions = (info.requestedPermissions ?: emptyArray<String>()).toList()

        // 提取 Application class name
        val appInfo = info.applicationInfo
        val applicationClass = (appInfo?.className ?: "").takeIf { it.isNotEmpty() }

        // minSdk / targetSdk
        val minSdk = appInfo?.minSdkVersion ?: 28
        val targetSdk = appInfo?.targetSdkVersion ?: 36

        return ParsedManifest(
            packageName = info.packageName,
            applicationClass = applicationClass,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = permissions,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            providerMetaData = providerMetaDataMap
        )
    }

    /**
     * 从 ActivityInfo 数组中查找带 MAIN+LAUNCHER intent-filter 的 activity
     */
    private fun findLauncherFromFilters(activities: Array<android.content.pm.ActivityInfo>): String? {
        for (act in activities) {
            val filters = extractFiltersFromActivity(act)
            for (f in filters) {
                if (f.actions.contains("android.intent.action.MAIN") &&
                    f.categories.contains("android.intent.category.LAUNCHER")) {
                    return act.name
                }
            }
        }
        return null
    }

    /**
     * 从 ActivityInfo 中提取 intent-filter 信息
     * 通过反射访问 ActivityInfo.filter（IntentFilter 对象），该字段在 GET_RESOLVED_FILTER 标志下可能被填充
     */
    private fun extractFiltersFromActivity(act: android.content.pm.ActivityInfo): List<IntentFilterInfo> {
        try {
            // ActivityInfo.filter 是隐藏 API，通过反射访问
            val filterField = android.content.pm.ActivityInfo::class.java.getDeclaredField("filter")
            filterField.isAccessible = true
            val filter = filterField.get(act) as? android.content.IntentFilter ?: return emptyList()

            val actions = mutableListOf<String>()
            val categories = mutableListOf<String>()

            // IntentFilter.actions 是隐藏字段，通过反射获取
            try {
                val actionsField = android.content.IntentFilter::class.java.getDeclaredField("mActions")
                actionsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val actionList = actionsField.get(filter) as? List<String> ?: emptyList()
                actions.addAll(actionList)
            } catch (_: Exception) {
                // 尝试替代方案
                try {
                    val countMethod = android.content.IntentFilter::class.java.getDeclaredMethod("countActions")
                    countMethod.isAccessible = true
                    val count = countMethod.invoke(filter) as Int
                    for (i in 0 until count) {
                        val getMethod = android.content.IntentFilter::class.java.getDeclaredMethod("getAction", Int::class.java)
                        getMethod.isAccessible = true
                        (getMethod.invoke(filter, i) as? String)?.let { actions.add(it) }
                    }
                } catch (_: Exception) {}
            }

            try {
                val catsField = android.content.IntentFilter::class.java.getDeclaredField("mCategories")
                catsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val catList = catsField.get(filter) as? List<String> ?: emptyList()
                categories.addAll(catList)
            } catch (_: Exception) {
                try {
                    val countMethod = android.content.IntentFilter::class.java.getDeclaredMethod("countCategories")
                    countMethod.isAccessible = true
                    val count = countMethod.invoke(filter) as Int
                    for (i in 0 until count) {
                        val getMethod = android.content.IntentFilter::class.java.getDeclaredMethod("getCategory", Int::class.java)
                        getMethod.isAccessible = true
                        (getMethod.invoke(filter, i) as? String)?.let { categories.add(it) }
                    }
                } catch (_: Exception) {}
            }

            if (actions.isNotEmpty()) {
                return listOf(IntentFilterInfo(actions = actions, categories = categories))
            }
        } catch (_: Exception) {
            // filter 字段不可用（非安装 APK 或 API 版本差异）
        }
        return emptyList()
    }

    /**
     * 直接从 XML 字符串解析（供测试使用，或已解码的 manifest）
     */
    fun parseFromXml(xml: String): ParsedManifest {
        val doc = parseXmlString(xml)

        val manifestEl = doc.documentElement
        val packageName = manifestEl.getAttribute("package")

        val applicationEl = findFirstChild(manifestEl, "application")
        val applicationClass = applicationEl
            ?.getAttributeNS(ANDROID_NS, "name")
            ?.takeIf { it.isNotEmpty() }

        val applicationTheme = applicationEl
            ?.getAttributeNS(ANDROID_NS, "theme")
            ?.takeIf { it.isNotEmpty() }

        val permissions = extractPermissions(manifestEl)
        val activities = extractComponents(applicationEl, "activity") +
            extractComponents(applicationEl, "activity-alias")
        val services = extractComponents(applicationEl, "service")
        val receivers = extractComponents(applicationEl, "receiver")
        val (providers, providerMetaData) = extractProviders(applicationEl)

        // 提取 SDK 版本
        val usesSdk = findFirstChild(manifestEl, "uses-sdk")
        val minSdkVersion = usesSdk?.getAttributeNS(ANDROID_NS, "minSdkVersion")
            ?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 28
        val targetSdkVersion = usesSdk?.getAttributeNS(ANDROID_NS, "targetSdkVersion")
            ?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 36

        return ParsedManifest(
            packageName = packageName,
            applicationClass = applicationClass,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = permissions,
            minSdkVersion = minSdkVersion,
            targetSdkVersion = targetSdkVersion,
            applicationTheme = applicationTheme,
            providerMetaData = providerMetaData
        )
    }

    // ── XML 解析辅助 ──────────────────────────────────────────────

    private fun parseXmlString(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
    }

    private fun extractPermissions(manifestEl: Element): List<String> {
        val result = mutableListOf<String>()
        forEachChild(manifestEl, "uses-permission") { el ->
            val name = el.getAttributeNS(ANDROID_NS, "name")
            if (name.isNotEmpty()) result.add(name)
        }
        return result
    }

    private fun extractComponents(
        applicationEl: Element?,
        tagName: String
    ): List<ComponentInfo> {
        if (applicationEl == null) return emptyList()
        val components = mutableListOf<ComponentInfo>()
        forEachChild(applicationEl, tagName) { el ->
            val name = el.getAttributeNS(ANDROID_NS, "name")
            if (name.isNotEmpty()) {
                components.add(
                    ComponentInfo(
                        name = name,
                        exported = el.getAttributeNS(ANDROID_NS, "exported") == "true",
                        process = el.getAttributeNS(ANDROID_NS, "process").takeIf { it.isNotEmpty() },
                        intentFilters = extractIntentFilters(el),
                        launchMode = el.getAttributeNS(ANDROID_NS, "launchMode").takeIf { it.isNotEmpty() },
                        configChanges = el.getAttributeNS(ANDROID_NS, "configChanges").takeIf { it.isNotEmpty() },
                        screenOrientation = el.getAttributeNS(ANDROID_NS, "screenOrientation").takeIf { it.isNotEmpty() },
                        windowSoftInputMode = el.getAttributeNS(ANDROID_NS, "windowSoftInputMode").takeIf { it.isNotEmpty() },
                        taskAffinity = el.getAttributeNS(ANDROID_NS, "taskAffinity").let { v ->
                            // 区分"未声明"(null) 和"显式设为空串"("")
                            if (el.hasAttributeNS(ANDROID_NS, "taskAffinity")) v else null
                        },
                        permission = el.getAttributeNS(ANDROID_NS, "permission").takeIf { it.isNotEmpty() },
                        stateNotNeeded = el.getAttributeNS(ANDROID_NS, "stateNotNeeded") == "true",
                        noHistory = el.getAttributeNS(ANDROID_NS, "noHistory") == "true",
                        allowTaskReparenting = el.getAttributeNS(ANDROID_NS, "allowTaskReparenting") == "true",
                        clearTaskOnLaunch = el.getAttributeNS(ANDROID_NS, "clearTaskOnLaunch") == "true",
                        finishOnTaskLaunch = el.getAttributeNS(ANDROID_NS, "finishOnTaskLaunch") == "true",
                        enabled = el.getAttributeNS(ANDROID_NS, "enabled").let {
                            if (it.isEmpty()) true else it == "true"
                        }
                    )
                )
            }
        }
        return components
    }

    private fun extractProviders(applicationEl: Element?): Pair<List<ProviderInfo>, Map<String, List<MetaDataInfo>>> {
        if (applicationEl == null) return emptyList<ProviderInfo>() to emptyMap()
        val providers = mutableListOf<ProviderInfo>()
        val metaDataMap = mutableMapOf<String, List<MetaDataInfo>>()
        forEachChild(applicationEl, "provider") { el ->
            val name = el.getAttributeNS(ANDROID_NS, "name")
            if (name.isNotEmpty()) {
                providers.add(
                    ProviderInfo(
                        name = name,
                        authorities = el.getAttributeNS(ANDROID_NS, "authorities"),
                        exported = el.getAttributeNS(ANDROID_NS, "exported") == "true",
                        grantUriPermissions = el.getAttributeNS(ANDROID_NS, "grantUriPermissions") == "true"
                    )
                )
                val metaData = extractMetaData(el)
                if (metaData.isNotEmpty()) {
                    metaDataMap[name] = metaData
                }
            }
        }
        return providers to metaDataMap
    }

    private fun extractMetaData(el: Element?): List<MetaDataInfo> {
        if (el == null) return emptyList()
        val result = mutableListOf<MetaDataInfo>()
        forEachChild(el, "meta-data") { meta ->
            val metaName = meta.getAttributeNS(ANDROID_NS, "name")
            if (metaName.isNotEmpty()) {
                result.add(MetaDataInfo(
                    name = metaName,
                    resource = meta.getAttributeNS(ANDROID_NS, "resource").ifEmpty { null },
                    value = meta.getAttributeNS(ANDROID_NS, "value").ifEmpty { null }
                ))
            }
        }
        return result
    }

    private fun extractIntentFilters(componentEl: Element): List<IntentFilterInfo> {
        val filters = mutableListOf<IntentFilterInfo>()
        forEachChild(componentEl, "intent-filter") { filterEl ->
            val actions = collectNames(filterEl, "action")
            val categories = collectNames(filterEl, "category")
            if (actions.isNotEmpty()) {
                filters.add(IntentFilterInfo(actions = actions, categories = categories))
            }
        }
        return filters
    }

    private fun collectNames(parent: Element, tagName: String): List<String> {
        val names = mutableListOf<String>()
        forEachChild(parent, tagName) { el ->
            val value = el.getAttributeNS(ANDROID_NS, "name")
            if (value.isNotEmpty()) names.add(value)
        }
        return names
    }

    // ── DOM 遍历工具 ──────────────────────────────────────────────

    private fun findFirstChild(parent: Element, tagName: String): Element? {
        val children = parent.getElementsByTagName(tagName)
        return if (children.length > 0) children.item(0) as? Element else null
    }

    private fun forEachChild(parent: Element, tagName: String, action: (Element) -> Unit) {
        val nodes: NodeList = parent.getElementsByTagName(tagName)
        for (i in 0 until nodes.length) {
            action(nodes.item(i) as Element)
        }
    }
}
