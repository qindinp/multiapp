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
     * 生成二进制 XML（设备上无 aapt 时使用）
     *
     * 关键规则（参照 aapt 输出）：
     * 1. StringPool 前 N 个字符串是属性名，顺序与 ResourceMap 对应
     * 2. ResourceMap 只包含前 N 个字符串（属性名）的 resource ID
     * 3. 后面的字符串（元素名、值等）不在 ResourceMap 中
     */
    fun generateBytes(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): ByteArray {
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId)

        // ─── 构建字符串池 ───
        // 属性名必须在前 N 个位置，顺序与 ResourceMap 对应
        val attrNames = mutableListOf<String>()  // 属性名（ResourceMap 对应）
        val otherStrings = mutableListOf<String>() // 其他字符串（元素名、值等）

        val attrNameIndex = { s: String ->
            val idx = attrNames.indexOf(s)
            if (idx >= 0) idx else { attrNames.add(s); attrNames.size - 1 }
        }
        val otherStringIndex = { s: String ->
            val idx = otherStrings.indexOf(s)
            if (idx >= 0) idx else { otherStrings.add(s); otherStrings.size - 1 }
        }

        // 属性名（必须在 ResourceMap 中，顺序与 ResourceMap 一致）
        // 参照 aapt: compileSdkVersion, compileSdkVersionCodename, minSdkVersion, targetSdkVersion, name, appComponentFactory, label, exported, authorities
        val COMPILE_SDK = attrNameIndex("compileSdkVersion")
        val COMPILE_SDK_CODENAME = attrNameIndex("compileSdkVersionCodename")
        val MIN_SDK = attrNameIndex("minSdkVersion")
        val TARGET_SDK = attrNameIndex("targetSdkVersion")
        val NAME = attrNameIndex("name")
        val APP_COMP_FACTORY = attrNameIndex("appComponentFactory")
        val LABEL = attrNameIndex("label")
        val EXPORTED = attrNameIndex("exported")
        val AUTHORITIES = attrNameIndex("authorities")

        // 其他字符串
        val NS_URI = otherStringIndex("http://schemas.android.com/apk/res/android")
        val PKG_NAME = otherStringIndex(stubPackageName)
        val MANIFEST = otherStringIndex("manifest")
        val PACKAGE = otherStringIndex("package")
        val USES_SDK = otherStringIndex("uses-sdk")
        val USES_PERM = otherStringIndex("uses-permission")
        val APPLICATION = otherStringIndex("application")
        val COMP_FACTORY_VAL = otherStringIndex("com.multiapp.core.loader.LoaderFactory")
        val ACTIVITY = otherStringIndex("activity")
        val INTENT_FILTER = otherStringIndex("intent-filter")
        val ACTION = otherStringIndex("action")
        val MAIN_ACTION = otherStringIndex("android.intent.action.MAIN")
        val CATEGORY = otherStringIndex("android.intent.category")
        val LAUNCHER_CAT = otherStringIndex("android.intent.category.LAUNCHER")
        val SERVICE = otherStringIndex("service")
        val RECEIVER = otherStringIndex("receiver")
        val PROVIDER = otherStringIndex("provider")
        val SDK_VAL = otherStringIndex("36")
        val CODENAME_VAL = otherStringIndex("16")
        val PLATFORM_CODE = otherStringIndex("platformBuildVersionCode")
        val PLATFORM_NAME = otherStringIndex("platformBuildVersionName")

        // 动态字符串
        for (perm in manifest.permissions) otherStringIndex(perm)
        otherStringIndex(launcherActivity.name)
        for (activity in manifest.activities) { if (activity.name != launcherActivity.name) otherStringIndex(activity.name) }
        for (service in manifest.services) otherStringIndex(service.name)
        for (receiver in manifest.receivers) otherStringIndex(receiver.name)
        for (provider in rewrittenProviders) { otherStringIndex(provider.name); otherStringIndex(provider.authorities) }

        // 合并字符串池：属性名在前，其他在后
        val allStrings = attrNames + otherStrings
        val attrOffset = 0
        val otherOffset = attrNames.size

        // 索引转换函数
        val attrIdx = { localIdx: Int -> attrOffset + localIdx }
        val otherIdx = { localIdx: Int -> otherOffset + localIdx }

        val out = ByteArrayOutputStream()

        // === XML Header ===
        out.writeLE32(0x00080003)
        out.writeLE32(0)

        // === StringPool ===
        val spBytes = buildStringPool(allStrings)
        out.write(spBytes)

        // === ResourceMap ===
        val resourceMap = intArrayOf(
            0x01010572, 0x01010573, 0x0101020c, 0x01010270,
            0x01010003, 0x0101057a, 0x01010001, 0x01010010, 0x0101001d
        )
        out.writeLE32(0x00080180)
        out.writeLE32(8 + resourceMap.size * 4)
        for (id in resourceMap) out.writeLE32(id)

        // === XML Tree ===
        writeStartNs(out, otherIdx(NS_URI))

        // <manifest> — 属性顺序和类型必须与 aapt 一致
        // aapt 顺序: compileSdkVersion(android), compileSdkVersionCodename(android), package, platformBuildVersionCode, platformBuildVersionName
        writeStartElement(out, otherIdx(MANIFEST), -1, listOf(
            AttrVal(otherIdx(NS_URI), attrIdx(COMPILE_SDK), 0x10, 36),                    // android:compileSdkVersion = 36 (INT_DEC)
            AttrVal(otherIdx(NS_URI), attrIdx(COMPILE_SDK_CODENAME), 0x03, otherIdx(CODENAME_VAL)), // android:compileSdkVersionCodename = "16" (STRING)
            AttrVal(-1, otherIdx(PACKAGE), 0x03, otherIdx(PKG_NAME)),                     // package = "..." (STRING, no ns)
            AttrVal(-1, otherIdx(PLATFORM_CODE), 0x10, 36),                                // platformBuildVersionCode = 36 (INT_DEC)
            AttrVal(-1, otherIdx(PLATFORM_NAME), 0x10, 16)                                 // platformBuildVersionName = 16 (INT_DEC)
        ))

        // <uses-sdk>
        writeStartElement(out, otherIdx(USES_SDK), -1, listOf(
            AttrVal(otherIdx(NS_URI), attrIdx(MIN_SDK), 0x10, config.deviceIdentity.sdkInt),
            AttrVal(otherIdx(NS_URI), attrIdx(TARGET_SDK), 0x10, config.deviceIdentity.sdkInt)
        ))
        writeEndElement(out, otherIdx(USES_SDK))

        // <uses-permission>
        for (perm in manifest.permissions) {
            writeStartElement(out, otherIdx(USES_PERM), -1, listOf(
                AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, otherStringIndex(perm) + otherOffset)
            ))
            writeEndElement(out, otherIdx(USES_PERM))
        }

        // <application>
        writeStartElement(out, otherIdx(APPLICATION), -1, listOf(
            AttrVal(otherIdx(NS_URI), attrIdx(LABEL), 0x03, otherIdx(PKG_NAME)),
            AttrVal(otherIdx(NS_URI), attrIdx(APP_COMP_FACTORY), 0x03, otherIdx(COMP_FACTORY_VAL))
        ))

        // Launcher activity
        val launcherNameIdx = otherStringIndex(launcherActivity.name) + otherOffset
        writeStartElement(out, otherIdx(ACTIVITY), -1, listOf(
            AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, launcherNameIdx),
            AttrVal(otherIdx(NS_URI), attrIdx(EXPORTED), 0x12, 1)
        ))
        writeStartElement(out, otherIdx(INTENT_FILTER), -1, emptyList())
        writeStartElement(out, otherIdx(ACTION), -1, listOf(
            AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, otherIdx(MAIN_ACTION))
        ))
        writeEndElement(out, otherIdx(ACTION))
        writeStartElement(out, otherIdx(CATEGORY), -1, listOf(
            AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, otherIdx(LAUNCHER_CAT))
        ))
        writeEndElement(out, otherIdx(CATEGORY))
        writeEndElement(out, otherIdx(INTENT_FILTER))
        writeEndElement(out, otherIdx(ACTIVITY))

        // Other activities
        for (activity in manifest.activities) {
            if (activity.name == launcherActivity.name) continue
            val nameIdx = otherStringIndex(activity.name) + otherOffset
            writeStartElement(out, otherIdx(ACTIVITY), -1, listOf(
                AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, nameIdx),
                AttrVal(otherIdx(NS_URI), attrIdx(EXPORTED), 0x12, if (activity.exported) 1 else 0)
            ))
            writeEndElement(out, otherIdx(ACTIVITY))
        }

        // Services
        for (service in manifest.services) {
            val nameIdx = otherStringIndex(service.name) + otherOffset
            writeStartElement(out, otherIdx(SERVICE), -1, listOf(
                AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, nameIdx),
                AttrVal(otherIdx(NS_URI), attrIdx(EXPORTED), 0x12, if (service.exported) 1 else 0)
            ))
            writeEndElement(out, otherIdx(SERVICE))
        }

        // Receivers
        for (receiver in manifest.receivers) {
            val nameIdx = otherStringIndex(receiver.name) + otherOffset
            writeStartElement(out, otherIdx(RECEIVER), -1, listOf(
                AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, nameIdx),
                AttrVal(otherIdx(NS_URI), attrIdx(EXPORTED), 0x12, if (receiver.exported) 1 else 0)
            ))
            writeEndElement(out, otherIdx(RECEIVER))
        }

        // Providers
        for (provider in rewrittenProviders) {
            val nameIdx = otherStringIndex(provider.name) + otherOffset
            val authIdx = otherStringIndex(provider.authorities) + otherOffset
            writeStartElement(out, otherIdx(PROVIDER), -1, listOf(
                AttrVal(otherIdx(NS_URI), attrIdx(NAME), 0x03, nameIdx),
                AttrVal(otherIdx(NS_URI), attrIdx(AUTHORITIES), 0x03, authIdx),
                AttrVal(otherIdx(NS_URI), attrIdx(EXPORTED), 0x12, if (provider.exported) 1 else 0)
            ))
            writeEndElement(out, otherIdx(PROVIDER))
        }

        writeEndElement(out, otherIdx(APPLICATION))
        writeEndElement(out, otherIdx(MANIFEST))
        writeEndNs(out, otherIdx(NS_URI))

        val result = out.toByteArray()
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
        // 结构: type(4) + size(4) + lineNumber(4) + comment(4) + ns(4) + name(4) + attrStart(2) + attrSize(2) + attrCount(2) + idIndex(2) + classIndex(2) + styleIndex(2)
        // = 16 + 4 + 4 + 2 + 2 + 2 + 2 + 2 + 2 = 36 bytes header before attributes
        out.writeLE32(0x00100102) // type
        val headerSize = 36  // 16 (chunk header) + 4 (ns) + 4 (name) + 12 (6 x uint16)
        val size = headerSize + attrs.size * 20
        out.writeLE32(size)       // size
        out.writeLE32(1)          // lineNumber
        out.writeLE32(-1)         // comment
        out.writeLE32(nsIdx)      // element ns
        out.writeLE32(nameIdx)    // element name
        // attrStart: 从 ResXMLTree_attrExt 开始（offset 16，即 ns 字段）到第一个属性的偏移
        // ns(4) + name(4) + 6*uint16(12) = 20
        out.writeLE16(20)           // attrStart = 20 (从 ns 算起)
        out.writeLE16(0x0014)       // attrSize = 20 (sizeof each ResXMLTree_attribute)
        out.writeLE16(attrs.size)   // attrCount
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
        out.writeLE32(24)         // size (16 + ns(4) + name(4) = 24)
        out.writeLE32(1)          // lineNumber
        out.writeLE32(-1)         // comment
        out.writeLE32(-1)         // ns (0xFFFFFFFF = none)
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
