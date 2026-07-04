package com.multiapp.core.manifest

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ManifestParserTest {

    private lateinit var parser: ManifestParser

    @BeforeEach
    fun setUp() {
        parser = ManifestParser(mockk<Context>(relaxed = true))
    }

    // -- 辅助 XML 模板 --

    private fun fullManifestXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.testapp"
            android:versionCode="1"
            android:versionName="1.0">

            <uses-permission android:name="android.permission.INTERNET"/>
            <uses-permission android:name="android.permission.CAMERA"/>
            <uses-permission android:name="android.permission.READ_CONTACTS"/>

            <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>

            <application android:name=".MyApplication" android:label="TestApp">

                <activity android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>

                <activity android:name=".SettingsActivity" android:exported="false"/>

                <service android:name=".MyService"
                    android:exported="false"
                    android:process=":remote"/>

                <receiver android:name=".MyReceiver" android:exported="true"/>

                <provider android:name=".MyProvider"
                    android:authorities="com.example.testapp.provider"
                    android:exported="false"
                    android:permission="com.example.testapp.permission.PROVIDER"
                    android:grantUriPermissions="true"/>

                <provider android:name=".FileProvider"
                    android:authorities="com.example.testapp.fileprovider"
                    android:exported="false"/>

            </application>
        </manifest>
    """.trimIndent()

    private fun minimalManifestXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.minimal">

            <application>
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    private fun emptyManifestXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.empty">

            <application/>
        </manifest>
    """.trimIndent()

    private fun processManifestXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.process">

            <application>
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>

                <service android:name=".RemoteService"
                    android:process=":remote"
                    android:exported="false"/>

                <service android:name=".BgService"
                    android:process="com.example.process:bg"
                    android:exported="false"/>

                <receiver android:name=".PushReceiver"
                    android:process=":push"
                    android:exported="false"/>

                <activity android:name=".IsolatedActivity"
                    android:process=":isolated"
                    android:exported="false"/>
            </application>
        </manifest>
    """.trimIndent()

    // -- 1. 解析有效 manifest XML 提取所有组件 --

    @Nested
    inner class ParseValidManifest {

        @Test
        fun `解析完整 manifest 提取所有 activity`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(2, result.activities.size)
            val names = result.activities.map { it.name }.toSet()
            assertTrue(names.contains(".MainActivity"))
            assertTrue(names.contains(".SettingsActivity"))
        }

        @Test
        fun `解析完整 manifest 提取所有 service`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(1, result.services.size)
            assertEquals(".MyService", result.services[0].name)
        }

        @Test
        fun `解析完整 manifest 提取所有 receiver`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(1, result.receivers.size)
            assertEquals(".MyReceiver", result.receivers[0].name)
        }

        @Test
        fun `解析完整 manifest 提取所有 provider`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(2, result.providers.size)
            val names = result.providers.map { it.name }.toSet()
            assertTrue(names.contains(".MyProvider"))
            assertTrue(names.contains(".FileProvider"))
        }

        @Test
        fun `解析完整 manifest 提取 application class`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(".MyApplication", result.applicationClass)
        }

        @Test
        fun `解析完整 manifest 提取 application label`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals("TestApp", result.applicationLabel)
        }

        @Test
        fun `解析完整 manifest 提取 package name`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals("com.example.testapp", result.packageName)
        }

        @Test
        fun `提取 exported 属性`() {
            val result = parser.parseFromXml(fullManifestXml())
            val mainActivity = result.activities.find { it.name == ".MainActivity" }
            assertNotNull(mainActivity)
            assertTrue(mainActivity!!.exported)
            val settingsActivity = result.activities.find { it.name == ".SettingsActivity" }
            assertNotNull(settingsActivity)
            assertFalse(settingsActivity!!.exported)
        }

        @Test
        fun `提取 intent filter`() {
            val result = parser.parseFromXml(fullManifestXml())
            val mainActivity = result.activities.find { it.name == ".MainActivity" }
            assertNotNull(mainActivity)
            assertEquals(1, mainActivity!!.intentFilters.size)
            val filter = mainActivity.intentFilters[0]
            assertTrue(filter.actions.contains("android.intent.action.MAIN"))
            assertTrue(filter.categories.contains("android.intent.category.LAUNCHER"))
        }

        @Test
        fun `提取 provider authorities`() {
            val result = parser.parseFromXml(fullManifestXml())
            val provider = result.providers.find { it.name == ".MyProvider" }
            assertNotNull(provider)
            assertEquals("com.example.testapp.provider", provider!!.authorities)
        }

        @Test
        fun `extracts provider policy attributes`() {
            val result = parser.parseFromXml(fullManifestXml())
            val provider = result.providers.find { it.name == ".MyProvider" }
            assertNotNull(provider)
            assertEquals("com.example.testapp.permission.PROVIDER", provider!!.permission)
            assertTrue(provider.grantUriPermissions)
            assertFalse(provider.exported)
        }
    }

    // -- 2. 处理空 manifest --

    @Nested
    inner class EmptyManifest {

        @Test
        fun `空 application 返回空组件列表`() {
            val result = parser.parseFromXml(emptyManifestXml())
            assertEquals("com.example.empty", result.packageName)
            assertTrue(result.activities.isEmpty())
            assertTrue(result.services.isEmpty())
            assertTrue(result.receivers.isEmpty())
            assertTrue(result.providers.isEmpty())
            assertTrue(result.permissions.isEmpty())
        }

        @Test
        fun `空 application 的 applicationClass 为 null`() {
            val result = parser.parseFromXml(emptyManifestXml())
            assertNull(result.applicationClass)
        }
    }

    // -- 3. 处理只有 launcher activity 的最小 manifest --

    @Nested
    inner class MinimalManifest {

        @Test
        fun `最小 manifest 正确提取 launcher activity`() {
            val result = parser.parseFromXml(minimalManifestXml())
            assertEquals("com.example.minimal", result.packageName)
            assertEquals(1, result.activities.size)
            assertEquals(".MainActivity", result.activities[0].name)
            assertTrue(result.activities[0].exported)
            assertEquals(1, result.activities[0].intentFilters.size)
        }

        @Test
        fun `最小 manifest 其他组件列表为空`() {
            val result = parser.parseFromXml(minimalManifestXml())
            assertTrue(result.services.isEmpty())
            assertTrue(result.receivers.isEmpty())
            assertTrue(result.providers.isEmpty())
            assertTrue(result.permissions.isEmpty())
            assertNull(result.applicationClass)
        }
    }

    // -- 4. 提取 uses-permission --

    @Nested
    inner class ExtractPermissions {

        @Test
        fun `提取所有权限声明`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(3, result.permissions.size)
            assertTrue(result.permissions.contains("android.permission.INTERNET"))
            assertTrue(result.permissions.contains("android.permission.CAMERA"))
            assertTrue(result.permissions.contains("android.permission.READ_CONTACTS"))
        }

        @Test
        fun `无权限声明时返回空列表`() {
            val result = parser.parseFromXml(minimalManifestXml())
            assertTrue(result.permissions.isEmpty())
        }
    }

    // -- 5. 提取 minSdkVersion 和 targetSdkVersion --

    @Nested
    inner class ExtractSdkVersions {

        @Test
        fun `提取声明的 minSdkVersion`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(26, result.minSdkVersion)
        }

        @Test
        fun `提取声明的 targetSdkVersion`() {
            val result = parser.parseFromXml(fullManifestXml())
            assertEquals(34, result.targetSdkVersion)
        }

        @Test
        fun `无 uses-sdk 时使用默认值`() {
            val result = parser.parseFromXml(minimalManifestXml())
            assertEquals(28, result.minSdkVersion, "无 uses-sdk 时 minSdkVersion 默认为 28")
            assertEquals(36, result.targetSdkVersion, "无 uses-sdk 时 targetSdkVersion 默认为 36")
        }
    }

    // -- 6. 处理含 process 属性的组件 --

    @Nested
    inner class ProcessAttribute {

        @Test
        fun `提取 service 的 process 属性`() {
            val result = parser.parseFromXml(fullManifestXml())
            val service = result.services.find { it.name == ".MyService" }
            assertNotNull(service)
            assertEquals(":remote", service!!.process)
        }

        @Test
        fun `无 process 属性时为 null`() {
            val result = parser.parseFromXml(fullManifestXml())
            val mainActivity = result.activities.find { it.name == ".MainActivity" }
            assertNotNull(mainActivity)
            assertNull(mainActivity!!.process)
        }

        @Test
        fun `提取多个组件的不同 process 属性`() {
            val result = parser.parseFromXml(processManifestXml())
            val remoteService = result.services.find { it.name == ".RemoteService" }
            assertNotNull(remoteService)
            assertEquals(":remote", remoteService!!.process)
            val bgService = result.services.find { it.name == ".BgService" }
            assertNotNull(bgService)
            assertEquals("com.example.process:bg", bgService!!.process)
            val receiver = result.receivers.find { it.name == ".PushReceiver" }
            assertNotNull(receiver)
            assertEquals(":push", receiver!!.process)
            val isolatedActivity = result.activities.find { it.name == ".IsolatedActivity" }
            assertNotNull(isolatedActivity)
            assertEquals(":isolated", isolatedActivity!!.process)
        }

        @Test
        fun `相对进程名以冒号开头保持原样`() {
            val result = parser.parseFromXml(processManifestXml())
            val remoteService = result.services.find { it.process == ":remote" }
            assertNotNull(remoteService, "应保留 :remote 格式的进程名")
        }

        @Test
        fun `全限定进程名保持原样`() {
            val result = parser.parseFromXml(processManifestXml())
            val bgService = result.services.find { it.process == "com.example.process:bg" }
            assertNotNull(bgService, "应保留全限定进程名")
        }
    }
}
