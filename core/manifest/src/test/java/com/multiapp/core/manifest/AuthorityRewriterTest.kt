package com.multiapp.core.manifest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AuthorityRewriterTest {

    private lateinit var rewriter: AuthorityRewriter

    @BeforeEach
    fun setUp() {
        rewriter = AuthorityRewriter()
    }

    // -- 辅助工厂方法 --

    private fun createProvider(
        name: String = ".MyProvider",
        authorities: String = "com.example.provider",
        exported: Boolean = false
    ): ManifestParser.ProviderInfo = ManifestParser.ProviderInfo(
        name = name,
        authorities = authorities,
        exported = exported
    )

    // -- 1. rewrite 正确替换 authority 中的占位符 --

    @Nested
    inner class Rewrite {

        @Test
        fun `rewrite 正确替换 authority 添加 instanceId 后缀`() {
            val providers = listOf(
                createProvider(authorities = "com.example.provider")
            )

            val (rewrittenProviders, authorityMap) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_abc123"
            )

            assertEquals(1, rewrittenProviders.size)
            assertEquals(
                "com.example.provider.stub_abc123",
                rewrittenProviders[0].authorities,
                "authority 应添加 instanceId 后缀"
            )
        }

        @Test
        fun `rewrite 返回正确的 authorityMap`() {
            val providers = listOf(
                createProvider(authorities = "com.example.provider")
            )

            val (_, authorityMap) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_abc123"
            )

            assertEquals(1, authorityMap.size)
            assertEquals(
                "com.example.provider.stub_abc123",
                authorityMap["com.example.provider"]
            )
        }

        @Test
        fun `使用自定义 authorityMap 时按指定映射替换`() {
            val providers = listOf(
                createProvider(authorities = "com.example.provider")
            )
            val customMap = mapOf(
                "com.example.provider" to "com.custom.rewritten.provider"
            )

            val (rewrittenProviders, returnedMap) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_abc123",
                authorityMap = customMap
            )

            assertEquals(
                "com.custom.rewritten.provider",
                rewrittenProviders[0].authorities,
                "应使用自定义 authorityMap 中的值"
            )
            assertEquals(customMap, returnedMap)
        }

        @Test
        fun `provider 的其他属性保持不变`() {
            val providers = listOf(
                createProvider(
                    name = ".ContentProvider",
                    authorities = "com.example.provider",
                    exported = true
                )
            )

            val (rewrittenProviders, _) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_abc123"
            )

            assertEquals(".ContentProvider", rewrittenProviders[0].name)
            assertTrue(rewrittenProviders[0].exported)
        }
    }

    // -- 2. authorityMap 为空时使用默认替换 --

    @Nested
    inner class DefaultReplacement {

        @Test
        fun `authorityMap 为 null 时自动生成映射`() {
            val providers = listOf(
                createProvider(authorities = "com.example.provider")
            )

            val (rewrittenProviders, authorityMap) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_xyz",
                authorityMap = null
            )

            assertEquals(
                "com.example.provider.stub_xyz",
                rewrittenProviders[0].authorities
            )
            assertEquals(
                "com.example.provider.stub_xyz",
                authorityMap["com.example.provider"]
            )
        }

        @Test
        fun `默认映射格式为 originalAuthority_instanceId`() {
            val providers = listOf(
                createProvider(authorities = "com.app.data")
            )

            val (_, authorityMap) = rewriter.rewrite(
                providers = providers,
                instanceId = "id_999"
            )

            assertEquals("com.app.data.id_999", authorityMap["com.app.data"])
        }

        @Test
        fun `authorityMap 中无匹配 key 时使用默认后缀替换`() {
            val providers = listOf(
                createProvider(authorities = "com.unmapped.provider")
            )
            val customMap = mapOf(
                "com.other.provider" to "com.other.rewritten"
            )

            val (rewrittenProviders, _) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_001",
                authorityMap = customMap
            )

            assertEquals(
                "com.unmapped.provider.stub_001",
                rewrittenProviders[0].authorities,
                "无匹配时应使用默认后缀替换"
            )
        }
    }

    // -- 3. 多个 provider 的 authority 都被替换 --

    @Nested
    inner class MultipleProviders {

        @Test
        fun `多个 provider 的 authority 都被替换`() {
            val providers = listOf(
                createProvider(name = ".Provider1", authorities = "com.example.provider"),
                createProvider(name = ".Provider2", authorities = "com.example.fileprovider"),
                createProvider(name = ".Provider3", authorities = "com.example.analytics")
            )

            val (rewrittenProviders, authorityMap) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_multi"
            )

            assertEquals(3, rewrittenProviders.size)
            assertEquals(
                "com.example.provider.stub_multi",
                rewrittenProviders[0].authorities
            )
            assertEquals(
                "com.example.fileprovider.stub_multi",
                rewrittenProviders[1].authorities
            )
            assertEquals(
                "com.example.analytics.stub_multi",
                rewrittenProviders[2].authorities
            )
            assertEquals(3, authorityMap.size)
        }

        @Test
        fun `多个 provider 使用自定义 authorityMap`() {
            val providers = listOf(
                createProvider(name = ".P1", authorities = "com.a.provider"),
                createProvider(name = ".P2", authorities = "com.b.provider")
            )
            val customMap = mapOf(
                "com.a.provider" to "com.a.rewritten",
                "com.b.provider" to "com.b.rewritten"
            )

            val (rewrittenProviders, _) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_custom",
                authorityMap = customMap
            )

            assertEquals("com.a.rewritten", rewrittenProviders[0].authorities)
            assertEquals("com.b.rewritten", rewrittenProviders[1].authorities)
        }

        @Test
        fun `空 provider 列表返回空结果`() {
            val (rewrittenProviders, authorityMap) = rewriter.rewrite(
                providers = emptyList(),
                instanceId = "stub_empty"
            )

            assertTrue(rewrittenProviders.isEmpty())
            assertTrue(authorityMap.isEmpty())
        }

        @Test
        fun `多个 provider 的 name 和 exported 保持不变`() {
            val providers = listOf(
                createProvider(name = ".P1", authorities = "com.a.auth", exported = true),
                createProvider(name = ".P2", authorities = "com.b.auth", exported = false)
            )

            val (rewrittenProviders, _) = rewriter.rewrite(
                providers = providers,
                instanceId = "stub_keep"
            )

            assertEquals(".P1", rewrittenProviders[0].name)
            assertTrue(rewrittenProviders[0].exported)
            assertEquals(".P2", rewrittenProviders[1].name)
            assertFalse(rewrittenProviders[1].exported)
        }
    }
}
