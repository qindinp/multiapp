package com.multiapp.core.manifest

/**
 * 生成 Stub APK 的 AndroidManifest.xml (二进制 XML)
 *
 * 完全参照 aapt 输出格式：
 * - StringPool: UTF-8 (flags=0x100)
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
        sb.appendLine("""    <uses-sdk android:minSdkVersion="${manifest.minSdkVersion}" android:targetSdkVersion="${manifest.targetSdkVersion}" />""")
        for (permission in manifest.permissions) {
            sb.appendLine("""    <uses-permission android:name="$permission" />""")
        }
        sb.appendLine("""    <application""")
        sb.appendLine("""        android:appComponentFactory="com.multiapp.core.loader.LoaderFactory"""")
        if (manifest.applicationClass != null) {
            sb.appendLine("""        android:name="${manifest.applicationClass}"""")
        }
        sb.appendLine("""        android:label="${config.stubPackageName}" android:extractNativeLibs="true">""")
        sb.appendLine("""        <activity android:name="${launcherActivity.name}" android:exported="true" android:enabled="true">""")
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
        val (rewrittenProviders, _) = authorityRewriter.rewrite(manifest.providers, config.instanceId, config.authorityMap)
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
        return BinaryXmlEncoder().encodeFromManifest(stubPackageName, manifest, launcherActivity, config)
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
