package com.multiapp.core.manifest

/**
 * 生成 Stub APK 的 AndroidManifest.xml (文本 XML)
 * 后续 Phase 3 中 StubBuilder 会编译为二进制 XML
 */
class ManifestGenerator {

    private val authorityRewriter = AuthorityRewriter()
    private val binaryEncoder = BinaryXmlEncoder()

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

        // 权限声明
        for (permission in manifest.permissions) {
            sb.appendLine("""    <uses-permission android:name="$permission" />""")
        }

        sb.appendLine("""    <application""")
        sb.appendLine("""        android:appComponentFactory="com.multiapp.core.loader.LoaderFactory"""")
        sb.appendLine("""        android:label="${config.stubPackageName}"""")
        sb.appendLine("""        android:icon="@mipmap/ic_launcher">""")

        // Launcher Activity
        sb.appendLine("""        <activity""")
        sb.appendLine("""            android:name="${launcherActivity.name}"""")
        sb.appendLine("""            android:exported="true">""")
        sb.appendLine("""            <intent-filter>""")
        sb.appendLine("""                <action android:name="android.intent.action.MAIN" />""")
        sb.appendLine("""                <category android:name="android.intent.category.LAUNCHER" />""")
        sb.appendLine("""            </intent-filter>""")
        sb.appendLine("""        </activity>""")

        // 其他 Activities
        for (activity in manifest.activities) {
            if (activity.name == launcherActivity.name) continue
            appendComponent(sb, "activity", activity.name, activity.exported, activity.process)
        }

        // Services
        for (service in manifest.services) {
            appendComponent(sb, "service", service.name, service.exported, service.process)
        }

        // Receivers
        for (receiver in manifest.receivers) {
            appendComponent(sb, "receiver", receiver.name, receiver.exported, receiver.process)
        }

        // Providers (rewritten authorities)
        val (rewrittenProviders, _) = authorityRewriter.rewrite(
            manifest.providers,
            config.instanceId
        )
        for (provider in rewrittenProviders) {
            sb.appendLine("""        <provider""")
            sb.appendLine("""            android:name="${provider.name}"""")
            sb.appendLine("""            android:authorities="${provider.authorities}"""")
            sb.appendLine("""            android:exported="${provider.exported}" />""")
        }

        sb.appendLine("""    </application>""")
        sb.appendLine("""</manifest>""")

        return sb.toString()
    }

    fun generateBytes(
        stubPackageName: String,
        manifest: ManifestParser.ParsedManifest,
        launcherActivity: ManifestParser.ComponentInfo,
        config: StubConfig
    ): ByteArray {
        return binaryEncoder.encodeFromManifest(stubPackageName, manifest, launcherActivity, config)
    }

    private fun appendComponent(
        sb: StringBuilder,
        tag: String,
        name: String,
        exported: Boolean,
        process: String?
    ) {
        sb.appendLine("""        <$tag""")
        sb.appendLine("""            android:name="$name"""")
        if (exported) {
            sb.appendLine("""            android:exported="true" />""")
        } else if (process != null) {
            sb.appendLine("""            android:process="$process" />""")
        } else {
            sb.appendLine("""            android:exported="false" />""")
        }
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
