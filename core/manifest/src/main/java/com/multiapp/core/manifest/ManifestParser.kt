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
class ManifestParser @Inject constructor() {

    data class ParsedManifest(
        val packageName: String,
        val applicationClass: String?,
        val activities: List<ComponentInfo>,
        val services: List<ComponentInfo>,
        val receivers: List<ComponentInfo>,
        val providers: List<ProviderInfo>,
        val permissions: List<String>,
        val minSdkVersion: Int = 28,
        val targetSdkVersion: Int = 36
    )

    data class ComponentInfo(
        val name: String,
        val exported: Boolean = false,
        val process: String? = null,
        val intentFilters: List<IntentFilterInfo> = emptyList()
    )

    data class ProviderInfo(
        val name: String,
        val authorities: String,
        val exported: Boolean = false
    )

    data class IntentFilterInfo(
        val actions: List<String>,
        val categories: List<String> = emptyList()
    )

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    fun parse(apkFile: File): ParsedManifest {
        ApkFile(apkFile).use { apk ->
            val manifestXml = apk.manifestXml
            return parseFromXml(manifestXml)
        }
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
            targetSdkVersion = targetSdkVersion
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
                        exported = el.getAttributeNS(ANDROID_NS, "exported") == "true"
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
