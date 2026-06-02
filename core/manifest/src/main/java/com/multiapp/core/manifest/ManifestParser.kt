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
        val applicationThemeId: Int = 0
    )

    data class ComponentInfo(
        val name: String,
        val exported: Boolean = false,
        val process: String? = null,
        val intentFilters: List<IntentFilterInfo> = emptyList(),
        // 该 activity 声明的 theme 资源 ID（int）。0 表示未声明，运行时回退到 app theme。
        val themeId: Int = 0
    )

    data class ProviderInfo(
        val name: String,
        val authorities: String,
        val exported: Boolean = false,
        val grantUriPermissions: Boolean = false
    )

    data class IntentFilterInfo(
        val actions: List<String>,
        val categories: List<String> = emptyList()
    )

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

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
        } catch (e: java.nio.BufferUnderflowException) {
            // resources.arsc 为空或损坏时（如最小测试APK），apk-parser 会抛 BufferUnderflowException
            // 回退到 Android PackageManager 解析（系统原生 parser 不受 resources.arsc 大小影响）
            android.util.Log.w("ManifestParser", "resources.arsc parse failed, falling back to PackageManager", e)
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
                intentFilters = resolvedFilters
            )
        }

        val services = (info.services ?: emptyArray<android.content.pm.ServiceInfo>()).map { svc ->
            ComponentInfo(
                name = svc.name,
                exported = svc.exported,
                process = svc.processName?.takeIf { it.isNotEmpty() },
                intentFilters = emptyList()
            )
        }

        val receivers = (info.receivers ?: emptyArray<android.content.pm.ActivityInfo>()).map { rcv ->
            ComponentInfo(
                name = rcv.name,
                exported = rcv.exported,
                process = rcv.processName?.takeIf { it.isNotEmpty() },
                intentFilters = emptyList()
            )
        }

        val providers = (info.providers ?: emptyArray<android.content.pm.ProviderInfo>()).map { prv: android.content.pm.ProviderInfo ->
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
            targetSdkVersion = targetSdk
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
        val providers = extractProviders(applicationEl)

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
            applicationTheme = applicationTheme
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
                        intentFilters = extractIntentFilters(el)
                    )
                )
            }
        }
        return components
    }

    private fun extractProviders(applicationEl: Element?): List<ProviderInfo> {
        if (applicationEl == null) return emptyList()
        val providers = mutableListOf<ProviderInfo>()
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
            }
        }
        return providers
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
