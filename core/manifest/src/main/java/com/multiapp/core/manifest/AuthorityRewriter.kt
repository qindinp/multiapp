package com.multiapp.core.manifest

/**
 * 将 ContentProvider 的 authorities 加上实例 ID 后缀
 * 例: com.tencent.mm.provider → com.tencent.mm.provider.stub_001
 */
class AuthorityRewriter {

    data class AuthorityMapping(
        val original: String,
        val rewritten: String
    )

    fun rewrite(
        providers: List<ManifestParser.ProviderInfo>,
        instanceId: String,
        authorityMap: Map<String, String>? = null
    ): Pair<List<ManifestParser.ProviderInfo>, Map<String, String>> {
        val resolvedMap = authorityMap ?: providers.associate { provider ->
            provider.authorities to "${provider.authorities}.$instanceId"
        }
        val rewrittenProviders = providers.map { provider ->
            val rewritten = resolvedMap[provider.authorities] ?: "${provider.authorities}.$instanceId"
            provider.copy(authorities = rewritten)
        }
        return rewrittenProviders to resolvedMap
    }
}
