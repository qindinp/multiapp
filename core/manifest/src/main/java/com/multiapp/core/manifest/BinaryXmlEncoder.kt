package com.multiapp.core.manifest

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Android Binary XML 编码器
 *
 * 将 Manifest 数据编译为 APK 所需的二进制 XML 格式 (ResXMLTree)。
 * 格式参考: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 *
 * 所有多字节整数使用 little-endian 字节序。
 */
class BinaryXmlEncoder {

    companion object {
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_TYPE = 0x0003
        private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val TYPE_STRING = 0x03
        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_BOOLEAN = 0x12

        private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

        private val ANDROID_ATTR_IDS = mapOf(
            "versionCode" to 0x0101021b,
            "versionName" to 0x0101021c,
            "minSdkVersion" to 0x0101020c,
            "targetSdkVersion" to 0x01010270,

            "name" to 0x01010003,
            "label" to 0x01010001,
            "icon" to 0x01010002,
            "theme" to 0x01010000,
            "debuggable" to 0x0101000f,
            "exported" to 0x01010010,
            "process" to 0x01010011,
            "authorities" to 0x01010018,
            "appComponentFactory" to 0x0101057a,
            "permission" to 0x01010006,
            "enabled" to 0x0101000e,
            "extractNativeLibs" to 0x010104ea,
            "grantUriPermissions" to 0x0101001b,
            // ── meta-data 属性 ──
            "resource" to 0x01010025,
            "value" to 0x01010024,
            // ── 组件属性 ──
            "launchMode" to 0x0101001d,
            "configChanges" to 0x0101001f,
            "screenOrientation" to 0x0101001e,
            "windowSoftInputMode" to 0x01010020,
            "taskAffinity" to 0x01010012,
            "stateNotNeeded" to 0x01010016,
            "noHistory" to 0x01010019,
            "allowTaskReparenting" to 0x01010021,
            "clearTaskOnLaunch" to 0x01010015,
            "finishOnTaskLaunch" to 0x01010014
        )
    }

    /**
     * @param rawValue 字符串形式的原始值 (在 string pool 中)
     * @param typedValue 整数形式的类型化值 (直接写入 data 字段)
     * @param dataType 值类型: TYPE_STRING(3), TYPE_INT_DEC(0x10), TYPE_INT_BOOLEAN(0x12)
     */
    private data class XmlAttr(
        val ns: String?,
        val name: String,
        val rawValue: String,
        val typedValue: Int = 0,
        val dataType: Int = TYPE_STRING
    )

    private sealed class Node {
        class NsStart(val prefix: String, val uri: String) : Node()
        class NsEnd(val prefix: String, val uri: String) : Node()
        class ElemStart(val ns: String?, val name: String, val attrs: List<XmlAttr>) : Node()
        class ElemEnd(val ns: String?, val name: String) : Node()
    }

