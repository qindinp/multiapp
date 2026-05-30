package com.multiapp.core.manifest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 生成 Stub APK 的 AndroidManifest.xml (二进制 XML)
 *
 * 完全参照 aapt 输出格式：
 * - StringPool: UTF-16LE (flags=0x000)
 * - ResourceMap: 按属性出现顺序排列
 * - Res_value: size(16) + res0(8) + dataType(8) + data(32)
 */
class ManifestGenerator {

    private val authorityRewriter = AuthorityRewriter()

    fun generate(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): String {
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
     * 生成二进制 XML（fallback，设备上无 aapt 时使用）
     */
    fun generateBytes(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): ByteArray {
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId)

        // 收集所有字符串（按出现顺序）
        val strings = mutableListOf<String>()
        val stringIndex = { s: String ->
            val idx = strings.indexOf(s)
            if (idx >= 0) idx else { strings.add(s); strings.size - 1 }
        }

        // android namespace 必须是第一个字符串（index=0）
        val NS_URI = stringIndex("http://schemas.android.com/apk/res/android")
        // 其他字符串
        val PKG_NAME = stringIndex(stubPackageName)
        val MANIFEST = stringIndex("manifest")
        val PACKAGE = stringIndex("package")
        val USES_SDK = stringIndex("uses-sdk")
        val MIN_SDK = stringIndex("minSdkVersion")
        val TARGET_SDK = stringIndex("targetSdkVersion")
        val USES_PERM = stringIndex("uses-permission")
        val NAME = stringIndex("name")
        val APPLICATION = stringIndex("application")
        val APP_COMP_FACTORY = stringIndex("appComponentFactory")
        val COMP_FACTORY_VAL = stringIndex("com.multiapp.core.loader.LoaderFactory")
        val LABEL = stringIndex("label")
        val ACTIVITY = stringIndex("activity")
        val EXPORTED = stringIndex("exported")
        val INTENT_FILTER = stringIndex("intent-filter")
        val ACTION = stringIndex("action")
        val MAIN_ACTION = stringIndex("android.intent.action.MAIN")
        val CATEGORY = stringIndex("android.intent.category")
        val LAUNCHER_CAT = stringIndex("android.intent.category.LAUNCHER")
        val SERVICE = stringIndex("service")
        val RECEIVER = stringIndex("receiver")
        val PROVIDER = stringIndex("provider")
        val AUTHORITIES = stringIndex("authorities")

        // 动态字符串
        for (perm in manifest.permissions) stringIndex(perm)
        stringIndex(launcherActivity.name)
        for (activity in manifest.activities) { if (activity.name != launcherActivity.name) stringIndex(activity.name) }
        for (service in manifest.services) stringIndex(service.name)
        for (receiver in manifest.receivers) stringIndex(receiver.name)
        for (provider in rewrittenProviders) { stringIndex(provider.name); stringIndex(provider.authorities) }

        val out = ByteArrayOutputStream()

        // === XML Header ===
        out.writeLE32(0x00080003) // RES_XML_TYPE
        out.writeLE32(0)          // fileSize placeholder (回填)

        // === StringPool (UTF-16LE, 参照 aapt 输出) ===
        val spBytes = buildStringPool(strings)
        out.write(spBytes)

        // === ResourceMap ===
        // 按属性在文档中首次出现的顺序排列
        val resourceMap = intArrayOf(
            0x01010572, // compileSdkVersion (aapt 自动添加)
            0x01010573, // compileSdkVersionCodename (aapt 自动添加)
            0x0101020c, // minSdkVersion
            0x01010270, // targetSdkVersion
            0x01010003, // name
            0x01010001, // label
            0x0101057a, // appComponentFactory
            0x01010010, // exported
            0x0101001d  // authorities
        )
        out.writeLE32(0x00080180) // RES_XML_RESOURCE_MAP_TYPE
        out.writeLE32(8 + resourceMap.size * 4)
        for (id in resourceMap) out.writeLE32(id)

        // === XML Tree ===
        val NS_ANDROID = NS_URI  // android namespace string index
        val NS_NONE = -1         // 0xFFFFFFFF

        // <manifest>
        writeStartNs(out, NS_URI)
        writeStartElement(out, MANIFEST, NS_NONE, listOf(
            AttrVal(NS_NONE, PACKAGE, 0x03, PKG_NAME),                         // package="..."
            AttrVal(NS_NONE, stringIndex("compileSdkVersion"), 0x10, 36),       // aapt 自动添加
            AttrVal(NS_NONE, stringIndex("compileSdkVersionCodename"), 0x03, stringIndex("16")),
            AttrVal(NS_NONE, stringIndex("platformBuildVersionCode"), 0x03, stringIndex("36")),
            AttrVal(NS_NONE, stringIndex("platformBuildVersionName"), 0x03, stringIndex("16"))
        ))

        // <uses-sdk>
        writeStartElement(out, USES_SDK, NS_NONE, listOf(
            AttrVal(NS_ANDROID, MIN_SDK, 0x10, config.deviceIdentity.sdkInt),
            AttrVal(NS_ANDROID, TARGET_SDK, 0x10, config.deviceIdentity.sdkInt)
        ))
        writeEndElement(out, USES_SDK)

        // <uses-permission>
        for (perm in manifest.permissions) {
            writeStartElement(out, USES_PERM, NS_NONE, listOf(
                AttrVal(NS_ANDROID, NAME, 0x03, stringIndex(perm))
            ))
            writeEndElement(out, USES_PERM)
        }

        // <application>
        writeStartElement(out, APPLICATION, NS_NONE, listOf(
            AttrVal(NS_ANDROID, LABEL, 0x03, PKG_NAME),
            AttrVal(NS_ANDROID, APP_COMP_FACTORY, 0x03, COMP_FACTORY_VAL)
        ))

        // Launcher activity
        writeStartElement(out, ACTIVITY, NS_NONE, listOf(
            AttrVal(NS_ANDROID, NAME, 0x03, stringIndex(launcherActivity.name)),
            AttrVal(NS_ANDROID, EXPORTED, 0x12, 1)
        ))
        // <intent-filter>
        writeStartElement(out, INTENT_FILTER, NS_NONE, emptyList())
        // <action>
        writeStartElement(out, ACTION, NS_NONE, listOf(
            AttrVal(NS_ANDROID, NAME, 0x03, MAIN_ACTION)
        ))
        writeEndElement(out, ACTION)
        // <category>
        writeStartElement(out, CATEGORY, NS_NONE, listOf(
            AttrVal(NS_ANDROID, NAME, 0x03, LAUNCHER_CAT)
        ))
        writeEndElement(out, CATEGORY)
        writeEndElement(out, INTENT_FILTER)
        writeEndElement(out, ACTIVITY)

        // Other activities
        for (activity in manifest.activities) {
            if (activity.name == launcherActivity.name) continue
            writeStartElement(out, ACTIVITY, NS_NONE, listOf(
                AttrVal(NS_ANDROID, NAME, 0x03, stringIndex(activity.name)),
                AttrVal(NS_ANDROID, EXPORTED, 0x12, if (activity.exported) 1 else 0)
            ))
            writeEndElement(out, ACTIVITY)
        }

        // Services
        for (service in manifest.services) {
            writeStartElement(out, SERVICE, NS_NONE, listOf(
                AttrVal(NS_ANDROID, NAME, 0x03, stringIndex(service.name)),
                AttrVal(NS_ANDROID, EXPORTED, 0x12, if (service.exported) 1 else 0)
            ))
            writeEndElement(out, SERVICE)
        }

        // Receivers
        for (receiver in manifest.receivers) {
            writeStartElement(out, RECEIVER, NS_NONE, listOf(
                AttrVal(NS_ANDROID, NAME, 0x03, stringIndex(receiver.name)),
                AttrVal(NS_ANDROID, EXPORTED, 0x12, if (receiver.exported) 1 else 0)
            ))
            writeEndElement(out, RECEIVER)
        }

        // Providers
        for (provider in rewrittenProviders) {
            writeStartElement(out, PROVIDER, NS_NONE, listOf(
                AttrVal(NS_ANDROID, NAME, 0x03, stringIndex(provider.name)),
                AttrVal(NS_ANDROID, AUTHORITIES, 0x03, stringIndex(provider.authorities)),
                AttrVal(NS_ANDROID, EXPORTED, 0x12, if (provider.exported) 1 else 0)
            ))
            writeEndElement(out, PROVIDER)
        }

        writeEndElement(out, APPLICATION)
        writeEndElement(out, MANIFEST)
        writeEndNs(out, NS_URI)

        val result = out.toByteArray()

        // 回填 fileSize
        val sizeBuf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuf.putInt(4, result.size)

        return result
    }

