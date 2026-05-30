package com.multiapp.core.manifest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 生成 Stub APK 的 AndroidManifest.xml (二进制 XML)
 *
 * 输出 Android 编译后的二进制 XML 格式，APK 可直接被系统解析。
 * 二进制 XML 格式: StringPool + ResourceIds + XML Tree Nodes
 */
class ManifestGenerator {

    private val authorityRewriter = AuthorityRewriter()

    fun generate(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): String {
        // 文本格式用于调试，实际 APK 使用 generateBytes()
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"""")
        sb.appendLine("""    package="$stubPackageName">""")
        sb.appendLine("""    <uses-sdk android:minSdkVersion="${config.deviceIdentity.sdkInt}" android:targetSdkVersion="${config.deviceIdentity.sdkInt}" />""")
        for (permission in manifest.permissions) {
            sb.appendLine("""    <uses-permission android:name="$permission" />""")
        }
        sb.appendLine("""    <application""")
        sb.appendLine("""        android:appComponentFactory="com.multiapp.core.loader.LoaderFactory"""")
        sb.appendLine("""        android:label="${config.stubPackageName}">""")
        sb.appendLine("""        <activity android:name="${launcherActivity.name}" android:exported="true">""")
        sb.appendLine("""            <intent-filter>""")
        sb.appendLine("""                <action android:name="android.intent.action.MAIN" />""")
        sb.appendLine("""                <category android:name="android.intent.category.LAUNCHER" />""")
        sb.appendLine("""            </intent-filter>""")
        sb.appendLine("""        </activity>""")
        for (activity in manifest.activities) {
            if (activity.name == launcherActivity.name) continue
            sb.appendLine("""        <activity android:name="${activity.name}" android:exported="${activity.exported}" />""")
        }
        for (service in manifest.services) {
            sb.appendLine("""        <service android:name="${service.name}" android:exported="${service.exported}" />""")
        }
        for (receiver in manifest.receivers) {
            sb.appendLine("""        <receiver android:name="${receiver.name}" android:exported="${receiver.exported}" />""")
        }
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId)
        for (provider in rewrittenProviders) {
            sb.appendLine("""        <provider android:name="${provider.name}" android:authorities="${provider.authorities}" android:exported="${provider.exported}" />""")
        }
        sb.appendLine("""    </application>""")
        sb.appendLine("""</manifest>""")
        return sb.toString()
    }

    /**
     * 生成二进制 XML 格式的 AndroidManifest.xml
     *
     * 二进制 XML 结构:
     * 1. Header (magic + fileSize)
     * 2. StringPool (所有字符串)
     * 3. ResourceIds (属性资源 ID)
     * 4. XML Tree Nodes (StartElement, EndElement, CData)
     */
    fun generateBytes(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): ByteArray {
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId)

        // 收集所有字符串
        val strings = mutableListOf<String>()
        val stringIndex = { s: String ->
            val idx = strings.indexOf(s)
            if (idx >= 0) idx else { strings.add(s); strings.size - 1 }
        }

        // 预填充字符串池
        stringIndex("") // 0: empty
        stringIndex("http://schemas.android.com/apk/res/android") // 1: android ns
        stringIndex(stubPackageName) // 2: package
        stringIndex("manifest") // 3
        stringIndex("package") // 4
        stringIndex("uses-permission") // 5
        stringIndex("name") // 6
        stringIndex("application") // 7
        stringIndex("uses-sdk") // 25
        stringIndex("minSdkVersion") // 26
        stringIndex("targetSdkVersion") // 27
        stringIndex("appComponentFactory") // 8
        stringIndex("com.multiapp.core.loader.LoaderFactory") // 9
        stringIndex("label") // 10
        stringIndex(config.stubPackageName) // 11
        stringIndex("icon") // 12
        stringIndex("activity") // 13
        stringIndex("exported") // 14
        stringIndex("intent-filter") // 15
        stringIndex("action") // 16
        stringIndex("android.intent.action.MAIN") // 17
        stringIndex("category") // 18
        stringIndex("android.intent.category.LAUNCHER") // 19
        stringIndex("service") // 20
        stringIndex("receiver") // 21
        stringIndex("provider") // 22
        stringIndex("authorities") // 23
        stringIndex("process") // 24

        // 动态字符串: 权限、组件名、provider authorities
        for (perm in manifest.permissions) stringIndex(perm)
        stringIndex(launcherActivity.name)
        for (activity in manifest.activities) { if (activity.name != launcherActivity.name) stringIndex(activity.name) }
        for (service in manifest.services) stringIndex(service.name)
        for (receiver in manifest.receivers) stringIndex(receiver.name)
        for (provider in rewrittenProviders) { stringIndex(provider.name); stringIndex(provider.authorities) }

        val baos = ByteArrayOutputStream()
        val out = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

        // 记录占位符位置，后面回填
        val headerSizePos = 4 // fileSize 位置

        // 写入 XML header (先写占位符)
        baos.write(intToBytes(0x00080003)) // RES_XML_TYPE
        baos.write(intToBytes(0)) // fileSize placeholder

        // StringPool
        val stringPoolBytes = buildStringPool(strings)
        baos.write(stringPoolBytes)

        // ResourceIds: android 属性 ID
        val androidAttrs = intArrayOf(
            0x01010003, // name
            0x01010001, // label
            0x01010002, // icon
            0x010102e0, // appComponentFactory
            0x01010010, // exported
            0x0101001c, // process
            0x0101001d, // authorities
            0x0101020c, // minSdkVersion
            0x01010270, // targetSdkVersion
        )
        baos.write(intToBytes(0x00080180)) // RES_XML_RESOURCE_MAP_TYPE
        baos.write(intToBytes(8 + androidAttrs.size * 4))
        for (id in androidAttrs) baos.write(intToBytes(id))

        // 根节点 <manifest>
        // android namespace 的 ns 索引是 1（"http://schemas.android.com/apk/res/android"）
        // 无 namespace 的属性 ns = 0xFFFFFFFF
        val NS_ANDROID = 1
        val NS_NONE = 0xFFFFFFFF.toInt()

        writeStartElement(baos, stringIndex("manifest"), listOf(
            Attr(NS_NONE, stringIndex("package"), 0x03, stringIndex(stubPackageName))
        ))

        // <uses-sdk> — 必须包含，否则系统无法确定最低平台版本
        writeStartElement(baos, stringIndex("uses-sdk"), listOf(
            Attr(NS_ANDROID, stringIndex("minSdkVersion"), 0x10, config.deviceIdentity.sdkInt),
            Attr(NS_ANDROID, stringIndex("targetSdkVersion"), 0x10, config.deviceIdentity.sdkInt)
        ))
        writeEndElement(baos, stringIndex("uses-sdk"))

        // <uses-permission>
        for (perm in manifest.permissions) {
            writeStartElement(baos, stringIndex("uses-permission"), listOf(
                Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex(perm))
            ))
            writeEndElement(baos, stringIndex("uses-permission"))
        }

        // <application>
        writeStartElement(baos, stringIndex("application"), listOf(
            Attr(NS_ANDROID, stringIndex("appComponentFactory"), 0x03, stringIndex("com.multiapp.core.loader.LoaderFactory")),
            Attr(NS_ANDROID, stringIndex("label"), 0x03, stringIndex(config.stubPackageName))
        ))

        // Launcher activity
        writeStartElement(baos, stringIndex("activity"), listOf(
            Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex(launcherActivity.name)),
            Attr(NS_ANDROID, stringIndex("exported"), 0x12, 1)
        ))
        writeStartElement(baos, stringIndex("intent-filter"), emptyList())
        writeStartElement(baos, stringIndex("action"), listOf(
            Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex("android.intent.action.MAIN"))
        ))
        writeEndElement(baos, stringIndex("action"))
        writeStartElement(baos, stringIndex("category"), listOf(
            Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex("android.intent.category.LAUNCHER"))
        ))
        writeEndElement(baos, stringIndex("category"))
        writeEndElement(baos, stringIndex("intent-filter"))
        writeEndElement(baos, stringIndex("activity"))

        // Other activities
        for (activity in manifest.activities) {
            if (activity.name == launcherActivity.name) continue
            writeStartElement(baos, stringIndex("activity"), listOf(
                Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex(activity.name)),
                Attr(NS_ANDROID, stringIndex("exported"), 0x12, if (activity.exported) 1 else 0)
            ))
            writeEndElement(baos, stringIndex("activity"))
        }

        // Services
        for (service in manifest.services) {
            val attrs = mutableListOf(
                Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex(service.name)),
                Attr(NS_ANDROID, stringIndex("exported"), 0x12, if (service.exported) 1 else 0)
            )
            if (service.process != null) {
                attrs.add(Attr(NS_ANDROID, stringIndex("process"), 0x03, stringIndex(service.process)))
            }
            writeStartElement(baos, stringIndex("service"), attrs)
            writeEndElement(baos, stringIndex("service"))
        }

        // Receivers
        for (receiver in manifest.receivers) {
            writeStartElement(baos, stringIndex("receiver"), listOf(
                Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex(receiver.name)),
                Attr(NS_ANDROID, stringIndex("exported"), 0x12, if (receiver.exported) 1 else 0)
            ))
            writeEndElement(baos, stringIndex("receiver"))
        }

        // Providers
        for (provider in rewrittenProviders) {
            writeStartElement(baos, stringIndex("provider"), listOf(
                Attr(NS_ANDROID, stringIndex("name"), 0x03, stringIndex(provider.name)),
                Attr(NS_ANDROID, stringIndex("authorities"), 0x03, stringIndex(provider.authorities)),
                Attr(NS_ANDROID, stringIndex("exported"), 0x12, if (provider.exported) 1 else 0)
            ))
            writeEndElement(baos, stringIndex("provider"))
        }

        writeEndElement(baos, stringIndex("application"))
        writeEndElement(baos, stringIndex("manifest"))

        val result = baos.toByteArray()

        // 回填 fileSize
        val sizeBuf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuf.putInt(4, result.size)

        return result
    }

    /**
     * Res_value 结构：
     *   size: uint16 (always 8)
     *   res0: uint8 (always 0)
     *   dataType: uint8
     *   data: uint32
     */
    private data class Attr(val ns: Int, val name: Int, val dataType: Int, val data: Int)

    private fun writeStartElement(out: ByteArrayOutputStream, nameIdx: Int, attrs: List<Attr>) {
        // ResXMLTree_startElement
        out.write(intToBytes(0x00100102)) // type: RES_XML_START_ELEMENT_TYPE
        val size = 16 + attrs.size * 20   // header(16) + attrs
        out.write(intToBytes(size))
        out.write(intToBytes(0)) // lineNumber
        out.write(intToBytes(0xFFFFFFFF.toInt())) // comment (none)
        out.write(intToBytes(0xFFFFFFFF.toInt())) // ns (none for element itself)
        out.write(intToBytes(nameIdx))
        out.write(shortToBytes(0x0014)) // attrStart (20 = sizeof(ResXMLTree_attribute))
        out.write(shortToBytes(0x0014)) // attrSize
        out.write(shortToBytes(attrs.size)) // attrCount
        out.write(shortToBytes(0)) // idIndex
        out.write(shortToBytes(0)) // classIndex
        out.write(shortToBytes(0)) // styleIndex

        // 写入每个属性
        for (attr in attrs) {
            out.write(intToBytes(attr.ns))   // ns (uint32)
            out.write(intToBytes(attr.name)) // name (uint32)
            out.write(intToBytes(0xFFFFFFFF.toInt())) // rawValue (uint32, none)
            // Res_value 结构
            out.write(shortToBytes(8))           // size = 8
            out.write(byteArrayOf(0))            // res0 = 0
            out.write(byteArrayOf(attr.dataType.toByte())) // dataType
            out.write(intToBytes(attr.data))     // data (uint32)
        }
    }

    private fun writeEndElement(out: ByteArrayOutputStream, nameIdx: Int) {
        // ResXMLTree_endElement
        out.write(intToBytes(0x00100103)) // type: RES_XML_END_ELEMENT_TYPE
        out.write(intToBytes(16))         // size
        out.write(intToBytes(0))          // lineNumber
        out.write(intToBytes(0xFFFFFFFF.toInt())) // comment
        out.write(intToBytes(0xFFFFFFFF.toInt())) // ns
        out.write(intToBytes(nameIdx))
    }

    private fun buildStringPool(strings: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        val headerSize = 28
        val stringCount = strings.size
        val styleCount = 0
        val UTF8_FLAG = 0x00000100

        // 计算每个字符串的 UTF-8 编码字节
        val strByteArrays = strings.map { it.toByteArray(Charsets.UTF_8) }

        // 计算 strings 区域偏移（header + offsets + padding）
        // offsets: 每个 string 4 bytes，需要 4 字节对齐
        val offsetsSize = stringCount * 4
        val stringsStart = headerSize + offsetsSize

        // 计算每个字符串的数据大小（UTF-8 格式：charLen + byteLen + data + \0）
        val stringDataSizes = strByteArrays.map { bytes ->
            val charLen = bytes.size // UTF-8 字符数近似等于字节数（对 ASCII）
            val byteLen = bytes.size
            // charLen 编码：1 byte（<128）或 2 bytes（>=128）
            val charLenSize = if (charLen < 128) 1 else 2
            // byteLen 编码：1 byte（<128）或 2 bytes（>=128）
            val byteLenSize = if (byteLen < 128) 1 else 2
            charLenSize + byteLenSize + byteLen + 1 // +1 for null terminator
        }

        val totalDataSize = stringDataSizes.sum()
        val totalSize = stringsStart + totalDataSize

        // 写入 header
        baos.write(intToBytes(0x001C0001)) // RES_STRING_POOL_TYPE
        baos.write(intToBytes(totalSize))
        baos.write(intToBytes(stringCount))
        baos.write(intToBytes(styleCount))
        baos.write(intToBytes(UTF8_FLAG)) // UTF-8 flag
        baos.write(intToBytes(stringsStart))
        baos.write(intToBytes(0)) // stylesStart

        // 写入 string offsets（相对于 stringsStart）
        var offset = 0
        for (size in stringDataSizes) {
            baos.write(intToBytes(offset))
            offset += size
        }

        // 写入字符串数据（UTF-8 格式）
        for ((index, bytes) in strByteArrays.withIndex()) {
            val charLen = bytes.size
            val byteLen = bytes.size

            // 写入 charLen（UTF-8 字符数）
            if (charLen < 128) {
                baos.write(byteArrayOf(charLen.toByte()))
            } else {
                baos.write(byteArrayOf((0x80 or (charLen shr 7)).toByte(), (charLen and 0x7F).toByte()))
            }

            // 写入 byteLen（UTF-8 字节数）
            if (byteLen < 128) {
                baos.write(byteArrayOf(byteLen.toByte()))
            } else {
                baos.write(byteArrayOf((0x80 or (byteLen shr 7)).toByte(), (byteLen and 0x7F).toByte()))
            }

            // 写入字符串数据
            baos.write(bytes)

            // null terminator
            baos.write(0)
        }

        return baos.toByteArray()
    }

    private fun intToBytes(v: Int): ByteArray {
        return byteArrayOf(
            (v and 0xFF).toByte(),
            (v shr 8 and 0xFF).toByte(),
            (v shr 16 and 0xFF).toByte(),
            (v shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToBytes(v: Int): ByteArray {
        return byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    }
}

data class StubConfig(
    val instanceId: String,
    val stubPackageName: String,
    val originalPackageName: String,
    val launchActivity: String,
    val originalSignatures: List<String>,
    val authorityMap: Map<String, String>,
    val deviceIdentity: DeviceIdentityConfig,
    val patchedDexPaths: List<String> = emptyList()
)

data class DeviceIdentityConfig(
    val imei: String,
    val androidId: String,
    val macAddress: String,
    val serial: String,
    val buildModel: String,
    val buildManufacturer: String,
    val buildFingerprint: String,
    val buildBrand: String = "",
    val buildDevice: String = "",
    val buildProduct: String = "",
    val versionRelease: String = "16",
    val sdkInt: Int = 36
)