    fun encodeFromManifest(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): ByteArray {
        // 属性名必须在 StringPool 前面，与 ResourceMap 对应
        val attrNames = mutableListOf<String>()
        val otherStrings = mutableListOf<String>()

        fun attrStr(s: String): Int {
            val idx = attrNames.indexOf(s)
            if (idx >= 0) return idx
            attrNames.add(s)
            return attrNames.size - 1
        }
        fun otherStr(s: String): Int {
            val idx = otherStrings.indexOf(s)
            if (idx >= 0) return idx
            otherStrings.add(s)
            return otherStrings.size - 1
        }

        // 属性名索引 (ResourceMap 对应)
        val IDX_VERSION_CODE = attrStr("versionCode")
        val IDX_VERSION_NAME = attrStr("versionName")
        val IDX_MIN_SDK = attrStr("minSdkVersion")
        val IDX_TARGET_SDK = attrStr("targetSdkVersion")
        val IDX_NAME = attrStr("name")
        val IDX_LABEL = attrStr("label")
        val IDX_THEME = attrStr("theme")
        val IDX_EXPORTED = attrStr("exported")
        val IDX_PROCESS = attrStr("process")
        val IDX_AUTHORITIES = attrStr("authorities")
        val IDX_APP_COMP_FACTORY = attrStr("appComponentFactory")
        val IDX_PERMISSION = attrStr("permission")
        val IDX_ENABLED = attrStr("enabled")
        val IDX_EXTRACT_NATIVE_LIBS = attrStr("extractNativeLibs")
        val IDX_DEBUGGABLE = attrStr("debuggable")
        val IDX_GRANT_URI_PERMS = attrStr("grantUriPermissions")
        val IDX_LAUNCH_MODE = attrStr("launchMode")
        val IDX_CONFIG_CHANGES = attrStr("configChanges")
        val IDX_SCREEN_ORIENTATION = attrStr("screenOrientation")
        val IDX_WINDOW_SOFT_INPUT_MODE = attrStr("windowSoftInputMode")
        val IDX_TASK_AFFINITY = attrStr("taskAffinity")
        val IDX_STATE_NOT_NEEDED = attrStr("stateNotNeeded")
        val IDX_NO_HISTORY = attrStr("noHistory")
        val IDX_ALLOW_TASK_REPARENTING = attrStr("allowTaskReparenting")
        val IDX_CLEAR_TASK_ON_LAUNCH = attrStr("clearTaskOnLaunch")
        val IDX_FINISH_ON_TASK_LAUNCH = attrStr("finishOnTaskLaunch")

        // 其他字符串
        val NS_URI = otherStr(ANDROID_NS_URI)
        val PKG = otherStr(stubPackageName)
        val MANIFEST = otherStr("manifest")
        val PACKAGE = otherStr("package")
        val USES_SDK = otherStr("uses-sdk")
        val minSdkStr = manifest.minSdkVersion.toString()
        val targetSdkStr = manifest.targetSdkVersion.toString()
        otherStr(minSdkStr)
        otherStr(targetSdkStr)
        val USES_PERM = otherStr("uses-permission")
        val APPLICATION = otherStr("application")
        val COMP_FACTORY_VAL = otherStr("com.multiapp.core.loader.LoaderFactory")
        val ACTIVITY = otherStr("activity")
        val INTENT_FILTER = otherStr("intent-filter")
        val ACTION = otherStr("action")
        val CATEGORY = otherStr("category")
        val MAIN_ACTION = otherStr("android.intent.action.MAIN")
        val LAUNCHER_CAT = otherStr("android.intent.category.LAUNCHER")
        val TRUE_VAL = otherStr("true")
        val FALSE_VAL = otherStr("false")
        val VERSION_NAME_VAL = otherStr("1.0")
        val SERVICE = otherStr("service")
        val RECEIVER = otherStr("receiver")
        val PROVIDER = otherStr("provider")
        val META_DATA = otherStr("meta-data")
        val IDX_RESOURCE = attrStr("resource")
        val IDX_VALUE = attrStr("value")
        val ANDROID = otherStr("android")
        val EMPTY = otherStr("")

        // 动态字符串
        for (p in manifest.permissions) otherStr(p)
        otherStr(launcherActivity.name)
        fun registerComponentStrings(c: ManifestParser.ComponentInfo) {
            otherStr(c.name)
            c.process?.let { otherStr(it) }
            c.launchMode?.let { otherStr(it) }
            c.configChanges?.let { otherStr(it) }
            c.screenOrientation?.let { otherStr(it) }
            c.windowSoftInputMode?.let { otherStr(it) }
            c.taskAffinity?.let { otherStr(it) }
            c.permission?.let { otherStr(it) }
        }
        registerComponentStrings(launcherActivity)
        for (a in manifest.activities) { if (a.name != launcherActivity.name) registerComponentStrings(a) }
        for (s in manifest.services) registerComponentStrings(s)
        for (r in manifest.receivers) registerComponentStrings(r)
        val authorityRewriter = AuthorityRewriter()
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId, config.authorityMap)
        for (p in rewrittenProviders) {
            otherStr(p.name)
            p.authorities?.let { otherStr(it) }
        }
        for ((_, metaList) in manifest.providerMetaData) {
            for (meta in metaList) {
                otherStr(meta.name)
                meta.resource?.let { otherStr(it) }
                meta.value?.let { otherStr(it) }
            }
        }