    // ─── XML Node Writers ────────────────────────────────────────

    private data class AttrVal(val ns: Int, val name: Int, val dataType: Int, val data: Int)

    private fun writeStartNs(out: ByteArrayOutputStream, nsUri: Int) {
        out.writeLE32(0x00100100) // RES_XML_START_NAMESPACE_TYPE
        out.writeLE32(24)         // size
        out.writeLE32(1)          // lineNumber
        out.writeLE32(-1)         // comment (0xFFFFFFFF)
        out.writeLE32(-1)         // prefix (none)
        out.writeLE32(nsUri)      // uri
    }

    private fun writeEndNs(out: ByteArrayOutputStream, nsUri: Int) {
        out.writeLE32(0x00100101) // RES_XML_END_NAMESPACE_TYPE
        out.writeLE32(24)         // size
        out.writeLE32(1)          // lineNumber
        out.writeLE32(-1)         // comment
        out.writeLE32(-1)         // prefix
        out.writeLE32(nsUri)      // uri
    }

    private fun writeStartElement(out: ByteArrayOutputStream, nameIdx: Int, nsIdx: Int, attrs: List<AttrVal>) {
        // ResXMLTree_startElement
        out.writeLE32(0x00100102) // type
        val size = 16 + attrs.size * 20
        out.writeLE32(size)       // size
        out.writeLE32(1)          // lineNumber
        out.writeLE32(-1)         // comment
        out.writeLE32(nsIdx)      // element ns
        out.writeLE32(nameIdx)    // element name
        // attrStart, attrSize, attrCount, idIndex, classIndex, styleIndex
        out.writeLE16(0x0014)     // attrStart = sizeof(ResXMLTree_attribute)
        out.writeLE16(0x0014)     // attrSize
        out.writeLE16(attrs.size) // attrCount
        out.writeLE16(0)          // idIndex
        out.writeLE16(0)          // classIndex
        out.writeLE16(0)          // styleIndex

        for (attr in attrs) {
            out.writeLE32(attr.ns)     // ns
            out.writeLE32(attr.name)   // name
            out.writeLE32(-1)          // rawValue (0xFFFFFFFF = none)
            // Res_value
            out.writeLE16(8)           // size
            out.write(0)               // res0
            out.write(attr.dataType)   // dataType
            out.writeLE32(attr.data)   // data
        }
    }

