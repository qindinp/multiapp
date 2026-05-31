package com.multiapp.core.manifest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ComponentExtractorTest {

    private lateinit var extractor: ComponentExtractor

    @BeforeEach
    fun setUp() {
        extractor = ComponentExtractor()
    }

    // -- 辅助工厂方法 --

    private fun createManifest(
        activities: List<ManifestParser.ComponentInfo> = emptyList(),
        services: List<ManifestParser.ComponentInfo> = emptyList(),
        receivers: List<ManifestParser.ComponentInfo> = emptyList(),
        providers: List<ManifestParser.ProviderInfo> = emptyList(),
        permissions: List<String> = emptyList()
    ): ManifestParser.ParsedManifest = ManifestParser.ParsedManifest(
        packageName = "com.example.testapp",
        applicationClass = null,
        activities = activities,
        services = services,
        receivers = receivers,
        providers = providers,
        permissions = permissions
    )

    private fun launcherActivity(
        name: String = ".MainActivity"
    ): ManifestParser.ComponentInfo = ManifestParser.ComponentInfo(
        name = name,
        exported = true,
        intentFilters = listOf(
            ManifestParser.IntentFilterInfo(
                actions = listOf("android.intent.action.MAIN"),
                categories = listOf("android.intent.category.LAUNCHER")
            )
        )
    )

    private fun plainActivity(
        name: String = ".PlainActivity",
        exported: Boolean = false,
        process: String? = null
    ): ManifestParser.ComponentInfo = ManifestParser.ComponentInfo(
        name = name,
        exported = exported,
        process = process
    )

    private fun service(
        name: String = ".MyService",
        process: String? = null
    ): ManifestParser.ComponentInfo = ManifestParser.ComponentInfo(
        name = name,
        exported = false,
        process = process
    )

    private fun receiver(
        name: String = ".MyReceiver",
        process: String? = null
    ): ManifestParser.ComponentInfo = ManifestParser.ComponentInfo(
        name = name,
        exported = false,
        process = process
    )

    private fun provider(
        name: String = ".MyProvider",
        authorities: String = "com.example.provider",
        exported: Boolean = false
    ): ManifestParser.ProviderInfo = ManifestParser.ProviderInfo(
        name = name,
        authorities = authorities,
        exported = exported
    )

    // -- 1. extractLauncherActivity --

    @Nested
    inner class ExtractLauncherActivity {

        @Test
        fun `正确提取 launcher activity`() {
            val manifest = createManifest(
                activities = listOf(
                    launcherActivity(".MainActivity"),
                    plainActivity(".SettingsActivity")
                )
            )

            val result = extractor.extractLauncherActivity(manifest)

            assertNotNull(result)
            assertEquals(".MainActivity", result!!.name)
        }

        @Test
        fun `多个 activity 中只提取 launcher 的`() {
            val manifest = createManifest(
                activities = listOf(
                    plainActivity(".SplashActivity"),
                    launcherActivity(".HomeActivity"),
                    plainActivity(".SettingsActivity")
                )
            )

            val result = extractor.extractLauncherActivity(manifest)

            assertNotNull(result)
            assertEquals(".HomeActivity", result!!.name)
        }

        @Test
        fun `无 launcher activity 时返回 null`() {
            val manifest = createManifest(
                activities = listOf(
                    plainActivity(".Activity1"),
                    plainActivity(".Activity2")
                )
            )

            val result = extractor.extractLauncherActivity(manifest)

            assertNull(result)
        }

        @Test
        fun `活动列表为空时返回 null`() {
            val manifest = createManifest()

            val result = extractor.extractLauncherActivity(manifest)

            assertNull(result)
        }

        @Test
        fun `只有 MAIN action 没有 LAUNCHER category 时不匹配`() {
            val manifest = createManifest(
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = ".MainActivity",
                        exported = true,
                        intentFilters = listOf(
                            ManifestParser.IntentFilterInfo(
                                actions = listOf("android.intent.action.MAIN"),
                                categories = emptyList()
                            )
                        )
                    )
                )
            )

            val result = extractor.extractLauncherActivity(manifest)

            assertNull(result)
        }

        @Test
        fun `只有 LAUNCHER category 没有 MAIN action 时不匹配`() {
            val manifest = createManifest(
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = ".MainActivity",
                        exported = true,
                        intentFilters = listOf(
                            ManifestParser.IntentFilterInfo(
                                actions = emptyList(),
                                categories = listOf("android.intent.category.LAUNCHER")
                            )
                        )
                    )
                )
            )

            val result = extractor.extractLauncherActivity(manifest)

            assertNull(result)
        }

        @Test
        fun `intent filter 为空列表的 activity 不匹配`() {
            val manifest = createManifest(
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = ".MainActivity",
                        exported = true,
                        intentFilters = emptyList()
                    )
                )
            )

            val result = extractor.extractLauncherActivity(manifest)

            assertNull(result)
        }
    }

    // -- 2. extractAllComponents --

    @Nested
    inner class ExtractAllComponents {

        @Test
        fun `提取所有组件类型`() {
            val activities = listOf(
                launcherActivity(),
                plainActivity(".Second")
            )
            val services = listOf(service())
            val receivers = listOf(receiver())
            val providers = listOf(provider())

            val manifest = createManifest(
                activities = activities,
                services = services,
                receivers = receivers,
                providers = providers
            )

            val result = extractor.extractAllComponents(manifest)

            // 2 activities + 1 service + 1 receiver + 1 provider = 5
            assertEquals(5, result.size)
        }

        @Test
        fun `provider 转换为 ComponentInfo`() {
            val manifest = createManifest(
                providers = listOf(
                    provider(name = ".MyProvider", exported = true)
                )
            )

            val result = extractor.extractAllComponents(manifest)

            assertEquals(1, result.size)
            assertEquals(".MyProvider", result[0].name)
            assertTrue(result[0].exported)
        }

        @Test
        fun `所有类型为空时返回空列表`() {
            val manifest = createManifest()

            val result = extractor.extractAllComponents(manifest)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `包含 activity-alias`() {
            val aliasActivity = ManifestParser.ComponentInfo(
                name = ".AliasActivity",
                exported = true,
                intentFilters = listOf(
                    ManifestParser.IntentFilterInfo(
                        actions = listOf("android.intent.action.VIEW")
                    )
                )
            )

            val manifest = createManifest(
                activities = listOf(launcherActivity(), aliasActivity)
            )

            val result = extractor.extractAllComponents(manifest)

            val alias = result.find { it.name == ".AliasActivity" }
            assertNotNull(alias, "应包含 activity-alias")
        }

        @Test
        fun `provider 的 exported 属性正确传递`() {
            val manifest = createManifest(
                providers = listOf(
                    provider(name = ".ExportedProvider", exported = true),
                    provider(name = ".PrivateProvider", exported = false)
                )
            )

            val result = extractor.extractAllComponents(manifest)

            val exported = result.find { it.name == ".ExportedProvider" }
            assertNotNull(exported)
            assertTrue(exported!!.exported)

            val private = result.find { it.name == ".PrivateProvider" }
            assertNotNull(private)
            assertFalse(private!!.exported)
        }
    }

    // -- 3. extractProcesses --

    @Nested
    inner class ExtractProcesses {

        @Test
        fun `提取唯一进程名`() {
            val manifest = createManifest(
                activities = listOf(
                    plainActivity(process = ":remote"),
                    plainActivity(process = ":bg")
                ),
                services = listOf(
                    service(process = ":remote")
                ),
                receivers = listOf(
                    receiver(process = ":push")
                )
            )

            val result = extractor.extractProcesses(manifest)

            assertEquals(3, result.size, "应去重为 3 个唯一进程名")
            assertTrue(result.contains(":remote"))
            assertTrue(result.contains(":bg"))
            assertTrue(result.contains(":push"))
        }

        @Test
        fun `无 process 属性的组件不贡献进程名`() {
            val manifest = createManifest(
                activities = listOf(plainActivity()),
                services = listOf(service()),
                receivers = listOf(receiver())
            )

            val result = extractor.extractProcesses(manifest)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `混合有无 process 属性的组件`() {
            val manifest = createManifest(
                activities = listOf(
                    plainActivity(".A1"),
                    plainActivity(".A2", process = ":special")
                ),
                services = listOf(service(".S1")),
                receivers = listOf(receiver(".R1", process = ":push"))
            )

            val result = extractor.extractProcesses(manifest)

            assertEquals(2, result.size)
            assertTrue(result.contains(":special"))
            assertTrue(result.contains(":push"))
        }

        @Test
        fun `空 manifest 返回空集合`() {
            val manifest = createManifest()

            val result = extractor.extractProcesses(manifest)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `保留冒号开头的相对进程名`() {
            val manifest = createManifest(
                services = listOf(service(process = ":remote"))
            )

            val result = extractor.extractProcesses(manifest)

            assertTrue(result.contains(":remote"), "应保留 :remote 格式")
        }

        @Test
        fun `保留全限定进程名`() {
            val manifest = createManifest(
                services = listOf(service(process = "com.example.testapp:bg"))
            )

            val result = extractor.extractProcesses(manifest)

            assertTrue(result.contains("com.example.testapp:bg"), "应保留全限定进程名")
        }

        @Test
        fun `相同进程名去重`() {
            val manifest = createManifest(
                activities = listOf(
                    plainActivity(process = ":remote"),
                    plainActivity(process = ":remote"),
                    plainActivity(process = ":remote")
                )
            )

            val result = extractor.extractProcesses(manifest)

            assertEquals(1, result.size, "相同进程名应去重为 1 个")
        }

        @Test
        fun `不包含 providers 的进程`() {
            // extractProcesses 只从 activities + services + receivers 提取
            // providers 不参与进程提取
            val manifest = createManifest(
                providers = listOf(provider())
            )

            val result = extractor.extractProcesses(manifest)

            assertTrue(result.isEmpty())
        }
    }

    // -- 4. extractPermissions --

    @Nested
    inner class ExtractPermissions {

        @Test
        fun `提取权限列表`() {
            val manifest = createManifest(
                permissions = listOf("android.permission.INTERNET", "android.permission.CAMERA")
            )

            val result = extractor.extractPermissions(manifest)

            assertEquals(2, result.size)
            assertTrue(result.contains("android.permission.INTERNET"))
            assertTrue(result.contains("android.permission.CAMERA"))
        }

        @Test
        fun `无权限时返回空列表`() {
            val manifest = createManifest()

            val result = extractor.extractPermissions(manifest)

            assertTrue(result.isEmpty())
        }
    }
}
