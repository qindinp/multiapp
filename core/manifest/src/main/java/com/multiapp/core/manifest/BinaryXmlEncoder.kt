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
            "debuggable" to 0x0101000f,
            "exported" to 0x01010010,
            "process" to 0x01010011,
            "authorities" to 0x01010018,
            "appComponentFactory" to 0x0101057a,
            "permission" to 0x01010006,
            "enabled" to 0x0101000e,
            "extractNativeLibs" to 0x01010419
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
        val pool = mutableListOf<String>()
        val nodes = mutableListOf<Node>()

        fun str(s: String): Int {
            val idx = pool.indexOf(s)
            if (idx >= 0) return idx
            pool.add(s)
            return pool.size - 1
        }

        // Pre-register ALL strings used in attributes and elements
        str("android")
        str(ANDROID_NS_URI)
        str("manifest")
        str(stubPackageName)
        str("package")
        str("versionCode")
        str("versionName")
        str("1.0")

        str("uses-sdk")
        str("minSdkVersion")
        str("targetSdkVersion")
        val minSdkStr = manifest.minSdkVersion.toString()
        val targetSdkStr = manifest.targetSdkVersion.toString()
        str(minSdkStr)
        str(targetSdkStr)

        str("uses-permission")
        for (p in manifest.permissions) str(p)

        str("application")
        str("appComponentFactory")
        str("com.multiapp.core.loader.LoaderFactory")
        str("label")
        str(config.stubPackageName)
        str("icon")
        str("@mipmap/ic_launcher")
        str("extractNativeLibs")
        str("enabled")

        str("activity")
        str("intent-filter")
        str("action")
        str("category")
        str("android.intent.action.MAIN")
        str("android.intent.category.LAUNCHER")
        str("name")
        str("exported")
        str("true")
        str("false")
        str("process")

        str(launcherActivity.name)

        for (a in manifest.activities) {
            if (a.name != launcherActivity.name) str(a.name)
            a.process?.let { str(it) }
        }
        str("service")
        for (s in manifest.services) { str(s.name); s.process?.let { str(it) } }
        str("receiver")
        for (r in manifest.receivers) { str(r.name); r.process?.let { str(it) } }

        str("provider")
        str("authorities")
        str("") // empty string for null authorities
        val authorityRewriter = AuthorityRewriter()
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId, config.authorityMap)
        for (p in rewrittenProviders) { str(p.name); p.authorities?.let { str(it) } }

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

        nodes.add(Node.ElemStart(null, "application", listOf(
            XmlAttr(ANDROID_NS_URI, "appComponentFactory", "com.multiapp.core.loader.LoaderFactory"),
            XmlAttr(ANDROID_NS_URI, "label", config.stubPackageName),
            XmlAttr(ANDROID_NS_URI, "icon", "@mipmap/ic_launcher"),
            XmlAttr(ANDROID_NS_URI, "extractNativeLibs", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN)
        )))

        // Launcher activity
        nodes.add(Node.ElemStart(null, "activity", listOf(
            XmlAttr(ANDROID_NS_URI, "name", launcherActivity.name),
            XmlAttr(ANDROID_NS_URI, "exported", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN),
            XmlAttr(ANDROID_NS_URI, "enabled", "true", typedValue = -1, dataType = TYPE_INT_BOOLEAN)
        )))
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
            addComponentNode(nodes, "activity", a.name, a.exported, a.process)
        }
        for (s in manifest.services) addComponentNode(nodes, "service", s.name, s.exported, s.process)
        for (r in manifest.receivers) addComponentNode(nodes, "receiver", r.name, r.exported, r.process)
        for (p in rewrittenProviders) {
            nodes.add(Node.ElemStart(null, "provider", listOf(
                XmlAttr(ANDROID_NS_URI, "name", p.name),
                XmlAttr(ANDROID_NS_URI, "authorities", p.authorities ?: ""),
                XmlAttr(ANDROID_NS_URI, "exported", if (p.exported) "true" else "false",
                    typedValue = if (p.exported) -1 else 0, dataType = TYPE_INT_BOOLEAN)
            )))
            nodes.add(Node.ElemEnd(null, "provider"))
        }

        nodes.add(Node.ElemEnd(null, "application"))
        nodes.add(Node.ElemEnd(null, "manifest"))
        nodes.add(Node.NsEnd("android", ANDROID_NS_URI))

        return encodeBinary(pool, nodes)
    }

    private fun addComponentNode(nodes: MutableList<Node>, tag: String, name: String, exported: Boolean, process: String?) {
        val attrs = mutableListOf(XmlAttr(ANDROID_NS_URI, "name", name))
        attrs.add(XmlAttr(ANDROID_NS_URI, "exported", if (exported) "true" else "false",
            typedValue = if (exported) -1 else 0, dataType = TYPE_INT_BOOLEAN))
        if (process != null) attrs.add(XmlAttr(ANDROID_NS_URI, "process", process))
        nodes.add(Node.ElemStart(null, tag, attrs))
        nodes.add(Node.ElemEnd(null, tag))
    }

    /**
     * 编码完整的二进制 XML 文档
     *
     * 使用 ByteBuffer (little-endian) 编码所有数据，包括 XML body。
     * DataOutputStream 是 big-endian，不能用于 Android 二进制 XML。
     */
    private fun encodeBinary(pool: List<String>, nodes: List<Node>): ByteArray {
        val strPoolBytes = encodeStringPool(pool)
        val resMapBytes = encodeResourceMap(pool)
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
                    val nodeHeaderSize: Short = 16  // ResChunk_header(8) + lineNumber(4) + comment(4)
                    val attrExtSize: Short = 20      // ns(4) + name(4) + attrStart(2) + attrSize(2) + attrCount(2) + idIndex(2) + classIndex(2) + styleIndex(2)
                    val chunkSize = 16 + attrExtSize + n.attrs.size * 20
                    body.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
                    body.putShort(nodeHeaderSize) // headerSize: apksig splits header/contents here
                    body.putInt(chunkSize)
                    body.putInt(1) // lineNumber
                    body.putInt(-1) // comment
                    body.putInt(if (n.ns != null) idx[n.ns]!! else -1)
                    body.putInt(idx[n.name]!!)
                    body.putShort(attrExtSize) // attributeStart: offset from attrExt start to first attr
                    body.putShort(20) // attributeSize: each attribute is 20 bytes
                    body.putShort(n.attrs.size.toShort())
                    body.putShort(0) // idIndex
                    body.putShort(0) // classIndex
                    body.putShort(0) // styleIndex
                    for (a in n.attrs) {
                        body.putInt(if (a.ns != null) idx[a.ns]!! else -1)
                        body.putInt(idx[a.name]!!)
                        val rawIdx = idx[a.rawValue] ?: -1
                        body.putInt(rawIdx) // rawValue in string pool
                        body.putShort(8) // typedValueSize
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
     * 编码 StringPool chunk (UTF-16LE)
     *
     * 每个字符串: [uint16 charCount] [UTF-16LE bytes] [uint16 0x0000]
     * 每个条目必须 4 字节对齐，Android ResStringPool 解析器要求此对齐。
     */
    private fun encodeStringPool(strings: List<String>): ByteArray {
        val count = strings.size
        val strData = ByteArrayOutputStream()
        val offsets = IntArray(count)

        for ((i, s) in strings.withIndex()) {
            offsets[i] = strData.size()
            val utf8Bytes = s.toByteArray(StandardCharsets.UTF_8)
            val charCount = s.length  // character count (not byte count)
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
}
