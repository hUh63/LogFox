package com.f0x1d.logfox.feature.logging.presentation.list

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import com.f0x1d.logfox.feature.filters.api.model.filterAndSearch
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import javax.inject.Inject

internal class LogsReducer @Inject constructor() : Reducer<LogsState, LogsCommand, LogsSideEffect> {

    override fun reduce(
        state: LogsState,
        command: LogsCommand,
    ): ReduceResult<LogsState, LogsSideEffect> = when (command) {
        is LogsCommand.LogsLoaded -> {
            if (state.paused) {
                val paramsChanged = command.query != state.query ||
                    command.filters != state.filters ||
                    command.caseSensitive != state.caseSensitive ||
                    command.showLogValues != state.showLogValues

                if (paramsChanged) {
                    state.copy(
                        query = command.query,
                        caseSensitive = command.caseSensitive,
                        filters = command.filters,
                        showLogValues = command.showLogValues,
                        logsChanged = true,
                        listRevision = state.listRevision + 1,
                    ).noSideEffects()
                } else {
                    state.noSideEffects()
                }
            } else {
                val paramsChanged = command.query != state.query ||
                    command.filters != state.filters ||
                    command.caseSensitive != state.caseSensitive ||
                    command.showLogValues != state.showLogValues

                // The service pushes a buffer snapshot every few hundred milliseconds even
                // when nothing arrived. Bumping listRevision for those re-filtered,
                // re-formatted and re-diffed the whole list (up to 10k rows) several times a
                // second, which showed up as jank, GC churn and battery drain
                val logsUnchanged = !paramsChanged && (
                    state.logs === command.logs ||
                        (state.logs.size == command.logs.size &&
                            state.logs.firstOrNull()?.id == command.logs.firstOrNull()?.id &&
                            state.logs.lastOrNull()?.id == command.logs.lastOrNull()?.id &&
                            // a continuation line updates the content of the last record
                            state.logs.lastOrNull()?.content == command.logs.lastOrNull()?.content)
                    )

                if (logsUnchanged) return@reduce state.noSideEffects()

                // Drop expand overrides of lines that were already evicted from the buffer:
                // the map was only ever added to, so it grew for the whole session (and ids
                // are reused after a clear, leaking stale expand state into new lines).
                // Building the id set is only needed when there is something to prune.
                val needsPruning = state.selectedIds.isNotEmpty() || state.expandedOverrides.isNotEmpty()

                val visibleIds = if (needsPruning) {
                    command.logs.mapTo(HashSet(command.logs.size)) { it.id }
                } else {
                    null
                }

                // Selection must follow the buffer as well. Keeping ids that no longer
                // exist left the toolbar stuck in selection mode with a count larger than
                // the visible rows, and "copy/export selected" silently produced nothing
                val selectedIds = if (visibleIds == null) {
                    state.selectedIds
                } else {
                    state.selectedIds.filterTo(HashSet()) { it in visibleIds }
                }

                val newState = state.copy(
                    logs = command.logs,
                    query = command.query,
                    caseSensitive = command.caseSensitive,
                    filters = command.filters,
                    showLogValues = command.showLogValues,
                    expandedOverrides = if (visibleIds == null) {
                        state.expandedOverrides
                    } else {
                        state.expandedOverrides.filterKeys { it in visibleIds }
                    },
                    selectedIds = selectedIds,
                    logsChanged = true,
                    listRevision = state.listRevision + 1,
                )

                if (selectedIds.size != state.selectedIds.size) {
                    newState.withSideEffects(
                        LogsSideEffect.SyncSelectedLines(newState.logsByIds(selectedIds)),
                    )
                } else {
                    newState.noSideEffects()
                }
            }
        }

        is LogsCommand.PreferencesUpdated -> {
            state.copy(
                resumeLoggingWithBottomTouch = command.resumeLoggingWithBottomTouch,
                textSize = command.textSize,
                logsExpanded = command.logsExpanded,
                logsChanged = command.textSize != state.textSize ||
                    command.logsExpanded != state.logsExpanded,
            ).noSideEffects()
        }

        is LogsCommand.ItemClicked -> {
            if (state.selectedIds.isNotEmpty()) {
                val newIds = state.selectedIds.toggle(command.logLineId)
                state.copy(selectedIds = newIds, logsChanged = true)
                    .withSideEffects(LogsSideEffect.SyncSelectedLines(state.logsByIds(newIds)))
            } else {
                val currentExpanded = state.expandedOverrides.getOrElse(command.logLineId) {
                    state.logsExpanded
                }
                state.copy(
                    expandedOverrides = state.expandedOverrides + (command.logLineId to !currentExpanded),
                    logsChanged = true,
                ).noSideEffects()
            }
        }

        is LogsCommand.SelectLine -> {
            val newIds = if (command.selected) {
                state.selectedIds + command.logLineId
            } else {
                state.selectedIds - command.logLineId
            }
            state.copy(selectedIds = newIds, logsChanged = true)
                .withSideEffects(LogsSideEffect.SyncSelectedLines(state.logsByIds(newIds)))
        }

        is LogsCommand.SelectAll -> {
            // "Select all" used to toggle to an empty selection on the second tap, which
            // deselected everything and left selection mode - the opposite of the label.
            // Deselecting is the job of the close button / ClearSelection.
            if (state.selectedIds == command.visibleIds) {
                state.noSideEffects()
            } else {
                state.copy(selectedIds = command.visibleIds, logsChanged = true)
                    .withSideEffects(
                        LogsSideEffect.SyncSelectedLines(state.logsByIds(command.visibleIds)),
                    )
            }
        }

        is LogsCommand.ClearSelection -> {
            state.copy(selectedIds = emptySet(), logsChanged = true)
                .withSideEffects(LogsSideEffect.SyncSelectedLines(emptyList()))
        }

        is LogsCommand.SelectedToRecording -> {
            state.withSideEffects(
                LogsSideEffect.CreateRecordingFromLines(lines = state.selectedLines()),
                LogsSideEffect.NavigateToRecordings,
            )
        }

        is LogsCommand.ExportSelectedClicked -> {
            val lines = state.selectedLines()

            if (lines.isEmpty()) {
                // Nothing visible is selected: asking for a file name and writing an empty
                // file is worse than doing nothing
                state.noSideEffects()
            } else {
                state.copy(pendingExportLines = lines).withSideEffects(LogsSideEffect.PrepareExport)
            }
        }

        is LogsCommand.ExportPickerReady -> {
            state.withSideEffects(
                LogsSideEffect.LaunchExportPicker(filename = command.filename),
            )
        }

        is LogsCommand.ExportSelectedTo -> {
            state.copy(pendingExportLines = null).withSideEffects(
                LogsSideEffect.ExportLogsTo(
                    uri = command.uri,
                    lines = state.pendingExportLines ?: state.selectedLines(),
                ),
            )
        }

        is LogsCommand.SaveCurrentLogsClicked -> {
            val lines = state.visibleLogs()

            if (lines.isEmpty()) {
                state.noSideEffects()
            } else {
                state.copy(pendingSaveCurrentLines = lines)
                    .withSideEffects(LogsSideEffect.PrepareSaveCurrentLogs)
            }
        }

        is LogsCommand.SaveCurrentPickerReady -> {
            state.withSideEffects(
                LogsSideEffect.LaunchSaveCurrentPicker(filename = command.filename),
            )
        }

        is LogsCommand.SaveCurrentLogsTo -> {
            state.copy(pendingSaveCurrentLines = null).withSideEffects(
                LogsSideEffect.ExportLogsTo(
                    uri = command.uri,
                    // "Save current logs" means what the list shows, not the whole buffer
                    lines = state.pendingSaveCurrentLines ?: state.visibleLogs(),
                ),
            )
        }

        is LogsCommand.ExportFailed -> {
            state.withSideEffects(LogsSideEffect.ShowExportFailed)
        }

        is LogsCommand.SwitchState -> {
            state.copy(
                paused = !state.paused,
                logsChanged = state.paused,
            ).noSideEffects()
        }

        is LogsCommand.Pause -> {
            if (state.paused) {
                state.noSideEffects()
            } else {
                state.copy(paused = true, logsChanged = false).noSideEffects()
            }
        }

        is LogsCommand.Resume -> {
            if (!state.paused) {
                state.noSideEffects()
            } else {
                state.copy(paused = false, logsChanged = true).noSideEffects()
            }
        }

        is LogsCommand.CopyLog -> {
            val logLine = state.logs
                .firstOrNull { it.id == command.logLineId }
                ?: return@reduce state.noSideEffects()
            state.withSideEffects(
                LogsSideEffect.FormatAndCopyLog(logLine),
            )
        }

        is LogsCommand.CopySelectedLogs -> {
            state.withSideEffects(
                LogsSideEffect.FormatAndCopyLogs(lines = state.selectedLines()),
            )
        }

        is LogsCommand.CopyFormattedText -> {
            state.withSideEffects(
                LogsSideEffect.CopyText(command.text),
            )
        }

        is LogsCommand.ToolbarClicked -> {
            val sideEffect = when {
                state.filters.isEmpty() -> LogsSideEffect.OpenFilters
                state.filters.size == 1 -> LogsSideEffect.OpenEditFilter(state.filters.first().id)
                else -> LogsSideEffect.OpenEditFilter(state.filters.last().id)
            }
            state.withSideEffects(sideEffect)
        }

        is LogsCommand.CreateFilterFromLog -> {
            val logLine = state.logs
                .firstOrNull { it.id == command.logLineId }
                ?: return@reduce state.noSideEffects()
            state.withSideEffects(
                LogsSideEffect.OpenEditFilterFromLogLine(
                    uid = logLine.uid,
                    pid = logLine.pid,
                    tid = logLine.tid,
                    packageName = logLine.packageName,
                    tag = logLine.tag,
                    content = logLine.content,
                    level = logLine.level,
                ),
            )
        }

        is LogsCommand.OpenSearch -> {
            state.withSideEffects(LogsSideEffect.NavigateToSearch)
        }

        is LogsCommand.OpenFiltersScreen -> {
            state.withSideEffects(LogsSideEffect.OpenFilters)
        }

        is LogsCommand.OpenExtendedCopy -> {
            state.withSideEffects(LogsSideEffect.NavigateToExtendedCopy)
        }

        is LogsCommand.SetLevelFilter -> {
            state.copy(
                levelFilters = command.levels,
                logsChanged = true,
                listRevision = state.listRevision + 1,
            ).noSideEffects()
        }

        is LogsCommand.ToggleLevelFilter -> {
            val levels = if (command.level in state.levelFilters) {
                state.levelFilters - command.level
            } else {
                state.levelFilters + command.level
            }

            state.copy(
                levelFilters = levels,
                logsChanged = true,
                listRevision = state.listRevision + 1,
            ).noSideEffects()
        }

        is LogsCommand.LogcatSilentFound -> {
            val property = command.property

            if (property == null) {
                state.noSideEffects()
            } else {
                state.withSideEffects(LogsSideEffect.LogcatSilentWarning(property))
            }
        }

        is LogsCommand.ClearLogs -> {
            state.copy(
                logs = emptyList(),
                selectedIds = emptySet(),
                expandedOverrides = emptyMap(),
                logsChanged = true,
            ).withSideEffects(LogsSideEffect.ClearLogs)
        }

        is LogsCommand.RestartLogging -> {
            state.withSideEffects(LogsSideEffect.RestartLogging)
        }

        is LogsCommand.KillService -> {
            state.withSideEffects(LogsSideEffect.KillService)
        }
    }

    private fun LogsState.selectedLines(): List<LogLine> =
        logsByIds(selectedIds)

    /**
     * Mirrors [LogsViewStateMapper] exactly: the toolbar counts only lines that survived the
     * query and the user filters, so copy/export must use the same set. It previously ignored
     * both, which put invisible lines into the clipboard and into exported files.
     */
    private fun LogsState.visibleLogs(): List<LogLine> =
        logs.filterAndSearch(
            filters = filters,
            query = query,
            caseSensitive = caseSensitive,
        ).let { lines -> if (levelFilters.isEmpty()) lines else lines.filter { it.level in levelFilters } }

    private fun LogsState.logsByIds(ids: Set<Long>): List<LogLine> =
        visibleLogs().filter { line -> line.id in ids }.sortedBy { it.dateAndTime }

    private fun Set<Long>.toggle(id: Long): Set<Long> =
        if (id in this) this - id else this + id
}
