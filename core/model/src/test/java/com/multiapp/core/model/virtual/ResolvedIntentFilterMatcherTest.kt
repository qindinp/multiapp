package com.multiapp.core.model.virtual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolvedIntentFilterMatcherTest {

    @Test
    fun `legacy constructor remains compatible and exposes structured views`() {
        val filter = ResolvedIntentFilter(
            listOf(ACTION_VIEW),
            listOf(CATEGORY_DEFAULT),
            listOf("https"),
            listOf("image/*"),
            listOf("example.com"),
            listOf("/legacy"),
            7
        )

        assertEquals(ResolvedIntentAuthority("example.com"), filter.resolvedAuthorities.single())
        assertEquals(
            ResolvedIntentPathPattern("/legacy", ResolvedIntentPathPatternType.LITERAL),
            filter.resolvedPathPatterns.single()
        )
        assertEquals(7, filter.priority)
    }

    @Test
    fun `structured data exposes host and path compatibility views`() {
        val filter = ResolvedIntentFilter(
            authorityEntries = listOf(ResolvedIntentAuthority("example.com", 8443)),
            pathPatterns = listOf(
                ResolvedIntentPathPattern("/items", ResolvedIntentPathPatternType.PREFIX)
            )
        )

        assertEquals(listOf("example.com"), filter.legacyDataAuthorities)
        assertEquals(listOf("/items"), filter.legacyDataPaths)
    }

    @Test
    fun `authority requires a declared port to match`() {
        val filter = dataFilter(
            authorityEntries = listOf(ResolvedIntentAuthority("api.example.com", 8443))
        )

        val wrongPort = ResolvedIntentFilterMatcher.match(
            filter,
            request(host = "api.example.com", port = 443)
        )
        val matchingPort = ResolvedIntentFilterMatcher.match(
            filter,
            request(host = "API.EXAMPLE.COM", port = 8443)
        )

        assertFalse(wrongPort.matched)
        assertEquals(IntentFilterMatchReason.AUTHORITY_MISMATCH, wrongPort.reason)
        assertTrue(matchingPort.matched)
    }

    @Test
    fun `prefix and simple glob paths match with AOSP semantics`() {
        val prefixFilter = dataFilter(
            authorityEntries = listOf(ResolvedIntentAuthority("example.com")),
            pathPatterns = listOf(
                ResolvedIntentPathPattern("/items", ResolvedIntentPathPatternType.PREFIX)
            )
        )
        val globFilter = prefixFilter.copy(
            pathPatterns = listOf(
                ResolvedIntentPathPattern("/files/.*\\.json", ResolvedIntentPathPatternType.SIMPLE_GLOB)
            )
        )

        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                prefixFilter,
                request(host = "example.com", path = "/items/42")
            )
        )
        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                globFilter,
                request(host = "example.com", path = "/files/report.json")
            )
        )
        assertFalse(
            ResolvedIntentFilterMatcher.matches(
                globFilter,
                request(host = "example.com", path = "/files/report.txt")
            )
        )
    }

    @Test
    fun `advanced glob and suffix path types are supported`() {
        val advancedFilter = dataFilter(
            authorityEntries = listOf(ResolvedIntentAuthority("example.com")),
            pathPatterns = listOf(
                ResolvedIntentPathPattern("/items/[0-9]+", ResolvedIntentPathPatternType.ADVANCED_GLOB)
            )
        )
        val suffixFilter = advancedFilter.copy(
            pathPatterns = listOf(
                ResolvedIntentPathPattern(".json", ResolvedIntentPathPatternType.SUFFIX)
            )
        )

        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                advancedFilter,
                request(host = "example.com", path = "/items/42")
            )
        )
        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                suffixFilter,
                request(host = "example.com", path = "/items/42.json")
            )
        )
    }

    @Test
    fun `MIME exact and wildcard forms match`() {
        val imageFilter = ResolvedIntentFilter(
            actions = listOf(ACTION_VIEW),
            dataMimeTypes = listOf("image/*")
        )
        val anyFilter = imageFilter.copy(dataMimeTypes = listOf("*/*"))

        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                imageFilter,
                IntentFilterMatchRequest(
                    action = ACTION_VIEW,
                    scheme = "content",
                    mimeType = "image/png"
                )
            )
        )
        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                anyFilter,
                IntentFilterMatchRequest(action = ACTION_VIEW, mimeType = "application/json")
            )
        )
        assertFalse(
            ResolvedIntentFilterMatcher.matches(
                imageFilter,
                IntentFilterMatchRequest(action = ACTION_VIEW, mimeType = "text/plain")
            )
        )
    }

    @Test
    fun `scheme and MIME are case sensitive while authority host is not`() {
        val filter = ResolvedIntentFilter(
            actions = listOf(ACTION_VIEW),
            dataSchemes = listOf("https"),
            dataMimeTypes = listOf("image/png"),
            authorityEntries = listOf(ResolvedIntentAuthority("api.example.com"))
        )

        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                filter,
                IntentFilterMatchRequest(
                    action = ACTION_VIEW,
                    scheme = "https",
                    mimeType = "image/png",
                    host = "API.EXAMPLE.COM"
                )
            )
        )
        assertFalse(
            ResolvedIntentFilterMatcher.matches(
                filter,
                IntentFilterMatchRequest(
                    action = ACTION_VIEW,
                    scheme = "HTTPS",
                    mimeType = "image/png",
                    host = "api.example.com"
                )
            )
        )
        assertFalse(
            ResolvedIntentFilterMatcher.matches(
                filter,
                IntentFilterMatchRequest(
                    action = ACTION_VIEW,
                    scheme = "https",
                    mimeType = "IMAGE/PNG",
                    host = "api.example.com"
                )
            )
        )
    }

    @Test
    fun `filter without data rules rejects intents carrying data or MIME`() {
        val filter = ResolvedIntentFilter(actions = listOf(ACTION_VIEW))

        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                filter,
                IntentFilterMatchRequest(action = ACTION_VIEW)
            )
        )
        assertEquals(
            IntentFilterMatchReason.DATA_MISMATCH,
            ResolvedIntentFilterMatcher.match(
                filter,
                IntentFilterMatchRequest(action = ACTION_VIEW, scheme = "content")
            ).reason
        )
        assertFalse(
            ResolvedIntentFilterMatcher.matches(
                filter,
                IntentFilterMatchRequest(action = ACTION_VIEW, mimeType = "image/png")
            )
        )
    }

    @Test
    fun `intent categories must be a subset of filter categories`() {
        val filter = ResolvedIntentFilter(
            actions = listOf(ACTION_VIEW),
            categories = listOf(CATEGORY_DEFAULT, CATEGORY_BROWSABLE)
        )

        assertTrue(
            ResolvedIntentFilterMatcher.matches(
                filter,
                IntentFilterMatchRequest(
                    action = ACTION_VIEW,
                    categories = setOf(CATEGORY_DEFAULT)
                )
            )
        )
        val mismatch = ResolvedIntentFilterMatcher.match(
            filter,
            IntentFilterMatchRequest(
                action = ACTION_VIEW,
                categories = setOf(CATEGORY_DEFAULT, "test.MISSING")
            )
        )
        assertEquals(IntentFilterMatchReason.CATEGORY_MISMATCH, mismatch.reason)
    }

    @Test
    fun `match result retains filter priority`() {
        val result = ResolvedIntentFilterMatcher.match(
            ResolvedIntentFilter(actions = listOf(ACTION_VIEW), priority = 42),
            IntentFilterMatchRequest(action = ACTION_VIEW)
        )

        assertTrue(result.matched)
        assertEquals(42, result.priority)
        assertEquals(IntentFilterMatchReason.MATCHED, result.reason)
    }

    private fun dataFilter(
        authorityEntries: List<ResolvedIntentAuthority>,
        pathPatterns: List<ResolvedIntentPathPattern> = emptyList()
    ): ResolvedIntentFilter = ResolvedIntentFilter(
        actions = listOf(ACTION_VIEW),
        dataSchemes = listOf("https"),
        authorityEntries = authorityEntries,
        pathPatterns = pathPatterns
    )

    private fun request(
        host: String,
        port: Int? = null,
        path: String? = null
    ): IntentFilterMatchRequest = IntentFilterMatchRequest(
        action = ACTION_VIEW,
        scheme = "https",
        host = host,
        port = port,
        path = path
    )

    private companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
    }
}
