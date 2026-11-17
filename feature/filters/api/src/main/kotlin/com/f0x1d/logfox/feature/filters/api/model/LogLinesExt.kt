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

    val includingFilters = filters.filter { it.including }
    val excludingFilters = filters.filter { !it.including }

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

private fun UserFilter.lineSuits(logLine: LogLine) =
    (allowedLevels.isEmpty() || allowedLevels.contains(logLine.level)) &&
    uid.equalsOrTrueIfNull(logLine.uid) &&
    pid.equalsOrTrueIfNull(logLine.pid) &&
    tid.equalsOrTrueIfNull(logLine.tid) &&
    packageName.equalsOrTrueIfNull(logLine.packageName ?: "") &&
    tag.matchesOrTrueIfNull(logLine.tag, tagRegex) &&
    content.matchesOrTrueIfNull(logLine.content, contentRegex)

private fun String?.equalsOrTrueIfNull(other: String) = if (this == null) true else other == this

/**
 * Exact value matches, unless the "regex" switch of the filter is on: then the value is
 * treated as a regular expression and matched against the whole log tag/content
 * (F0x1d/LogFox#240). An invalid pattern matches nothing instead of crashing/ignoring the filter.
 */
private fun String?.matchesOrTrueIfNull(other: String, regex: Boolean): Boolean {
    if (this == null) return true

    if (!regex) return other.contains(this)

    return runCatching {
        Regex(this).containsMatchIn(other)
    }.getOrDefault(false)
}
