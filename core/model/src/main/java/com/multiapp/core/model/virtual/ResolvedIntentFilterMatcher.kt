package com.multiapp.core.model.virtual

data class IntentFilterMatchRequest(
    val action: String? = null,
    val categories: Set<String> = emptySet(),
    val scheme: String? = null,
    val mimeType: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val path: String? = null,
    val hasData: Boolean = false
) {
    init {
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(categories.none { it.isBlank() }) { "categories must not contain blank entries" }
        require(scheme == null || scheme.isNotBlank()) { "scheme must not be blank" }
        require(mimeType == null || mimeType.isNotBlank()) { "mimeType must not be blank" }
        require(host == null || host.isNotBlank()) { "host must not be blank" }
        require(port == null || port >= 0) { "port must not be negative" }
        require(path == null || path.isNotEmpty()) { "path must not be empty" }
    }

    internal val containsData: Boolean
        get() = hasData || scheme != null || host != null || port != null || path != null
}

enum class IntentFilterMatchReason {
    MATCHED,
    ACTION_MISMATCH,
    CATEGORY_MISMATCH,
    DATA_MISMATCH,
    SCHEME_MISMATCH,
    MIME_TYPE_MISMATCH,
    AUTHORITY_MISMATCH,
    PATH_MISMATCH
}

data class IntentFilterMatchResult(
    val matched: Boolean,
    val priority: Int,
    val reason: IntentFilterMatchReason,
    val detail: String? = null
)

/** Android-free equivalent of the relevant IntentFilter.match/matchData behavior. */
object ResolvedIntentFilterMatcher {

    fun matches(filter: ResolvedIntentFilter, request: IntentFilterMatchRequest): Boolean =
        match(filter, request).matched

    fun match(
        filter: ResolvedIntentFilter,
        request: IntentFilterMatchRequest
    ): IntentFilterMatchResult {
        if (request.action != null && request.action !in filter.actions) {
            return mismatch(filter, IntentFilterMatchReason.ACTION_MISMATCH, request.action)
        }

        val dataResult = matchData(filter, request)
        if (dataResult != null) return dataResult

        val missingCategory = request.categories.firstOrNull { category -> category !in filter.categories }
        if (missingCategory != null) {
            return mismatch(filter, IntentFilterMatchReason.CATEGORY_MISMATCH, missingCategory)
        }

        return IntentFilterMatchResult(
            matched = true,
            priority = filter.priority,
            reason = IntentFilterMatchReason.MATCHED
        )
    }

    private fun matchData(
        filter: ResolvedIntentFilter,
        request: IntentFilterMatchRequest
    ): IntentFilterMatchResult? {
        val schemes = filter.dataSchemes
        val mimeTypes = filter.dataMimeTypes

        if (schemes.isEmpty() && mimeTypes.isEmpty()) {
            return if (request.mimeType == null && !request.containsData) {
                null
            } else {
                mismatch(filter, IntentFilterMatchReason.DATA_MISMATCH, "filter has no data rules")
            }
        }

        if (schemes.isNotEmpty()) {
            if (request.scheme.orEmpty() !in schemes) {
                return mismatch(filter, IntentFilterMatchReason.SCHEME_MISMATCH, request.scheme)
            }

            val authorities = filter.resolvedAuthorities
            if (authorities.isNotEmpty()) {
                val authorityMatches = authorities.any { authority -> authority.matches(request.host, request.port) }
                if (!authorityMatches) {
                    return mismatch(
                        filter,
                        IntentFilterMatchReason.AUTHORITY_MISMATCH,
                        request.hostWithPort()
                    )
                }

                val paths = filter.resolvedPathPatterns
                if (paths.isNotEmpty() && paths.none { pattern -> pattern.matches(request.path) }) {
                    return mismatch(filter, IntentFilterMatchReason.PATH_MISMATCH, request.path)
                }
            }
        } else if (request.scheme !in setOf(null, "content", "file")) {
            return mismatch(filter, IntentFilterMatchReason.SCHEME_MISMATCH, request.scheme)
        }

        if (mimeTypes.isEmpty()) {
            if (request.mimeType != null) {
                return mismatch(filter, IntentFilterMatchReason.MIME_TYPE_MISMATCH, request.mimeType)
            }
        } else if (!mimeTypesMatch(mimeTypes, request.mimeType)) {
            return mismatch(filter, IntentFilterMatchReason.MIME_TYPE_MISMATCH, request.mimeType)
        }

        return null
    }

