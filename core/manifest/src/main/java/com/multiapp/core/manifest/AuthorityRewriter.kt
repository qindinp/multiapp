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
        instanceId: String
    ): Pair<List<ManifestParser.ProviderInfo>, Map<String, String>> {
        val authorityMap = mutableMapOf<String, String>()
        val rewrittenProviders = providers.map { provider ->
            val rewritten = "${provider.authorities}.$instanceId"
            authorityMap[provider.authorities] = rewritten
            provider.copy(authorities = rewritten)
        }
        return rewrittenProviders to authorityMap
    }
}
