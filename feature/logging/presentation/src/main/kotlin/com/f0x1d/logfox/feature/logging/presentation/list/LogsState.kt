package com.f0x1d.logfox.feature.logging.presentation.list

import com.f0x1d.logfox.feature.logging.api.model.LogLevel

import com.f0x1d.logfox.feature.filters.api.model.UserFilter
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.logging.api.model.ShowLogValues

internal data class LogsState(
    val logs: List<LogLine>,
    val paused: Boolean,
    val query: String?,
    val levelFilters: Set<LogLevel>,
    val caseSensitive: Boolean,
    val filters: List<UserFilter>,
    val showLogValues: ShowLogValues,
    val selectedIds: Set<Long>,
    val expandedOverrides: Map<Long, Boolean>,
    val logsExpanded: Boolean,
    val textSize: Int,
    val logsChanged: Boolean,
    val resumeLoggingWithBottomTouch: Boolean,
    /**
     * Monotonic counter bumped by every command that can change the *visible* list.
     *
     * [logsChanged] alone was not reliable: commands like [LogsCommand.Pause] set it back to
     * `false`, so a level-button tap could be followed by a list submit that never happened.
     * A counter can only grow, so the fragment can always tell whether the list it shows is
     * up to date.
     */
    val listRevision: Long = 0L,
)
