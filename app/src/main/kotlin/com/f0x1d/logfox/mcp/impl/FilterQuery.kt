package com.f0x1d.logfox.mcp.impl

import com.f0x1d.logfox.feature.filters.api.model.UserFilter

/**
 * Converts a [UserFilter] into a log query string used by LogFox search.
 */
internal fun buildQueryFromFilter(filter: UserFilter): String = buildList {
    filter.uid?.let { add("uid:$it") }
    filter.pid?.let { add("pid:$it") }
    filter.tid?.let { add("tid:$it") }
    filter.packageName?.let { add("pkg:$it") }
    filter.tag?.let { add("tag:$it") }
    filter.content?.let { add("msg:$it") }
}.joinToString(separator = " ")
