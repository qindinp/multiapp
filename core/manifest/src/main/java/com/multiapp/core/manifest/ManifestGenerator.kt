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
        for (permission in manifest.permissions) {
            sb.appendLine("""    <uses-permission android:name="$permission" />""")
        }
        sb.appendLine("""    <application""")
        sb.appendLine("""        android:appComponentFactory="com.multiapp.core.loader.LoaderFactory"""")
        sb.appendLine("""        android:label="${config.stubPackageName}"""")
        sb.appendLine("""        android:icon="@mipmap/ic_launcher">""")
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
        )
        baos.write(intToBytes(0x00080180)) // RES_XML_RESOURCE_MAP_TYPE
        baos.write(intToBytes(8 + androidAttrs.size * 4))
        for (id in androidAttrs) baos.write(intToBytes(id))

        // 根节点 <manifest>
        writeStartElement(baos, stringIndex("manifest"), listOf(
            Attr(1, stringIndex("package"), 0x03000008, stringIndex(stubPackageName))
        ))

        // <uses-permission>
        for (perm in manifest.permissions) {
            writeStartElement(baos, stringIndex("uses-permission"), listOf(
                Attr(1, stringIndex("name"), 0x03000008, stringIndex(perm))
            ))
            writeEndElement(baos, stringIndex("uses-permission"))
        }

        // <application>
        writeStartElement(baos, stringIndex("application"), listOf(
            Attr(1, stringIndex("appComponentFactory"), 0x03000008, stringIndex("com.multiapp.core.loader.LoaderFactory")),
            Attr(1, stringIndex("label"), 0x03000008, stringIndex(config.stubPackageName)),
            Attr(-1, stringIndex("icon"), 0x01000000, -1) // @mipmap/ic_launcher → resource ref placeholder
        ))

        // Launcher activity
        writeStartElement(baos, stringIndex("activity"), listOf(
            Attr(-1, stringIndex("name"), 0x03000008, stringIndex(launcherActivity.name)),
            Attr(-1, stringIndex("exported"), 0x12000000, 1)
        ))
        writeStartElement(baos, stringIndex("intent-filter"), emptyList())
        writeStartElement(baos, stringIndex("action"), listOf(
            Attr(1, stringIndex("name"), 0x03000008, stringIndex("android.intent.action.MAIN"))
        ))
        writeEndElement(baos, stringIndex("action"))
        writeStartElement(baos, stringIndex("category"), listOf(
            Attr(1, stringIndex("name"), 0x03000008, stringIndex("android.intent.category.LAUNCHER"))
        ))
        writeEndElement(baos, stringIndex("category"))
        writeEndElement(baos, stringIndex("intent-filter"))
        writeEndElement(baos, stringIndex("activity"))

        // Other activities
        for (activity in manifest.activities) {
            if (activity.name == launcherActivity.name) continue
            writeStartElement(baos, stringIndex("activity"), listOf(
                Attr(-1, stringIndex("name"), 0x03000008, stringIndex(activity.name)),
                Attr(-1, stringIndex("exported"), 0x12000000, if (activity.exported) 1 else 0)
            ))
            writeEndElement(baos, stringIndex("activity"))
        }

        // Services
        for (service in manifest.services) {
            val attrs = mutableListOf(
                Attr(-1, stringIndex("name"), 0x03000008, stringIndex(service.name)),
                Attr(-1, stringIndex("exported"), 0x12000000, if (service.exported) 1 else 0)
            )
            if (service.process != null) {
                attrs.add(Attr(-1, stringIndex("process"), 0x03000008, stringIndex(service.process)))
            }
            writeStartElement(baos, stringIndex("service"), attrs)
            writeEndElement(baos, stringIndex("service"))
        }

        // Receivers
        for (receiver in manifest.receivers) {
            writeStartElement(baos, stringIndex("receiver"), listOf(
                Attr(-1, stringIndex("name"), 0x03000008, stringIndex(receiver.name)),
                Attr(-1, stringIndex("exported"), 0x12000000, if (receiver.exported) 1 else 0)
            ))
            writeEndElement(baos, stringIndex("receiver"))
        }

        // Providers
        for (provider in rewrittenProviders) {
            writeStartElement(baos, stringIndex("provider"), listOf(
                Attr(-1, stringIndex("name"), 0x03000008, stringIndex(provider.name)),
                Attr(-1, stringIndex("authorities"), 0x03000008, stringIndex(provider.authorities)),
                Attr(-1, stringIndex("exported"), 0x12000000, if (provider.exported) 1 else 0)
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

    private data class Attr(val ns: Int, val name: Int, val type: Int, val value: Int)

    private fun writeStartElement(out: ByteArrayOutputStream, nameIdx: Int, attrs: List<Attr>) {
        out.write(intToBytes(0x00100102)) // RES_XML_START_ELEMENT_TYPE
        val size = 16 + attrs.size * 20
        out.write(intToBytes(size))
        out.write(intToBytes(0)) // lineNumber
        out.write(intToBytes(0xFFFFFFFF.toInt()) ) // comment
        out.write(intToBytes(0xFFFFFFFF.toInt())) // ns
        out.write(intToBytes(nameIdx))
        out.write(shortToBytes(0x0014)) // attrStart
        out.write(shortToBytes(0x0014)) // attrSize
        out.write(shortToBytes(attrs.size))
        out.write(shortToBytes(0)) // idIndex
        out.write(shortToBytes(0)) // classIndex
        out.write(shortToBytes(0)) // styleIndex
        for (attr in attrs) {
            out.write(intToBytes(attr.ns))
            out.write(intToBytes(attr.name))
            out.write(intToBytes(0xFFFFFFFF.toInt())) // rawValue
            out.write(shortToBytes(attr.type))
            out.write(shortToBytes(0)) // reserved
            out.write(intToBytes(attr.value))
        }
    }

    private fun writeEndElement(out: ByteArrayOutputStream, nameIdx: Int) {
        out.write(intToBytes(0x00100103)) // RES_XML_END_ELEMENT_TYPE
        out.write(intToBytes(16))
        out.write(intToBytes(0)) // lineNumber
        out.write(intToBytes(0xFFFFFFFF.toInt())) // comment
        out.write(intToBytes(0xFFFFFFFF.toInt())) // ns
        out.write(intToBytes(nameIdx))
    }

    private fun buildStringPool(strings: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        val strBytes = strings.map { it.toByteArray(Charsets.UTF_16LE) }
        val totalChars = strBytes.sumOf { it.size / 2 }
        val styleCount = 0

        // Header
        baos.write(intToBytes(0x001C0001)) // RES_STRING_POOL_TYPE
        val headerSize = 28
        // 计算 strings 区域大小
        val stringsStart = headerSize + strings.size * 4 + 4 // 4 bytes per string offset + padding
        var dataSize = 0
        for (b in strBytes) {
            dataSize += 2 + b.size + 2 // uint16 len + data + null terminator
        }
        val totalSize = stringsStart + dataSize
        baos.write(intToBytes(totalSize))
        baos.write(intToBytes(strings.size)) // stringCount
        baos.write(intToBytes(styleCount))
        baos.write(intToBytes(0)) // flags: UTF-16
        baos.write(intToBytes(stringsStart)) // stringsStart
        baos.write(intToBytes(0)) // stylesStart

        // String offsets
        var offset = 0
        for (b in strBytes) {
            baos.write(intToBytes(offset))
            offset += 2 + b.size + 2
        }
        baos.write(intToBytes(0)) // padding

        // String data
        for (b in strBytes) {
            baos.write(shortToBytes(b.size / 2)) // char count
            baos.write(b)
            baos.write(shortToBytes(0)) // null terminator
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