    private fun mismatch(
        filter: ResolvedIntentFilter,
        reason: IntentFilterMatchReason,
        detail: String?
    ): IntentFilterMatchResult = IntentFilterMatchResult(
        matched = false,
        priority = filter.priority,
        reason = reason,
        detail = detail
    )

    private fun ResolvedIntentAuthority.matches(requestHost: String?, requestPort: Int?): Boolean {
        val actualHost = requestHost ?: return false
        val hostMatches = if (host.startsWith('*')) {
            val suffix = host.substring(1)
            actualHost.length >= suffix.length && actualHost.endsWith(suffix, ignoreCase = true)
        } else {
            actualHost.equals(host, ignoreCase = true)
        }
        return hostMatches && (port == null || port == requestPort)
    }

    private fun IntentFilterMatchRequest.hostWithPort(): String? = when {
        host == null -> null
        port == null -> host
        else -> "$host:$port"
    }

    private fun ResolvedIntentPathPattern.matches(requestPath: String?): Boolean {
        val value = requestPath ?: return false
        return when (type) {
            ResolvedIntentPathPatternType.LITERAL -> value == path
            ResolvedIntentPathPatternType.PREFIX -> value.startsWith(path)
            ResolvedIntentPathPatternType.SIMPLE_GLOB -> simpleGlobMatches(path, value)
            ResolvedIntentPathPatternType.ADVANCED_GLOB -> advancedGlobMatches(path, value)
            ResolvedIntentPathPatternType.SUFFIX -> value.endsWith(path)
        }
    }

    private fun mimeTypesMatch(filterTypes: List<String>, requestedType: String?): Boolean {
        val requested = requestedType?.toCanonicalMimeType() ?: return false
        val canonicalFilters = filterTypes.map { it.toCanonicalMimeType() }
        if (requested in canonicalFilters) return true
        if (requested == "*/*") return canonicalFilters.isNotEmpty()
        if ("*/*" in canonicalFilters) return true

        val requestedParts = requested.mimeParts() ?: return false
        return canonicalFilters.any { candidate ->
            val filterParts = candidate.mimeParts() ?: return@any false
            filterParts.first == requestedParts.first &&
                (filterParts.second == "*" || requestedParts.second == "*")
        }
    }

    private fun String.toCanonicalMimeType(): String = when {
        this == "*" -> "*/*"
        '/' !in this -> "$this/*"
        else -> this
    }

    private fun String.mimeParts(): Pair<String, String>? {
        val separator = indexOf('/')
        if (separator <= 0 || separator == lastIndex || indexOf('/', separator + 1) >= 0) return null
        return substring(0, separator) to substring(separator + 1)
    }

    private fun simpleGlobMatches(pattern: String, value: String): Boolean {
        val patternLength = pattern.length
        if (patternLength == 0) return value.isEmpty()

        val valueLength = value.length
        var patternIndex = 0
        var valueIndex = 0
        var nextChar = pattern[0]
        while (patternIndex < patternLength && valueIndex < valueLength) {
            var current = nextChar
            patternIndex++
            nextChar = pattern.getOrNull(patternIndex) ?: '\u0000'
            val escaped = current == '\\'
            if (escaped) {
                current = nextChar
                patternIndex++
                nextChar = pattern.getOrNull(patternIndex) ?: '\u0000'
            }

            if (nextChar == '*') {
                if (!escaped && current == '.') {
                    if (patternIndex >= patternLength - 1) return true
                    patternIndex++
                    nextChar = pattern[patternIndex]
                    if (nextChar == '\\') {
                        patternIndex++
                        nextChar = pattern.getOrNull(patternIndex) ?: '\u0000'
                    }
                    while (valueIndex < valueLength && value[valueIndex] != nextChar) {
                        valueIndex++
                    }
                    if (valueIndex == valueLength) return false
                    patternIndex++
                    nextChar = pattern.getOrNull(patternIndex) ?: '\u0000'
                    valueIndex++
                } else {
                    while (valueIndex < valueLength && value[valueIndex] == current) {
                        valueIndex++
                    }
                    patternIndex++
                    nextChar = pattern.getOrNull(patternIndex) ?: '\u0000'
                }
            } else {
                if (current != '.' && value[valueIndex] != current) return false
                valueIndex++
            }
        }

        if (patternIndex >= patternLength && valueIndex >= valueLength) return true
        return patternIndex == patternLength - 2 &&
            pattern[patternIndex] == '.' &&
            pattern[patternIndex + 1] == '*'
    }

