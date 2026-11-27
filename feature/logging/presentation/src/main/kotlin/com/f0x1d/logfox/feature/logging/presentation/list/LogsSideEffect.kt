package com.f0x1d.logfox.feature.logging.presentation.list

import com.f0x1d.logfox.core.tea.ReliableSideEffect
import android.net.Uri
import com.f0x1d.logfox.feature.logging.api.model.LogLevel
import com.f0x1d.logfox.feature.logging.api.model.LogLine

internal sealed interface LogsSideEffect {
    // Business logic side effects - handled by EffectHandler
    data object LoadLogs : LogsSideEffect

    data object ObservePreferences : LogsSideEffect

    data class SyncSelectedLines(val lines: List<LogLine>) : LogsSideEffect

    data class CreateRecordingFromLines(val lines: List<LogLine>) : LogsSideEffect

    data class ExportLogsTo(val uri: Uri, val lines: List<LogLine>) : LogsSideEffect

    data class FormatAndCopyLog(val logLine: LogLine) : LogsSideEffect

    data class FormatAndCopyLogs(val lines: List<LogLine>) : LogsSideEffect

    data object PrepareExport : LogsSideEffect

    data object PrepareSaveCurrentLogs : LogsSideEffect

    // UI side effects - handled by Fragment
    data object NavigateToRecordings : LogsSideEffect, ReliableSideEffect

    data object OpenFilters : LogsSideEffect, ReliableSideEffect

    data class OpenEditFilter(val filterId: Long) : LogsSideEffect, ReliableSideEffect

    data class OpenEditFilterFromLogLine(
        val uid: String,
        val pid: String,
        val tid: String,
        val packageName: String?,
        val tag: String,
        val content: String,
        val level: LogLevel,
    ) : LogsSideEffect, ReliableSideEffect

    data class CopyText(val text: String) : LogsSideEffect, ReliableSideEffect

    data object NavigateToSearch : LogsSideEffect, ReliableSideEffect

    data object NavigateToExtendedCopy : LogsSideEffect, ReliableSideEffect

    data class LaunchExportPicker(val filename: String) : LogsSideEffect, ReliableSideEffect

    data class LaunchSaveCurrentPicker(val filename: String) : LogsSideEffect, ReliableSideEffect

    data object ClearLogs : LogsSideEffect

    data object RestartLogging : LogsSideEffect

    data object KillService : LogsSideEffect

    data object CheckLogcatSilent : LogsSideEffect

    // UI side effects
    data class LogcatSilentWarning(val property: String) : LogsSideEffect, ReliableSideEffect

    data object ShowExportFailed : LogsSideEffect, ReliableSideEffect
}
