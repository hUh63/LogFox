package com.f0x1d.logfox.feature.filters.api.model

import com.f0x1d.logfox.feature.logging.api.model.LogLine

fun LogLine.suits(filters: List<UserFilter>) = listOf(this)
    .filterAndSearch(filters)
    .isNotEmpty()

fun List<LogLine>.filterAndSearch(
    filters: List<UserFilter>,
    query: String? = null,
    caseSensitive: Boolean = true,
) = filterByExtendedFilters(filters).let {
    if (query == null) {
        it
    } else {
        it.filter { logLine ->
            logLine.tag.contains(query, ignoreCase = !caseSensitive) ||
                logLine.content.contains(query, ignoreCase = !caseSensitive)
        }
    }
}

private fun List<LogLine>.filterByExtendedFilters(filters: List<UserFilter>): List<LogLine> {
    if (filters.isEmpty()) return this

    // Regexes are compiled once per filtering pass. Compiling inside the predicate meant
    // one Regex (NFA build) per log line per filter, i.e. tens of thousands of compilations
    // per refresh once a regex filter was enabled
    val compiledFilters = filters.map { it.compile() }

    val includingFilters = compiledFilters.filter { it.filter.including }
    val excludingFilters = compiledFilters.filter { !it.filter.including }

    return filter { logLine ->
        val shouldExclude = excludingFilters.any {
            it.lineSuits(logLine)
        }

        if (shouldExclude) {
            false
        } else {
            includingFilters.run {
                if (isEmpty()) {
                    true
                } else {
                    any {
                        it.lineSuits(logLine)
                    }
                }
            }
        }
    }
}

private class CompiledFilter(
    val filter: UserFilter,
    private val tagMatcher: (String) -> Boolean,
    private val contentMatcher: (String) -> Boolean,
) {
    fun lineSuits(logLine: LogLine) = with(filter) {
        (allowedLevels.isEmpty() || allowedLevels.contains(logLine.level)) &&
            uid.equalsOrTrueIfNull(logLine.uid) &&
            pid.equalsOrTrueIfNull(logLine.pid) &&
            tid.equalsOrTrueIfNull(logLine.tid) &&
            packageName.equalsOrTrueIfNull(logLine.packageName ?: "") &&
            tagMatcher(logLine.tag) &&
            contentMatcher(logLine.content)
    }
}

private fun UserFilter.compile() = CompiledFilter(
    filter = this,
    tagMatcher = compileMatcher(tag, tagRegex),
    contentMatcher = compileMatcher(content, contentRegex),
)

private fun String?.equalsOrTrueIfNull(other: String) = if (this == null) true else other == this

/**
 * Exact value matches, unless the "regex" switch of the filter is on: then the value is
 * treated as a regular expression and matched against the whole log tag/content
 * (F0x1d/LogFox#240). An invalid pattern is treated as "this condition is not set": treating
 * it as "matches nothing" emptied the whole list for an including filter with no hint at all,
 * and the edit screen now flags the broken expression anyway.
 */
private fun compileMatcher(pattern: String?, regex: Boolean): (String) -> Boolean {
    if (pattern == null) return { true }

    if (!regex) return { other -> other.contains(pattern) }

    val compiled = runCatching { Regex(pattern) }.getOrNull()
        ?: return { true }

    return { other -> compiled.containsMatchIn(other) }
}