    private fun advancedGlobMatches(pattern: String, value: String): Boolean = runCatching {
        val tokens = parseAdvancedGlob(pattern)
        var valueIndex = 0
        for (token in tokens) {
            var repetitions = 0
            while (
                repetitions < token.maxRepetitions &&
                valueIndex < value.length &&
                token.characterMatcher(value[valueIndex])
            ) {
                repetitions++
                valueIndex++
            }
            if (repetitions < token.minRepetitions) return@runCatching false
        }
        valueIndex == value.length
    }.getOrDefault(false)

    private fun parseAdvancedGlob(pattern: String): List<AdvancedGlobToken> {
        val tokens = mutableListOf<AdvancedGlobToken>()
        var index = 0
        while (index < pattern.length) {
            val matcher: (Char) -> Boolean
            when (val current = pattern[index]) {
                '\\' -> {
                    require(index + 1 < pattern.length) { "escape at end of pattern" }
                    val literal = pattern[index + 1]
                    matcher = { candidate -> candidate == literal }
                    index += 2
                }
                '.' -> {
                    matcher = { true }
                    index++
                }
                '[' -> {
                    val parsedSet = parseCharacterSet(pattern, index)
                    matcher = parsedSet.matcher
                    index = parsedSet.nextIndex
                }
                '*', '+', '{' -> throw IllegalArgumentException("modifier must follow a token")
                else -> {
                    matcher = { candidate -> candidate == current }
                    index++
                }
            }

            var minRepetitions = 1
            var maxRepetitions = 1
            if (index < pattern.length) {
                when (pattern[index]) {
                    '*' -> {
                        minRepetitions = 0
                        maxRepetitions = Int.MAX_VALUE
                        index++
                    }
                    '+' -> {
                        maxRepetitions = Int.MAX_VALUE
                        index++
                    }
                    '{' -> {
                        val rangeEnd = pattern.indexOf('}', index + 1)
                        require(rangeEnd >= 0) { "range is not terminated" }
                        val range = pattern.substring(index + 1, rangeEnd)
                        val separator = range.indexOf(',')
                        if (separator < 0) {
                            minRepetitions = range.toInt()
                            maxRepetitions = minRepetitions
                        } else {
                            minRepetitions = range.substring(0, separator).toInt()
                            maxRepetitions = range.substring(separator + 1)
                                .takeIf { it.isNotEmpty() }
                                ?.toInt()
                                ?: Int.MAX_VALUE
                        }
                        require(minRepetitions >= 0 && minRepetitions <= maxRepetitions) {
                            "invalid repetition range"
                        }
                        index = rangeEnd + 1
                    }
                }
            }
            tokens += AdvancedGlobToken(matcher, minRepetitions, maxRepetitions)
        }
        return tokens
    }

    private fun parseCharacterSet(pattern: String, startIndex: Int): ParsedCharacterSet {
        var index = startIndex + 1
        require(index < pattern.length) { "set is not terminated" }
        val inverse = pattern[index] == '^'
        if (inverse) index++

        val ranges = mutableListOf<CharRange>()
        while (index < pattern.length && pattern[index] != ']') {
            val start = if (pattern[index] == '\\') {
                require(index + 1 < pattern.length) { "escape at end of set" }
                index += 2
                pattern[index - 1]
            } else {
                pattern[index++]
            }
            if (index + 1 < pattern.length && pattern[index] == '-' && pattern[index + 1] != ']') {
                index++
                val end = if (pattern[index] == '\\') {
                    require(index + 1 < pattern.length) { "escape at end of set range" }
                    index += 2
                    pattern[index - 1]
                } else {
                    pattern[index++]
                }
                require(start <= end) { "invalid character range" }
                ranges += start..end
            } else {
                ranges += start..start
            }
        }
        require(index < pattern.length && pattern[index] == ']') { "set is not terminated" }
        require(ranges.isNotEmpty()) { "set must contain characters" }
        val matcher: (Char) -> Boolean = { candidate ->
            val contained = ranges.any { range -> candidate in range }
            if (inverse) !contained else contained
        }
        return ParsedCharacterSet(matcher, index + 1)
    }

    private data class AdvancedGlobToken(
        val characterMatcher: (Char) -> Boolean,
        val minRepetitions: Int,
        val maxRepetitions: Int
    )

    private data class ParsedCharacterSet(
        val matcher: (Char) -> Boolean,
        val nextIndex: Int
    )
}
