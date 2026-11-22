package com.f0x1d.logfox.feature.logging.presentation.list

import com.f0x1d.logfox.core.tea.ViewStateMapper
import com.f0x1d.logfox.feature.datetime.api.DateTimeFormatter
import com.f0x1d.logfox.feature.filters.api.model.filterAndSearch
import com.f0x1d.logfox.feature.logging.presentation.list.model.toPresentationModel
import javax.inject.Inject

internal class LogsViewStateMapper @Inject constructor(
    private val dateTimeFormatter: DateTimeFormatter,
) : ViewStateMapper<LogsState, LogsViewState> {

    override fun map(state: LogsState): LogsViewState {
        val filteredLogs = state.logs
            .filterAndSearch(
                filters = state.filters,
                query = state.query,
                caseSensitive = state.caseSensitive,
            )
            .let { lines ->
                // Quick level filtering from the button row above the list (F0x1d/LogFox#259)
                if (state.levelFilters.isEmpty()) {
                    lines
                } else {
                    lines.filter { it.level in state.levelFilters }
                }
            }

        return LogsViewState(
            logs = filteredLogs.map { line ->
                line.toPresentationModel(
                    displayText = line.formatOriginal(
                        values = state.showLogValues,
                        formatDate = dateTimeFormatter::formatDate,
                        formatTime = dateTimeFormatter::formatTime,
                    ),
                    expanded = state.expandedOverrides.getOrElse(line.id) { state.logsExpanded },
                    selected = line.id in state.selectedIds,
                    // Guards against a 0 saved by an older build: 0 sp renders invisible rows
                    textSize = state.textSize.coerceIn(6, 40).toFloat(),
                )
            },
            logsChanged = state.logsChanged,
            paused = state.paused,
            query = state.query,
            levelFilters = state.levelFilters,
            filters = state.filters,
            // Count only selected lines that survived the current query/filters. The toolbar
            // used to claim "N selected" while far fewer rows were highlighted, and
            // copy/export included lines the user could not see
            selecting = filteredLogs.any { it.id in state.selectedIds },
            selectedCount = filteredLogs.count { it.id in state.selectedIds },
            resumeLoggingWithBottomTouch = state.resumeLoggingWithBottomTouch,
            listRevision = state.listRevision,
        )
    }
}
