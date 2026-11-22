package com.f0x1d.logfox.feature.logging.presentation.list

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.datetime.api.DateTimeFormatter
import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.ExportLogsToUriUseCase
import com.f0x1d.logfox.feature.logging.api.domain.FormatLogLineUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetCaseSensitiveFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogcatSilentTagUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetShowLogValuesFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateSelectedLogLinesUseCase
import com.f0x1d.logfox.feature.logging.api.presentation.LoggingServiceDelegate
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetLogsExpandedFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetLogsTextSizeFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetResumeLoggingWithBottomTouchFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.service.GetLogFileExtensionUseCase
import com.f0x1d.logfox.feature.recordings.api.domain.CreateRecordingFromLinesUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class LogsEffectHandler @Inject constructor(
    private val getLogsFlowUseCase: GetLogsFlowUseCase,
    private val getLogcatSilentTagUseCase: GetLogcatSilentTagUseCase,
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
    private val getCaseSensitiveFlowUseCase: GetCaseSensitiveFlowUseCase,
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
    private val updateSelectedLogLinesUseCase: UpdateSelectedLogLinesUseCase,
    private val createRecordingFromLinesUseCase: CreateRecordingFromLinesUseCase,
    private val getShowLogValuesFlowUseCase: GetShowLogValuesFlowUseCase,
    private val getResumeLoggingWithBottomTouchFlowUseCase: GetResumeLoggingWithBottomTouchFlowUseCase,
    private val getLogsTextSizeFlowUseCase: GetLogsTextSizeFlowUseCase,
    private val getLogsExpandedFlowUseCase: GetLogsExpandedFlowUseCase,
    private val formatLogLineUseCase: FormatLogLineUseCase,
    private val exportLogsToUriUseCase: ExportLogsToUriUseCase,
    private val loggingServiceDelegate: LoggingServiceDelegate,
    private val getLogFileExtensionUseCase: GetLogFileExtensionUseCase,
    private val dateTimeFormatter: DateTimeFormatter,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : EffectHandler<LogsSideEffect, LogsCommand> {

    override suspend fun handle(effect: LogsSideEffect, onCommand: suspend (LogsCommand) -> Unit) {
        when (effect) {
            is LogsSideEffect.CheckLogcatSilent -> {
                onCommand(LogsCommand.LogcatSilentFound(getLogcatSilentTagUseCase()))
            }

            is LogsSideEffect.LoadLogs -> {
                combine(
                    getLogsFlowUseCase(),
                    getAllEnabledFiltersFlowUseCase(),
                    getQueryFlowUseCase(),
                    getCaseSensitiveFlowUseCase(),
                    getShowLogValuesFlowUseCase(),
                ) { logs, filters, query, caseSensitive, showLogValues ->
                    LogsCommand.LogsLoaded(
                        logs = logs,
                        query = query,
                        caseSensitive = caseSensitive,
                        filters = filters,
                        showLogValues = showLogValues,
                    )
                }.collect { command ->
                    // One bad emission (dirty row, formatter failure, ...) must not kill the
                    // only collector of the screen: without this the log list froze forever.
                    runCatching { onCommand(command) }
                        .onFailure { throwable ->
                            if (throwable is CancellationException) throw throwable
                            throwable.printStackTrace()
                        }
                }
            }

            is LogsSideEffect.ObservePreferences -> {
                combine(
                    getResumeLoggingWithBottomTouchFlowUseCase(),
                    getLogsTextSizeFlowUseCase(),
                    getLogsExpandedFlowUseCase(),
                ) { resumeLoggingWithBottomTouch, textSize, logsExpanded ->
                    LogsCommand.PreferencesUpdated(
                        resumeLoggingWithBottomTouch = resumeLoggingWithBottomTouch,
                        textSize = textSize,
                        logsExpanded = logsExpanded,
                    )
                }.collect { command ->
                    runCatching { onCommand(command) }
                        .onFailure { throwable ->
                            if (throwable is CancellationException) throw throwable
                            throwable.printStackTrace()
                        }
                }
            }

            is LogsSideEffect.SyncSelectedLines -> {
                updateSelectedLogLinesUseCase(selectedLines = effect.lines)
            }

            is LogsSideEffect.CreateRecordingFromLines -> {
                createRecordingFromLinesUseCase(lines = effect.lines)
            }

            is LogsSideEffect.ExportLogsTo -> {
                exportLogsToUriUseCase(effect.lines, effect.uri)
            }

            is LogsSideEffect.PrepareExport -> {
                val filename = "${dateTimeFormatter.formatForExport(System.currentTimeMillis())}.${getLogFileExtensionUseCase()}"
                onCommand(LogsCommand.ExportPickerReady(filename))
            }

            is LogsSideEffect.PrepareSaveCurrentLogs -> {
                val filename = "${dateTimeFormatter.formatForExport(System.currentTimeMillis())}.${getLogFileExtensionUseCase()}"
                onCommand(LogsCommand.SaveCurrentPickerReady(filename))
            }

            is LogsSideEffect.FormatAndCopyLog -> {
                val formattedText = formatLogLineUseCase(
                    logLine = effect.logLine,
                )
                onCommand(LogsCommand.CopyFormattedText(formattedText))
            }

            is LogsSideEffect.FormatAndCopyLogs -> {
                // Same as export: formatting thousands of lines belongs on the IO thread
                val formattedText = withContext(ioDispatcher) {
                    effect.lines.joinToString("\n") { line ->
                        formatLogLineUseCase(line)
                    }
                }
                onCommand(LogsCommand.CopyFormattedText(formattedText))
            }

            is LogsSideEffect.ClearLogs -> {
                loggingServiceDelegate.clearLogs()
            }

            is LogsSideEffect.RestartLogging -> {
                loggingServiceDelegate.restartLogging()
            }

            is LogsSideEffect.KillService -> {
                loggingServiceDelegate.killService()
            }

            // UI side effects - handled by Fragment
            else -> Unit
        }
    }
}