    private fun writeEndElement(out: ByteArrayOutputStream, nameIdx: Int) {
        out.writeLE32(0x00100103) // RES_XML_END_ELEMENT_TYPE
        out.writeLE32(16)         // size
        out.writeLE32(1)          // lineNumber
        out.writeLE32(-1)         // comment
        out.writeLE32(-1)         // ns
        out.writeLE32(nameIdx)    // name
    }

    // ─── StringPool (UTF-16LE, 参照 aapt 输出) ────────────────────

    private fun buildStringPool(strings: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        val headerSize = 28
        val stringCount = strings.size
        val styleCount = 0
        val flags = 0x00000000 // UTF-16LE (与 aapt 输出一致)

        // 计算每个字符串的 UTF-16LE 编码
        val strByteArrays = strings.map { it.toByteArray(Charsets.UTF_16LE) }

        // 计算 strings 区域偏移
        val offsetsSize = stringCount * 4
        val stringsStart = headerSize + offsetsSize

        // 计算每个字符串的数据大小（UTF-16LE 格式：uint16 charLen + UTF-16LE bytes + \0\0）
        val stringDataSizes = strByteArrays.map { bytes ->
            val charLen = bytes.size / 2
            2 + bytes.size + 2 // uint16 charLen + data + null terminator (2 bytes)
        }

        val totalDataSize = stringDataSizes.sum()
        // 4 字节对齐
        val paddedTotalDataSize = (totalDataSize + 3) and 0x7FFFFFFC.toInt()
        val totalSize = stringsStart + paddedTotalDataSize

        // 写入 header
        baos.writeLE32(0x001C0001) // RES_STRING_POOL_TYPE
        baos.writeLE32(totalSize)
        baos.writeLE32(stringCount)
        baos.writeLE32(styleCount)
        baos.writeLE32(flags)
        baos.writeLE32(stringsStart)
        baos.writeLE32(0) // stylesStart

        // 写入 string offsets
        var offset = 0
        for (size in stringDataSizes) {
            baos.writeLE32(offset)
            offset += size
        }

        // 写入字符串数据
        for (bytes in strByteArrays) {
            val charLen = bytes.size / 2
            baos.writeLE16(charLen) // char count
            baos.write(bytes)       // UTF-16LE data
            baos.writeLE16(0)       // null terminator
        }

        // padding 到 4 字节对齐
        val padding = paddedTotalDataSize - totalDataSize
        for (i in 0 until padding) baos.write(0)

        return baos.toByteArray()
    }

    // ─── Little-endian writers ────────────────────────────────────

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLE16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
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