        // 如果有 applicationClass，动态添加到 string pool
        val appClassName = manifest.applicationClass
        if (appClassName != null) otherStr(appClassName)

        // theme 以 TYPE_REFERENCE（资源 ID）写入，不进 string pool。
        // applicationTheme 字符串仅保留用于日志/调试，不再注入。

        // 合并: 属性名在前，其他在后
        val pool = attrNames + otherStrings
        val otherOffset = attrNames.size
        fun otherIdx(localIdx: Int) = otherOffset + localIdx

        val nodes = mutableListOf<Node>()

        // Build node tree
        nodes.add(Node.NsStart("android", ANDROID_NS_URI))
        nodes.add(Node.ElemStart(null, "manifest", listOf(
            XmlAttr(ANDROID_NS_URI, "versionCode", "1", typedValue = 1, dataType = TYPE_INT_DEC),
            XmlAttr(ANDROID_NS_URI, "versionName", "1.0"),
            XmlAttr(null, "package", stubPackageName)
        )))

        // <uses-sdk> — 必须声明，否则 Android 12+ 拒绝安装
        nodes.add(Node.ElemStart(null, "uses-sdk", listOf(
            XmlAttr(ANDROID_NS_URI, "minSdkVersion", minSdkStr, typedValue = manifest.minSdkVersion, dataType = TYPE_INT_DEC),
            XmlAttr(ANDROID_NS_URI, "targetSdkVersion", targetSdkStr, typedValue = manifest.targetSdkVersion, dataType = TYPE_INT_DEC)
        )))
        nodes.add(Node.ElemEnd(null, "uses-sdk"))

        for (p in manifest.permissions) {
            nodes.add(Node.ElemStart(null, "uses-permission", listOf(
                XmlAttr(ANDROID_NS_URI, "name", p)
            )))
            nodes.add(Node.ElemEnd(null, "uses-permission"))
        }

        nodes.add(Node.ElemStart(null, "application", buildApplicationAttrs(config, manifest.applicationClass, manifest.applicationThemeId)))

        // Launcher activity
        nodes.add(Node.ElemStart(null, "activity", buildComponentAttrs(launcherActivity, forceExported = true)))

        nodes.add(Node.ElemStart(null, "intent-filter", emptyList()))
        nodes.add(Node.ElemStart(null, "action", listOf(XmlAttr(ANDROID_NS_URI, "name", "android.intent.action.MAIN"))))
        nodes.add(Node.ElemEnd(null, "action"))
        nodes.add(Node.ElemStart(null, "category", listOf(XmlAttr(ANDROID_NS_URI, "name", "android.intent.category.LAUNCHER"))))
        nodes.add(Node.ElemEnd(null, "category"))
        nodes.add(Node.ElemEnd(null, "intent-filter"))
        nodes.add(Node.ElemEnd(null, "activity"))

        // Other activities
        for (a in manifest.activities) {
            if (a.name == launcherActivity.name) continue
            addComponentNode(nodes, "activity", a)
        }
        for (s in manifest.services) addComponentNode(nodes, "service", s)
        for (r in manifest.receivers) addComponentNode(nodes, "receiver", r)
        for (p in rewrittenProviders) {
            val attrs = mutableListOf(
                XmlAttr(ANDROID_NS_URI, "name", p.name),
                XmlAttr(ANDROID_NS_URI, "authorities", p.authorities ?: ""),
                XmlAttr(ANDROID_NS_URI, "exported", if (p.exported) "true" else "false",
                    typedValue = if (p.exported) -1 else 0, dataType = TYPE_INT_BOOLEAN)
            )
            if (p.grantUriPermissions) {
                attrs.add(XmlAttr(ANDROID_NS_URI, "grantUriPermissions", "true",
                    typedValue = -1, dataType = TYPE_INT_BOOLEAN))
            }
            nodes.add(Node.ElemStart(null, "provider", attrs))
            // meta-data 编码在 MIUI 上导致安装失败，暂时禁用
            // ManifestRewriter 路径会保留原始 meta-data，此处仅影响加密 APK 的回退路径
            nodes.add(Node.ElemEnd(null, "provider"))
        }

        nodes.add(Node.ElemEnd(null, "application"))
        nodes.add(Node.ElemEnd(null, "manifest"))
        nodes.add(Node.NsEnd("android", ANDROID_NS_URI))

        return encodeBinary(pool, attrNames.size, nodes)
    }

    /**
     * 构建组件的完整属性列表 — 供 launcher activity 和 addComponentNode 共用
     */
    private fun buildComponentAttrs(
        component: ManifestParser.ComponentInfo,
        forceExported: Boolean? = null
    ): List<XmlAttr> {
        val attrs = mutableListOf(XmlAttr(ANDROID_NS_URI, "name", component.name))
        val exported = forceExported ?: component.exported
        attrs.add(XmlAttr(ANDROID_NS_URI, "exported", if (exported) "true" else "false",
            typedValue = if (exported) -1 else 0, dataType = TYPE_INT_BOOLEAN))
        if (!component.enabled) {
            attrs.add(XmlAttr(ANDROID_NS_URI, "enabled", "false", typedValue = 0, dataType = TYPE_INT_BOOLEAN))
        }
        if (component.process != null) attrs.add(XmlAttr(ANDROID_NS_URI, "process", component.process))
        if (component.themeId != 0) attrs.add(XmlAttr(ANDROID_NS_URI, "theme", "", typedValue = component.themeId, dataType = TYPE_REFERENCE))
        if (component.launchMode != null) attrs.add(XmlAttr(ANDROID_NS_URI, "launchMode", component.launchMode,
            typedValue = launchModeToTypedValue(component.launchMode), dataType = TYPE_INT_DEC))
        if (component.configChanges != null) attrs.add(XmlAttr(ANDROID_NS_URI, "configChanges", component.configChanges,
            typedValue = configChangesToBitmask(component.configChanges), dataType = TYPE_INT_DEC))
        if (component.screenOrientation != null) attrs.add(XmlAttr(ANDROID_NS_URI, "screenOrientation", component.screenOrientation,
            typedValue = screenOrientationToTypedValue(component.screenOrientation), dataType = TYPE_INT_DEC))
        if (component.windowSoftInputMode != null) attrs.add(XmlAttr(ANDROID_NS_URI, "windowSoftInputMode", component.windowSoftInputMode,
            typedValue = softInputModeToBitmask(component.windowSoftInputMode), dataType = TYPE_INT_DEC))
        if (component.taskAffinity != null) attrs.add(XmlAttr(ANDROID_NS_URI, "taskAffinity", component.taskAffinity))
        if (component.permission != null) attrs.add(XmlAttr(ANDROID_NS_URI, "permission", component.permission))
        if (component.stateNotNeeded) attrs.add(XmlAttr(ANDROID_NS_URI, "stateNotNeeded", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        if (component.noHistory) attrs.add(XmlAttr(ANDROID_NS_URI, "noHistory", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        if (component.allowTaskReparenting) attrs.add(XmlAttr(ANDROID_NS_URI, "allowTaskReparenting", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        if (component.clearTaskOnLaunch) attrs.add(XmlAttr(ANDROID_NS_URI, "clearTaskOnLaunch", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        if (component.finishOnTaskLaunch) attrs.add(XmlAttr(ANDROID_NS_URI, "finishOnTaskLaunch", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        return attrs
    }

    private fun addComponentNode(nodes: MutableList<Node>, tag: String, component: ManifestParser.ComponentInfo) {
        nodes.add(Node.ElemStart(null, tag, buildComponentAttrs(component)))
        nodes.add(Node.ElemEnd(null, tag))
    }

    // ── 整数编码辅助：字符串 → typedValue ──────────────────────────

    private fun launchModeToTypedValue(value: String): Int = when (value) {
        "singleTop" -> 1
        "singleTask" -> 2
        "singleInstance" -> 3
        "singleInstancePerTask" -> 4
        else -> 0
    }

    private fun screenOrientationToTypedValue(value: String): Int = when (value) {
        "unspecified" -> -1
        "landscape" -> 0
        "portrait" -> 1
        "user" -> 2
        "behind" -> 3
        "sensor" -> 4
        "nosensor" -> 5
        "sensorLandscape" -> 6
        "sensorPortrait" -> 7
        "reverseLandscape" -> 8
        "reversePortrait" -> 9
        "fullSensor" -> 10
        "userLandscape" -> 11
        "userPortrait" -> 12
        "fullUser" -> 13
        "locked" -> 14
        else -> -1
    }

    private fun configChangesToBitmask(value: String): Int {
        var mask = 0
        for (flag in value.split("|")) {
            mask += when (flag.trim()) {
                "mcc" -> 0x0001
                "mnc" -> 0x0002
                "locale" -> 0x0004
                "touchscreen" -> 0x0008
                "keyboard" -> 0x0010
                "keyboardHidden" -> 0x0020
                "navigation" -> 0x0040
                "orientation" -> 0x0080
                "screenLayout" -> 0x0100
                "uiMode" -> 0x0200
                "screenSize" -> 0x0400
                "smallestScreenSize" -> 0x0800
                "density" -> 0x1000
                "layoutDirection" -> 0x2000
                "colorMode" -> 0x4000
                "fontScale" -> 0x8000
                "fontWeightAdjustment" -> 0x10000
                "grammaticalGender" -> 0x10000000
                else -> {
                    // 尝试解析十六进制数字 (如 "0x80000000")
                    if (flag.trim().startsWith("0x")) {
                        flag.trim().removePrefix("0x").toIntOrNull(16) ?: 0
                    } else 0
                }
            }
        }
        return mask
    }

    private fun softInputModeToBitmask(value: String): Int {
        var mask = 0
        for (flag in value.split("|")) {
            mask += when (flag.trim()) {
                "stateUnchanged" -> 0x01
                "stateHidden" -> 0x02
                "stateAlwaysHidden" -> 0x03
                "stateVisible" -> 0x04
                "stateAlwaysVisible" -> 0x05
                "adjustResize" -> 0x10
                "adjustPan" -> 0x20
                "adjustNothing" -> 0x30
                "isForwardNavigation" -> 0x100
                else -> 0
            }
        }
        return mask
    }

    private fun buildApplicationAttrs(config: StubConfig, applicationClass: String?, applicationThemeId: Int): List<XmlAttr> {
        val attrs = mutableListOf(XmlAttr(ANDROID_NS_URI, "appComponentFactory", "com.multiapp.core.loader.LoaderFactory"))
        if (applicationClass != null) {
            attrs.add(XmlAttr(ANDROID_NS_URI, "name", applicationClass))
        }
        // theme 以 TYPE_REFERENCE 写入原 app 的资源 ID。运行时 addAssetPath(origin.apk)
        // 已挂载原 app 资源，该 ID 解析到原 app 自己的 theme。0 表示原 app 未声明，则不写。
        if (applicationThemeId != 0) {
            attrs.add(XmlAttr(ANDROID_NS_URI, "theme", "", typedValue = applicationThemeId, dataType = TYPE_REFERENCE))
        }
        attrs.add(XmlAttr(ANDROID_NS_URI, "label", config.stubPackageName))
        attrs.add(XmlAttr(ANDROID_NS_URI, "extractNativeLibs", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        attrs.add(XmlAttr(ANDROID_NS_URI, "debuggable", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN))
        return attrs
    }

    /**
     * 编码完整的二进制 XML 文档
     *
     * 使用 ByteBuffer (little-endian) 编码所有数据，包括 XML body。
     * DataOutputStream 是 big-endian，不能用于 Android 二进制 XML。
     */
    private fun encodeBinary(pool: List<String>, attrNameCount: Int, nodes: List<Node>): ByteArray {
        val strPoolBytes = encodeStringPool(pool)
        val resMapBytes = encodeResourceMap(pool.take(attrNameCount))
        val idx = pool.withIndex().associate { (i, s) -> s to i }

        // Pre-calculate body size
        var bodySize = 0
        var elemCount = 0
        var totalAttrs = 0
        for (n in nodes) {
            bodySize += when (n) {
                is Node.NsStart, is Node.NsEnd -> 24
                is Node.ElemStart -> { elemCount++; totalAttrs += n.attrs.size; 36 + n.attrs.size * 20 }
                is Node.ElemEnd -> 24
            }
        }
        Timber.d("BinaryXmlEncoder: bodySize=$bodySize, nodes=${nodes.size}, elems=$elemCount, attrs=$totalAttrs")

        val body = ByteBuffer.allocate(bodySize).order(ByteOrder.LITTLE_ENDIAN)

        for (n in nodes) {
            when (n) {
                is Node.NsStart -> {
                    body.putShort(RES_XML_START_NAMESPACE_TYPE.toShort())
                    body.putShort(16)
                    body.putInt(24)
                    body.putInt(1) // lineNumber
                    body.putInt(-1) // comment
                    body.putInt(idx[n.prefix]!!)
                    body.putInt(idx[n.uri]!!)
                }
                is Node.NsEnd -> {
                    body.putShort(RES_XML_END_NAMESPACE_TYPE.toShort())
                    body.putShort(16)
                    body.putInt(24)
                    body.putInt(1)
                    body.putInt(-1)
                    body.putInt(idx[n.prefix]!!)
                    body.putInt(idx[n.uri]!!)
                }
                is Node.ElemStart -> {
                    // AOSP ResXMLTree_attrExt layout:
                    //   chunk_header(8) + lineNumber(4) + comment(4) = 16 (nodeHeaderSize)
                    //   ns(4) + name(4) + attrStart(2)+attrSize(2)+attrCount(2)+idIdx(2)+classIdx(2)+styleIdx(2) = 20 (attrExtSize)
                    //   attrs * 20
                    val nodeHeaderSize: Short = 16
                    val attrExtSize: Short = 20
                    val chunkSize = 36 + n.attrs.size * 20
                    body.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
                    body.putShort(nodeHeaderSize)
                    body.putInt(chunkSize)
                    body.putInt(1) // lineNumber
                    body.putInt(-1) // comment
                    body.putInt(if (n.ns != null) idx[n.ns]!! else -1)
                    body.putInt(idx[n.name]!!)
                    body.putShort(attrExtSize) // attributeStart
                    body.putShort(20) // attributeSize
                    body.putShort(n.attrs.size.toShort())
                    body.putShort(0) // idIndex
                    body.putShort(0) // classIndex
                    body.putShort(0) // styleIndex
                    for (a in n.attrs) {
                        body.putInt(if (a.ns != null) idx[a.ns]!! else -1)
                        body.putInt(idx[a.name]!!)
                        val rawIdx = if (a.rawValue.isEmpty() && a.dataType != TYPE_STRING) {
                            -1
                        } else {
                            idx[a.rawValue] ?: -1
                        }
                        body.putInt(rawIdx) // rawValue in string pool
                        body.putShort(4) // typedValueSize
                        body.put(0) // res0
                        body.put(a.dataType.toByte()) // dataType
                        body.putInt(if (a.dataType == TYPE_STRING) rawIdx else a.typedValue)
                    }
                }
                is Node.ElemEnd -> {
                    body.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
                    body.putShort(16)
                    body.putInt(24)
                    body.putInt(1)
                    body.putInt(-1)
                    body.putInt(if (n.ns != null) idx[n.ns]!! else -1)
                    body.putInt(idx[n.name]!!)
                }
            }
        }

        body.flip()
        val bodyBytes = ByteArray(body.remaining())
        body.get(bodyBytes)
        val totalSize = 8 + strPoolBytes.size + resMapBytes.size + bodyBytes.size
        val result = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        result.putShort(RES_XML_TYPE.toShort())
        result.putShort(8)
        result.putInt(totalSize)
        result.put(strPoolBytes)
        result.put(resMapBytes)
        result.put(bodyBytes)
        return result.array()
    }

    /**
     * 编码 StringPool chunk (UTF-8)
     *
     * 每个字符串: [charCount] [byteCount] [UTF-8 bytes] [null terminator]
     * 每个条目必须 4 字节对齐，Android ResStringPool 解析器要求此对齐。
     */
    private fun encodeStringPool(strings: List<String>): ByteArray {
        val count = strings.size
        val strData = ByteArrayOutputStream()
        val offsets = IntArray(count)

        for ((i, s) in strings.withIndex()) {
            offsets[i] = strData.size()
            val utf8Bytes = s.toByteArray(StandardCharsets.UTF_8)
            val charCount = s.length  // ASCII-only: correct for package names/URIs; non-BMP text would need UTF-16 code unit count
            val byteCount = utf8Bytes.size

            // Write char count (1-2 bytes, high bit = 2-byte mode)
            if (charCount > 0x7F) {
                strData.write(0x80 or ((charCount shr 8) and 0x7F))
                strData.write(charCount and 0xFF)
            } else {
                strData.write(charCount)
            }
            // Write byte count (1-2 bytes)
            if (byteCount > 0x7F) {
                strData.write(0x80 or ((byteCount shr 8) and 0x7F))
                strData.write(byteCount and 0xFF)
            } else {
                strData.write(byteCount)
            }
            // UTF-8 encoded bytes
            strData.write(utf8Bytes)
            // null terminator
            strData.write(0)
            // Per-string 4-byte alignment padding (Android ResStringPool parser requires this)
            val currentSize = strData.size()
            val alignPad = (4 - (currentSize % 4)) % 4
            for (p in 0 until alignPad) strData.write(0)
        }

        val dataBytes = strData.toByteArray()
        val stringsStart = 28 + count * 4
        val unpaddedTotal = stringsStart + dataBytes.size
        // Chunk size must be 4-byte aligned (Android requirement)
        val total = (unpaddedTotal + 3) and 0xFFFFFFFC.toInt()
        val paddingBytes = total - unpaddedTotal

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE.toShort())
        buf.putShort(28) // headerSize (28 = 8 base + 4*5 fields)
        buf.putInt(total)
        buf.putInt(count) // stringCount
        buf.putInt(0) // styleCount
        buf.putInt(0x00000100) // flags: UTF-8
        buf.putInt(stringsStart) // stringsStart
        buf.putInt(0) // stylesStart
        for (o in offsets) buf.putInt(o)
        buf.put(dataBytes)
        // Pad to 4-byte alignment
        for (i in 0 until paddingBytes) buf.put(0)
        return buf.array()
    }

    /**
     * 编码 ResourceMap chunk
     *
     * 每个字符串对应一个 uint32 资源 ID (0 表示无映射)。
     * 必须与 string pool 等长。
     */
    private fun encodeResourceMap(strings: List<String>): ByteArray {
        val ids = strings.map { ANDROID_ATTR_IDS[it] ?: 0 }
        if (ids.all { it == 0 }) return ByteArray(0)
        val total = 8 + ids.size * 4
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
        buf.putShort(8)
        buf.putInt(total)
        for (id in ids) buf.putInt(id)
        return buf.array()
    }

    private fun parseResourceRef(ref: String): Int {
        if (ref.startsWith("@0x") || ref.startsWith("@0X")) {
            return ref.substring(3).toIntOrNull(16) ?: 0
        }
        return 0
    }
}
